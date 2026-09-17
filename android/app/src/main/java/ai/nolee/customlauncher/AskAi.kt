package ai.nolee.customlauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.sin

/** Where one Ask AI turn is. Home shows its listening layout in every phase except [Off] and [Executing]. */
enum class AskPhase { Off, Preparing, Listening, Heard, Executing, Missed, Failed }

/** One Ask AI turn, owned by MainActivity and drawn on Home. The microphone capture thread fills [meter]. */
@Stable
class AskState {
    val meter = LevelMeter()
    var phase by mutableStateOf(AskPhase.Off)
    /** What has been heard so far, as Vosk spells it. */
    var caption by mutableStateOf("")
    /** What the command will do ("OPENING CAMERA"), or why listening failed. */
    var detail by mutableStateOf("")
    /** Which of [AskPrompts.all] Home is asking. It advances every turn, and while nobody speaks. */
    var prompt by mutableIntStateOf(0)
    /** Rounds of listening this turn that followed speech that was not a command; 0 on the first round. */
    var retries by mutableIntStateOf(0)
    /** The last miss heard nothing at all. Silence ends the turn: nobody is talking to the watch. */
    var missedSilence by mutableStateOf(false)
    /** The current miss will be followed by another round of listening. */
    var retrying by mutableStateOf(false)

    /** Home trades its app drum for the waveform and transcript. */
    val listening get() = phase != AskPhase.Off && phase != AskPhase.Executing
    val active get() = phase != AskPhase.Off
}

/** What Home's greeting and subtitle say during a turn. */
object AskPrompts {
    class Prompt(val question: String, val example: String)

    /** Each question comes with a phrase that answers it and that Vosk can hear (VoiceGrammarTest checks both). */
    val all = listOf(
        Prompt("Which app should\nI open?", "open camera"),
        Prompt("What should I\nchange?", "turn wi fi off"),
        Prompt("Where would you\nlike to go?", "open vitals"),
        Prompt("Too bright?\nToo loud?", "mute all"),
        Prompt("Want a quick\ncheck-up?", "measure heart rate"),
    )

    private const val HEARD = "Ok, on it!"
    private const val MISSED = "Sorry, I didn’t\ncatch that."
    private const val SILENT = "I didn’t hear\nanything."
    private const val FAILED = "I can’t hear\nyou right now."
    private const val AGAIN = "Sorry, please\nsay that again."

    /** A new question while nobody has spoken. Erasing and typing take about a second of it. */
    const val CYCLE_MS = 4_200L
    /** Extra rounds of listening after speech that is not a command, before the turn gives up. */
    const val MAX_RETRIES = 2

    /** Every line the greeting can retype into, so its size is fitted once and never jumps during a turn. */
    val lines = all.map { it.question } + listOf(HEARD, MISSED, SILENT, FAILED, AGAIN)

    fun current(ask: AskState) = all[Math.floorMod(ask.prompt, all.size)]

    fun greeting(ask: AskState, phrase: String) = when (ask.phase) {
        AskPhase.Off -> phrase
        AskPhase.Preparing, AskPhase.Listening -> if (ask.retries > 0) AGAIN else current(ask).question
        AskPhase.Heard, AskPhase.Executing -> HEARD
        // A miss that listens again goes straight to asking again, rather than retyping twice.
        AskPhase.Missed -> when {
            ask.retrying -> AGAIN
            ask.missedSilence -> SILENT
            else -> MISSED
        }
        AskPhase.Failed -> FAILED
    }

    fun status(ask: AskState) = when (ask.phase) {
        AskPhase.Off -> "Designed for Vibe Coders"
        AskPhase.Preparing -> if (ask.retries > 0) "Listening again" else "Preparing speech model"
        AskPhase.Listening -> if (ask.retries > 0) "Listening again" else "Listening for command"
        AskPhase.Heard, AskPhase.Executing -> "Executing command"
        AskPhase.Missed -> if (ask.missedSilence) "No speech detected" else "Command not recognised"
        AskPhase.Failed -> "Microphone unavailable"
    }
}

private const val SWAP_MS = 420
private const val BARS = 24

