package ai.nolee.brandedlauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

// A page's parts arrive one after another, at Harness's pace (620 ms panel, 270 ms rows): the title leads, each
// row follows 55 ms behind the one above it, and the page being left is gone in 220 ms.
private const val LEAVE_MS = 220
private const val ARRIVE_MS = 380
private const val STAGGER_MS = 55L
private const val FIRST_MS = 40L
/** The stagger stops mattering past this many rows; a long list should not take a second to finish arriving. */
private const val STAGGER_CAP = 7

/** Pages that stage their own entrance: Home's rise, the watch face's boot, the persona's morph. */
private fun staged(page: Page) = page == Page.Home || page == Page.Watch || page == Page.Persona

/**
 * A page's arrival. Each part claims the next slot in the order it first composes — the title, then the rows down
 * the page — so [arrive] alone gives a page its stagger with nothing to number by hand.
 */
@Stable
class PageEntry(val clock: State<Long>) {
    private var claimed = 0
    fun claim() = claimed++
}

val LocalPageEntry = staticCompositionLocalOf<PageEntry?> { null }

/** A page clock for anything that animates itself, such as a drum's rise. Finished when there is no entrance. */
@Composable
fun pageClock(): State<Long> = LocalPageEntry.current?.clock ?: RestingClock

/**
 * Slides a part of a page in as the page arrives: from the right by [from] design px, or from the left with a
 * negative value. Parts animate in the order they first compose, [STAGGER_MS] apart.
 */
@Composable
fun Modifier.arrive(stage: Stage, from: Float = 16f, delay: Long = 0): Modifier {
    val entry = LocalPageEntry.current ?: return this
    val index = remember { entry.claim() }
    val start = FIRST_MS + delay + STAGGER_MS * index.coerceAtMost(STAGGER_CAP)
    return this.graphicsLayer {
        val p = progress(entry.clock.value, start, ARRIVE_MS.toLong(), EaseOut)
        alpha = p
        translationX = stage.px(from * (1 - p))
    }
}

/**
 * Page changes. The page being left fades back while the new one builds itself up part by part — its title first,
 * then its rows — which is what makes a voice command read as the watch being operated. Home, the watch face and
 * the persona keep their own entrances.
 */
@Composable
fun PageSwitch(stage: Stage, page: Page, forward: Boolean, content: @Composable (Page) -> Unit) {
    var current by remember { mutableStateOf(page) }
    var leaving by remember { mutableStateOf<Page?>(null) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(page) {
        if (page == current) return@LaunchedEffect
        // The watch face already plays its own exit above the next page; fading it here would draw it twice.
        leaving = current.takeIf { it != Page.Watch && !(it == Page.Persona && page == Page.Watch) }
        current = page
        fade.snapTo(0f)
        fade.animateTo(1f, tween(LEAVE_MS))
        leaving = null
    }

    leaving?.let { old ->
        key(old) {
            // A finished clock: the page on its way out must not start arriving again.
            CompositionLocalProvider(LocalPageEntry provides null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = EaseOut.transform(fade.value)
                            alpha = 1f - p
                            translationX = stage.px(-14f * (if (forward) 1f else -1f) * p)
                        },
                ) { content(old) }
            }
        }
    }

    key(current) {
        val clock = remember { mutableLongStateOf(0L) }
        val entry = remember { PageEntry(clock) }
        // The clock runs only until the last part can have arrived, so a page at rest draws no frames.
        LaunchedEffect(Unit) {
            if (staged(current)) return@LaunchedEffect
            val start = withFrameMillis { it }
            val end = FIRST_MS + STAGGER_MS * STAGGER_CAP + ARRIVE_MS + 32
            while (true) {
                val elapsed = withFrameMillis { it } - start
                clock.longValue = elapsed
                if (elapsed >= end) break
            }
        }
        CompositionLocalProvider(LocalPageEntry provides entry.takeUnless { staged(current) }) {
            Box(Modifier.fillMaxSize()) { content(current) }
        }
    }
}
