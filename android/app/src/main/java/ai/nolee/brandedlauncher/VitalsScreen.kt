package ai.nolee.brandedlauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Vitals overview entries, in drum order. The first three open a measuring sub-page. */
enum class VitalItem(val label: String, val glyph: Glyph, val metric: VitalMetric?) {
    Heart("Heart rate", Glyph.Vitals, VitalMetric.Heart),
    Oxygen("Blood oxygen", Glyph.Drop, VitalMetric.Oxygen),
    Pressure("Blood pressure", Glyph.Gauge, VitalMetric.Pressure),
    Steps("Steps", Glyph.Steps, null),
    Thermal("Temperature", Glyph.Thermo, null),
    Battery("Battery", Glyph.Battery, null),
    Storage("Storage", Glyph.Storage, null),
}

/** Vitals as a rolling drum. Steps and temperatures update while this page shows; the optical sensors stay off. */
@Composable
fun VitalsScreen(stage: Stage, vitals: VitalsMonitor, device: DeviceState, drum: DrumState, onMeasure: (VitalMetric) -> Unit) {
    val r = vitals.reading
    val (free, total) = remember { device.storage() }
    fun one(value: Float?) = value?.let { String.format(Locale.US, "%.0f°", it) } ?: "--"
    PageTitle(stage, "Vitals", "BODY / DEVICE")
    val items = VitalItem.entries.map { item ->
        val subtitle = when (item) {
            VitalItem.Steps -> r.steps?.let { "$it STEPS SINCE BOOT" } ?: "READING STEP COUNTER"
            VitalItem.Thermal -> "SOC ${one(r.socC)} · BOARD ${one(r.boardC)}"
            VitalItem.Battery -> (if (device.battery >= 0) "${device.battery}%" else "--") + if (device.batteryTempC.isNaN()) "" else " · ${device.batteryTempC.toInt()}°C"
            VitalItem.Storage -> String.format(Locale.US, "%.1f / %.1f GB USED", (total - free) / 1e9, total / 1e9)
            else -> r.value(item.metric!!)?.let { "LAST $it ${item.metric.unit}" } ?: "TAP TO MEASURE"
        }
        DrumItem(item.label, subtitle, item.glyph)
    }
    Drum(stage, drum, items, DrumGeometry.Tall, 39f, 118f, pageClock(), riseDelay = 80) { index ->
        VitalItem.entries[index].metric?.let(onMeasure)
    }
}

private val Dim = Color(0xFF2A3B33)
private val Faint = Color(0xFF52665B)

// Every instrument shares one centre and scale radius, clear of the HUD rows above and below it.
private const val CX = 205f
private const val CY = 262f
private const val RADIUS = 112f

private val VitalMetric.channel get() = when (this) {
    VitalMetric.Heart -> "ECG // CH-01"
    VitalMetric.Oxygen -> "SPO2 // CH-02"
    VitalMetric.Pressure -> "CUFF // CH-03"
}

/**
 * One optical reading, measured in 30 s windows for as long as this page is open, on that metric's own instrument:
 * an ECG monitor for heart rate, a filling saturation vessel for blood oxygen, a cuff gauge for blood pressure.
 * They animate only while the page shows, which is also the only time the sensor LEDs are on.
 */
