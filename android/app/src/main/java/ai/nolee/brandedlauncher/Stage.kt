package ai.nolee.brandedlauncher

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Measured lens of the Nolee Devkit Ultra (see Nolee_Launcher DisplaySafeZone.kt): two units matched a
 * 2 px straight-edge inset and a 112 px corner radius on 2026-08-13. Critical text keeps 4 px more.
 */
object SafeZone {
    const val WIDTH_PX = 408
    const val HEIGHT_PX = 502
    const val EDGE_INSET_PX = 2f
    const val CORNER_RADIUS_PX = 112f
    const val CONTENT_MARGIN_PX = 4f

    fun isCalibrated(widthPx: Int, heightPx: Int) = widthPx == WIDTH_PX && heightPx == HEIGHT_PX

    /** Horizontal inset readable content needs at [depthPx] from the nearest top or bottom edge. */
    fun contentInsetPx(depthPx: Float): Float {
        val edge = EDGE_INSET_PX + CONTENT_MARGIN_PX
        val radius = CORNER_RADIUS_PX - CONTENT_MARGIN_PX
        if (depthPx <= edge) return edge + radius
        if (depthPx >= edge + radius) return edge
        val fromCentre = edge + radius - depthPx
        return edge + radius - sqrt(radius * radius - fromCentre * fromCentre)
    }
}

/** The web prototype's coordinate space: a 410 × 502 surface whose mint rim sits 6 px inside it. */
object Design {
    const val WIDTH = 410f
    const val HEIGHT = 502f
    const val RIM_INSET = 6f
}

/**
 * Maps design coordinates onto the physical screen. On the calibrated panel the design rim (398 px wide)
 * is scaled to the safe-zone outline (404 px wide), about 1.5% larger, and everything else scales with it.
 */
@Immutable
class Stage(
    val scale: Float,
    val originX: Float,
    val originY: Float,
    val widthPx: Int,
    val heightPx: Int,
    val calibrated: Boolean,
    private val density: Float,
    private val fontScale: Float,
) {
    fun px(design: Float): Float = design * scale
    fun dp(design: Float): Dp = (design * scale / density).dp
    /** Pixel-exact type: the owner's system font scale would otherwise push text through the curve. */
    fun sp(design: Float): TextUnit = (design * scale / density / fontScale).sp

    companion object {
        fun of(widthPx: Int, heightPx: Int, density: Float, fontScale: Float): Stage {
            val calibrated = SafeZone.isCalibrated(widthPx, heightPx)
            val scale = if (calibrated) {
                (widthPx - 2 * SafeZone.EDGE_INSET_PX) / (Design.WIDTH - 2 * Design.RIM_INSET)
            } else {
                min(widthPx / Design.WIDTH, heightPx / Design.HEIGHT)
            }
            return Stage(
                scale = scale,
                originX = widthPx / 2f - Design.WIDTH / 2f * scale,
                originY = heightPx / 2f - Design.HEIGHT / 2f * scale,
                widthPx = widthPx,
                heightPx = heightPx,
                calibrated = calibrated,
                density = density,
                fontScale = fontScale,
            )
        }
    }
}

val LocalStage = staticCompositionLocalOf<Stage> { error("Stage not provided") }

/** Places a child at a design-space rectangle inside a design-sized parent. */
fun Modifier.at(stage: Stage, x: Float, y: Float, width: Float, height: Float): Modifier = layout { measurable, _ ->
    val w = stage.px(width).roundToInt()
    val h = stage.px(height).roundToInt()
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(w, h) { placeable.place(stage.px(x).roundToInt(), stage.px(y).roundToInt()) }
}

/** Places a wrap-content child with its top-left at a design-space point. */
fun Modifier.at(stage: Stage, x: Float, y: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(placeable.width, placeable.height) { placeable.place(stage.px(x).roundToInt(), stage.px(y).roundToInt()) }
}

object Palette {
    val Ground = Color(0xFF030504)
    val Mint = Color(0xFF83F5D0)
    val Ink = Color(0xFFF0F2EF)
    val White = Color(0xFFFFFFFF)
    val Status = Color(0xFFAAB1AC)
    val Sub = Color(0xFF9CA79F)
    val Hair = Color(0xFF34483B)
    val NavLine = Color(0xFF3B443E)
    val TagPaper = Color(0xFFE6ECE7)
    val TagInk = Color(0xFF080A09)
    val AppCard = Color(0xE8060B08)
    val RowCard = Color(0xED070C09)
    val SmallMuted = Color(0xFF96AB9E)
    val Count = Color(0xFF9EAFA5)
    val KioskMuted = Color(0xFF89978F)
    val Notice = Color(0xFFA4B5AA)
    val RowDetail = Color(0xFF87998C)
    val Rim = Color(0x1C83F5D0)
    val GridLine = Color(0x06FFFFFF)
    val WatchGridLine = Color(0x0DFFFFFF)
}

val Spline = FontFamily(Font(R.font.spline_sans_mono))
