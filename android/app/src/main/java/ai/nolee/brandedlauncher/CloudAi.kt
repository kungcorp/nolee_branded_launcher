package ai.nolee.brandedlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class CloudAiPhase { LOADING, READY, RECORDING, THINKING, SPEAKING, ERROR }

data class CloudAiTurn(
    val id: Int = 0,
    val question: String = "",
    val answer: String = "",
    val completed: Boolean = false,
)

internal fun recentCloudHistory(turns: List<CloudAiTurn>) =
    turns.filter { it.completed && it.question.isNotBlank() && it.answer.isNotBlank() }.takeLast(5)

data class CloudAiState(
    val phase: CloudAiPhase = CloudAiPhase.LOADING,
    val message: String = "",
    val answer: String = "",
    val turns: List<CloudAiTurn> = emptyList(),
    val requestsRemaining: Int? = null,
    val requestsLimit: Int? = null,
    val dailyRemaining: Int? = null,
    val dailyLimit: Int? = null,
    val closeAfterOpeningSilence: Boolean = false,
)

/** Device-owned Nolee AI client. The raw key is read from root-only reset-surviving storage. */
class CloudAi(private val context: Context, private val profileFacts: () -> List<String>, private val listener: (CloudAiState) -> Unit,
    private val onCommands: (List<VoiceCommand>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val mic = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .callTimeout(110, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val generation = AtomicInteger()

    /** Settings-only refresh. Opening the ball never performs a status preflight. */
    fun refreshSettings(callback: (Result<JSONObject>) -> Unit) {
        worker.execute {
            var requestCall: Call? = null
            val result = runCatching {
                val credential = key() ?: throw IOException("Activate Nolee AI and enable this app's Root access in Nolee Launcher.")
                val active = http.newCall(Request.Builder().url("$BASE/status?forceFunctionRegion=ap-southeast-1")
                    .header("Authorization", "Bearer $credential").get().build())
                synchronized(this) {
                    if (!live) return@execute
                    requestCall = active
                    call = active
                }
                active.execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IOException(cloudMessage(body, response.code))
                    JSONObject(body)
                }
            }.recoverCatching { throw IOException(friendly(it as? Exception ?: IOException("Status unavailable"))) }
            synchronized(this) { if (call === requestCall) call = null }
            main.post { if (live) callback(result) }
        }
    }
    private var recorder: AudioRecord? = null
    private var pcm: ByteArrayOutputStream? = null
    @Volatile private var call: Call? = null
    @Volatile private var toolResultCall: Call? = null
    @Volatile private var player: SpeechPlayer? = null
    @Volatile private var capturing = false
    @Volatile private var autoListen = false
    private var openingListen = false
    @Volatile private var live = true
    private val heard = AtomicBoolean(false)
    private var lastVoiceAt = 0L
    private var captureStarted = 0L
    @Volatile private var lastStatus = CloudAiState()
    private val listenTimeout = object : Runnable {
        override fun run() {
            if (!capturing) return
            // Eight/ten seconds bounds waiting for speech; an ongoing sentence gets up to 30.
            val remaining = 30_000L - (SystemClock.elapsedRealtime() - captureStarted)
            if (heard.get() && remaining > 0L) main.postDelayed(this, remaining)
            else releaseHold()
        }
    }

    // Only the stock Launcher manages activation and rotation.
    fun openListening() = worker.execute { startHold(OPEN_LISTEN_MS, opening = true) }
    fun cancel() = cancelTurn(true)

    /** Debug-only bench input uses the same authenticated stream and speaker as microphone turns. */
    fun debugQuestion(text: String) {
        if (!BuildConfig.DEBUG || text.isBlank()) return
        cancelTurn(false)
        val turn = generation.incrementAndGet()
        publish(lastStatus.copy(phase = CloudAiPhase.RECORDING, closeAfterOpeningSilence = false))
        worker.execute { ask(turn, ByteArray(0), text.take(4_000)) }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startHold(timeoutMs: Long = 0L, opening: Boolean = false): Boolean {
        if (!live) return false
        if (lastStatus.phase != CloudAiPhase.LOADING &&
            lastStatus.phase != CloudAiPhase.READY &&
            lastStatus.phase != CloudAiPhase.THINKING &&
            lastStatus.phase != CloudAiPhase.SPEAKING &&
            lastStatus.phase != CloudAiPhase.ERROR
        ) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            publish(lastStatus.copy(phase = CloudAiPhase.ERROR, message = "Microphone permission is unavailable."))
            return false
        }
        cancelTurn(false)
        val turn = generation.incrementAndGet()
        val minimum = AudioRecord.getMinBufferSize(INPUT_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) {
            publish(lastStatus.copy(phase = CloudAiPhase.ERROR, message = "Microphone is unavailable."))
            return false
        }
        return try {
            val output = ByteArrayOutputStream()
            val buffer = maxOf(minimum * 4, 16_384)
            val record = openMic(buffer) ?: throw IllegalStateException("microphone")
            recorder = record
            pcm = output
            capturing = true
            autoListen = timeoutMs > 0L
            openingListen = opening
            heard.set(false)
            lastVoiceAt = 0L
            captureStarted = SystemClock.elapsedRealtime()
            record.startRecording()
            publish(lastStatus.copy(phase = CloudAiPhase.RECORDING, message = "Listening…",
                closeAfterOpeningSilence = false))
            mic.execute { capture(turn, record, output, maxOf(minimum, 2048)) }
            if (timeoutMs > 0L) main.postDelayed(listenTimeout, timeoutMs)
            true
        } catch (_: Exception) {
            capturing = false
            runCatching { recorder?.release() }
            recorder = null
            publish(lastStatus.copy(phase = CloudAiPhase.ERROR, message = "Could not start the microphone."))
            false
        }
    }

    @Synchronized
    fun releaseHold() {
        if (!capturing) return
        capturing = false
        main.removeCallbacks(listenTimeout)
        runCatching { recorder?.stop() }
        val turn = generation.get()
        val timed = autoListen
        val wasOpening = openingListen
        val output = pcm
        val started = captureStarted
        mic.execute {
            if (turn != generation.get()) return@execute
            val captured = output?.toByteArray() ?: ByteArray(0)
            val spoke = heard.get() || pcm16HasSpeech(captured)
            if (SystemClock.elapsedRealtime() - started < 300 || captured.size < INPUT_RATE * 2 / 5 ||
                !spoke
            ) {
                val turns = lastStatus.turns.dropLastWhile { it.question.isBlank() && it.answer.isBlank() }
                publish(lastStatus.copy(
                    phase = CloudAiPhase.READY,
                    message = if (timed) "" else "Hold while speaking, then release.",
                    turns = turns,
                    closeAfterOpeningSilence = wasOpening && !spoke,
                ), turn)
            } else worker.execute { ask(turn, wav(captured)) }
        }
    }

    private fun capture(turn: Int, record: AudioRecord, output: ByteArrayOutputStream, size: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val buffer = ByteArray(size)
        try {
            while (capturing && turn == generation.get() && output.size() < MAX_INPUT_BYTES) {
                val count = record.read(buffer, 0, minOf(buffer.size, MAX_INPUT_BYTES - output.size()),
                    AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    if (turn != generation.get() || !live) return
                    output.write(buffer, 0, count)
                    if (pcm16HasSpeech(if (count == buffer.size) buffer else buffer.copyOf(count))) {
                        heard.set(true)
                        lastVoiceAt = SystemClock.elapsedRealtime()
                    } else if (autoListen && heard.get() &&
                        SystemClock.elapsedRealtime() - lastVoiceAt > 750
                    ) {
                        main.post { if (turn == generation.get()) releaseHold() }
                    }
                } else if (count < 0) throw IOException("microphone read failed")
            }
            if (capturing && output.size() >= MAX_INPUT_BYTES) main.post { if (turn == generation.get()) releaseHold() }
        } catch (_: Exception) {
            if (live && turn == generation.get() && capturing) {
                capturing = false
                publish(lastStatus.copy(phase = CloudAiPhase.ERROR, message = "Microphone recording failed. Hold to try again."), turn)
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            if (recorder === record) recorder = null
        }
    }

    fun resumeAfterCommands() { startHold(FOLLOW_UP_LISTEN_MS) }

    private fun ask(turn: Int, wav: ByteArray, textQuestion: String? = null) {
        fun emit(state: CloudAiState) = publish(state, turn)
        // Never submit a capture cancelled while it waited for the request worker.
        if (turn != generation.get() || lastStatus.phase != CloudAiPhase.RECORDING) return
        val key = runCatching { key() }.getOrNull() ?: return emit(lastStatus.copy(phase = CloudAiPhase.ERROR,
            message = "Allow Root access for this app in Nolee Launcher → System → Access. Activate Nolee AI there if needed."))
        if (!live || turn != generation.get()) return
        val answer = StringBuffer()
        val question = java.util.concurrent.atomic.AtomicReference(textQuestion.orEmpty())
        val prior = recentCloudHistory(lastStatus.turns)
        val responseCompleted = AtomicBoolean(false)
        fun turnsNow() = prior + CloudAiTurn(id = turn, question = question.get(), answer = answer.toString(), completed = responseCompleted.get())
        val spoken = spokenAnswers(context)
        var speech: SpeechPlayer? = null
        val pendingCommands = AtomicReference<List<VoiceCommand>>(emptyList())
        var requestCall: Call? = null
        fun finishTurn() {
            main.postDelayed({
                if (live && turn == generation.get()) {
                    val commands = CloudCommands.completedTurnActions(question.get(), pendingCommands.getAndSet(emptyList()))
                    if (BuildConfig.DEBUG) android.util.Log.i("NoleeAiCommands", "Completed turn $turn: ${commands.map { it.javaClass.simpleName }}")
                    if (commands.isNotEmpty()) onCommands(commands)
                    else startHold(FOLLOW_UP_LISTEN_MS)
                }
            }, 220)
        }
        val decoder = Base64Stream { bytes ->
            synchronized(this) {
            if (!live || turn != generation.get()) return@Base64Stream
            if (speech == null) speech = SpeechPlayer(PLAYBACK_GAIN,
                onStarted = { if (turn == generation.get()) emit(lastStatus.copy(phase = CloudAiPhase.SPEAKING, message = "Speaking…",
                    answer = answer.toString(), turns = turnsNow())) },
                onDone = done@{
                    if (turn != generation.get()) return@done
                    emit(lastStatus.copy(phase = CloudAiPhase.READY, message = "",
                        answer = answer.toString(), turns = turnsNow()))
                    finishTurn()
                },
                onFailure = { if (turn == generation.get()) emit(lastStatus.copy(phase = CloudAiPhase.ERROR, message = it,
                    answer = answer.toString(), turns = turnsNow())) }
            ).also { player = it }
            }
            speech?.enqueue(bytes)
        }
        emit(lastStatus.copy(phase = CloudAiPhase.THINKING, message = "Asking Nolee AI…",
            answer = "", turns = turnsNow()))
        try {
            val web = webSearch(context)
            val payload = JSONObject()
                .put("audio_enabled", spoken)
                .put("web_enabled", web)
                .put("tools_enabled", true)
                .put("device_status_tool", true)
                .put("audio_controls_before_reply", true)
                .put("device_commands", JSONArray(CloudCommands.names))
                .put("return_transcript", true)
                .put("mode", "general")
                .put("app_prompt", run {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val stream = android.media.AudioManager.STREAM_MUSIC
                    val percent = (audio.getStreamVolume(stream) * 100 + audio.getStreamMaxVolume(stream) / 2) /
                        audio.getStreamMaxVolume(stream).coerceAtLeast(1)
                    appPrompt(percent)
                })
                .put("voice", voice(context))
                .put("client_request_id", UUID.randomUUID().toString())
                .put("app_version", "branded-launcher-" + BuildConfig.VERSION_NAME)
                .put("memory", JSONObject().put("profile_facts", JSONArray(profileFacts())).put("recent_turns", JSONArray().apply {
                    prior.forEach { put(JSONObject().put("question", it.question).put("answer", it.answer)) }
                }))
            if (textQuestion != null) payload.put("question", textQuestion)
            else payload.put("audio_wav_base64", Base64.encodeToString(wav, Base64.NO_WRAP))
            val request = Request.Builder().url("$BASE/ask?forceFunctionRegion=ap-southeast-1")
                .header("Authorization", "Bearer $key")
                .post(payload.toString().toRequestBody(JSON)).build()
            val active = http.newCall(request)
            synchronized(this) {
                // Cancellation and registration share the same lock: a cancelled turn must not
                // sneak a new authenticated request through after cancelTurn() cleared call.
                if (!live || turn != generation.get()) return
                requestCall = active
                call = active
            }
            active.execute().use { response ->
                if (!response.isSuccessful) throw IOException(cloudMessage(response.body?.string().orEmpty(), response.code))
                val source = response.body?.source() ?: throw IOException("No response body")
                var event = ""
                var completed = false
                var statusRead = false
                while (turn == generation.get() && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("event:")) { event = line.removePrefix("event:").trim(); continue }
                    if (!line.startsWith("data:")) continue
                    val chunk = JSONObject(line.removePrefix("data:").trim())
                    if (event == "device.tool_call") {
                        if (statusRead || chunk.getString("name") != "get_device_status") throw IOException("Unsupported device status request")
                        statusRead = true
                        if (!live || turn != generation.get()) return
                        val result = DeviceStatusTool.read(context)
                        val reply = JSONObject().put("request_id", chunk.getString("request_id"))
                            .put("call_id", chunk.getString("call_id")).put("result", result)
                        val statusRequest = Request.Builder().url("$BASE/device-tool-result?forceFunctionRegion=ap-southeast-1")
                            .header("Authorization", "Bearer $key").post(reply.toString().toRequestBody(JSON)).build()
                        val resultCall = http.newCall(statusRequest)
                        synchronized(this) {
                            if (!live || turn != generation.get()) return
                            toolResultCall = resultCall
                        }
                        try {
                            resultCall.execute().use { statusResponse ->
                                if (!statusResponse.isSuccessful) throw IOException("Could not return current device status")
                            }
                        } finally {
                            synchronized(this) { if (toolResultCall === resultCall) toolResultCall = null }
                        }
                        if (BuildConfig.DEBUG) android.util.Log.i("NoleeAiCommands", "Read get_device_status before reply")
                    }
                    if (event == "device.actions" || event == "device.audio_controls") {
                        val actions = chunk.getJSONArray("actions")
                        require(actions.length() in 1..3) { "Invalid number of device actions" }
                        val resolved = (0 until actions.length()).map { index ->
                            val action = actions.getJSONObject(index)
                            val rawPercent = action.opt("percent")
                            val percent = if (rawPercent == null || rawPercent == JSONObject.NULL) null else {
                                require(rawPercent is Number && rawPercent.toDouble() == rawPercent.toInt().toDouble()) { "Invalid percentage" }
                                rawPercent.toInt()
                            }
                            val profilePatch = if (action.has("profile")) {
                                val fields = action.getJSONObject("profile")
                                fields.keys().asSequence().associateWith { field ->
                                    (fields.get(field) as? String) ?: throw IOException("Invalid profile value")
                                }
                            } else null
                            CloudCommands.resolve(action.getString("command"), percent, profilePatch)
                                ?: throw IOException("Nolee AI requested an unsupported device action.")
                        }
                        if (event == "device.audio_controls") {
                            require(resolved.all { it is VoiceCommand.AiVolume ||
                                (it is VoiceCommand.Volume && it.stream == SoundStream.Media) }) {
                                "Only media volume may change before the reply"
                            }
                            synchronized(this) {
                                if (!live || turn != generation.get()) return
                                val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                                val stream = android.media.AudioManager.STREAM_MUSIC
                                for (command in resolved) {
                                    val percent = when (command) {
                                        is VoiceCommand.AiVolume -> command.percent
                                        is VoiceCommand.Volume -> command.percent
                                        else -> error("Unsupported audio control")
                                    }
                                    val target = (percent * audio.getStreamMaxVolume(stream) + 50) / 100
                                    audio.setStreamVolume(stream, target, 0)
                                    check(audio.getStreamVolume(stream) == target) { "Could not change media volume" }
                                    if (BuildConfig.DEBUG) android.util.Log.i("NoleeAiCommands", "Media volume applied before reply: $target")
                                }
                            }
                        } else pendingCommands.set(resolved)
                    }
                    if (event == "done") completed = true
                    chunk.optString("text").takeIf { event == "transcript" && it.isNotEmpty() }?.let {
                        question.set(it)
                        emit(lastStatus.copy(phase = if (lastStatus.phase == CloudAiPhase.SPEAKING) CloudAiPhase.SPEAKING else CloudAiPhase.THINKING, message = "Answering…",
                            turns = turnsNow()))
                    }
                    chunk.optString("delta").takeIf { event == "text.delta" && it.isNotEmpty() }?.let {
                        answer.append(it)
                        emit(lastStatus.copy(phase = if (lastStatus.phase == CloudAiPhase.SPEAKING) CloudAiPhase.SPEAKING else CloudAiPhase.THINKING, message = "Answering…",
                            answer = answer.toString(), turns = turnsNow()))
                    }
                    if (spoken && event == "audio.delta") chunk.optString("data").takeIf { it.isNotEmpty() }?.let(decoder::append)
                    if (event == "error") {
                        throw IOException(quotaMessage(chunk.optString("code"), chunk.optString("message")))
                    }
                    chunk.optJSONObject("usage")?.let { updateUsage(it, turn) }
                }
                if (turn != generation.get()) return
                if (!completed) throw IOException("The connection ended before the answer finished. Hold to try again.")
            }
            responseCompleted.set(true)
            // A successful mute command stops the remaining acknowledgement, not device media.
            if (pendingCommands.get().any { it == VoiceCommand.SpokenAnswers(false) }) {
                decoder.cancel()
                speech?.stop()
                speech = null
            } else decoder.finish()
            if (speech != null) speech?.finish()
            else {
                emit(lastStatus.copy(phase = CloudAiPhase.READY, message = "",
                    answer = answer.toString(), turns = turnsNow()))
                finishTurn()
            }
        } catch (e: Exception) {
            decoder.cancel(); speech?.stop()
            if (turn == generation.get()) emit(lastStatus.copy(phase = CloudAiPhase.ERROR, message = friendly(e),
                answer = answer.toString(), turns = turnsNow()))
        } finally { synchronized(this) { if (call === requestCall) call = null } }
    }

    @Synchronized
    private fun updateUsage(json: JSONObject, turn: Int) {
        if (!live || turn != generation.get()) return
        val dailyLimit = json.optInt("daily_requests_limit").takeIf { json.has("daily_requests_limit") }
            ?: lastStatus.dailyLimit
        val dailyUsed = json.optInt("daily_requests_used").takeIf { json.has("daily_requests_used") }
        lastStatus = lastStatus.copy(
            requestsRemaining = json.optInt("requests_remaining", lastStatus.requestsRemaining ?: 0),
            requestsLimit = json.optInt("requests_limit", lastStatus.requestsLimit ?: 0),
            dailyLimit = dailyLimit,
            dailyRemaining = dailyLimit?.let { limit ->
                dailyUsed?.let { used -> (limit - used).coerceAtLeast(0) } ?: lastStatus.dailyRemaining
            },
        )
    }

    /** Read at request time so Launcher rotation is picked up without a copied secret. */
    private fun key(): String? {
        val process = ProcessBuilder("su", "-c", "cat $KEY").start()
        return try {
            if (!process.waitFor(4, TimeUnit.SECONDS)) return null
            if (process.exitValue() != 0) return null
            process.inputStream.bufferedReader().readText().trim()
                .takeIf { it.matches(Regex("nolee_(?:key|ai)_[A-Za-z0-9_-]{40,}")) }
        } finally { process.destroy() }
    }
    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "Connect to the internet and try again."
        is java.net.ConnectException -> "Connect to the internet and try again."
        is java.net.SocketTimeoutException -> "The connection timed out. Hold to try again."
        else -> cloudMessage(e.message.orEmpty(), 0).ifBlank { "Nolee AI could not complete the request." }
    }
    private fun cloudMessage(body: String, code: Int): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val error = parsed?.optJSONObject("error") ?: parsed
        val mapped = quotaMessage(error?.optString("code").orEmpty(), error?.optString("message").orEmpty())
        if (mapped.isNotEmpty() && !mapped.startsWith("{")) return mapped
        return if (code > 0) "Nolee Cloud HTTP $code" else body.ifBlank { "Nolee AI could not complete the request." }
    }
    private fun quotaMessage(code: String, message: String): String {
        val lower = "$code $message".lowercase()
        return when {
            code == "invalid_credential" || code == "not_eligible" -> "Check activation and key status in Nolee Launcher → System → Applications → Nolee AI."
            lower.contains("pending") || lower.contains("not activated") -> "Activate Nolee AI in Nolee Launcher → System → Applications → Nolee AI."
            lower.contains("revoked") || lower.contains("invalid credential") -> "This Nolee key is unavailable. Check Nolee AI in Nolee Launcher settings."
            lower.contains("expired") -> "Your Nolee AI access has expired. Check Nolee AI in Nolee Launcher settings."
            code == "daily_quota_reached" || lower.contains("daily request quota") ||
                lower.contains("daily limit") -> "Daily Limit Exceeded"
            code == "monthly_quota_reached" || code == "quota_reached" ||
                lower.contains("monthly request quota") || lower.contains("monthly limit") ->
                "Monthly Limit Exceeded"
            code == "search_quota_reached" || lower.contains("web-search quota") ||
                lower.contains("search limit") -> "Search Limit Exceeded"
            else -> message.trim()
        }
    }
    @Synchronized
    private fun publish(state: CloudAiState, turn: Int = generation.get()) {
        if (!live || turn != generation.get()) return
        lastStatus = state
        main.post { if (live && turn == generation.get()) listener(state) }
    }
    @Synchronized
    private fun cancelTurn(idle: Boolean) {
        generation.incrementAndGet(); capturing = false
        main.removeCallbacks(listenTimeout)
        val record = recorder
        recorder = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        call?.cancel(); call = null; toolResultCall?.cancel(); toolResultCall = null; player?.stop(); player = null
        if (idle) publish(lastStatus.copy(phase = CloudAiPhase.READY, message = ""))
    }
    fun destroy() {
        live = false
        cancelTurn(false)
        mic.shutdownNow()
        worker.shutdownNow()
        Thread({
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }, "nolee-ai-cleanup").apply { isDaemon = true; start() }
    }

    @SuppressLint("MissingPermission")
    private fun openMic(buffer: Int): AudioRecord? {
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val record = runCatching {
                AudioRecord(source, INPUT_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, buffer)
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            runCatching { record.release() }
        }
        return null
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(INPUT_RATE).putInt(INPUT_RATE * 2)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    companion object {
        private const val BASE = "https://rqcdmkgonibfiolgbexo.supabase.co/functions/v1/nolee-ai"
        private const val KEY = "/system/nolee/credentials/nolee-key"
                private const val INPUT_RATE = 16_000
        private const val MAX_INPUT_BYTES = INPUT_RATE * 2 * 30
        private const val PLAYBACK_GAIN = 5f
        const val OPEN_LISTEN_MS = 8_000L
        const val FOLLOW_UP_LISTEN_MS = 10_000L
        const val IDLE_CLOSE_MS = 30_000L
        private const val DEFAULT_VOICE = "Serena"
        private const val PREFS = "nolee_ai"
        internal const val APP_PROMPT = "You are Nolee AI, the friendly companion in the owner's Nolee Branded Launcher. " +
            "You are a glowing ball on Nolee DevKit Ultra. You receive no images and cannot see the screen. " +
            "Home has Camera, Files, Gallery, Phone, SMS, Nolee AI, Watch, Vitals and System cards. " +
            "Hold the full-screen watch face to enter your page, or use Home > Nolee AI > Start Nolee AI. " +
            "The side button switches Home/Watch and returns from your page to Watch; a long press or System > Shutdown opens the same Power page with slide-to-confirm Restart and Shut down. " +
            "Hold your ball to interrupt and listen; releasing before the hold completes cancels. Long-touch the camera lid to toggle the transcript. " +
            "Swipe left/right to cycle ball camera views, up for top view, down for front. " +
            "Nolee AI settings show status, shared usage, spoken answers and voice. Home Quick Command is the separate offline Vosk feature; it can open you via Watch. " +
            "You receive the five most recent conversation pairs, the current question and saved Profile facts. History ends with the session; Profile persists. Never claim older memories; ask for a reminder. " +
            "Explain unavailable features honestly. Suggest asking the owner's coding agent to build them on this programmable device, without promising unsupported hardware, permissions or easy implementation. " +
            "Help with everyday questions. Use get_device_status for current watch time, battery, charging, connectivity, sound, display, storage and AI settings. Prefer fresh tool readings over history. Null means unknown. Wi-Fi connected does not prove internet access. " +
            "Use queue_device_command for device actions the owner requests. Media volume runs before your reply; other actions run after. " +
            "Use show transcript / hide transcript to change this conversation's transcript. " +
            "Keep the owner's Profile up to date using update profile with a profile object of name, age, occupation, city and/or about. " +
            "Save clear first-person facts the owner volunteers in normal conversation, without demanding an explicit save command or navigating away. " +
            "Do not infer missing facts, store third-party or hypothetical/quoted claims, passwords, keys, or transient requests as profile facts. " +
            "The supplied Profile facts are the current saved values. Update only changed fields; preserve other facts, especially when merging the short About summary. " +
            "Clear a field with an empty string only if the owner asks to forget/remove it. Name max 20 characters, age integer 0-150, occupation/city max 40, about max 160. " +
            "If Name is absent you may naturally ask what to call the owner once; don't repeatedly ask if declined. Do not interview them for the other fields. " +
            "Profile updates save locally after your reply; do not claim they are already saved. " +
            "Use mute ai voice / enable ai voice for your own spoken replies, not system volume; these preferences persist across sessions. " +
            "Use set ai volume with percent 0-100 to adjust how loud your voice is without leaving this conversation. The acknowledgement plays at the requested volume; do not say it will change after your reply. " +
            "Your speech uses the shared media volume stream; this changes other media too, but not ring, alarm, notification or call volume. " +
            "For requests such as speak quieter or louder, adjust from the supplied current AI/media volume; if no amount is specified, use a 10 percentage point step, bounded to 0-100. " +
            "Use exit kiosk when the owner asks to exit or leave kiosk and return to the stock Nolee Launcher. " +
            "Say briefly what you will do, never claim an action already succeeded. Do not queue actions merely mentioned in a question or profile. " +
            "Do not behave as a product support bot unless the owner asks for product help."
        internal fun appPrompt(mediaPercent: Int): String =
            (APP_PROMPT + " Current AI/media volume: ${mediaPercent.coerceIn(0, 100)} percent.").also {
                require(it.length <= 4000) { "Nolee AI app prompt exceeds the gateway limit" }
            }
        val VOICES = listOf("Serena", "Tina", "Jennifer", "Ethan", "Aiden", "Mione", "Harvey", "Andre", "Ryan")
        private val JSON = "application/json".toMediaType()

        fun voice(c: Context): String {
            val stored = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
            return VOICES.firstOrNull { it.equals(stored, ignoreCase = true) } ?: DEFAULT_VOICE
        }

        fun setVoice(c: Context, name: String): String {
            val chosen = VOICES.firstOrNull { it.equals(name, ignoreCase = true) } ?: DEFAULT_VOICE
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("voice", chosen).apply()
            return chosen
        }

        fun spokenAnswers(c: Context): Boolean =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("spoken_answers", true)

        fun webSearch(c: Context): Boolean =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("web_search", true)

        fun setWebSearch(c: Context, on: Boolean) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("web_search", on).apply()
        }

        fun setSpokenAnswers(c: Context, on: Boolean) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("spoken_answers", on).apply()
        }
    }
}

