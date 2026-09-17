package ai.nolee.customlauncher

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * Recent microphone loudness, 0..1, for Ask AI's waveform. The capture thread writes a reading every 25 ms and the UI
 * reads them each frame. A torn read misdraws one bar for one frame, so there is no lock.
 */
class LevelMeter(private val capacity: Int = 64) {
    private val readings = FloatArray(capacity)
    @Volatile private var written = 0

    fun clear() {
        written = 0
        readings.fill(0f)
    }

    internal fun push(level: Float) {
        readings[written % capacity] = level
        written++
    }

    /** The reading [age] steps ago, 0 being the newest; silence before the first. */
    fun level(age: Int): Float {
        val count = written
        if (age >= count || age >= capacity) return 0f
        return readings[(count - 1 - age) % capacity]
    }
}

/**
 * Offline command recognition, after Harness (HarnessVosk). The bundled small English model is constrained to
 * [VoiceGrammar.phrases], so only commands this launcher can run are ever heard. Captions stream to [onCaption];
 * a second of quiet after speech, or ten seconds of nothing, ends the turn.
 *
 * The microphone is read here rather than through Vosk's SpeechService, which keeps the audio to itself: every 25 ms
 * of it feeds [meter] for the waveform, and 100 ms blocks feed the recognizer. Only the capture loop runs off the
 * main thread.
 */
