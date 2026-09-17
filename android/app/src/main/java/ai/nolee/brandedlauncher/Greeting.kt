package ai.nolee.brandedlauncher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.min

/**
 * The Boot Art 05 typewriter. Each Home entry types one phrase, blinks the cursor five times, fades it out
 * and then holds still; the next entry shows the next phrase.
 */
object Greetings {
    private const val DELAY_MS = 360L
    private const val TYPE_MS = 80L // Boot Art 06's pace; 05's 145 ms felt slow on the watch
    private const val BLINK_MS = 500L
    private const val BLINK_HALVES = 9 // on, off … on: four hard blinks, and the fade is the fifth
    private const val FADE_MS = 450L

    fun phrases(hour: Int, name: String): List<String> {
        val period = when (hour) { in 5..11 -> "Morning"; in 12..17 -> "Afternoon"; else -> "Evening" }
        // With no name set in Profile, the named greetings stand on their own.
        val named = if (name.isBlank()) {
            listOf("Hello\nthere", "Good\n$period", "Welcome\nback")
        } else {
            listOf("Hello\n$name", "Good $period,\n$name", "Welcome back,\n$name")
        }
        return named + listOf(
            "What would you\nlike to do?",
            "Ready when\nyou are.",
            "What’s on\nyour mind?",
        )
    }

    class Frame(val text: String, val cursor: Float)

    fun frame(t: Long, phrase: String): Frame {
        val typed = t - DELAY_MS
        if (typed < 0) return Frame("", 1f)
        val typeEnd = phrase.length * TYPE_MS
        if (typed < typeEnd) return Frame(phrase.substring(0, (typed / TYPE_MS).toInt()), 1f)
        val afterTyping = typed - typeEnd
        val blinkEnd = BLINK_HALVES * BLINK_MS
        val cursor = when {
            afterTyping < blinkEnd -> if ((afterTyping / BLINK_MS) % 2 == 0L) 1f else 0f
            afterTyping < blinkEnd + FADE_MS -> 1f - (afterTyping - blinkEnd).toFloat() / FADE_MS
            else -> 0f
        }
        return Frame(phrase, cursor)
    }

    fun endMs(phrase: String) = DELAY_MS + phrase.length * TYPE_MS + BLINK_HALVES * BLINK_MS + FADE_MS
}

private const val ERASE_MS = 16L
private const val RETYPE_MS = 42L

/** Greeting type, shrunk only when a line of one of [phrases] would not fit the column beside the rule. */
private fun greetingStyle(stage: Stage, measurer: TextMeasurer, phrases: List<String>): TextStyle {
    val base = TextStyle(
        fontFamily = Spline,
        fontSize = stage.sp(31f),
        letterSpacing = stage.sp(-1.3f),
        lineHeight = stage.sp(31f * 1.15f),
        color = Palette.Ink,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    val column = stage.px(332f - 2f - 13f)
    val needed = phrases.flatMap { it.split('\n') }.maxOf { measurer.measure(it, base).size.width } + stage.px(31f * .64f)
    val fit = min(1f, column / needed)
    return if (fit >= 1f) base else {
        val size = floor(31f * fit * 10f) / 10f
        base.copy(fontSize = stage.sp(size), lineHeight = stage.sp(size * 1.15f))
    }
}

/**
 * Two-line greeting with the mint left rule. Home entry types [phrase] with the Boot Art typewriter. Whenever [line]
 * moves away from the phrase (Ask AI's questions and replies) or back to it, the greeting backspaces to what the two
 * share and types the rest, at a size fitted to [phrase] and every one of [fitLines]. The retype cursor holds solid
 * while typing and blinks while [live].
 */
@Composable
fun HomeGreeting(stage: Stage, phrase: String, line: String, fitLines: List<String>, live: Boolean, clock: State<Long>, modifier: Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    var retyping by remember(phrase) { mutableStateOf(false) }
    var shown by remember(phrase) { mutableStateOf(phrase) }
    var typing by remember(phrase) { mutableStateOf(false) }
    LaunchedEffect(phrase, line) {
        if (!retyping) {
            if (line == phrase) return@LaunchedEffect
            shown = Greetings.frame(clock.value, phrase).text
            retyping = true
        }
        typing = true
        while (!line.startsWith(shown)) {
            shown = shown.dropLast(1)
            delay(ERASE_MS)
        }
        while (shown.length < line.length) {
            delay(RETYPE_MS)
            shown = line.take(shown.length + 1)
        }
        typing = false
    }
    val blink = if (retyping && live && !typing) {
        rememberInfiniteTransition(label = "cursor").animateFloat(0f, 2f, infiniteRepeatable(tween(1_000, easing = LinearEasing)), label = "blink")
    } else null

    val style = remember(stage, phrase, retyping) { greetingStyle(stage, measurer, if (retyping) listOf(phrase) + fitLines else listOf(phrase)) }
    val layouts = remember(style) { HashMap<String, TextLayoutResult>() }
    Canvas(modifier) {
        drawRect(Palette.Mint, size = Size(stage.px(2f), size.height))
        val frame = when {
            !retyping -> Greetings.frame(clock.value, phrase)
            typing -> Greetings.Frame(shown, 1f)
            else -> Greetings.Frame(shown, if (blink != null && blink.value < 1f) 1f else 0f)
        }
        val layout = layouts.getOrPut(frame.text) { measurer.measure(frame.text, style) }
        val left = stage.px(15f)
        drawText(layout, topLeft = Offset(left, 0f))
        if (frame.cursor > 0f) {
            val em = style.fontSize.toPx()
            val line = layout.getLineForOffset(frame.text.length)
            val x = left + (if (frame.text.endsWith('\n') || frame.text.isEmpty()) 0f else layout.getLineRight(line)) + em * .12f
            val bottom = layout.getLineBaseline(line) + em * .1f
            drawRect(
                Palette.Mint,
                topLeft = Offset(x, bottom - em * .95f),
                size = Size(em * .52f, em * .95f),
                alpha = frame.cursor,
            )
        }
    }
}
