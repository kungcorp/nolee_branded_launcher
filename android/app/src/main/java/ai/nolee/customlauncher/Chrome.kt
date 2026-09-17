package ai.nolee.customlauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val centred = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** Full-screen ground, with a design-sized (410 × 502) box positioned by the [Stage] mapping. */
@Composable
fun StageSurface(content: @Composable (Stage) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Ground)) {
        val density = LocalDensity.current
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Type is laid out in exact design pixels, so drop the system font scale here. Compose scales sp
        // non-linearly (large sizes far less than small ones), which shrank the 67 px clock to about 45 px.
        val exact = remember(density) { Density(density.density, fontScale = 1f) }
        val stage = remember(width, height, exact) { Stage.of(width, height, exact.density, 1f) }
        CompositionLocalProvider(LocalStage provides stage, LocalDensity provides exact) {
            Box(
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(
                        Constraints.fixed((Design.WIDTH * stage.scale).roundToInt(), (Design.HEIGHT * stage.scale).roundToInt()),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(stage.originX.roundToInt(), stage.originY.roundToInt())
                    }
                },
            ) { content(stage) }
        }
    }
}

/**
 * Peace artwork (revealed bottom-up by a lifting veil), its mint glow, the grid above it, and the rim.
 * The watch face fades the artwork out and brings in a slightly stronger grid.
 */
@Composable
fun Backdrop(stage: Stage, peace: ImageBitmap, clock: State<Long>, watchMode: Boolean) {
    val watch by animateFloatAsState(if (watchMode) 1f else 0f, tween(450, easing = EaseOut), label = "watch")
    Canvas(Modifier.fillMaxSize()) {
        val t = clock.value
        val peaceAlpha = .21f * (1f - watch)
        val left = stage.px(15f)
        val top = stage.px(-2.5f)
        val w = stage.px(410f)
        val h = stage.px(615f)
        if (peaceAlpha > 0f) {
            val art = progress(t, 60, 2_800, RevealEasing)
            drawImage(
                peace,
                srcSize = IntSize(peace.width, peace.height),
                dstOffset = IntOffset(left.roundToInt(), (top + stage.px(10f) * (1 - art)).roundToInt()),
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                alpha = peaceAlpha * .96f * art,
            )
            val lift = progress(t, 0, 2_800, RevealEasing)
            if (lift < 1f) {
                val veilH = h * 2.3f
                val veilY = top - veilH * .56f * lift
                drawRect(
                    Brush.verticalGradient(
                        0f to Palette.Ground, .5f to Palette.Ground, .56f to Palette.Ground.copy(alpha = 0f),
                        startY = veilY, endY = veilY + veilH,
                    ),
                    topLeft = Offset(left, veilY), size = Size(w, veilH),
                )
            }
            val glow = HomeMotion.Glow.fraction(t)
            val glowAlpha = glow?.let { HomeMotion.glowAlpha.at(it) } ?: .08f
            val glowScale = glow?.let { HomeMotion.glowScale.at(it) } ?: .88f
            val gw = w * .42f
            val gh = w * .336f
            val centre = Offset(left + w * .22f + gw / 2, top + h * .58f + gh / 2)
            val radius = sqrt(gw * gw + gh * gh) / 2 * .68f
            scale(glowScale, pivot = centre) {
                drawCircle(
                    Brush.radialGradient(listOf(Color(0x3D83F5D0), Color(0x0083F5D0)), center = centre, radius = radius),
                    radius = radius, center = centre, alpha = glowAlpha * peaceAlpha / .21f * .21f,
                )
            }
        }
        val step = stage.px(34f)
        val line = stage.px(1f)
        fun grid(color: Color) {
            var x = 0f
            while (x < size.width) { drawRect(color, Offset(x, 0f), Size(line, size.height)); x += step }
            var y = 0f
            while (y < size.height) { drawRect(color, Offset(0f, y), Size(size.width, line)); y += step }
        }
        grid(Palette.GridLine)
        if (watch > 0f) grid(Palette.WatchGridLine.copy(alpha = Palette.WatchGridLine.alpha * watch))
    }
}

@Composable
fun StatusHeader(stage: Stage, time: String, battery: Int) {
    val style = TextStyle(fontFamily = Spline, fontSize = stage.sp(14f), color = Palette.Status, lineHeight = stage.sp(17f), lineHeightStyle = centred)
    val mint = SpanStyle(color = Palette.Mint)
    BasicText(buildAnnotatedString { append("NOLEE / "); withStyle(mint) { append("ULTRA") } }, Modifier.at(stage, 65f, 28f), style = style)
    Box(Modifier.at(stage, 65f, 28f, 280f, 17f), contentAlignment = Alignment.TopEnd) {
        BasicText(
            buildAnnotatedString { append(time); append(" "); withStyle(mint) { append(if (battery >= 0) "$battery%" else "--%") } },
            style = style,
        )
    }
}