class VoiceListener(
    private val context: Context,
    private val meter: LevelMeter,
    private val onReady: () -> Unit,
    private val onCaption: (String) -> Unit,
    private val onComplete: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    /** One turn. [stopped] ends its capture loop; [deliver] asks for the final result on the way out. */
    private class Turn {
        @Volatile var stopped = false
        @Volatile var deliver = false
    }

    // One thread for loading and capture, so a new turn cannot open the microphone until the last one has released it.
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    /** The turn in progress. It stays set after [finish] until that turn's final result arrives. */
    private var turn: Turn? = null
    private var accumulated = ""
    private val finishAfterSilence = Runnable { finish() }

    fun start(grammar: List<String>? = null) {
        if (turn != null) return
        val current = Turn().also { turn = it }
        accumulated = ""
        meter.clear()
        io.execute {
            if (current.stopped) {
                if (current.deliver) main.post { complete(current, "") }
                return@execute
            }
            val loaded = model ?: loadModel(current) ?: return@execute
            capture(current, loaded, grammar?.let { JSONArray(it.distinct()).toString() })
        }
    }

    fun cancel() {
        main.removeCallbacks(finishAfterSilence)
        turn?.stopped = true
        turn = null
    }

    fun destroy() {
        cancel()
        io.execute { runCatching { model?.close() } }
        io.shutdown()
    }

    private fun finish() {
        main.removeCallbacks(finishAfterSilence)
        val current = turn ?: return
        if (current.stopped) return
        current.deliver = true
        current.stopped = true
    }

    private fun capture(current: Turn, model: Model, grammar: String?) {
        val recognizer = try {
            if (grammar == null) Recognizer(model, SAMPLE_RATE.toFloat())
            else Recognizer(model, SAMPLE_RATE.toFloat(), grammar)
        } catch (e: Exception) {
            return fail(current, e.message ?: "Speech recognition failed")
        }
        var record: AudioRecord? = null
        try {
            val bufferBytes = max(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), SAMPLE_RATE)
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            if (record.state != AudioRecord.STATE_INITIALIZED) return fail(current, "Could not open the microphone")
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return fail(current, "The microphone is in use")
            main.post {
                if (turn === current && !current.stopped) {
                    onReady()
                    armSilenceTimer(heardSpeech = false)
                }
            }
            val chunk = ShortArray(METER_SAMPLES)
            val block = ShortArray(BLOCK_SAMPLES)
            var filled = 0
            var level = 0f
            while (!current.stopped) {
                val read = record.read(chunk, 0, chunk.size)
                if (read < 0) return fail(current, "The microphone stopped")
                if (read == 0) continue
                val loud = loudness(chunk, read)
                // Fast attack, slower release: syllables jump, and the bars fall back smoothly between them.
                level += (loud - level) * if (loud > level) .7f else .3f
                meter.push(level)
                var offset = 0
                while (offset < read) {
                    val take = min(read - offset, block.size - filled)
                    System.arraycopy(chunk, offset, block, filled, take)
                    filled += take
                    offset += take
                    if (filled == block.size) {
                        if (recognizer.acceptWaveForm(block, filled)) {
                            val json = recognizer.result
                            main.post { result(current, json) }
                        } else {
                            val json = recognizer.partialResult
                            main.post { partial(current, json) }
                        }
                        filled = 0
                    }
                }
            }
            if (current.deliver) {
                if (filled > 0) recognizer.acceptWaveForm(block, filled)
                val json = recognizer.finalResult
                main.post { complete(current, json) }
            }
        } catch (e: Exception) {
            fail(current, e.message ?: "Speech recognition failed")
        } finally {
            record?.let {
                runCatching { it.stop() }
                it.release()
            }
            recognizer.close()
        }
    }

    private fun partial(current: Turn, json: String) {
        if (turn !== current || current.stopped) return
        val partial = clean(field(json, "partial"))
        if (partial.isBlank()) return
        onCaption(join(accumulated, partial))
        armSilenceTimer(heardSpeech = true)
    }

    private fun result(current: Turn, json: String) {
        if (turn !== current || current.stopped) return
        accumulated = join(accumulated, clean(field(json, "text")))
        onCaption(accumulated)
        if (accumulated.isNotBlank()) armSilenceTimer(heardSpeech = true)
    }

    private fun complete(current: Turn, json: String) {
        if (turn !== current || !current.deliver) return
        turn = null
        accumulated = join(accumulated, clean(field(json, "text")))
        onComplete(accumulated.trim())
    }

    private fun fail(current: Turn, message: String) {
        Log.w(TAG, message)
        main.post {
            if (turn !== current) return@post
            main.removeCallbacks(finishAfterSilence)
            current.stopped = true
            turn = null
            onFailure(message)
        }
    }

    private fun armSilenceTimer(heardSpeech: Boolean) {
        main.removeCallbacks(finishAfterSilence)
        main.postDelayed(finishAfterSilence, if (heardSpeech) COMMAND_SILENCE_MS else NO_SPEECH_MS)
    }

    /** First use copies the model out of the APK (a few seconds); later starts reuse the unpacked copy. */
    private fun loadModel(current: Turn): Model? {
        val dir = runCatching { File(StorageService.sync(context, MODEL_ASSET, MODEL_CACHE_DIR)) }
            .onFailure { Log.w(TAG, "Could not unpack the bundled Vosk model: ${it.message}") }
            .getOrNull()
            ?.takeIf { File(it, "am/final.mdl").isFile }
        if (dir == null) {
            fail(current, "Speech model could not be prepared")
            return null
        }
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(dir.absolutePath).also { model = it }
        } catch (e: Exception) {
            fail(current, e.message ?: "Could not load the speech model")
            null
        }
    }

    /** A chunk's RMS in dBFS, mapped from a quiet room to loud speech at arm's length. */
    private fun loudness(samples: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        val db = 20 * log10(max(sqrt(sum / count), 1.0) / 32_768.0)
        return ((db - FLOOR_DB) / (LOUD_DB - FLOOR_DB)).toFloat().coerceIn(0f, 1f)
    }

    private fun join(a: String, b: String) = listOf(a, b).filter(String::isNotBlank).joinToString(" ")
    /** Vosk marks out-of-grammar speech as [unk]; it is never a word the user said. */
    private fun clean(text: String) = text.split(' ').filter { it.isNotBlank() && it != "[unk]" }.joinToString(" ")
    private fun field(json: String?, key: String): String = runCatching {
        JSONObject(json.orEmpty()).optString(key, "").trim()
    }.getOrDefault("")

    companion object {
        private const val TAG = "NoleeVoice"
        private const val SAMPLE_RATE = 16_000
        private const val METER_SAMPLES = 400 // 25 ms
        private const val BLOCK_SAMPLES = 1_600 // 100 ms
        // An office room measured about -48 dBFS through VOICE_RECOGNITION on the watch (2026-09-15), so a floor just
        // above it keeps a quiet room to the resting breath and leaves speech the whole height of the bars.
        private const val FLOOR_DB = -46.0
        private const val LOUD_DB = -16.0
        // A command is one short utterance, so a second of quiet after it means the speaker is done.
        private const val COMMAND_SILENCE_MS = 1_000L
        // Nothing heard at all: give up rather than hold the microphone open in front of someone who walked away.
        private const val NO_SPEECH_MS = 10_000L
        private const val MODEL_ASSET = "vosk-model-small-en-us-0.15"
        private const val MODEL_CACHE_DIR = "vosk-model"
    }
}