/** 0..1 through [from]..[to] of a 0..1 timeline. */
private fun span(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

/** A line of text for [SwapLine]. A [live] line ends in dots that count up, so it reads as work in progress. */
internal data class Line(val text: String, val color: Color, val live: Boolean = false)

/** Swaps text by rolling it upward: the old line leaves as the new one arrives from below. */
@Composable
internal fun SwapLine(
    stage: Stage,
    line: Line,
    style: TextStyle,
    modifier: Modifier,
    alignment: Alignment = Alignment.CenterStart,
    maxLines: Int = 1,
) {
    var current by remember { mutableStateOf(line) }
    var leaving by remember { mutableStateOf<Line?>(null) }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(line) {
        if (line == current) return@LaunchedEffect
        leaving = current
        current = line
        swap.snapTo(0f)
        swap.animateTo(1f, tween(SWAP_MS, easing = LinearEasing))
        leaving = null
    }
    Box(modifier, contentAlignment = alignment) {
        leaving?.let { old ->
            LineText(old, style, maxLines, Modifier.graphicsLayer {
                val p = EaseOut.transform(span(swap.value, 0f, .55f))
                alpha = 1f - p
                translationY = stage.px(-7f * p)
            })
        }
        LineText(current, style, maxLines, Modifier.graphicsLayer {
            val p = EaseOut.transform(span(swap.value, .3f, 1f))
            alpha = p
            translationY = stage.px(7f * (1f - p))
        })
    }
}

@Composable
private fun LineText(line: Line, style: TextStyle, maxLines: Int, modifier: Modifier) {
    val styled = style.copy(color = line.color)
    if (!line.live) {
        BasicText(line.text, modifier, style = styled, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        return
    }
    val count by rememberInfiniteTransition(label = "dots")
        .animateFloat(0f, 4f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "dotCount")
    BasicText(
        buildAnnotatedString {
            append(line.text)
            repeat(3) { k -> withStyle(SpanStyle(color = line.color.copy(alpha = if (count.toInt() > k) 1f else .2f))) { append('.') } }
        },
        modifier,
        style = styled,
        maxLines = maxLines,
    )
}

/**
 * The microphone as bars mirrored about the centre. The newest loudness reading sits in the middle and older readings
 * travel outward, so speech spreads from the centre and dies away at the ends. At rest the bars breathe, so a quiet
 * room still reads as listening; while the model loads, a pulse runs outward instead.
 */
@Composable
fun ListeningWave(stage: Stage, ask: AskState, modifier: Modifier) {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) clock.longValue = withFrameMillis { it } - start
    }
    val listening = ask.phase == AskPhase.Listening
    val live by animateFloatAsState(if (listening) 1f else 0f, tween(if (listening) 520 else 280, easing = EaseInOut), label = "live")
    val loading by animateFloatAsState(if (ask.phase == AskPhase.Preparing) 1f else 0f, tween(300), label = "loading")
    val heard by animateFloatAsState(if (ask.phase == AskPhase.Heard) 1f else 0f, tween(260), label = "heard")

    Canvas(modifier) {
        val t = clock.longValue
        val mid = size.height / 2
        val centre = size.width / 2
        val pitch = centre / BARS
        val hair = stage.px(1f)
        val tick = stage.px(5f)
        val rest = stage.px(1.2f)
        val reach = mid - stage.px(4f)

        // Loudness glow behind the centre.
        val now = ask.meter.level(0) * live
        if (now > .02f) {
            val radius = stage.px(96f)
            drawCircle(Brush.radialGradient(listOf(Palette.Mint.copy(alpha = .16f * now), Color.Transparent), Offset(centre, mid), radius), radius, Offset(centre, mid))
        }

        // The rail, which lights mint once a command is recognised.
        drawRect(lerp(Palette.NavLine, Palette.Mint, heard), Offset(0f, mid - hair / 2), Size(size.width, hair))
        drawRect(Palette.NavLine, Offset(0f, mid - tick), Size(hair, tick * 2))
        drawRect(Palette.NavLine, Offset(size.width - hair, mid - tick), Size(hair, tick * 2))

        val scanAt = (t % 1_100L) / 1_100f * (BARS + 8) - 4
        for (i in 0 until BARS) {
            val edge = i / (BARS - 1f)
            val taper = 1f - .6f * edge * edge
            val breathe = .5f + .5f * sin(t / 240f - i * .5f)
            val speech = ask.meter.level(i)
            val d = i - scanAt
            val scan = exp(-d * d / 6f)
            val half = rest + live * (stage.px(1.6f) * breathe + reach * speech * taper) + loading * stage.px(9f) * scan * taper
            val colour = Palette.Mint.copy(alpha = 1f - .72f * edge)
            val dx = pitch * (i + .5f)
            for (x in floatArrayOf(centre - dx, centre + dx)) {
                drawLine(colour, Offset(x, mid - half), Offset(x, mid + half), stage.px(2.4f), StrokeCap.Round)
            }
        }
    }
}

