package ai.nolee.brandedlauncher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class Page { Home, Watch, Persona, NoleeAi, AiVoice, Vitals, HeartRate, Oxygen, Pressure, System, Profile, DateTime, Wifi, Bluetooth, Display, Sound, Launcher, Power }

private val VitalMetric.page get() = when (this) {
    VitalMetric.Heart -> Page.HeartRate
    VitalMetric.Oxygen -> Page.Oxygen
    VitalMetric.Pressure -> Page.Pressure
}

/** Idle on Home switches to the watch face; idle on the watch face dims everything outside the dial. */
private const val HOME_IDLE_MS = 15_000L
private const val FACE_IDLE_MS = 10_000L
private const val LONG_PRESS_MS = 400L
private const val PERSONA_HOLD_MS = 650L

// Voice flow pacing, after Harness: a card per 290 ms, a beat on the chosen card, and time to see each page arrive.
private const val STEP_MS = 290L
private const val CHOOSE_MS = 520L
private const val PAGE_SETTLE_MS = 750L
private const val ACT_DELAY_MS = 900L
private const val TICK_MS = 12L
private const val TAP_MS = 35L
// Ask AI holds each reply long enough to read once it has typed out.
private const val HEARD_HOLD_MS = 1_100L
private const val MISSED_HOLD_MS = 2_600L
// Between a miss and listening again: long enough to see it missed, short enough to keep talking.
private const val RETRY_GAP_MS = 900L
private const val FAILED_HOLD_MS = 2_800L

/** Debug builds only: runs a phrase as if heard. `adb shell am broadcast -a ai.nolee.brandedlauncher.DEBUG_VOICE --es text "open vitals"` */
private const val DEBUG_VOICE = "ai.nolee.brandedlauncher.DEBUG_VOICE"

/** Keys that cancel listening instead of acting on the page underneath. */
private val VOICE_CANCEL_KEYS = setOf(
    KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
)