/** Everything the voice controls can do. Each maps onto an existing page or control. */
sealed interface VoiceCommand {
    data class Open(val page: Page) : VoiceCommand
    data class Launch(val entry: AppEntry) : VoiceCommand
    data class Radio(val wifi: Boolean, val on: Boolean) : VoiceCommand
    data class Brightness(val percent: Int) : VoiceCommand
    data class Adaptive(val on: Boolean) : VoiceCommand
    data class Volume(val stream: SoundStream, val percent: Int) : VoiceCommand
    data object MuteAll : VoiceCommand
    data object RestoreSound : VoiceCommand
    /** Android's automatic clock, or its automatic time zone. */
    data class AutoClock(val zone: Boolean, val on: Boolean) : VoiceCommand
    data class Zone(val id: String) : VoiceCommand
    data class EditProfile(val field: ProfileField) : VoiceCommand
}

/**
 * The phrases Vosk may recognise, and the parser that turns a recognised phrase into a [VoiceCommand].
 * Vosk spells Wi-Fi "wi fi" and SMS "s m s".
 */
object VoiceGrammar {
    private val pages = linkedMapOf(
        "home" to Page.Home, "home screen" to Page.Home,
        "watch" to Page.Watch, "watch face" to Page.Watch, "clock face" to Page.Watch,
        "date" to Page.DateTime, "time" to Page.DateTime, "date and time" to Page.DateTime,
        "clock" to Page.DateTime, "time zone" to Page.DateTime,
        "vitals" to Page.Vitals, "heart rate" to Page.HeartRate, "blood oxygen" to Page.Oxygen,
        "oxygen" to Page.Oxygen, "blood pressure" to Page.Pressure,
        "system" to Page.System, "settings" to Page.System, "profile" to Page.Profile,
        "wi fi" to Page.Wifi, "bluetooth" to Page.Bluetooth, "display" to Page.Display, "brightness" to Page.Display,
        "sound" to Page.Sound, "volume" to Page.Sound, "launcher" to Page.Launcher,
    )
    private val genericPages = setOf("settings", "system")
    private val apps = linkedMapOf(
        "camera" to AppEntry.Camera, "files" to AppEntry.Files, "gallery" to AppEntry.Gallery, "photos" to AppEntry.Gallery,
        "phone" to AppEntry.Phone, "messages" to AppEntry.SMS, "s m s" to AppEntry.SMS,
    )
    /** What to say for a time zone, and the profile fields that can be opened for editing. */
    private val zoneNames = TIME_ZONES.entries.associate { (id, spoken) -> spoken to id }
    private val profileFields = ProfileField.entries.associateBy { it.label.lowercase() }
    private val streams = linkedMapOf(
        "media" to SoundStream.Media, "music" to SoundStream.Media, "ring" to SoundStream.Ring, "alarm" to SoundStream.Alarm,
        "notification" to SoundStream.Notification, "call" to SoundStream.Call,
    )

    /** What the overlay says a page is called. */
    fun title(page: Page) = when (page) {
        Page.Watch -> "WATCH FACE"
        Page.HeartRate -> "HEART RATE"
        Page.Oxygen -> "BLOOD OXYGEN"
        Page.Pressure -> "BLOOD PRESSURE"
        Page.Wifi -> "WI-FI"
        Page.DateTime -> "DATE AND TIME"
        else -> page.name.uppercase()
    }