enum class BarMode { VoiceCommand, Home, Cancel }

private val homeIcon: Path by lazy { PathParser().parsePathString("M4 11.5L12 5l8 6.5M6.5 9.6V19h11V9.6").toPath() }
private val cancelIcon: Path by lazy { PathParser().parsePathString("M7 7l10 10M17 7L7 17").toPath() }

/** The bottom button is Voice Command on Home (Cancel while listening), Home elsewhere. */
@Composable
fun BottomBar(stage: Stage, mode: BarMode, clock: State<Long>, onTap: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val swap = remember(mode) { Animatable(0f) }
    LaunchedEffect(mode) { swap.animateTo(1f, tween(430, easing = LinearEasingCompat)) }
    val onHome = mode == BarMode.VoiceCommand

    Box(
        Modifier
            .at(stage, 0f, 437f, Design.WIDTH, 65f)
            .graphicsLayer {
                val p = if (onHome) progress(clock.value, 460, 550, EaseOut) else 1f
                alpha = p
                translationY = stage.px(10f * (1 - p))
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onTap),
    ) {
        // The hairline spans the content column (x 39–371), so it matches the rules above it, like the watch face's.
        Canvas(Modifier.at(stage, 39f, -1f, 332f, 2f)) {
            drawRect(Palette.NavLine, topLeft = Offset(0f, stage.px(1f)), size = Size(size.width, stage.px(1f)))
            if (onHome) HomeMotion.NavSweep.fraction(clock.value)?.let { f ->
                val w = size.width * .34f
                val x = HomeMotion.navSweepX.at(f) * w
                clipRect {
                    drawRect(
                        Brush.horizontalGradient(listOf(Color(0x0083F5D0), Palette.Mint, Color(0x0083F5D0)), startX = x, endX = x + w),
                        topLeft = Offset(x, 0f), size = Size(w, stage.px(1f)), alpha = HomeMotion.navSweepAlpha.at(f),
                    )
                }
            }
        }
        if (pressed) Box(Modifier.at(stage, 39f, 1f, 332f, 64f).background(Color(0x0D83F5D0)))
        Box(Modifier.at(stage, 62f, 1f, 286f, 44f), contentAlignment = Alignment.Center) {
            Row(
                Modifier.graphicsLayer { val s = if (pressed) .92f else 1f; scaleX = s; scaleY = s },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(
                    Modifier
                        .at(stage, 0f, 0f, if (onHome) 22f else 24f, if (onHome) 22f else 24f)
                        .graphicsLayer {
                            val p = EaseOut.transform((swap.value * 430f / 380f).coerceIn(0f, 1f))
                            alpha = p
                            translationY = stage.px(6f * (1 - p))
                            if (onHome) {
                                val k = HomeMotion.Star.fraction(clock.value)?.let { HomeMotion.star.at(it) } ?: 0f
                                scaleX = 1f + .15f * k
                                scaleY = scaleX
                                rotationZ = 12f * k
                            }
                        },
                ) {
                    if (onHome) drawAiStar(Palette.White)
                    else scale(size.minDimension / 24f, pivot = Offset.Zero) {
                        drawPath(if (mode == BarMode.Cancel) cancelIcon else homeIcon, Palette.White, style = Stroke(1.5f))
                    }
                }
                Box(Modifier.layout { m, c -> val p = m.measure(c); layout(p.width + stage.px(9f).roundToInt(), p.height) { p.place(stage.px(9f).roundToInt(), 0) } }) {
                    BasicText(
                        when (mode) {
                            BarMode.VoiceCommand -> "VOICE COMMAND"
                            BarMode.Home -> "HOME"
                            BarMode.Cancel -> "CANCEL"
                        },
                        Modifier.graphicsLayer {
                            val p = EaseOut.transform(((swap.value * 430f - 50f) / 380f).coerceIn(0f, 1f))
                            alpha = p
                            translationY = stage.px(6f * (1 - p))
                        },
                        style = TextStyle(fontFamily = Spline, color = Palette.White, fontSize = stage.sp(16f), letterSpacing = stage.sp(1.5f), lineHeight = stage.sp(19f), lineHeightStyle = centred),
                    )
                }
            }
        }
    }
}

private val LinearEasingCompat = androidx.compose.animation.core.LinearEasing