@Composable
fun VitalMeasureScreen(stage: Stage, metric: VitalMetric, vitals: VitalsMonitor, onRemeasure: () -> Unit) {
    val r = vitals.reading
    val measuring = r.measuring == metric
    // One window per run: when it ends the sensor is off and the result holds until a tap measures again.
    val done = !measuring && r.completed == metric
    val active = measuring && r.contact != false
    val value = r.value(metric)
    PageTitle(
        stage, metric.label,
        when {
            r.blocked -> "NO ACCESS"
            done -> if (value != null) "COMPLETE" else "NO READING"
            measuring && r.contact == false -> "NO CONTACT"
            measuring -> "MEASURING"
            else -> "STARTING"
        },
    )

    // The instruments animate only while a window runs; a finished reading is drawn once and left still.
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(measuring) {
        if (!measuring) return@LaunchedEffect
        val start = withFrameMillis { it } - clock.longValue
        while (true) clock.longValue = withFrameMillis { it } - start
    }
    val started = r.windowStartedAt
    // Read inside drawing, so the window advances every frame without recomposing the page.
    val window = {
        when {
            measuring -> ((System.currentTimeMillis() - started).toFloat() / VitalsMonitor.WINDOW_MS).coerceIn(0f, 1f)
            done -> 1f
            else -> 0f
        }
    }
    val secondsLeft by remember(measuring, started) {
        derivedStateOf {
            clock.longValue
            if (measuring) ((VitalsMonitor.WINDOW_MS - (System.currentTimeMillis() - started)) / 1000).toInt().coerceIn(0, 30) else 30
        }
    }

    val hud = stage.text(12f, Palette.Count, 15f, 1f)
    Box(Modifier.at(stage, 39f, 118f, 332f, 15f)) {
        BasicText(metric.channel, Modifier.align(Alignment.CenterStart), style = hud)
        BasicText(if (done) "DONE" else "WINDOW ${secondsLeft.toString().padStart(2, '0')}S", Modifier.align(Alignment.CenterEnd), style = hud.copy(color = Palette.Mint))
    }
    when (metric) {
        VitalMetric.Heart -> HeartMonitor(stage, clock, window, r.heartRate, active, held = done && r.heartRate != null)
        VitalMetric.Oxygen -> OxygenVessel(stage, clock, window, r.oxygen, active)
        VitalMetric.Pressure -> PressureGauge(stage, clock, window, r.systolic, r.diastolic, active)
    }
    // The bottom HUD row mirrors the top one, pinned to the column edges and well clear of the dial.
    Box(Modifier.at(stage, 39f, 372f, 332f, 15f)) {
        BasicText("SENSOR HX3918", Modifier.align(Alignment.CenterStart), style = hud)
        BasicText(
            when (r.contact) { true -> "CONTACT ●"; false -> "CONTACT ○"; null -> "CONTACT –" },
            Modifier.align(Alignment.CenterEnd),
            style = hud.copy(color = if (r.contact == true) Palette.Mint else Palette.Count),
        )
    }
    // One line, lower, with clear space below the dial and the label row.
    Box(Modifier.at(stage, 39f, 407f, 332f, 22f), contentAlignment = Alignment.Center) {
        BasicText(
            when {
                r.blocked -> "No sensor access. Run setup."
                done -> if (value != null) "Done. Tap to measure again." else "No reading. Tap to try again."
                !measuring -> "Starting the sensor…"
                r.contact == false -> "No skin contact. Wear it snugly."
                r.contact == true -> "Skin contact. Hold still."
                else -> "Hold still while the sensor reads."
            },
            style = stage.text(15f, Palette.Notice, 18f).copy(textAlign = TextAlign.Center),
            maxLines = 1,
        )
    }
    if (done) {
        Box(Modifier.at(stage, 39f, 118f, 332f, 314f).pointerInput(onRemeasure) { detectTapGestures { onRemeasure() } })
    }
}

@Composable
private fun Reading(stage: Stage, value: String?, size: Float, y: Float, height: Float) {
    Box(Modifier.at(stage, CX - 120f, y, 240f, height), contentAlignment = Alignment.Center) {
        BasicText(value ?: "--", style = stage.text(size, if (value == null) Faint else Palette.Ink, height, -2f))
    }
}

@Composable
private fun Caption(stage: Stage, text: String, y: Float, size: Float = 15f) {
    Box(Modifier.at(stage, CX - 110f, y, 220f, size + 5f), contentAlignment = Alignment.Center) {
        BasicText(text, style = stage.text(size, Palette.Mint, size + 5f, if (size >= 15f) 2f else 1f))
    }
}

/** One PQRST complex over [u] 0..1: a small P wave, a sharp R spike between Q and S dips, and a rounded T wave. */
private fun ecg(u: Float): Float = when {
    u < .10f -> 0f
    u < .20f -> .15f * sin((u - .10f) / .10f * PI.toFloat())
    u < .26f -> 0f
    u < .29f -> -.15f * ((u - .26f) / .03f)
    u < .32f -> -.15f + 1.15f * ((u - .29f) / .03f)
    u < .35f -> 1f - 1.35f * ((u - .32f) / .03f)
    u < .38f -> -.35f + .35f * ((u - .35f) / .03f)
    u < .52f -> 0f
    u < .70f -> .28f * sin((u - .52f) / .18f * PI.toFloat())
    else -> 0f
}

/**
 * Heart rate: a segment ring that flares on every beat, a ping expanding from the reading, and an ECG sweep. [held]
 * keeps the last frame (the clock stops with the window), so a captured reading freezes mid-beat instead of going flat.
 */