class MainActivity : ComponentActivity() {
    private lateinit var device: DeviceState
    private lateinit var vitals: VitalsMonitor
    private lateinit var lid: LidScroll
    private lateinit var voice: VoiceListener
    private var cloudAi: CloudAi? = null
    private var transcriptLayerVisible = false
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }
    private val drum = DrumState(AppEntry.entries.size)
    private val systemDrum = DrumState(SystemEntry.entries.size)
    private val vitalsDrum = DrumState(VitalItem.entries.size)
    private val backStack = ArrayDeque<Page>()
    private var page by mutableStateOf(Page.Home)
    private var homeEntry by mutableIntStateOf(0)
    private var phrase by mutableStateOf("")
    /** Bumped when the watch face is left; while set, the face plays its exit above the next page. */
    private var timeExit by mutableIntStateOf(0)
    private var lastInteraction by mutableLongStateOf(SystemClock.uptimeMillis())
    private var faceIdle by mutableStateOf(false)
    private var faceIdleDelay = FACE_IDLE_MS
    /** Watch wake and dial-long-press gestures are consumed before Compose can click through them. */
    private var swallowing = false
    private var watchPressActive = false
    private var watchDownX = 0f
    private var watchDownY = 0f
    private var watchDownAt = 0L
    private var watchPressJob: Job? = null
    private var personaActive by mutableStateOf(false)
    private val personaSeconds = mutableFloatStateOf(0f)
    private var personaStartedAt by mutableStateOf(LocalDateTime.now())
    private var personaEmotion by mutableStateOf(PersonaEmotion.Regular)
    private var personaMotion: Job? = null
    private var personaListening by mutableStateOf(false)
    private var personaHeardSpeech = false
    private var personaIdleReturn: Job? = null
    private var personaReturning by mutableStateOf(false)
    private var watchSettled by mutableStateOf(false)
    private var personaCameraView by mutableIntStateOf(0)
    private var personaHoldPreview by mutableFloatStateOf(0f)
    private var personaPreviewReturn: Job? = null
    private var personaCameraRevision by mutableIntStateOf(0)
    private var personaTranscriptOpen by mutableStateOf(false)
    private var personaPartial by mutableStateOf("")
    private val personaTranscript = androidx.compose.runtime.mutableStateListOf<PersonaTranscriptEntry>()
    private var personaHoldJob: Job? = null
    private var personaHoldHandled = false
    private var personaTap = false
    private var sideDownAt = 0L
    private var sideTracking = false
    private var sideLongHandled = false
    private var sideWasListening = false
    private var sidePressJob: Job? = null
    private var clockJob: Job? = null
    private lateinit var profile: Profile
    private val profileUi = ProfileUi()
    /** Which way the next page change moves: forward for go, back for Back and Home. */
    private var forward by mutableStateOf(true)

    /** Ask AI: one voice turn, from the first question until the command has finished on screen. */
    private val ask = AskState()
    private var askJob: Job? = null
    /** A recognised command being carried out on screen; any touch or key cancels the rest of it. */
    private var voiceFlow: Job? = null
    /** Bumped by "tap to measure again": restarts the open vitals page's single measurement. */
    private var measureRun by mutableIntStateOf(0)
    private var debugVoice: BroadcastReceiver? = null

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (page == Page.Persona) {
            if (granted) startPersonaListening()
            else {
                personaTranscript.add(PersonaTranscriptEntry("NOLEE", "Microphone permission is needed to ask a question."))
                personaTranscriptOpen = true
            }
        } else if (granted) beginListening() else voiceFailed("MICROPHONE PERMISSION NEEDED")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        device = DeviceState(this)
        device.start()
        profile = Profile(this)
        vitals = VitalsMonitor(this)
        lid = LidScroll(this, ::onLidStep)
        voice = VoiceListener(
            this,
            ask.meter,
            onReady = { if (ask.phase == AskPhase.Preparing) ask.phase = AskPhase.Listening },
            onCaption = { ask.caption = it; interact() },
            onComplete = ::onVoiceResult,
            onFailure = ::voiceFailed,
        )
        // Bench hook for checking the voice flows without speaking. Debug builds only.
        if (BuildConfig.DEBUG) {
            debugVoice = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    intent?.getStringExtra("cloud_question")?.let { question ->
                        if (page == Page.Persona) cloudAi?.debugQuestion(question)
                        return
                    }
                    val previewState=intent?.getStringExtra("persona_state")
                    if(previewState!=null && page==Page.Persona){
                        val state=when(previewState){
                            "thinking" -> PersonaEmotion.Thinking
                            "speaking" -> PersonaEmotion.Speaking
                            "regular" -> PersonaEmotion.Regular
                            else -> return
                        }
                        stopPersonaListening()
                        personaEmotion=state
                        intent.getStringExtra("text")?.takeIf { it.isNotBlank() }?.let {
                            personaTranscript.add(PersonaTranscriptEntry(if(state==PersonaEmotion.Speaking)"NOLEE" else "YOU",it))
                        }
                        return
                    }
                    val text = intent?.getStringExtra("text") ?: return
                    voiceFlow?.cancel()
                    voice.cancel()
                    ask.retries = 0
                    if (page != Page.Home) home()
                    onVoiceResult(text)
                }
            }.also { ContextCompat.registerReceiver(this, it, IntentFilter(DEBUG_VOICE),
                "android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED) }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Half-size decode: the artwork is drawn about 416 px wide, so the full 1024 × 1536 PNG only costs memory.
        val peace = BitmapFactory.decodeResource(resources, R.drawable.peace, BitmapFactory.Options().apply { inSampleSize = 2 }).asImageBitmap()
        enterHome()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                interact()
                // Sensors run only for the page that needs them, and only while the app is in front. Changing page
                // or pausing cancels the run, which turns sensors, LEDs and the wakelock off.
                launch {
                    snapshotFlow { page to measureRun }.collectLatest { (current, _) ->
                        when (current) {
                            Page.Vitals -> vitals.overview()
                            Page.HeartRate -> vitals.measure(VitalMetric.Heart)
                            Page.Oxygen -> vitals.measure(VitalMetric.Oxygen)
                            Page.Pressure -> vitals.measure(VitalMetric.Pressure)
                            else -> Unit
                        }
                    }
                }
                // Idle timers restart on every touch, key, lid step, caption and page change, and hold while Ask AI runs.
                snapshotFlow { Triple(page, lastInteraction, ask.active) }.collectLatest { (current, last, busy) ->
                    if (busy) return@collectLatest
                    when (current) {
                        Page.Home -> {
                            delay((last + HOME_IDLE_MS - SystemClock.uptimeMillis()).coerceAtLeast(0))
                            go(Page.Watch)
                        }
                        Page.Watch -> {
                            delay((last + faceIdleDelay - SystemClock.uptimeMillis()).coerceAtLeast(0))
                            faceIdle = true
                            faceIdleDelay = FACE_IDLE_MS
                        }
                        else -> Unit
                    }
                }
            }
        }

        setContent {
            val compositionScope = rememberCoroutineScope()
            SideEffect { kioskExitScope = compositionScope }
            BackHandler {
                when {
                    personaTranscriptOpen -> personaTranscriptOpen = false
                    ask.listening -> cancelListening()
                    // Back leaves an open profile field before it leaves the page.
                    profileUi.editing != null -> profileUi.editing = null
                    page == Page.Persona -> returnToWatch()
                    else -> back()
                }
            }
            // Keyed on the entry, so the frame that first shows Home already reads 0 (everything hidden). A shared
            // clock kept its finished value for that frame, which flashed the whole page before the entrance.
            val clock = remember(homeEntry) { mutableLongStateOf(0L) }
            // Home's motion clock. It stops for good once the entrance, typewriter and permitted idle cycles have
            // finished, so a resting Home draws no frames at all.
            LaunchedEffect(homeEntry) {
                val start = withFrameMillis { it }
                val end = max(HomeMotion.LOOPS_END_MS, Greetings.endMs(phrase)) + 32
                while (true) {
                    val elapsed = withFrameMillis { it } - start
                    clock.longValue = elapsed
                    if (elapsed >= end) break
                }
            }
            val dimmed = (faceIdle && page == Page.Watch) || page == Page.Persona || personaActive
            val dim = animateFloatAsState(if (dimmed) 1f else 0f, tween(if (dimmed) 900 else 260), label = "faceIdle")
            val personaMorph = remember { derivedStateOf { (personaSeconds.floatValue / PERSONA_BODY_SECONDS).coerceIn(0f, 1f) } }
            val fullDial = remember { mutableFloatStateOf(1f) }
            KioskExitSurface(kioskExitProgress) {
            StageSurface { stage ->
                PersonaGlitchSurface(stage, page == Page.Persona && !personaReturning &&
                    personaSeconds.floatValue >= PERSONA_ENTER_SECONDS && personaEmotion == PersonaEmotion.Regular) {
                // The watch face and the three measuring instruments sit on the plain grid, without the peace artwork.
                val plain = page == Page.Watch || page == Page.Persona || page == Page.HeartRate || page == Page.Oxygen || page == Page.Pressure
                Backdrop(stage, peace, clock, watchMode = plain)
                // Idle watch face: cover the grid, then fade the header, rails and bar; only the dial stays.
                Canvas(Modifier.fillMaxSize()) { if (dim.value > 0f) drawRect(Palette.Ground, alpha = dim.value) }
                val chromeAlpha = 1f - dim.value
                if (page != Page.Persona && !personaActive) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = chromeAlpha }) {
                        StatusHeader(stage, device.time, device.battery)
                    }
                }
                PageSwitch(stage, page, forward) { shown ->
                    when (shown) {
                        Page.Home -> HomeScreen(stage, drum, clock, phrase, device.kioskActive, ask, ::open)
                        Page.Watch -> TimeScreen(
                            stage,
                            dim = dim,
                            settled = watchSettled,
                            personaMorph = personaMorph,
                            onFullScreen = {
                                lastInteraction = SystemClock.uptimeMillis()
                                faceIdle = true
                            },
                        )
                        Page.Persona -> if (personaMorph.value < .6f) {
                            TimeScreen(stage, dim = fullDial, settled = true, personaMorph = personaMorph)
                        }
                        Page.Vitals -> VitalsScreen(stage, vitals, device, vitalsDrum) { go(it.page) }
                        Page.HeartRate -> VitalMeasureScreen(stage, VitalMetric.Heart, vitals) { measureRun++ }
                        Page.Oxygen -> VitalMeasureScreen(stage, VitalMetric.Oxygen, vitals) { measureRun++ }
                        Page.Pressure -> VitalMeasureScreen(stage, VitalMetric.Pressure, vitals) { measureRun++ }
                        Page.Power -> PowerPage(stage, device)
                        Page.AiVoice -> CloudAiVoicePage(stage) { back() }
                        Page.NoleeAi -> CloudAiPage(stage, openVoice = { go(Page.AiVoice) }) {
                            go(Page.Watch)
                            faceIdle = true
                            beginPersona()
                            commitPersona()
                        }
                        else -> SystemPages(stage, shown, device, systemDrum, profile, profileUi, ::go, ::exitKiosk)
                    }
                }
                if (timeExit > 0) key(timeExit) {
                    TimeScreen(stage, exiting = true, onExited = { timeExit = 0 })
                }
                // Keep this outside PageSwitch: committing the hold must not recreate the native view.
                if (personaActive && (page == Page.Watch || page == Page.Persona)) {
                    PersonaScreen(stage, personaSeconds.floatValue, personaStartedAt, personaEmotion, personaListening, personaReturning, personaCameraView, personaCameraRevision, exitProgress = { kioskExitProgress.value }) { personaCameraView = it }
                }
                if (page != Page.Persona && !personaActive) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = chromeAlpha }) {
                        val mode = when {
                            page != Page.Home -> BarMode.Home
                            ask.listening -> BarMode.Cancel
                            else -> BarMode.VoiceCommand
                        }
                        BottomBar(stage, mode, clock) {
                            when (mode) {
                                BarMode.VoiceCommand -> requestListening()
                                BarMode.Cancel -> cancelListening()
                                BarMode.Home -> home()
                            }
                        }
                    }
                }
                // While listening, a tap anywhere cancels rather than reaching the page underneath.
                if (ask.listening) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { cancelListening() } })
                Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = 1f - (kioskExitProgress.value / .34f).coerceIn(0f, 1f)
                }) {
                EdgeLight(stage, ask.listening || personaListening, tealGradient = false,
                    transitionProgress = if (page == Page.Persona && personaHoldPreview > 0f && !personaListening) personaHoldPreview
                        else if (personaActive && !personaReturning && personaSeconds.floatValue < PERSONA_ENTER_SECONDS)
                        (personaSeconds.floatValue / .9f).coerceIn(0f, 1f) else null)
                }
                if (personaActive && (page == Page.Watch || page == Page.Persona)) {
                    PersonaLens(stage, (personaSeconds.floatValue / PERSONA_BODY_SECONDS).coerceIn(0f,1f))
                }
                }
                val transcriptAlpha by animateFloatAsState(
                    if(page==Page.Persona && personaTranscriptOpen)1f else 0f,
                    tween(if(personaTranscriptOpen)600 else 500, easing=EaseInOut), label="transcriptFade")
                SideEffect { transcriptLayerVisible=page==Page.Persona && (personaTranscriptOpen || transcriptAlpha>.001f) }
                if(page==Page.Persona && (personaTranscriptOpen || transcriptAlpha>.001f)){
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha=transcriptAlpha }){
                        PersonaTranscript(stage,personaTranscript,personaPartial,personaListening,
                            busy=personaEmotion!=PersonaEmotion.Regular,
                            enabled=personaTranscriptOpen && !personaReturning,
                            onListen={ restartPersonaListening() })
                    }
                }
            }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { kioskExitProgress.snapTo(0f) }
        hideBars()
        lid.start()
        clockJob = lifecycleScope.launch {
            while (isActive) {
                device.refresh()
                delay(1_000L * (60 - LocalDateTime.now().second))
            }
        }
    }

    override fun onPause() {
        if (personaReturning) { personaReturning = false; returnToWatchImmediately() }
        personaHoldJob?.cancel();personaTap=false
        // Never keep the microphone open behind another app.
        stopPersonaListening()
        if (ask.listening) cancelListening()
        voiceFlow?.cancel()
        watchPressJob?.cancel()
        personaMotion?.cancel()
        if (page == Page.Watch) { personaActive = false; personaSeconds.floatValue = 0f }
        else if (page == Page.Persona) personaSeconds.floatValue = PERSONA_ENTER_SECONDS
        sidePressJob?.cancel()
        sideTracking = false
        sideWasListening = false
        watchPressActive = false
        swallowing = false
        clockJob?.cancel()
        lid.stop()
        super.onPause()
    }

    override fun onDestroy() {
        profile.close()
        debugVoice?.let { runCatching { unregisterReceiver(it) } }
        voice.destroy()
        lid.destroy()
        device.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
        else if (watchPressActive) {
            watchPressActive = false
            watchPressJob?.cancel()
            reversePersona()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (ask.listening) cancelListening()
            home()
        }
    }

    private fun interact() {
        lastInteraction = SystemClock.uptimeMillis()
        faceIdle = false
        faceIdleDelay = FACE_IDLE_MS
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (kioskExiting) return true
        if(page==Page.Persona && (personaTranscriptOpen || transcriptLayerVisible))return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // The wearer taking over stops a voice command mid-flow.
            voiceFlow?.cancel()
            if (personaReturning) return true
            if (page == Page.Persona && event.x > window.decorView.width*.12f && isInWatchCircle(event.x,event.y)) {
                personaTap = true
                personaHoldHandled=false
                watchDownAt=event.eventTime
                watchDownX = event.x; watchDownY = event.y
                personaHoldJob?.cancel()
                personaPreviewReturn?.cancel()
                personaHoldJob=lifecycleScope.launch {
                    val started = SystemClock.uptimeMillis()
                    while (SystemClock.uptimeMillis() - started < PERSONA_HOLD_MS) {
                        personaHoldPreview = ((SystemClock.uptimeMillis() - started) / 900f).coerceIn(0f, 1f)
                        delay(16)
                    }
                    if(personaTap && page==Page.Persona && !personaReturning){
                        personaHoldHandled=true
                        restartPersonaListening()
                        personaHoldPreview = 0f
                    }
                }
                swallowing = true
                return true
            }
            if (page == Page.Watch && faceIdle && isInWatchCircle(event.x, event.y)) {
                watchDownX = event.x
                watchDownY = event.y
                watchDownAt = event.eventTime
                watchPressActive = true
                swallowing = true
                watchPressJob?.cancel()
                beginPersona()
                watchPressJob = lifecycleScope.launch {
                    delay(PERSONA_HOLD_MS)
                    if (watchPressActive && page == Page.Watch && faceIdle) {
                        commitPersona()
                    }
                }
                return true
            }
            swallowing = faceIdle && page == Page.Watch
            interact()
        }
        if(event.actionMasked==MotionEvent.ACTION_MOVE && personaTap){
            val dx=event.x-watchDownX;val dy=event.y-watchDownY
            if(dx*dx+dy*dy>18f*18f) {
                personaHoldJob?.cancel()
                reverseHoldPreview()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && watchPressActive) {
            val dx = event.x - watchDownX
            val dy = event.y - watchDownY
            if (dx * dx + dy * dy > 18f * 18f) {
                watchPressActive = false
                watchPressJob?.cancel()
                reversePersona()
            }
        }
        if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) && watchPressActive) {
            val completedHold = event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - watchDownAt >= PERSONA_HOLD_MS
            watchPressActive = false
            watchPressJob?.cancel()
            if (completedHold && page == Page.Watch && faceIdle) commitPersona() else reversePersona()
        }
        if (personaTap && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            personaHoldJob?.cancel()
            if (!personaHoldHandled) reverseHoldPreview()
            val dx=event.x-watchDownX;val dy=event.y-watchDownY
            if(event.actionMasked==MotionEvent.ACTION_UP && page==Page.Persona && !personaHoldHandled){
                if(abs(dx)>48f && abs(dx)>abs(dy)*1.25f){
                    personaCameraView=(personaCameraView + if(dx<0f)1 else 3)%4
                    personaCameraRevision++
                    personaIdleReturn?.cancel();personaIdleReturn=null
                } else if (abs(dy)>48f && abs(dy)>abs(dx)*1.25f) {
                    personaCameraView=if(dy<0f)3 else 0
                    personaCameraRevision++
                    personaIdleReturn?.cancel();personaIdleReturn=null
                }
            }
            personaTap = false
        }
        if (swallowing) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    /** The full-screen dial and orb share this exact centre and radius. */
    private fun isInWatchCircle(x: Float, y: Float): Boolean {
        val scale = if (SafeZone.isCalibrated(window.decorView.width, window.decorView.height)) {
            (window.decorView.width - 2f * SafeZone.EDGE_INSET_PX) / (Design.WIDTH - 2f * Design.RIM_INSET)
        } else {
            minOf(window.decorView.width / Design.WIDTH, window.decorView.height / Design.HEIGHT)
        }
        val dx = x - window.decorView.width / 2f
        val dy = y - window.decorView.height / 2f
        val radius = 184f * scale
        return dx * dx + dy * dy <= radius * radius
    }

    private fun beginPersona() {
        personaMotion?.cancel()
        if (!personaActive) {
            personaTranscriptOpen=false;personaTranscript.clear();personaPartial=""
            personaStartedAt = LocalDateTime.now()
            personaCameraView = 0
            personaEmotion = PersonaEmotion.Regular
            personaSeconds.floatValue = 0f
        }
        personaActive = true
        val from = personaSeconds.floatValue
        personaMotion = lifecycleScope.launch {
            val start = SystemClock.uptimeMillis()
            while (isActive) {
                personaSeconds.floatValue = (from + (SystemClock.uptimeMillis() - start) / 1000f).coerceAtMost(PERSONA_ENTER_SECONDS)
                if (personaSeconds.floatValue >= PERSONA_ENTER_SECONDS) {
                    if (page == Page.Persona) startPersonaListening()
                    break
                }
                delay(33)
            }
        }
    }

    /** A hold interrupts the current cloud turn and opens a fresh microphone window. */
    private fun reverseHoldPreview() {
        if (personaPreviewReturn?.isActive == true || personaHoldPreview <= 0f) return
        val from = personaHoldPreview
        personaPreviewReturn = lifecycleScope.launch {
            val started = SystemClock.uptimeMillis()
            while (isActive) {
                val p = ((SystemClock.uptimeMillis() - started) / 420f).coerceIn(0f, 1f)
                personaHoldPreview = from * (1f - auroraSmooth(p))
                if (p >= 1f) break
                delay(16)
            }
        }
    }

    private fun restartPersonaListening() {
        if (page != Page.Persona || personaReturning) return
        personaIdleReturn?.cancel()
        personaPartial=""
        cloudAi?.cancel()
        personaListening = false
        personaEmotion=PersonaEmotion.Regular
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(65L,255))
        startPersonaListening()
    }

    private fun startPersonaListening() {
        if (page != Page.Persona || personaEmotion != PersonaEmotion.Regular || personaListening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        cancelListening()
        personaHeardSpeech = false
        personaListening = true
        val client = cloudAi ?: CloudAi(applicationContext,
            profileFacts = {
                ProfileField.entries.mapNotNull { field ->
                    profile[field].takeIf(String::isNotBlank)?.let { "${field.label}: $it" }
                }
            }, listener = ::onCloudState, onCommands = { commands ->
                if (page == Page.Persona && !personaReturning) {
                    personaIdleReturn?.cancel()
                    voiceFlow?.cancel()
                    voiceFlow = lifecycleScope.launch {
                        try {
                            if (VoiceCommand.ExitKiosk in commands) {
                                // Exit owns its transition and must not resume the microphone.
                                stopPersonaListening()
                                perform(VoiceCommand.ExitKiosk)
                                return@launch
                            }
                            // Save conversation updates while the persona is still alive, even in a mixed batch.
                            commands.filterNot { it.needsDeviceNavigation() }.forEach { perform(it) }
                            val navigating = commands.filter { it.needsDeviceNavigation() }
                            if (navigating.isNotEmpty()) {
                                personaTranscriptOpen = false
                                returnToWatch()
                                personaMotion?.join()
                                if (page != Page.Watch) return@launch
                                delay(650L)
                                home()
                                delay(1_100L)
                                navigating.forEach { perform(it) }
                            }
                            if (page == Page.Persona && !personaReturning) cloudAi?.resumeAfterCommands()
                        }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) {
                            android.widget.Toast.makeText(this@MainActivity, "Could not complete the device command. Check the setting and try again.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }).also { cloudAi = it }
        client.openListening()
    }

    private fun onCloudState(state: CloudAiState) {
        if (page != Page.Persona || personaReturning) return
        personaListening = state.phase == CloudAiPhase.RECORDING
        personaEmotion = when (state.phase) {
            CloudAiPhase.THINKING -> PersonaEmotion.Thinking
            CloudAiPhase.SPEAKING -> PersonaEmotion.Speaking
            else -> PersonaEmotion.Regular
        }
        personaPartial = when (state.phase) {
            CloudAiPhase.RECORDING -> "Listening…"
            CloudAiPhase.THINKING -> if (state.turns.lastOrNull()?.question.isNullOrBlank()) "Transcribing…" else ""
            else -> ""
        }
        personaTranscript.clear()
        state.turns.forEach { turn ->
            if (turn.question.isNotBlank()) personaTranscript.add(PersonaTranscriptEntry("YOU", turn.question, turn.id))
            if (turn.answer.isNotBlank()) personaTranscript.add(PersonaTranscriptEntry("NOLEE", turn.answer, turn.id))
        }
        if (state.phase == CloudAiPhase.ERROR) {
            personaTranscript.add(PersonaTranscriptEntry("NOLEE", state.message))
            personaTranscriptOpen = true
        }
        personaIdleReturn?.cancel()
        if (state.phase == CloudAiPhase.READY) personaIdleReturn = lifecycleScope.launch {
            delay(if (state.closeAfterOpeningSilence && state.turns.isEmpty()) 5_000L else 30_000L)
            personaIdleReturn = null
            if (page == Page.Persona && !personaReturning && !personaListening &&
                !personaTranscriptOpen && personaEmotion == PersonaEmotion.Regular) returnToWatch()
        }
    }

    private fun stopPersonaListening() {
        personaPreviewReturn?.cancel(); personaHoldPreview = 0f
        personaIdleReturn?.cancel(); personaIdleReturn = null
        personaListening = false
        cloudAi?.destroy()
        cloudAi = null
        personaEmotion = PersonaEmotion.Regular
    }

    private fun commitPersona() {
        if (page != Page.Watch || !personaActive) return
        watchPressActive = false
        // Fire only at the irreversible 650 ms hold commit, never on initial touch.
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(
            longArrayOf(0, 65, 35, 95), intArrayOf(0, 255, 0, 235), -1))
        go(Page.Persona)
        personaTranscriptOpen = !CloudAi.spokenAnswers(this)
    }

    private fun reversePersona() {
        if (page != Page.Watch || !personaActive) return
        personaMotion?.cancel()
        val from = personaSeconds.floatValue
        personaMotion = lifecycleScope.launch {
            val start = SystemClock.uptimeMillis()
            while (isActive) {
                val p = ((SystemClock.uptimeMillis() - start) / 420f).coerceIn(0f, 1f)
                personaSeconds.floatValue = from * (1f - auroraSmooth(p))
                if (p >= 1f) break
                delay(33)
            }
            personaSeconds.floatValue = 0f
            personaActive = false
            faceIdle = true
            lastInteraction = SystemClock.uptimeMillis()
        }
    }

    /** Lid scroll: rolls the drum on drum pages, and scrolls the list on pages with one. */
    private fun onLidStep(step: Int) {
        if (ask.listening) return
        val wasIdle = faceIdle && page == Page.Watch
        interact()
        if (wasIdle) return
        when (page) {
            Page.Home -> drum.step(step)
            Page.System -> systemDrum.step(step)
            Page.Vitals -> vitalsDrum.step(step)
            Page.Profile -> if (profileUi.editing == null) profileUi.drum.step(step) else LidTargets.scroll?.invoke(step)
            else -> LidTargets.scroll?.invoke(step)
        }
    }

    /**
     * Keys are taken before Compose sees them. Left to onKeyDown, Compose used the D-pad to move focus onto the
     * bottom bar and Enter then clicked it, so the lid keys opened Ask AI instead of the selected card.
     */
    // Android's public Activity hook; the inherited AndroidX bridge carries a library annotation.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (kioskExiting && event.keyCode !in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        // Firmware emits a discrete F6 pulse after a lid long-touch, not a held key.
        if (page == Page.Persona && event.keyCode in setOf(KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F2)) {
            if (event.keyCode == KeyEvent.KEYCODE_F6 && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                personaTranscriptOpen = !personaTranscriptOpen
                personaIdleReturn?.cancel()
                buzz(TAP_MS)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            interact()
            voiceFlow?.cancel()
        }

        // Original Home/Watch shortcut; the persona returns to Watch. Hold opens Power.
        if (event.keyCode == KeyEvent.KEYCODE_F9) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    sideDownAt = event.eventTime
                    sideTracking = true
                    sideLongHandled = false
                    sideWasListening = ask.listening
                    sidePressJob?.cancel()
                    sidePressJob = lifecycleScope.launch {
                        delay(LONG_PRESS_MS)
                        showPowerFromSideButton()
                    }
                } else if (event.isLongPress) {
                    sidePressJob?.cancel()
                    showPowerFromSideButton()
                }
                KeyEvent.ACTION_UP -> {
                    sidePressJob?.cancel()
                    if (sideTracking && !event.isCanceled) {
                        if (!sideLongHandled && event.eventTime - sideDownAt >= LONG_PRESS_MS) {
                            showPowerFromSideButton()
                        } else if (!sideLongHandled) {
                            if (sideWasListening) cancelListening()
                            else when (page) {
                                Page.Home -> go(Page.Watch)
                                Page.Watch -> home()
                                Page.Persona -> returnToWatch()
                                else -> Unit
                            }
                        }
                    }
                    sideTracking = false
                    sideLongHandled = false
                    sideWasListening = false
                }
            }
            return true
        }

        if (ask.listening && event.keyCode in VOICE_CANCEL_KEYS) {
            if (event.action == KeyEvent.ACTION_UP) cancelListening()
            return true
        }

        val drumKeys = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_F2)
        val active = when (page) {
            Page.Home -> drum
            Page.System -> systemDrum
            Page.Vitals -> vitalsDrum
            // While a field is open the keys belong to the keyboard, not the drum.
            Page.Profile -> profileUi.drum.takeIf { profileUi.editing == null }
            else -> null
        }
        val handled = event.keyCode == KeyEvent.KEYCODE_F5 || (event.keyCode in drumKeys && active != null)
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_F5 -> home()
            KeyEvent.KEYCODE_DPAD_UP -> active?.step(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> active?.step(1)
            else -> if (event.repeatCount == 0) when (page) {
                Page.Home -> open(AppEntry.entries[drum.selectedIndex])
                Page.System -> go(SystemEntry.entries[systemDrum.selectedIndex].page)
                Page.Vitals -> VitalItem.entries[vitalsDrum.selectedIndex].metric?.let { go(it.page) }
                Page.Profile -> profileUi.editing = ProfileField.entries[profileUi.drum.selectedIndex]
                else -> Unit
            }
        }
        return true
    }

    private fun showPowerFromSideButton() {
        if (!sideTracking || sideLongHandled) return
        sideLongHandled = true
        if (ask.listening) cancelListening()
        if (page != Page.Power) go(Page.Power)
        buzz(TAP_MS)
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Each entry shows the next greeting phrase, spins the drum in and restarts Home's motion clock. */
    private fun enterHome() {
        val phrases = Greetings.phrases(LocalDateTime.now().hour, profile.name)
        val sequence = prefs.getInt("greetingSequence", 0)
        phrase = phrases[Math.floorMod(sequence, phrases.size)]
        prefs.edit().putInt("greetingSequence", Math.floorMod(sequence + 1, phrases.size)).apply()
        drum.spinIn()
        homeEntry++
    }

    private fun show(next: Page) {
        if (next == page) return
        watchSettled = next == Page.Watch && page == Page.Persona
        if (next != Page.Persona) {
            personaHoldJob?.cancel();personaTap=false
            personaTranscriptOpen=false
            personaReturning = false
            stopPersonaListening()
            personaMotion?.cancel(); personaActive = false; personaSeconds.floatValue = 0f
        }
        interact()
        if (page == Page.Profile) profileUi.editing = null
        if (page == Page.Watch && next != Page.Persona) timeExit++
        if (next == Page.Home) enterHome()
        if (next == Page.Wifi || next == Page.Bluetooth) requestLocation()
        page = next
    }

    private fun go(next: Page) {
        backStack.addLast(page)
        forward = true
        show(next)
    }

    private fun back() {
        forward = false
        show(backStack.removeLastOrNull() ?: Page.Home)
    }

    /** Return the persona to the same full-screen watch state it grew out of. */
    private fun returnToWatch() {
        if (personaReturning) return
        stopPersonaListening()
        personaMotion?.cancel()
        personaReturning = true
        val from = personaSeconds.floatValue
        personaMotion = lifecycleScope.launch {
            val start = SystemClock.uptimeMillis()
            while (isActive) {
                val progress = ((SystemClock.uptimeMillis()-start)/950f).coerceIn(0f,1f)
                personaSeconds.floatValue = from*(1f-auroraSmooth(progress))
                if(progress>=1f)break
                delay(16)
            }
            personaMotion = null
            returnToWatchImmediately()
        }
    }

    private fun returnToWatchImmediately() {
        forward = false
        val target = backStack.removeLastOrNull()
        show(if (target == Page.Watch) Page.Watch else target ?: Page.Watch)
        if (page == Page.Watch) faceIdle = true
    }

    private fun home() {
        backStack.clear()
        forward = false
        show(Page.Home)
    }

    /** Wi-Fi scan results and Bluetooth discovery come back empty on API 28 without location permission. */
    private fun requestLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun open(entry: AppEntry) {
        when (entry) {
            AppEntry.Watch -> go(Page.Watch)
            AppEntry.Vitals -> go(Page.Vitals)
            AppEntry.System -> go(Page.System)
            AppEntry.NoleeAi -> go(Page.NoleeAi)
            else -> if (!device.launch(entry.packageName!!)) {
                Toast.makeText(this, "${entry.label} is not installed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private lateinit var kioskExitScope: CoroutineScope
    private val kioskExitProgress = Animatable(0f)
    private var kioskExiting by mutableStateOf(false)

    private fun exitKiosk() {
        if (kioskExiting) return
        kioskExiting = true
        stopPersonaListening()
        // Use Compose's frame clock, independent of the voice job cancelled onPause.
        kioskExitScope.launch {
            var handedOff = false
            try {
                kioskExitProgress.animateTo(1f, tween(920, easing = androidx.compose.animation.core.LinearEasing))
                val problem = device.exitKiosk()
                handedOff = problem == null
                device.refresh()
                if (problem != null) {
                    kioskExitProgress.animateTo(0f, tween(240))
                    Toast.makeText(this@MainActivity, problem, Toast.LENGTH_SHORT).show()
                }
            } finally {
                kioskExiting = false
                if (!handedOff) kioskExitProgress.snapTo(0f)
            }
        }
    }

    /** Ask AI: listen for one voice command. */
    private fun requestListening() {
        if (ask.listening) return
        stopPersonaListening()
        interact()
        voiceFlow?.cancel()
        // A firm double tap. The single 55 ms pulse at amplitude 150 ran, but was too faint to feel on the wrist.
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 45, 70, 55), intArrayOf(0, 255, 0, 255), -1))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginListening()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** Starts a turn, or with [again] another round of the current one after a miss. */
    private fun beginListening(again: Boolean = false) {
        askJob?.cancel()
        ask.caption = ""
        ask.detail = ""
        ask.retrying = false
        if (!again) {
            ask.retries = 0
            ask.prompt++
        }
        ask.phase = AskPhase.Preparing
        voice.start(VoiceGrammar.phrases)
    }

    private fun cancelListening() {
        personaIdleReturn?.cancel(); personaIdleReturn = null
        personaListening = false
        askJob?.cancel()
        voice.cancel()
        ask.phase = AskPhase.Off
    }

    private fun onVoiceResult(text: String) {
        ask.caption = text
        val command = VoiceGrammar.parse(text)
        askJob?.cancel()
        askJob = lifecycleScope.launch {
            if (command == null) {
                ask.missedSilence = text.isBlank()
                // Silence ends the turn: nobody is speaking to the watch. Words that are not a command get another go.
                ask.retrying = !ask.missedSilence && ask.retries < AskPrompts.MAX_RETRIES
                ask.phase = AskPhase.Missed
                if (ask.retrying) {
                    delay(RETRY_GAP_MS)
                    ask.retries++
                    askJob = null // This job is ending; beginListening must not cancel it before the microphone reopens.
                    getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(40L, 220))
                    beginListening(again = true)
                } else {
                    delay(MISSED_HOLD_MS)
                    ask.phase = AskPhase.Off
                }
                return@launch
            }
            ask.detail = describe(command)
            ask.phase = AskPhase.Heard
            delay(HEARD_HOLD_MS)
            ask.phase = AskPhase.Executing
            voiceFlow?.cancel()
            voiceFlow = lifecycleScope.launch {
                try {
                    delay(480L) // The drum is back in place before the first card moves.
                    perform(command)
                    delay(600L) // The result lands before Home stops reading AI IN CONTROL.
                } finally {
                    // A new turn may already have started; only this flow's own phase is cleared.
                    if (ask.phase == AskPhase.Executing) ask.phase = AskPhase.Off
                }
            }
        }
    }

    private fun voiceFailed(message: String) {
        askJob?.cancel()
        voice.cancel()
        ask.caption = ""
        ask.detail = message
        ask.phase = AskPhase.Failed
        askJob = lifecycleScope.launch {
            delay(FAILED_HOLD_MS)
            ask.phase = AskPhase.Off
        }
    }

    private fun describe(command: VoiceCommand): String = when (command) {
        VoiceCommand.StartAi -> "OPENING NOLEE AI"
        VoiceCommand.ExitKiosk -> "LEAVING KIOSK"
        is VoiceCommand.AiVolume -> "AI / MEDIA VOLUME ${command.percent}%"
        is VoiceCommand.UpdateProfile -> "UPDATING PROFILE"
        is VoiceCommand.Transcript -> if(command.on) "SHOWING TRANSCRIPT" else "HIDING TRANSCRIPT"
        is VoiceCommand.SpokenAnswers -> if(command.on) "AI VOICE ON" else "AI VOICE MUTED"
        is VoiceCommand.Open -> "OPENING ${VoiceGrammar.title(command.page)}"
        is VoiceCommand.Launch -> "OPENING ${command.entry.label.uppercase()}"
        is VoiceCommand.Radio -> "${if (command.wifi) "WI-FI" else "BLUETOOTH"} ${if (command.on) "ON" else "OFF"}"
        is VoiceCommand.Brightness -> "BRIGHTNESS ${command.percent}%"
        is VoiceCommand.Adaptive -> "ADAPTIVE BRIGHTNESS ${if (command.on) "ON" else "OFF"}"
        is VoiceCommand.Volume -> "${command.stream.label} ${command.percent}%"
        is VoiceCommand.AutoClock -> "AUTOMATIC ${if (command.zone) "ZONE" else "TIME"} ${if (command.on) "ON" else "OFF"}"
        is VoiceCommand.Zone -> "ZONE ${command.id.substringAfterLast('/').replace('_', ' ').uppercase()}"
        is VoiceCommand.EditProfile -> "EDITING ${command.field.label.uppercase()}"
        VoiceCommand.MuteAll -> "MUTING ALL SOUND"
        VoiceCommand.RestoreSound -> if (device.savedLevels != null) "RESTORING LEVELS" else "NOTHING TO RESTORE"
    }

    /**
     * Carries out a command the way Harness does: visibly, through the screens a person would use, so it reads as the
     * AI operating the watch. The drum rolls a card at a time with a tick, the chosen card flashes and opens, and the
     * control on the destination page moves to its new value. A touch or key cancels the rest.
     */
    private suspend fun perform(command: VoiceCommand) {
        when (command) {
            VoiceCommand.ExitKiosk -> exitKiosk()
            is VoiceCommand.AiVolume -> {
                val stream = SoundStream.Media.stream
                device.setStream(stream, (command.percent * device.streamMax(stream) + 50) / 100)
                val expected = (command.percent * device.streamMax(stream) + 50) / 100
                check(device.streamLevel(stream) == expected) { "Media volume did not change" }
                device.savedLevels = null
            }
            VoiceCommand.StartAi -> if (page != Page.Persona) {
                openFromHome(AppEntry.Watch)
                delay(PAGE_SETTLE_MS)
                faceIdle = true
                delay(650L)
                beginPersona()
                commitPersona()
            }
            is VoiceCommand.UpdateProfile -> command.values.forEach { (field, value) -> profile[field] = value }
            is VoiceCommand.Transcript -> {
                personaTranscriptOpen = command.on
                personaIdleReturn?.cancel()
            }
            is VoiceCommand.SpokenAnswers -> {
                CloudAi.setSpokenAnswers(this,command.on)
                if (!command.on && page == Page.Persona) {
                    personaTranscriptOpen = true
                    personaIdleReturn?.cancel()
                }
            }
            is VoiceCommand.Open -> when (val target = command.page) {
                Page.Home -> home()
                Page.Watch -> openFromHome(AppEntry.Watch)
                Page.Vitals -> openFromHome(AppEntry.Vitals)
                Page.HeartRate, Page.Oxygen, Page.Pressure -> {
                    openFromHome(AppEntry.Vitals)
                    delay(PAGE_SETTLE_MS)
                    rollTo(vitalsDrum, VitalItem.entries.indexOfFirst { it.metric?.page == target })
                    openCard(vitalsDrum) { go(target) }
                }
                Page.System -> openFromHome(AppEntry.System)
                Page.NoleeAi -> openFromHome(AppEntry.NoleeAi)
                else -> openSystem(SystemEntry.entries.first { it.page == target })
            }
            is VoiceCommand.Launch -> openFromHome(command.entry)
            is VoiceCommand.Radio -> {
                openSystem(if (command.wifi) SystemEntry.Wifi else SystemEntry.Bluetooth)
                delay(ACT_DELAY_MS)
                buzz(TAP_MS)
                if (command.wifi) device.setWifi(command.on) else device.setBluetooth(command.on)
            }
            is VoiceCommand.Adaptive -> {
                openSystem(SystemEntry.Display)
                delay(ACT_DELAY_MS)
                buzz(TAP_MS)
                device.setAdaptive(command.on)
            }
            is VoiceCommand.Brightness -> {
                openSystem(SystemEntry.Display)
                delay(ACT_DELAY_MS)
                if (device.adaptive) {
                    buzz(TAP_MS)
                    device.setAdaptive(false)
                    delay(420L)
                }
                sweepBrightness((command.percent * 255 + 50) / 100)
            }
            is VoiceCommand.Volume -> {
                openSystem(SystemEntry.Sound)
                delay(ACT_DELAY_MS)
                val max = device.streamMax(command.stream.stream)
                sweepLevels(mapOf(command.stream to (command.percent * max + 50) / 100))
                device.savedLevels = null
            }
            is VoiceCommand.AutoClock -> {
                openSystem(SystemEntry.DateTime)
                delay(ACT_DELAY_MS)
                buzz(TAP_MS)
                if (command.zone) device.setAutoTimeZone(command.on) else device.setAutoTime(command.on)
            }
            is VoiceCommand.Zone -> {
                openSystem(SystemEntry.DateTime)
                delay(ACT_DELAY_MS)
                // A zone set by hand only sticks with Android's automatic zone off, so that goes first.
                if (device.autoTimeZone) {
                    buzz(TAP_MS)
                    device.setAutoTimeZone(false)
                    delay(420L)
                }
                buzz(TAP_MS)
                device.setTimeZone(command.id)
            }
            is VoiceCommand.EditProfile -> {
                openSystem(SystemEntry.Profile)
                delay(PAGE_SETTLE_MS)
                rollTo(profileUi.drum, command.field.ordinal)
                openCard(profileUi.drum) { profileUi.editing = command.field }
            }
            VoiceCommand.MuteAll -> {
                openSystem(SystemEntry.Sound)
                delay(ACT_DELAY_MS)
                if (device.savedLevels == null) device.savedLevels = SoundStream.entries.associate { it.stream to device.streamLevel(it.stream) }
                sweepLevels(SoundStream.entries.associateWith { 0 })
            }
            VoiceCommand.RestoreSound -> {
                openSystem(SystemEntry.Sound)
                delay(ACT_DELAY_MS)
                val saved = device.savedLevels ?: return
                sweepLevels(SoundStream.entries.filter { it.stream in saved }.associateWith { saved.getValue(it.stream) })
                device.savedLevels = null
            }
        }
    }

    private suspend fun openFromHome(entry: AppEntry) {
        if (page != Page.Home) {
            home()
            delay(PAGE_SETTLE_MS)
        }
        rollTo(drum, entry.ordinal)
        openCard(drum) { open(entry) }
    }

    private suspend fun openSystem(entry: SystemEntry) {
        openFromHome(AppEntry.System)
        delay(PAGE_SETTLE_MS)
        rollTo(systemDrum, entry.ordinal)
        openCard(systemDrum) { go(entry.page) }
    }

    /** One card at a time, the short way round, with a tick for each. */
    private suspend fun rollTo(state: DrumState, index: Int) {
        while (state.selectedIndex != index) {
            val forward = Math.floorMod(index - state.selectedIndex, state.count)
            state.step(if (forward <= state.count / 2) 1 else -1)
            interact()
            buzz(TICK_MS)
            delay(STEP_MS)
        }
    }

    /** The drum's own launch: a beat on the chosen card, its flash, then open. */
    private suspend fun openCard(state: DrumState, action: () -> Unit) {
        delay(CHOOSE_MS)
        buzz(TAP_MS)
        state.launching = state.selectedIndex
        try {
            delay(150L)
        } finally {
            state.launching = -1
        }
        action()
    }

    /** The Display bar and the panel sweep together; the setting is written once, at the end. */
    private suspend fun sweepBrightness(target: Int) {
        val start = device.brightness
        val end = target.coerceIn(8, 255)
        try {
            repeat(32) { frame ->
                val t = EaseInOut.transform((frame + 1f) / 32)
                device.previewBrightness((start + (end - start) * t).roundToInt())
                delay(28L)
            }
            device.setBrightness(end)
        } finally {
            device.endBrightnessPreview()
        }
    }

    /** Levels step toward their targets together, one step per 75 ms, so the Sound page's bars visibly move. */
    private suspend fun sweepLevels(targets: Map<SoundStream, Int>) {
        val starts = targets.keys.associateWith { device.streamLevel(it.stream) }
        val frames = targets.maxOf { (sound, level) -> abs(level - starts.getValue(sound)) }.coerceAtLeast(1)
        repeat(frames) { frame ->
            val p = (frame + 1f) / frames
            targets.forEach { (sound, level) ->
                val from = starts.getValue(sound)
                device.setStream(sound.stream, (from + (level - from) * p).roundToInt())
            }
            delay(75L)
        }
    }

    private fun buzz(ms: Long) {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(ms, if (ms >= TAP_MS) 170 else 90))
    }
}