private class Base64Stream(private val sink: (ByteArray) -> Unit) {
    private val pending = StringBuilder(); private var cancelled = false
    fun append(value: String) { if (cancelled) return; pending.append(value.filterNot(Char::isWhitespace)); var n = pending.length / 4 * 4; if (n == pending.length && n >= 4) n -= 4; if (n > 0) { sink(Base64.decode(pending.substring(0, n), Base64.NO_WRAP)); pending.delete(0, n) } }
    fun finish() { if (cancelled || pending.isEmpty()) return; while (pending.length % 4 != 0) pending.append('='); sink(Base64.decode(pending.toString(), Base64.NO_WRAP)); pending.clear() }
    fun cancel() { cancelled = true; pending.clear() }
}

private class SpeechPlayer(private val gain: Float, private val onStarted: () -> Unit,
    private val onDone: () -> Unit, private val onFailure: (String) -> Unit) {
    private val queue = LinkedBlockingQueue<ByteArray>(128); private val running = AtomicBoolean(true)
    @Volatile private var track: AudioTrack? = null
    init { Thread(::loop, "nolee-ai-speaker").apply { isDaemon = true; start() } }
    fun enqueue(bytes: ByteArray) { if (bytes.isNotEmpty() && running.get()) queue.put(bytes) }
    fun finish() { if (running.get()) queue.put(END) }
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        queue.clear(); queue.offer(END)
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
    }
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            val initial = ByteArrayOutputStream(); var ended = false
            while (running.get() && initial.size() < PREBUFFER_BYTES) {
                val b = queue.take()
                if (b === END) { ended = true; break } else initial.write(b)
            }
            check(initial.size() > 0) { "Provider returned no PCM audio" }
            val minimum = AudioTrack.getMinBufferSize(OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "AudioTrack rejected 24 kHz mono PCM" }
            val audio = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(OUTPUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum * 4, TRACK_BUFFER_BYTES)).setTransferMode(AudioTrack.MODE_STREAM).build()
            check(audio.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack did not initialize" }
            track = audio; audio.play(); onStarted()
            var writtenBytes = 0L
            val gainStage = Pcm16Gain(gain)
            fun write(bytes: ByteArray) {
                if (bytes.isEmpty()) return
                val out = gainStage.append(bytes)
                var offset = 0
                while (running.get() && offset < out.size) {
                    val count = audio.write(out, offset, out.size - offset, AudioTrack.WRITE_BLOCKING)
                    check(count > 0) { "AudioTrack write failed: $count" }
                    offset += count
                    writtenBytes += count
                }
            }
            write(pcm16Payload(initial.toByteArray()))
            while (running.get() && !ended) {
                val b = queue.take()
                if (b === END) ended = true else write(b)
            }
            val writtenFrames = writtenBytes / 2
            val drainDeadline = SystemClock.elapsedRealtime() + (writtenFrames * 1_000L / OUTPUT_RATE) + 2_000
            while (running.get() && audio.playbackHeadPosition.toLong() < writtenFrames && SystemClock.elapsedRealtime() < drainDeadline) Thread.sleep(10)
            audio.stop(); audio.release(); track = null
            if (running.compareAndSet(true, false)) onDone()
        } catch (e: Exception) {
            runCatching { track?.release() }; track = null
            if (running.compareAndSet(true, false)) onFailure(e.message ?: "Audio playback failed")
        }
    }
    companion object {
        private const val OUTPUT_RATE = 24_000
        private const val PREBUFFER_BYTES = OUTPUT_RATE * 2 * 450 / 1_000
        private const val TRACK_BUFFER_BYTES = OUTPUT_RATE * 2
        private val END = ByteArray(0)
    }
}