@Composable
private fun HeartMonitor(stage: Stage, clock: State<Long>, window: () -> Float, bpm: Int?, active: Boolean, held: Boolean) {
    val live = active || held
    // Beats at the measured rate once there is one.
    val rate = (bpm ?: 72).coerceIn(40, 180)
    Canvas(Modifier.fillMaxSize()) {
        val t = clock.value
        val c = Offset(stage.px(CX), stage.px(CY))
        val radius = stage.px(RADIUS)
        val period = 60_000L / rate
        val beat = (t % period).toFloat() / period
        val flare = if (live) exp(-beat * 7f) else 0f

        val lit = (window() * 72).toInt()
        for (i in 0 until 72) rotate(i * 5f, c) {
            val on = i < lit
            drawLine(
                if (on) Palette.Mint else Dim,
                Offset(c.x, c.y - radius - stage.px(if (i % 6 == 0) 12f else 9f)),
                Offset(c.x, c.y - radius),
                stage.px(if (i % 6 == 0) 2.2f else 1.4f),
                alpha = if (on) .5f + .5f * flare else 1f,
            )
        }
        val inner = radius - stage.px(8f)
        drawCircle(Palette.Mint.copy(alpha = .14f), inner, c, style = Stroke(stage.px(1f)))
        if (live) {
            val ping = (beat / .5f).coerceIn(0f, 1f)
            drawCircle(Palette.Mint.copy(alpha = .5f * (1f - ping)), lerp(stage.px(56f), inner, EaseOut.transform(ping)), c, style = Stroke(stage.px(1.5f)))
        }

        // Two beats per pass, drawn from the left with a fading tail and a glowing head.
        val left = c.x - stage.px(84f)
        val width = stage.px(168f)
        val base = c.y + stage.px(58f)
        val amp = stage.px(24f)
        drawLine(Palette.Hair, Offset(left, base), Offset(left + width, base), stage.px(1f))
        val sweep = (t % (period * 2)).toFloat() / (period * 2)
        val steps = 96
        var previous: Offset? = null
        for (s in 0..(sweep * steps).toInt()) {
            val u = s / steps.toFloat()
            val point = Offset(left + width * u, base - amp * (if (live) ecg((u * 2f) % 1f) else 0f))
            previous?.let { drawLine(Palette.Mint, it, point, stage.px(1.6f), alpha = (.15f + .85f * u / sweep.coerceAtLeast(.01f)).coerceAtMost(1f)) }
            previous = point
        }
        previous?.let {
            drawCircle(Palette.Mint.copy(alpha = .25f), stage.px(8f), it)
            drawCircle(Palette.White, stage.px(2.6f), it)
        }
    }
    Reading(stage, bpm?.toString(), 64f, CY - 66f, 72f)
    Caption(stage, "BPM", CY + 8f)
}

/** Blood oxygen: a vessel filling to the saturation with a moving surface and rising bubbles, on a 100-tick scale. */
@Composable
private fun OxygenVessel(stage: Stage, clock: State<Long>, window: () -> Float, oxygen: Int?, active: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val t = clock.value
        val c = Offset(stage.px(CX), stage.px(CY))
        val radius = stage.px(RADIUS)
        val progress = window()

        val lit = ((oxygen?.div(100f) ?: progress) * 100).toInt()
        for (i in 0 until 100) rotate(i * 3.6f, c) {
            val major = i % 10 == 0
            drawLine(
                if (i < lit) Palette.Mint else Dim,
                Offset(c.x, c.y - radius - stage.px(if (major) 12f else 7f)),
                Offset(c.x, c.y - radius),
                stage.px(if (major) 1.8f else 1f),
            )
        }

        // A scanner arc orbiting outside the scale, brightest at its head.
        val orbit = stage.px(RADIUS + 18f)
        val angle = (t % 6_000L) / 6_000f * 360f
        repeat(10) { k ->
            drawArc(Palette.Mint.copy(alpha = .06f * (k + 1)), angle + k * 4f, 4f, false, c - Offset(orbit, orbit), Size(orbit * 2, orbit * 2), style = Stroke(stage.px(2f)))
        }

        val inner = radius - stage.px(16f)
        val fill = when {
            oxygen != null -> oxygen / 100f
            active -> .15f + .6f * progress
            else -> .1f
        }
        val surface = c.y + inner - 2 * inner * fill
        clipPath(Path().apply { addOval(Rect(c, inner)) }) {
            repeat(2) { layer ->
                val amp = stage.px(if (layer == 0) 5f else 3.5f)
                val phase = t / (if (layer == 0) 650f else 1_050f) + layer * 1.7f
                val wave = Path().apply {
                    moveTo(c.x - inner, c.y + inner)
                    var x = c.x - inner
                    while (x <= c.x + inner + stage.px(4f)) {
                        lineTo(x, surface + amp * sin((x - c.x) / stage.px(30f) + phase))
                        x += stage.px(4f)
                    }
                    lineTo(c.x + inner, c.y + inner)
                    close()
                }
                drawPath(wave, Palette.Mint.copy(alpha = if (layer == 0) .22f else .12f))
            }
            if (active) repeat(14) { b ->
                val lifetime = 2_400L + (b * 173L) % 1_600L
                val life = ((t + b * 431L) % lifetime).toFloat() / lifetime
                val x = c.x + inner * (((b * .618f) % 1f) * 1.6f - .8f) + stage.px(4f) * sin(life * 6.28f + b)
                val y = c.y + inner - (c.y + inner - surface) * life
                drawCircle(Palette.Ink.copy(alpha = .4f * (1f - life)), stage.px(1.4f + b % 3), Offset(x, y))
            }
        }
        drawCircle(Palette.Mint.copy(alpha = .35f), inner, c, style = Stroke(stage.px(1.2f)))
    }
    Reading(stage, oxygen?.toString(), 64f, CY - 58f, 72f)
    Caption(stage, "% SPO2", CY + 14f)
}

