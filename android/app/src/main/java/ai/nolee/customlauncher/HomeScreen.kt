package ai.nolee.customlauncher

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import kotlinx.coroutines.delay

private val centred = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** Rise used by the Home entrance: fade in while settling up from 10 design px. */
private fun Modifier.rise(stage: Stage, clock: State<Long>, delay: Long, duration: Long = 620) = graphicsLayer {
    val p = progress(clock.value, delay, duration, EaseOut)
    alpha = p
    translationY = stage.px(10f * (1 - p))
}

/**
 * Home, in the prototype's portrait design coordinates (content column x 39–371). While Ask AI listens, Home stays
 * put: the greeting retypes into a question, the subtitle reports progress, and a waveform and transcript take the
 * app drum's place.
 */
@Composable
fun HomeScreen(
    stage: Stage,
    drum: DrumState,
    clock: State<Long>,
    phrase: String,
    kioskActive: Boolean,
    ask: AskState,
    onOpen: (AppEntry) -> Unit,
) {
    BasicText(
        "PERSONAL SYSTEM",
        Modifier
            .at(stage, 39f, 78f)
            .drawWithContent {
                val p = progress(clock.value, 0, 500, EaseOut)
                clipRect(right = size.width * p) { this@drawWithContent.drawContent() }
            }
            .background(Palette.TagPaper)
            .padding(horizontal = stage.dp(7f), vertical = stage.dp(4f)),
        style = TextStyle(fontFamily = Spline, color = Palette.TagInk, fontSize = stage.sp(13f), letterSpacing = stage.sp(.5f), lineHeight = stage.sp(16f), lineHeightStyle = centred),
    )

    // Until someone speaks, ask something else every few seconds.
    LaunchedEffect(ask.phase, ask.caption.isBlank()) {
        if (ask.phase != AskPhase.Listening || ask.caption.isNotBlank()) return@LaunchedEffect
        while (true) {
            delay(AskPrompts.CYCLE_MS)
            ask.prompt++
        }
    }

    HomeGreeting(
        stage, phrase, AskPrompts.greeting(ask, phrase), AskPrompts.lines, live = ask.active, clock,
        Modifier.at(stage, 39f, 114f, 332f, 72f).rise(stage, clock, 80),
    )

    val quiet = ask.phase == AskPhase.Off || ask.phase == AskPhase.Missed || ask.phase == AskPhase.Failed
    SwapLine(
        stage,
        Line(AskPrompts.status(ask), if (quiet) Palette.Sub else Palette.Mint, live = !quiet),
        TextStyle(fontFamily = Spline, fontSize = stage.sp(15f), lineHeight = stage.sp(22.1f), lineHeightStyle = centred),
        Modifier
            .at(stage, 39f, 192f, 332f, 22.1f)
            .graphicsLayer {
                val p = progress(clock.value, 200, 900, EaseOut)
                alpha = p
                translationX = stage.px(-6f * (1 - p))
            },
    )

    val asking by animateFloatAsState(if (ask.listening) 1f else 0f, tween(420, easing = EaseInOut), label = "asking")
    val items = remember { AppEntry.entries.map { it.item } }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            alpha = 1f - asking
            scaleX = 1f - .05f * asking
            scaleY = scaleX
            transformOrigin = TransformOrigin(.5f, 307f / Design.HEIGHT)
        },
    ) {
        Drum(stage, drum, items, DrumGeometry.Main, 39f, 229.1f, clock, riseDelay = 280) { onOpen(AppEntry.entries[it]) }
    }
    if (asking > 0f) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = asking; translationY = stage.px(6f * (1f - asking)) }) {
            ListeningWave(stage, ask, Modifier.at(stage, 39f, 232f, 332f, 98f))
            AskTranscript(stage, ask)
        }
    }

    val session = TextStyle(fontFamily = Spline, fontSize = stage.sp(13f), lineHeight = stage.sp(16f), lineHeightStyle = centred)
    val sessionLabel = when (ask.phase) {
        AskPhase.Off -> "SESSION READY"
        AskPhase.Preparing -> "MIC STARTING"
        AskPhase.Listening -> "MIC LIVE"
        AskPhase.Heard, AskPhase.Executing -> "AI IN CONTROL"
        AskPhase.Missed -> if (ask.retrying) "MIC STARTING" else "MIC OFF"
        AskPhase.Failed -> "MIC OFF"
    }
    val sessionHint = when (ask.phase) {
        AskPhase.Off -> if (kioskActive) "KIOSK ACTIVE" else "KIOSK OFF"
        AskPhase.Heard, AskPhase.Executing -> "TOUCH TO STOP"
        else -> "TAP TO CANCEL"
    }
    Box(Modifier.at(stage, 39f, 407.5f, 332f, 16f).rise(stage, clock, 400)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            SessionDot(clock, ask.active, session.copy(color = Palette.Mint))
            SwapLine(stage, Line(" $sessionLabel", Palette.Mint), session, Modifier)
        }
        SwapLine(stage, Line(sessionHint, Palette.KioskMuted), session, Modifier.align(Alignment.CenterEnd), Alignment.CenterEnd)
    }
}

/** The session dot: Home's few permitted pulses at rest, and a steady pulse while Ask AI has the watch. */
@Composable
private fun SessionDot(clock: State<Long>, live: Boolean, style: TextStyle) {
    val pulse = if (live) {
        rememberInfiniteTransition(label = "mic").animateFloat(1f, .3f, infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse), label = "micDot")
    } else null
    BasicText(
        "●",
        Modifier.graphicsLayer {
            alpha = pulse?.value ?: HomeMotion.SessionPulse.fraction(clock.value)?.let { HomeMotion.sessionAlpha.at(it) } ?: 1f
        },
        style = style,
    )
}
