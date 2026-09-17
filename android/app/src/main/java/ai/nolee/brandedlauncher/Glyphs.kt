package ai.nolee.brandedlauncher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser

/** One card on a drum: a label, its small caps subtitle and a 24 × 24 stroke glyph. */
class DrumItem(val label: String, val subtitle: String, val glyph: Glyph)

/** The main drum's entries, in wheel order. Companions open their real app; the rest stay in the launcher. */
enum class AppEntry(val label: String, val subtitle: String, val packageName: String?, val glyph: Glyph) {
    Camera("Camera", "NATIVE APP", "ai.nolee.camera", Glyph.Camera),
    Files("Files", "NATIVE APP", "ai.nolee.files", Glyph.Files),
    Gallery("Gallery", "NATIVE APP", "ai.nolee.gallery", Glyph.Gallery),
    Phone("Phone", "NATIVE APP", "ai.nolee.phone", Glyph.Phone),
    SMS("SMS", "NATIVE APP", "ai.nolee.sms", Glyph.Sms),
    NoleeAi("Nolee AI", "CLOUD COMPANION", null, Glyph.Ai),
    Watch("Watch", "WATCH FACE", null, Glyph.Time),
    Vitals("Vitals", "BODY · DEVICE", null, Glyph.Vitals),
    System("System", "SETTINGS", null, Glyph.System);

    val item get() = DrumItem(label, subtitle, glyph)
}

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0Z"

/** 24 × 24 stroke icons, drawn at stroke width 1.5. The first seven are the prototype's (app.js). */
enum class Glyph(vararg val sources: String) {
    Power("M12 2v10", "M6 5a9 9 0 1 0 12 0"),
    Ai(circle(12f, 12f, 8f), circle(9f, 10f, .8f), circle(15f, 10f, .8f), "M8 14q4 4 8 0"),
    Camera("M3 7h5l2-3h4l2 3h5v13H3Z", circle(12f, 13f, 4f)),
    Files("M3 5h7l2 3h9v12H3Z"),
    Gallery("M4 3h16a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z", circle(8f, 8f, 2f), "M4 19l6-7 4 4 3-3 4 6"),
    Phone("M5 3l4 1 1 5-3 2c2 3 3 4 6 6l2-3 5 1 1 4c-1 4-7 2-12-3S1 4 5 3Z"),
    Sms("M3 4h18v13H9l-6 4Z", "M7 8h10M7 12h7"),
    Time(circle(12f, 12f, 9f), "M12 6v6l4 2"),
    System(circle(12f, 12f, 3f), "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1"),
    Vitals("M2 12h4l2.5-6 4 12 2.5-6H22"),
    Profile(circle(12f, 8f, 4f), "M4 21a8 8 0 0 1 16 0"),
    Work("M3 8h18v12H3Z", "M9 8V5h6v3", "M3 13h18"),
    Pin("M12 21s7-6.2 7-11a7 7 0 1 0-14 0c0 4.8 7 11 7 11Z", circle(12f, 10f, 2.5f)),
    Note("M5 3h9l5 5v13H5Z", "M14 3v5h5", "M8 13h8M8 17h5"),
    Calendar("M4 6h16v15H4Z", "M4 11h16", "M8 3v5M16 3v5"),
    Wifi("M2.5 8.5a14 14 0 0 1 19 0", "M5.5 11.8a9.5 9.5 0 0 1 13 0", "M8.6 15a5 5 0 0 1 6.8 0", circle(12f, 18.5f, 1f)),
    Bluetooth("M7 7l10 10-5 4V3l5 4L7 17"),
    Display(circle(12f, 12f, 4f), "M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8"),
    Sound("M3 9h4l5-4v14l-5-4H3Z", "M16 9a4 4 0 0 1 0 6", "M18.5 6.5a7.5 7.5 0 0 1 0 11"),
    Launcher("M4 4h6v6H4Z", "M14 4h6v6h-6Z", "M4 14h6v6H4Z", "M14 14h6v6h-6Z"),
    Drop("M12 3c3 4 6 7.5 6 11a6 6 0 0 1-12 0c0-3.5 3-7 6-11Z"),
    Gauge("M4.5 17a8.5 8.5 0 1 1 15 0", "M12 13l4.5-4.5", circle(12f, 13f, 1f), "M7 20h10"),
    Steps("M8 3c2 0 3 2 3 5s-1 5-3 5-3-2-3-5 1-5 3-5Z", "M16 9c2 0 3 2 3 5s-1 5-3 5-3-2-3-5 1-5 3-5Z", "M6.5 15.5h3M14.5 21.5h3"),
    Thermo("M10 4a2 2 0 0 1 4 0v10.3a4 4 0 1 1-4 0Z", "M12 10v7"),
    Battery("M3 7h16v10H3Z", "M21 10v4", "M6 10h6v4H6Z"),
    Storage("M4 4h16v6H4Z", "M4 14h16v6H4Z", "M7 7h2M7 17h2");

    internal val path: Path by lazy { Path().apply { sources.forEach { addPath(PathParser().parsePathString(it).toPath()) } } }
}

/** The AI sparkle from Neumorphic Launcher, on a 28 × 28 grid. */
private val aiStarPath: Path by lazy {
    PathParser().parsePathString(
        "M12 4C13.3 10.1 15.9 12.7 22 14C15.9 15.3 13.3 17.9 12 24C10.7 17.9 8.1 15.3 2 14C8.1 12.7 10.7 10.1 12 4Z" +
            "M22.5 .5C23.125 3.875 24.125 4.875 27.5 5.5C24.125 6.125 23.125 7.125 22.5 10.5C21.875 7.125 20.875 6.125 17.5 5.5C20.875 4.875 21.875 3.875 22.5 .5Z",
    ).toPath()
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    scale(size.minDimension / 24f, pivot = Offset.Zero) {
        drawPath(glyph.path, color, style = Stroke(width = 1.5f))
    }
}

fun DrawScope.drawAiStar(color: Color) {
    scale(size.minDimension / 28f, pivot = Offset.Zero) { drawPath(aiStarPath, color) }
}