/** The transcript below the waveform: what was heard, typed out, or an example of what to say. */
@Composable
fun AskTranscript(stage: Stage, ask: AskState) {
    // Keep the last listening phase while the layout fades back to the drum, so nothing blanks on the way out.
    val held = remember { arrayOf(AskPhase.Preparing) }
    if (ask.listening) held[0] = ask.phase
    val phase = held[0]

    val label = stage.text(12f, Palette.Sub, 16f, 1f)
    BasicText("TRANSCRIPT", Modifier.at(stage, 39f, 338f, 110f, 16f), style = label, maxLines = 1)
    val state = when (phase) {
        AskPhase.Preparing -> Line("LOADING MODEL", Palette.Sub)
        AskPhase.Listening -> Line("EN-US · ON-DEVICE", Palette.Sub)
        AskPhase.Missed -> Line("NO MATCH", Palette.Sub)
        AskPhase.Failed -> Line("MIC ERROR", Palette.Sub)
        else -> Line(ask.detail, Palette.Mint)
    }
    SwapLine(stage, state, label, Modifier.at(stage, 151f, 338f, 220f, 16f), Alignment.CenterEnd)

    // The caption types out; one that only grows keeps what is already typed.
    val caption = ask.caption
    var typed by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf("") }
    LaunchedEffect(caption) {
        if (!caption.startsWith(last.take(typed))) typed = 0
        last = caption
        while (typed < caption.length) {
            typed++
            delay(30L)
        }
        typed = typed.coerceAtMost(caption.length)
    }
    val body = stage.text(16f, Palette.Ink, 22f)
    // Two lines need a little more than 2 × 22 once design px are rounded to the panel, or the second is ellipsized.
    Box(Modifier.at(stage, 39f, 356f, 332f, 50f)) {
        if (phase == AskPhase.Failed || caption.isBlank()) {
            val hint = if (phase == AskPhase.Failed) ask.detail else "Try “${AskPrompts.current(ask).example}”"
            SwapLine(stage, Line(hint, Palette.Sub), body, Modifier.fillMaxSize(), Alignment.TopStart, maxLines = 2)
        } else {
            BasicText(
                "“" + caption.take(typed) + if (typed < caption.length) "█" else "”",
                style = body.copy(color = if (phase == AskPhase.Missed) Palette.Sub else Palette.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A thin light running round the lens edge while Ask AI listens, through to the command being recognised. It traces
 * the outline on the way in and un-traces it on the way out, as the command starts running.
 */
@Composable
fun EdgeLight(stage: Stage, visible: Boolean, transitionProgress: Float? = null, tealGradient: Boolean = false) {
    val presence = remember { Animatable(0f) }
    LaunchedEffect(visible, transitionProgress) {
        if (transitionProgress != null) presence.snapTo(transitionProgress)
        else presence.animateTo(if (visible) 1f else 0f, tween(if (visible) 900 else 520, easing = LinearEasing))
    }
    if (!visible && presence.value <= 0f && (transitionProgress ?: 0f) <= 0f) return

    val edge = rememberInfiniteTransition(label = "edge")
    val glow by edge.animateFloat(.38f, 1f, infiniteRepeatable(tween(760, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val turn by edge.animateFloat(0f, 360f, infiniteRepeatable(tween(2_800, easing = LinearEasing)), label = "turn")
    val paint = remember {
        android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            isAntiAlias = true
        }
    }
    val rotation = remember { android.graphics.Matrix() }

    Canvas(Modifier.fillMaxSize()) {
        val a = transitionProgress ?: presence.value
        val traceIn = EaseInOut.transform(span(a, 0f, .62f))
        val run = span(a, .5f, 1f)
        val stroke = stage.px(1.5f + glow * 1.2f * run)
        val inset = stage.px(Design.RIM_INSET) + stroke / 2
        val corner = SafeZone.CORNER_RADIUS_PX - stroke / 2
        drawIntoCanvas { canvas ->
            val outline = android.graphics.Path().apply {
                addRoundRect(inset, inset, size.width - inset, size.height - inset, corner, corner, android.graphics.Path.Direction.CW)
            }
            if (traceIn > 0f && run < 1f) {
                val measure = android.graphics.PathMeasure(outline, false)
                val traced = android.graphics.Path()
                measure.getSegment(0f, measure.length * traceIn, traced, true)
                paint.shader = if (tealGradient) android.graphics.LinearGradient(
                    0f, 0f, size.width, size.height,
                    intArrayOf(Color(0xFF2AC9B4).toArgb(), Palette.Mint.toArgb(), Color(0xFF7DDCFA).toArgb()),
                    null, android.graphics.Shader.TileMode.CLAMP) else null
                paint.strokeWidth = stroke
                paint.color = Color.White.copy(alpha = .8f * (1f - run)).toArgb()
                canvas.nativeCanvas.drawPath(traced, paint)
            }
            if (run > 0f) {
                val colours = intArrayOf(
                    Color.Transparent.toArgb(),
                    (if(tealGradient) Color(0xFF20BBA5) else Color(0xFF6C7D87)).copy(alpha = .4f * glow * run).toArgb(),
                    (if(tealGradient) Palette.Mint else Color.White).copy(alpha = .9f * glow * run).toArgb(),
                    (if(tealGradient) Color(0xFF59BBD9) else Color(0xFF9AABB3)).copy(alpha = .3f * glow * run).toArgb(),
                    Color.Transparent.toArgb(),
                    (if(tealGradient) Color(0xFFC0FFF1) else Color.White).copy(alpha = .7f * glow * run).toArgb(),
                    Color.Transparent.toArgb(),
                )
                rotation.setRotate(turn, center.x, center.y)
                paint.shader = android.graphics.SweepGradient(center.x, center.y, colours, null).apply { setLocalMatrix(rotation) }
                // A soft halo, half of it hidden past the lens, then the line itself.
                paint.strokeWidth = stroke * 4f
                paint.color = Color.White.copy(alpha = .2f).toArgb()
                canvas.nativeCanvas.drawPath(outline, paint)
                paint.strokeWidth = stroke
                paint.color = Color.White.toArgb()
                canvas.nativeCanvas.drawPath(outline, paint)
            }
        }
    }
}