    val phrases: List<String> by lazy {
        buildSet {
            pages.keys.forEach { name ->
                add(name); add("open $name"); add("go to $name"); add("show $name")
            }
            add("go home")
            listOf("wi fi", "bluetooth", "display", "brightness", "sound", "volume", "launcher", "date", "time", "date and time", "profile")
                .forEach { add("$it settings"); add("open $it settings") }
            // Date & time's own controls.
            listOf("automatic time", "automatic date", "automatic time zone").forEach { name ->
                add("turn $name on"); add("turn $name off"); add("turn on $name"); add("turn off $name")
                add("enable $name"); add("disable $name"); add("$name on"); add("$name off")
            }
            TIME_ZONES.values.forEach { spoken ->
                add("set time zone to $spoken"); add("change time zone to $spoken"); add("time zone $spoken")
            }
            // Profile's fields open for editing; the keyboard takes it from there, since the model has no dictation.
            profileFields.keys.forEach { field ->
                add("edit $field"); add("edit my $field"); add("change $field"); add("change my $field")
            }
            listOf("heart rate", "blood oxygen", "blood pressure").forEach { add("measure $it"); add("measure my $it") }
            apps.keys.forEach { name -> listOf("open", "launch", "start").forEach { add("$it $name"); add("$it $name app") } }
            listOf("wi fi", "bluetooth", "adaptive brightness").forEach { name ->
                add("turn $name on"); add("turn $name off"); add("turn on $name"); add("turn off $name")
                add("enable $name"); add("disable $name"); add("$name on"); add("$name off")
            }
            for (n in 0..100) {
                val spoken = spokenNumber(n)
                if (n >= 5) listOf("brightness", "display", "screen").forEach { subject ->
                    add("set $subject to $spoken percent"); add("$subject to $spoken percent"); add("$subject $spoken percent")
                }
                add("set volume to $spoken percent"); add("volume to $spoken percent")
                streams.keys.forEach { stream ->
                    add("set $stream volume to $spoken percent"); add("$stream volume to $spoken percent"); add("$stream volume $spoken percent")
                }
            }
            // No "unmute": it is not in the small model's vocabulary, and Vosk silently drops such phrases.
            addAll(listOf("mute all", "mute everything", "mute", "silence", "restore sound", "restore levels", "restore volume"))
            // Vosk's reserved fallback keeps arbitrary speech from being forced into a valid phrase.
            add("[unk]")
        }.toList()
    }

    fun parse(raw: String): VoiceCommand? {
        val text = raw.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ')
            .filter { it.isNotBlank() && it != "unk" }.joinToString(" ")
        if (text.isBlank()) return null
        val words = text.split(' ')
        fun has(phrase: String) = " $text ".contains(" $phrase ")
        val on = "on" in words || "enable" in words
        val off = "off" in words || "disable" in words

        Regex("(?:to )?([a-z ]+?) percent").find(text)?.groupValues?.get(1)?.let { number(it) }?.let { percent ->
            if (has("volume")) {
                val stream = streams.entries.firstOrNull { has(it.key) }?.value ?: SoundStream.Media
                return VoiceCommand.Volume(stream, percent.coerceIn(0, 100))
            }
            if (has("brightness") || has("display") || has("screen")) return VoiceCommand.Brightness(percent.coerceIn(5, 100))
        }
        when {
            has("unmute") || has("restore") -> return VoiceCommand.RestoreSound
            has("mute") || has("silence") -> return VoiceCommand.MuteAll
            has("adaptive") && (on || off) -> return VoiceCommand.Adaptive(on)
            has("wi fi") && (on || off) -> return VoiceCommand.Radio(wifi = true, on = on)
            has("bluetooth") && (on || off) -> return VoiceCommand.Radio(wifi = false, on = on)
        }
        // Date & time and Profile, before the page names: "automatic time zone off" is a control, not the page.
        if (has("automatic") && (on || off)) return VoiceCommand.AutoClock(zone = has("zone"), on = on)
        if (has("zone")) zoneNames.entries.firstOrNull { has(it.key) }?.let { return VoiceCommand.Zone(it.value) }
        if (has("edit") || has("change")) {
            profileFields.entries.firstOrNull { has(it.key) }?.let { return VoiceCommand.EditProfile(it.value) }
        }
        apps.entries.sortedByDescending { it.key.length }.firstOrNull { has(it.key) }?.let { return VoiceCommand.Launch(it.value) }
        // A named page beats the generic words around it: "wi fi settings" is Wi-Fi, not System.
        pages.entries
            .sortedWith(compareBy({ it.key in genericPages }, { -it.key.length }))
            .firstOrNull { has(it.key) }
            ?.let { return VoiceCommand.Open(it.value) }
        return null
    }

    private val ones = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val teens = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun spokenNumber(value: Int): String = when {
        value < 10 -> ones[value]
        value < 20 -> teens[value - 10]
        value < 100 -> listOfNotNull(tens[value / 10], ones[value % 10].takeUnless { value % 10 == 0 }).joinToString(" ")
        else -> "one hundred"
    }

    /** The trailing spoken number in [raw] ("set brightness to forty five" → 45), or null. */
    private fun number(raw: String): Int? = (100 downTo 0).firstOrNull { raw == spokenNumber(it) || raw.endsWith(" " + spokenNumber(it)) }
}
