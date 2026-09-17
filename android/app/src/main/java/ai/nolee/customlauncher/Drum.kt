package ai.nolee.customlauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A drum's position. [target] is unbounded; indices wrap, so turns never reverse. */
@Stable
class DrumState(val count: Int, initialTarget: Int = 0) {
    var position by mutableFloatStateOf(initialTarget.toFloat())
    var target by mutableIntStateOf(initialTarget)
        private set
    internal var tau = 65f
    internal var motion by mutableIntStateOf(0)
    internal var dragging = false
    var launching by mutableIntStateOf(-1)

    val selectedIndex: Int get() = Math.floorMod(target, count)

    fun delta(index: Int, at: Float): Float {
        var d = (index - at) % count
        if (d >= count / 2f) d -= count
        if (d < -count / 2f) d += count
        return d
    }

    fun settle(next: Float) {
        target = next.roundToInt()
        tau = 65f
        motion++
    }

    fun step(by: Int) = settle((target + by).toFloat())

    /** Entrance: spin in from just above the remembered card. */
    fun spinIn() {
        position = target - 1.6f
        tau = 170f
        motion++
    }
}

/** Card spacing and depth. The main menu's drum shows three cards; System's taller drum shows five. */
class DrumGeometry(val height: Float, val radius: Float, val angleStep: Float, val visible: Float, val alphaFalloff: Float) {
    companion object {
        val Main = DrumGeometry(height = 157f, radius = 78f, angleStep = .77f, visible = 1.85f, alphaFalloff = .53f)
        val Tall = DrumGeometry(height = 300f, radius = 130f, angleStep = .5f, visible = 2.45f, alphaFalloff = .36f)
    }
}

private const val WINDOW_W = 290f
private const val CARD_H = 62f
private val centred = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** A clock that has already finished: no entrance and no idle loops. */
val RestingClock: State<Long> = mutableStateOf(1_000_000L)

/**
 * Rolling card selector at design ([x], [y]), 332 wide: a 290 px window with the mint selection frame, and a
 * counter rail. Tap a neighbour to select it, the centred card to open it, or drag to roll.
 */
