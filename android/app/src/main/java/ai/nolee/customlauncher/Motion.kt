package ai.nolee.customlauncher

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing

internal val EaseOut = CubicBezierEasing(.22f, 1f, .36f, 1f)
internal val EaseInOut = CubicBezierEasing(.42f, 0f, .58f, 1f)
internal val RevealEasing = CubicBezierEasing(.42f, 0f, .18f, 1f)
internal val SheenEasing = CubicBezierEasing(.45f, 0f, .2f, 1f)

internal fun lerp(a: Float, b: Float, fraction: Float) = a + (b - a) * fraction

/** Eased 0..1 progress of a one-shot animation at [t] ms. */
internal fun progress(t: Long, delay: Long, duration: Long, easing: Easing = LinearEasing): Float =
    easing.transform(((t - delay).toFloat() / duration).coerceIn(0f, 1f))

/** CSS-style keyframes: [stops] are (offset, value) pairs, with [easing] applied within each segment. */
class Keyframes(private val easing: Easing, private vararg val stops: Pair<Float, Float>) {
    fun at(fraction: Float): Float {
        val p = fraction.coerceIn(0f, 1f)
        for (i in 0 until stops.size - 1) {
            val (start, from) = stops[i]
            val (end, to) = stops[i + 1]
            if (p <= end) return if (end == start) to else lerp(from, to, easing.transform((p - start) / (end - start)))
        }
        return stops.last().second
    }
}

/**
 * Home's motion budget. Everything plays when Home is entered: the entrance, the peace reveal, the typewriter
 * and the idle loops. Loops may only begin a cycle until ten seconds after the reveal ends; each finishes that
 * cycle at its resting pose, and then Home is completely static so the watch is not kept busy or warm.
 */
object HomeMotion {
    const val REVEAL_END_MS = 2_860L
    const val IDLE_WINDOW_END_MS = REVEAL_END_MS + 10_000L

    class Loop(private val delay: Long, private val period: Long) {
        /** Cycle fraction, or null while resting (before [delay], or after the last permitted cycle). */
        fun fraction(t: Long): Float? {
            if (t < delay) return null
            val elapsed = t - delay
            if (delay + (elapsed / period) * period >= IDLE_WINDOW_END_MS) return null
            return (elapsed % period).toFloat() / period
        }

        val endMs: Long get() = delay + ((IDLE_WINDOW_END_MS - delay + period - 1) / period) * period
    }

    val Sheen = Loop(1_400, 4_600)
    val Notch = Loop(0, 2_400)
    val Nudge = Loop(0, 2_400)
    val SessionPulse = Loop(0, 2_400)
    val NavSweep = Loop(1_000, 5_200)
    val Star = Loop(0, 3_400)
    val Glow = Loop(1_900, 3_800)

    val LOOPS_END_MS = listOf(Sheen, Notch, Nudge, SessionPulse, NavSweep, Star, Glow).maxOf { it.endMs }

    val sheenX = Keyframes(SheenEasing, 0f to -1.1f, .38f to 3.3f, 1f to 3.3f)
    val notchScale = Keyframes(EaseInOut, 0f to 1f, .5f to 1.7f, 1f to 1f)
    val nudge = Keyframes(EaseInOut, 0f to 0f, .6f to 0f, .75f to 1f, 1f to 0f)
    val sessionAlpha = Keyframes(EaseInOut, 0f to 1f, .5f to .35f, 1f to 1f)
    val navSweepX = Keyframes(EaseInOut, 0f to -1f, .45f to 3f, 1f to 3f)
    val navSweepAlpha = Keyframes(EaseInOut, 0f to 0f, .1f to 1f, .45f to 0f, 1f to 0f)
    val star = Keyframes(EaseInOut, 0f to 0f, .45f to 1f, 1f to 0f)
    val glowAlpha = Keyframes(EaseInOut, 0f to .08f, .45f to .48f, .62f to .48f, 1f to .08f)
    val glowScale = Keyframes(EaseInOut, 0f to .88f, .45f to 1.08f, .62f to 1.08f, 1f to .88f)
}