/** Canvas degrees for a pressure on the gauge: 40 mmHg at 135° (lower left) round to 200 mmHg at 405° (lower right). */
private fun gaugeAngle(mmHg: Float) = 135f + 270f * ((mmHg - 40f) / 160f).coerceIn(0f, 1f)

/** Blood pressure: a cuff gauge whose needle inflates and bleeds down while measuring, with systolic and diastolic arcs. */
@Composable
private fun PressureGauge(stage: Stage, clock: State<Long>, window: () -> Float, systolic: Int?, diastolic: Int?, active: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val t = clock.value
        val c = Offset(stage.px(CX), stage.px(CY))
        val radius = stage.px(RADIUS)
        fun direction(mmHg: Float): Offset {
            val rad = Math.toRadians(gaugeAngle(mmHg).toDouble())
            return Offset(cos(rad).toFloat(), sin(rad).toFloat())
        }
        fun arc(r: Float, from: Float, to: Float, color: Color, width: Float) = drawArc(
            color, gaugeAngle(from), gaugeAngle(to) - gaugeAngle(from), false,
            c - Offset(r, r), Size(2 * r, 2 * r), style = Stroke(stage.px(width), cap = StrokeCap.Round),
        )

        for (v in 40..200 step 5) {
            val major = v % 20 == 0
            val d = direction(v.toFloat())
            drawLine(if (major) Palette.Ink.copy(alpha = .75f) else Dim, c + d * (radius - stage.px(if (major) 14f else 8f)), c + d * radius, stage.px(if (major) 1.8f else 1f))
        }
        val track = radius + stage.px(16f)
        arc(track, 40f, 200f, Dim, 1f)
        if (active) arc(track, 40f, 40f + 160f * window(), Palette.Mint.copy(alpha = .6f), 1.5f)
        systolic?.let { arc(radius + stage.px(6f), 40f, it.toFloat(), Palette.Mint, 3f) }
        diastolic?.let { arc(radius - stage.px(22f), 40f, it.toFloat(), Palette.Ink.copy(alpha = .55f), 2f) }

        val needle = when {
            active -> {
                val cycle = (t % 7_000L) / 7_000f
                if (cycle < .22f) lerp(55f, 185f, EaseOut.transform(cycle / .22f))
                else lerp(185f, 60f, (cycle - .22f) / .78f) + 3.5f * sin(cycle * 110f)
            }
            systolic != null -> systolic.toFloat()
            else -> 40f
        }
        val tip = c + direction(needle) * (radius - stage.px(20f))
        drawLine(Palette.Mint.copy(alpha = .25f), c, tip, stage.px(6f), cap = StrokeCap.Round)
        drawLine(Palette.White, c, tip, stage.px(2f), cap = StrokeCap.Round)
        drawCircle(Palette.Ground, stage.px(8f), c)
        drawCircle(Palette.Mint, stage.px(8f), c, style = Stroke(stage.px(1.5f)))
    }
    // Only the upper scale is labelled: the lower ends would collide with the reading in the gauge's open bottom.
    listOf(80, 120, 160).forEach { v ->
        val rad = Math.toRadians(gaugeAngle(v.toFloat()).toDouble())
        val x = CX + cos(rad).toFloat() * (RADIUS - 32f)
        val y = CY + sin(rad).toFloat() * (RADIUS - 32f)
        Box(Modifier.at(stage, x - 18f, y - 8f, 36f, 16f), contentAlignment = Alignment.Center) {
            BasicText("$v", style = stage.text(12f, Palette.Count, 16f))
        }
    }
    Reading(stage, if (systolic != null && diastolic != null) "$systolic/$diastolic" else null, 42f, CY + 30f, 50f)
    Caption(stage, "SYS / DIA · MMHG", CY + 80f, 12f)
}