@Composable
fun Drum(
    stage: Stage,
    state: DrumState,
    items: List<DrumItem>,
    geometry: DrumGeometry,
    x: Float,
    y: Float,
    clock: State<Long>,
    riseDelay: Long,
    emphasizeSelected: Boolean = false,
    onOpen: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val height = geometry.height

    LaunchedEffect(state.motion) {
        var last = 0L
        while (!state.dragging && abs(state.target - state.position) >= .002f) {
            withFrameMillis { now ->
                val dt = if (last == 0L) 16f else min(40f, (now - last).toFloat())
                last = now
                state.position += (state.target - state.position) * (1 - exp(-dt / state.tau))
            }
        }
        if (!state.dragging) state.position = state.target.toFloat()
    }

    val pop = remember { Animatable(1f) }
    LaunchedEffect(state.selectedIndex) {
        pop.snapTo(0f)
        pop.animateTo(1f, tween(260, easing = EaseOut))
    }

    fun open(index: Int) {
        if (state.launching >= 0) return
        state.launching = index
        scope.launch {
            delay(150)
            state.launching = -1
            onOpen(index)
        }
    }

    fun cardCentre(d: Float) = sin(d * geometry.angleStep) * geometry.radius
    fun cardScale(d: Float) = 1f - min(abs(d), 2f) * .1f
    fun cardTilt(d: Float) = cos(Math.toRadians(d * geometry.angleStep * 39.0)).toFloat()

    Box(
        Modifier
            .at(stage, x, y, 332f, height)
            .graphicsLayer {
                val p = progress(clock.value, riseDelay, 620, EaseOut)
                alpha = p
                translationY = stage.px(10f * (1 - p))
            },
    ) {
        val selectedCardHeight = if (emphasizeSelected) 76f else CARD_H
        Canvas(Modifier.at(stage, 0f, height / 2 - selectedCardHeight / 2, WINDOW_W, selectedCardHeight)) {
            val t = clock.value
            drawRect(Color(0x0A83F5D0))
            clipRect {
                HomeMotion.Sheen.fraction(t)?.let { f ->
                    val w = size.width * .45f
                    val sx = HomeMotion.sheenX.at(f) * w
                    drawRect(
                        Brush.horizontalGradient(listOf(Color(0x0083F5D0), Color(0x2483F5D0), Color(0x0083F5D0)), startX = sx, endX = sx + w),
                        topLeft = Offset(sx, 0f), size = Size(w, size.height),
                    )
                }
                val notch = HomeMotion.Notch.fraction(t)?.let { HomeMotion.notchScale.at(it) } ?: 1f
                val h = stage.px(14f) * notch
                drawRect(Palette.Mint, topLeft = Offset(0f, size.height / 2 - h / 2), size = Size(stage.px(3f), h))
            }
            val half = stage.px(.5f)
            drawRect(Palette.Mint, topLeft = Offset(half, half), size = Size(size.width - 2 * half, size.height - 2 * half), style = Stroke(stage.px(1f)))
        }

        Box(
            Modifier
                .at(stage, 0f, 0f, WINDOW_W, height)
                .pointerInput(state, stage, geometry) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val startY = down.position.y
                        val start = state.position
                        var moved = false
                        var tapAt: Offset? = null
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) { tapAt = change.position; break }
                            val dy = (startY - change.position.y) / stage.scale
                            if (!moved && abs(dy) > 6f) { moved = true; state.dragging = true }
                            if (moved) { state.position = start + dy / 56f; change.consume() }
                        }
                        if (moved) {
                            state.dragging = false
                            state.settle(state.position)
                        } else if (tapAt != null) {
                            val centre = size.height / 2f
                            val hit = (0 until state.count)
                                .sortedBy { abs(state.delta(it, state.position)) }
                                .firstOrNull { i ->
                                    val d = state.delta(i, state.position)
                                    if (abs(d) >= geometry.visible) return@firstOrNull false
                                    val cy = centre + stage.px(cardCentre(d))
                                    val cardHeight = if (emphasizeSelected && i == state.selectedIndex) selectedCardHeight else CARD_H
                                    abs(tapAt.y - cy) <= stage.px(cardHeight / 2) * cardScale(d) * cardTilt(d)
                                }
                            if (hit != null) {
                                val distance = state.delta(hit, state.target.toFloat()).roundToInt()
                                if (distance == 0 && abs(state.position - state.target) < .1f) open(hit)
                                else state.settle((state.target + distance).toFloat())
                            }
                        }
                    }
                },
        ) {
            val selected = state.selectedIndex
            items.forEachIndexed { i, item ->
                val isSelected = i == selected
                val cardHeight = if (emphasizeSelected && isSelected) selectedCardHeight else CARD_H
                val ring = abs(state.delta(i, state.target.toFloat())).roundToInt()
                Box(
                    Modifier
                        .zIndex(10f - ring)
                        .at(stage, 7f, height / 2 - cardHeight / 2, WINDOW_W - 14f, cardHeight)
                        .graphicsLayer {
                            val d = state.delta(i, state.position)
                            val s = cardScale(d)
                            translationY = stage.px(cardCentre(d))
                            scaleX = s
                            scaleY = s * cardTilt(d)
                            alpha = if (abs(d) < geometry.visible) max(0f, 1f - abs(d) * geometry.alphaFalloff) else 0f
                        }
                        .background(
                            when {
                                state.launching == i -> Color(0x2983F5D0)
                                isSelected -> Color.Transparent
                                else -> Palette.AppCard
                            },
                        ),
                ) {
                    val tint = if (isSelected) Palette.Mint else Palette.Ink
                    val iconSize = if (emphasizeSelected && isSelected) 31f else 27f
                    val titleSize = if (emphasizeSelected && isSelected) 21f else 18.5f
                    val titleY = if (emphasizeSelected && isSelected) 14f else 11.5f
                    val subtitleY = if (emphasizeSelected && isSelected) 43f else 36f
                    Canvas(Modifier.at(stage, 17f, (cardHeight - iconSize) / 2, iconSize, iconSize)) { drawGlyph(item.glyph, tint) }
                    BasicText(
                        item.label,
                        Modifier.at(stage, 60f, titleY),
                        style = TextStyle(fontFamily = Spline, color = tint, fontSize = stage.sp(titleSize), letterSpacing = stage.sp(-.4f), lineHeight = stage.sp(if (emphasizeSelected && isSelected) 25f else 22f), lineHeightStyle = centred),
                    )
                    if (isSelected) {
                        Box(Modifier.at(stage, 60f, subtitleY, 180f, if (emphasizeSelected) 17f else 15f)) {
                            BasicText(
                                item.subtitle,
                                style = TextStyle(fontFamily = Spline, color = Palette.SmallMuted, fontSize = stage.sp(if (emphasizeSelected) 13f else 12f), letterSpacing = stage.sp(.3f), lineHeight = stage.sp(if (emphasizeSelected) 17f else 15f), lineHeightStyle = centred),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicText(
                            "↗",
                            Modifier
                                .at(stage, 246.5f, (cardHeight - 22f) / 2)
                                .graphicsLayer {
                                    val k = HomeMotion.Nudge.fraction(clock.value)?.let { HomeMotion.nudge.at(it) } ?: 0f
                                    translationX = stage.px(3f * k)
                                    translationY = stage.px(-3f * k)
                                },
                            style = TextStyle(fontFamily = Spline, color = tint, fontSize = stage.sp(17f), lineHeight = stage.sp(22f), lineHeightStyle = centred),
                        )
                    }
                }
            }
        }

        Canvas(Modifier.at(stage, 298f, 0f, 34f, height)) {
            drawRect(Palette.Hair, size = Size(stage.px(1f), size.height))
        }
        RailButton(stage, y = -1.8f, up = true) { state.step(-1) }
        RailButton(stage, y = height - 42.2f, up = false) { state.step(1) }
        // writing-mode: vertical-rl in the prototype; rotated about its own centre here.
        Box(Modifier.at(stage, 298.5f, 0f, 34f, height), contentAlignment = Alignment.Center) {
            BasicText(
                "${(state.selectedIndex + 1).toString().padStart(2, '0')} / ${state.count.toString().padStart(2, '0')}",
                Modifier.graphicsLayer {
                    val p = pop.value
                    alpha = lerp(.2f, 1f, p)
                    scaleX = lerp(.7f, 1f, p)
                    scaleY = scaleX
                    rotationZ = 90f
                },
                style = TextStyle(fontFamily = Spline, color = Palette.Count, fontSize = stage.sp(12f), letterSpacing = stage.sp(.6f)),
            )
        }
    }
}

@Composable
private fun RailButton(stage: Stage, y: Float, up: Boolean, onTap: () -> Unit) {
    Canvas(
        Modifier
            .at(stage, 298.5f, y, 34f, 44f)
            .pointerInput(onTap) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (waitForUpOrCancellation() != null) onTap()
                }
            },
    ) {
        val w = stage.px(11f)
        val h = stage.px(5.5f)
        val dir = if (up) 1f else -1f
        val stroke = stage.px(1.6f)
        drawLine(Palette.Mint, Offset(center.x - w / 2, center.y + dir * h / 2), Offset(center.x, center.y - dir * h / 2), stroke, StrokeCap.Round)
        drawLine(Palette.Mint, Offset(center.x, center.y - dir * h / 2), Offset(center.x + w / 2, center.y + dir * h / 2), stroke, StrokeCap.Round)
    }
}
