package ai.nolee.customlauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val centred = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun Stage.face(size: Float, color: Color, spacing: Float = 0f, lineHeight: Float = size * 1.25f) =
    TextStyle(fontFamily = Spline, fontSize = sp(size), color = color, letterSpacing = sp(spacing), lineHeight = sp(lineHeight), lineHeightStyle = centred)

private const val REVEAL_MS = 1_300L
private const val EXIT_MS = 480L
private val MajorTick = Color(0xFFADBBB0)
private val MinorTick = Color(0xFF466152)
private val Dim = Color(0xFF8DA397)
private val NoDim: State<Float> = mutableFloatStateOf(0f)

// Ring bounds in design space; its centre (205, 235) is the face's optical centre.
private const val RING_X = 60f
private const val RING_Y = 90f
private const val RING = 290f

// Idle: the dial settles at the same footprint as the AI persona's outer halo.
private const val DIAL_CENTRE_Y = 235f
internal const val IDLE_SCALE = 1.27f
internal const val FULL_WATCH_RADIUS = RING / 2f * IDLE_SCALE
// The visible seconds outline, not the invisible outer bounds of the dial canvas.
internal const val FULL_WATCH_OUTLINE_RADIUS = RING * (96f / 240f) * IDLE_SCALE
internal const val FULL_WATCH_OUTLINE_STROKE = RING * (2f / 240f) * IDLE_SCALE

/**
 * Peace watch face: live 24-hour time, a 60-tick seconds ring, weekday, date and time zone.
 *
 * Entrance (1.3 s): a scanline sweeps the dial and uncovers the readout, the ticks boot clockwise with a mint
 * flash, corner brackets lock onto the ring, the digits decode from scrambled glyphs, and the date and zone
 * rails slide in. Exit (0.48 s, drawn above the next page): the digits scramble, the ticks retract and the face
 * collapses to a bright line like a CRT switching off. [dim] (0..1, idle) fades everything outside the dial.
 */
@Composable
fun TimeScreen(
    stage: Stage,
    exiting: Boolean = false,
    dim: State<Float> = NoDim,
    onFullScreen: () -> Unit = {},
    onExited: () -> Unit = {},
    settled: Boolean = false,
    personaMorph: State<Float> = NoDim,
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    if (!exiting) {
        LaunchedEffect(Unit) {
            while (true) {
                now = LocalDateTime.now()
                delay(1_000L - System.currentTimeMillis() % 1_000L)
            }
        }
    }
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        if (settled) return@LaunchedEffect
        val start = withFrameMillis { it }
        val end = if (exiting) EXIT_MS else REVEAL_MS
        while (true) {
            val t = withFrameMillis { it } - start
            clock.longValue = t
            if (t >= end) break
        }
        if (exiting) onExited()
    }
    val reveal: State<Long> = remember { derivedStateOf { if (exiting || settled) REVEAL_MS else clock.longValue } }
    val exit: State<Float> = remember { derivedStateOf { if (exiting) (clock.longValue.toFloat() / EXIT_MS).coerceIn(0f, 1f) else 0f } }
    // Idle glitch: every ten seconds the digits split into offset colour channels for a quarter of a second.
    val glitch = remember { Animatable(0f) }
    LaunchedEffect(now.second) {
        if (!exiting && dim.value > .5f && now.second % 10 == 0) {
            glitch.snapTo(1f)
            glitch.animateTo(0f, tween(260))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(if (exiting) Modifier else Modifier.faceTap(stage, dim, onFullScreen))
            .graphicsLayer {
                // CRT power-off: squash to a line through the dial's centre, then fade.
                val e = exit.value
                val squash = EaseInOut.transform(((e - .3f) / .6f).coerceIn(0f, 1f))
                scaleY = lerp(1f, .012f, squash)
                scaleX = lerp(1f, 1.06f, squash)
                alpha = 1f - ((e - .85f) / .15f).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(.5f, 235f / Design.HEIGHT)
            },
    ) {
        if (dim.value > 0f) Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = 1f - auroraSmooth(personaMorph.value / .28f)
        }) { IdleArt(stage, now, dim) }
        Heading(stage, reveal, exit, dim)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val k = EaseInOut.transform(dim.value)
                    scaleX = lerp(1f, IDLE_SCALE, k)
                    scaleY = scaleX
                    translationY = stage.px((Design.HEIGHT / 2 - DIAL_CENTRE_Y) * k)
                    transformOrigin = TransformOrigin(.5f, DIAL_CENTRE_Y / Design.HEIGHT)
                },
        ) {
            Dial(stage, now, reveal, exit, dim, personaMorph)
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = 1f - auroraSmooth(personaMorph.value / .08f)
            }) {
                if (dim.value > 0f) IdleHud(stage, now, dim)
                Readout(stage, now, reveal, exit, exiting, dim, glitch.asState())
            }
        }
        Rails(stage, now, reveal, exit, dim)
    }
}

private fun Modifier.faceTap(
    stage: Stage,
    dim: State<Float>,
    onFullScreen: () -> Unit,
) = pointerInput(dim.value > .5f) {
    if (dim.value < .5f) {
        detectTapGestures { at ->
            val dx = at.x - stage.px(Design.WIDTH / 2)
            val dy = at.y - stage.px(DIAL_CENTRE_Y)
            val radius = stage.px(RING / 2)
            if (dx * dx + dy * dy <= radius * radius) onFullScreen()
        }
    }
}

@Composable
private fun Heading(stage: Stage, reveal: State<Long>, exit: State<Float>, dim: State<Float>, title: String = "WATCH / PERSONAL") {
    // Raised clear of the dial's top ticks.
    Box(
        Modifier
            .at(stage, 39f, 52f, 332f, 24f)
            .graphicsLayer { alpha = (1f - (exit.value / .3f).coerceIn(0f, 1f)) * (1f - dim.value) },
    ) {
        BasicText(
            title,
            Modifier
                .align(Alignment.CenterStart)
                .drawWithContent {
                    clipRect(right = size.width * progress(reveal.value, 0, 320, EaseOut)) { this@drawWithContent.drawContent() }
                }
                .background(Palette.TagPaper)
                .padding(horizontal = stage.dp(7f), vertical = stage.dp(4f)),
            style = stage.face(13f, Palette.TagInk, .5f, 16f),
        )
        BasicText(
            "● LIVE",
            Modifier
                .align(Alignment.CenterEnd)
                .graphicsLayer {
                    val t = reveal.value
                    alpha = if (t < 520) (if ((t / 90) % 2 == 0L) 1f else 0f) else 1f
                },
            style = stage.face(13f, Palette.Mint, 1f),
        )
    }
}

@Composable
private fun Dial(stage: Stage, now: LocalDateTime, reveal: State<Long>, exit: State<Float>, dim: State<Float>, personaMorph: State<Float>) {
    val seconds = now.second
    Canvas(Modifier.at(stage, RING_X, RING_Y, RING, RING)) {
        val t = reveal.value
        val e = exit.value
        val unit = size.width / 240f
        val c = Offset(size.width / 2, size.height / 2)
        val morph = personaMorph.value
        val gather = auroraSmooth(morph / .55f)
        val handoff = 1f - auroraSmooth(morph / .42f)

        // Ticks boot clockwise from the top with a mint flash, and retract counter-clockwise on exit.
        for (i in 0 until 60) {
            val appear = 120 + i * 9
            val on = ((t - appear) / 80f).coerceIn(0f, 1f)
            val vanish = (59 - i) / 59f * .4f
            if (on <= 0f || e >= vanish && e > 0f) continue
            val major = i % 5 == 0
            val settled = ((t - appear) / 240f).coerceIn(0f, 1f)
            rotate(i * 6f + gather * 12f * sin(i * 1.73f), c) {
                drawLine(
                    lerp(lerp(Palette.Mint, if (major) MajorTick else MinorTick, settled), Palette.Mint, gather),
                    Offset(c.x, lerp(if (major) 10f else 14f, 24f, gather) * unit),
                    Offset(c.x + gather * 7f * sin(i * .91f), lerp(if (major) 21f else 18f, 24f, gather) * unit),
                    (if (major) 1.3f else 1f) * unit,
                    alpha = on * handoff,
                )
            }
        }

        // The track draws itself round, then the seconds arc sweeps up to the current second.
        val r = 96f * unit
        val ring = Offset(c.x - r, c.y - r)
        val trackSweep = 360f * progress(t, 80, 620, EaseInOut) * (1f - e)
        if (trackSweep > 0f) drawArc(Color(0x1683F5D0), -90f, trackSweep, false, ring, Size(2 * r, 2 * r), alpha = handoff, style = Stroke(unit))
        val arc = seconds * 6f * progress(t, 450, 550, EaseOut) * (1f - (e / .5f).coerceIn(0f, 1f))
        val unfurl = arc
        if (unfurl > 0f) drawArc(Palette.Mint, -90f, unfurl, false, ring, Size(2 * r, 2 * r), alpha = handoff, style = Stroke((2f + 2f * gather) * unit))

        // Corner brackets lock onto the dial from further out, spring back out on exit, and fade when idle.
        val lock = progress(t, 0, 450, EaseOut)
        val spread = stage.px(14f) * (1f - lock) + stage.px(18f) * e
        val arm = stage.px(16f)
        val bracket = Color(0x6683F5D0).copy(alpha = .4f * lock * (1f - e) * (1f - dim.value))
        val stroke = stage.px(1f)
        if (bracket.alpha > 0f) listOf(Offset(-1f, -1f), Offset(1f, -1f), Offset(-1f, 1f), Offset(1f, 1f)).forEach { (sx, sy) ->
            val corner = Offset(c.x + sx * (size.width / 2 + spread), c.y + sy * (size.height / 2 + spread))
            drawLine(bracket, corner, Offset(corner.x - sx * arm, corner.y), stroke)
            drawLine(bracket, corner, Offset(corner.x, corner.y - sy * arm), stroke)
        }

        // Scanline across the dial during the entrance.
        val scan = progress(t, 0, 650, EaseInOut)
        val scanAlpha = 1f - ((t - 650) / 120f).coerceIn(0f, 1f)
        if (scanAlpha > 0f && t > 0) {
            val y = size.height * scan
            drawRect(
                Brush.verticalGradient(listOf(Color(0x0083F5D0), Color(0x4083F5D0)), startY = y - stage.px(22f), endY = y),
                topLeft = Offset(0f, y - stage.px(22f)), size = Size(size.width, stage.px(22f)), alpha = scanAlpha,
            )
            drawLine(Palette.Mint, Offset(0f, y), Offset(size.width, y), stage.px(1.2f), alpha = scanAlpha)
        }

        // The collapse leaves a bright line through the centre.
        val flash = ((e - .55f) / .45f).coerceIn(0f, 1f)
        if (flash > 0f) {
            // Undo the parent's vertical squash so the line stays 2 px thick while everything else flattens.
            val squash = lerp(1f, .012f, EaseInOut.transform(((e - .3f) / .6f).coerceIn(0f, 1f)))
            val half = size.width * .5f * (1f - flash * .8f)
            drawLine(Palette.Mint, Offset(c.x - half, c.y), Offset(c.x + half, c.y), stage.px(2f) / squash, alpha = 1f - flash * .6f)
        }
    }
}

/**
 * The full-screen clock's instrument layer, scaled with the dial and faded in with idle. Hairline, monochrome and
 * precise: a fine graduation ring turning slowly against a bracketed bezel, one hairline seconds index with a mint
 * notch, and cardinal marks, with a CHRONO label in place of the kicker. It steps once a second with a short ease,
 * so the idle face draws for about a third of each second rather than continuously, which keeps the watch cool.
 */
@Composable
private fun IdleHud(stage: Stage, now: LocalDateTime, dim: State<Float>) {
    // Seconds of the day: wraps once at midnight, which is one backwards step a day.
    val step by animateFloatAsState(now.toLocalTime().toSecondOfDay().toFloat(), tween(320, easing = EaseOut), label = "idleStep")
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = dim.value }) {
        val c = Offset(stage.px(205f), stage.px(DIAL_CENTRE_Y))
        val s = step
        val hair = stage.px(.8f)

        val graduation = stage.px(100f)
        for (i in 0 until 180) rotate(i * 2f - s * .25f, c) {
            val long = i % 10 == 0
            drawLine(
                Color.White.copy(alpha = if (long) .32f else .13f),
                Offset(c.x, c.y - graduation), Offset(c.x, c.y - graduation + stage.px(if (long) 5f else 2.5f)), hair,
            )
        }

        val bezel = stage.px(107f)
        repeat(4) { k ->
            val start = s * .8f + k * 90f + 20f
            drawArc(Color.White.copy(alpha = .3f), start, 50f, false, c - Offset(bezel, bezel), Size(bezel * 2, bezel * 2), style = Stroke(hair))
            listOf(start, start + 50f).forEach { deg ->
                rotate(deg + 90f, c) {
                    drawLine(Color.White.copy(alpha = .45f), Offset(c.x, c.y - bezel - stage.px(3f)), Offset(c.x, c.y - bezel + stage.px(3f)), hair)
                }
            }
        }

        rotate((s % 60f) * 6f, c) {
            drawLine(Color.White.copy(alpha = .85f), Offset(c.x, c.y - stage.px(124f)), Offset(c.x, c.y - stage.px(104f)), stage.px(1.1f))
            drawRect(Palette.Mint, Offset(c.x - stage.px(2f), c.y - stage.px(118f)), Size(stage.px(4f), stage.px(4f)))
        }

        for (deg in 0 until 360 step 90) rotate(deg.toFloat(), c) {
            drawLine(Color.White.copy(alpha = .5f), Offset(c.x, c.y - stage.px(94f)), Offset(c.x, c.y - stage.px(88f)), hair)
        }
    }
    Box(Modifier.at(stage, 39f, 172f, 332f, 16f).graphicsLayer { alpha = dim.value }, contentAlignment = Alignment.Center) {
        BasicText("CHRONO // LOCAL", style = stage.face(12f, Color.White.copy(alpha = .55f), 2f, 16f))
    }
}

/**
 * Background art for the full-screen clock, in the black space around the dial: a sparse star field, three tilted
 * orbits each carrying a satellite, dashed crosshairs, and registration marks with small readouts in the corners.
 * Monochrome and faint, stepped once a second like the dial.
 */
@Composable
internal fun IdleArt(stage: Stage, now: LocalDateTime, dim: State<Float>, showLabels: Boolean = true, showCorners: Boolean = true, brightness: Float = 1f) {
    val step by animateFloatAsState(now.toLocalTime().toSecondOfDay().toFloat(), tween(320, easing = EaseOut), label = "artStep")
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = dim.value }) {
        val s = step
        val centre = Offset(stage.px(205f), stage.px(Design.HEIGHT / 2))

        repeat(72) { i ->
            val base = .08f + .22f * starNoise(i * 11 + 3)
            val twinkle = if ((now.second + i) % 9 == 0) 2f else 1f
            drawCircle(
                Color.White.copy(alpha = (base * twinkle * (1f+(brightness-1f)*.55f)).coerceAtMost(.85f)),
                stage.px(.5f + .7f * starNoise(i * 13 + 4)),
                Offset(starNoise(i * 3 + 1) * size.width, starNoise(i * 7 + 2) * size.height),
            )
        }

        var dash = 0f
        while (dash < size.width) {
            drawLine(Color.White.copy(alpha = (.05f * (1f+(brightness-1f)*.5f)).coerceAtMost(1f)), Offset(dash, centre.y), Offset(dash + stage.px(4f), centre.y), 1f)
            dash += stage.px(9f)
        }
        dash = 0f
        while (dash < size.height) {
            drawLine(Color.White.copy(alpha = (.05f * (1f+(brightness-1f)*.5f)).coerceAtMost(1f)), Offset(centre.x, dash), Offset(centre.x, dash + stage.px(4f)), 1f)
            dash += stage.px(9f)
        }

        listOf(Triple(250f, 96f, -18f), Triple(280f, 130f, 28f), Triple(320f, 170f, 66f)).forEachIndexed { k, (rx, ry, tilt) ->
            rotate(tilt, centre) {
                drawOval(Color.White.copy(alpha = (.07f * brightness).coerceAtMost(1f)), Offset(centre.x - stage.px(rx), centre.y - stage.px(ry)), Size(stage.px(rx * 2), stage.px(ry * 2)), style = Stroke(1f))
                val angle = Math.toRadians((s * (2.4f - k * .7f) + k * 120f).toDouble())
                val satellite = Offset(centre.x + stage.px(rx) * cos(angle).toFloat(), centre.y + stage.px(ry) * sin(angle).toFloat())
                drawCircle(Color.White.copy(alpha = (.7f * (1f+(brightness-1f)*.2f)).coerceAtMost(1f)), stage.px(1.6f), satellite)
                drawCircle(Color.White.copy(alpha = (.15f * (1f+(brightness-1f)*.5f)).coerceAtMost(1f)), stage.px(5f), satellite, style = Stroke(1f))
            }
        }

        val arm = stage.px(9f)
        if (showCorners) listOf(Offset(62f, 52f) to Offset(1f, 1f), Offset(348f, 52f) to Offset(-1f, 1f), Offset(62f, 450f) to Offset(1f, -1f), Offset(348f, 450f) to Offset(-1f, -1f))
            .forEach { (at, towards) ->
                val corner = Offset(stage.px(at.x), stage.px(at.y))
                drawLine(Color.White.copy(alpha = .35f), corner, Offset(corner.x + towards.x * arm, corner.y), 1f)
                drawLine(Color.White.copy(alpha = .35f), corner, Offset(corner.x, corner.y + towards.y * arm), 1f)
            }
    }
    if (!showLabels) return
    val label = stage.face(12f, Color.White.copy(alpha = .45f), 1f, 15f)
    Box(Modifier.at(stage, 74f, 58f, 262f, 15f).graphicsLayer { alpha = dim.value }) {
        BasicText("CHRONO-01", Modifier.align(Alignment.CenterStart), style = label)
        BasicText("UTC" + ZoneId.systemDefault().rules.getOffset(now).id.replace("Z", "+00:00").take(3), Modifier.align(Alignment.CenterEnd), style = label)
    }
    Box(Modifier.at(stage, 74f, 431f, 262f, 15f).graphicsLayer { alpha = dim.value }) {
        BasicText("DAY ${now.dayOfYear.toString().padStart(3, '0')}", Modifier.align(Alignment.CenterStart), style = label)
        BasicText(now.format(DateTimeFormatter.ofPattern("dd.MM.yy")), Modifier.align(Alignment.CenterEnd), style = label)
    }
}

/** Stable pseudo-random 0..1 for a seed, so the star field never moves between frames. */
private fun starNoise(seed: Int): Float {
    var x = seed * 374_761_393 + 668_265_263
    x = (x xor (x ushr 13)) * 1_274_126_177
    return ((x xor (x ushr 16)) and 0x7fffffff) / 2_147_483_647f
}

@Composable
private fun Readout(stage: Stage, now: LocalDateTime, reveal: State<Long>, exit: State<Float>, exiting: Boolean, dim: State<Float>, glitch: State<Float>) {
    // The scanline uncovers the readout: everything below it stays hidden until it passes.
    val uncover = Modifier.drawWithContent {
        val scanY = stage.px(RING) * progress(reveal.value, 0, 650, EaseInOut)
        clipRect(bottom = scanY) { this@drawWithContent.drawContent() }
    }
    Box(Modifier.at(stage, 0f, RING_Y, Design.WIDTH, RING).then(uncover)) {
        val kicker = "YOUR TIME, AT PEACE."
        Box(Modifier.at(stage, 39f, 172f - RING_Y, 332f, 16f), contentAlignment = Alignment.Center) {
            val typed by remember { derivedStateOf { ((reveal.value - 150) / 24).toInt().coerceIn(0, kicker.length) } }
            BasicText(
                kicker.take(typed).padEnd(kicker.length, ' '),
                // Idle swaps the kicker for the HUD's SYNC label (IdleHud).
                Modifier.graphicsLayer { alpha = (1f - (exit.value / .35f).coerceIn(0f, 1f)) * (1f - dim.value) },
                style = stage.face(12f, Color(0xFFA7BAAD), 1.2f, 16f),
            )
        }

        // Digits decode left to right from cycling glyphs; on exit they scramble again.
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        val tick by remember { derivedStateOf { if (exiting) (exit.value * 40).toInt() else (reveal.value / 45).toInt() } }
        val glyphs = "0123456789#%&$@"
        val digits = buildAnnotatedString {
            time.forEachIndexed { k, ch ->
                val lockAt = 520 + k * 110
                val scrambled = if (exiting) exit.value > k * .07f else reveal.value < lockAt
                if (ch == ':' || !scrambled) append(ch)
                else withStyle(SpanStyle(color = Palette.Mint)) { append(glyphs[(tick * 7 + k * 13) % glyphs.length]) }
            }
        }
        Box(Modifier.at(stage, 39f, 188f - RING_Y, 332f, 83.75f), contentAlignment = Alignment.Center) {
            BasicText(
                digits,
                Modifier
                    .graphicsLayer { alpha = progress(reveal.value, 200, 200) }
                    // The backing that keeps ticks off the digits would cut a hard box through the idle radar wedge.
                    .background(Color(0xB8030504).copy(alpha = .72f * (1f - dim.value)))
                    .padding(horizontal = stage.dp(5f)),
                style = stage.face(67f, Color(0xFFF1F7F3), -5f, 83.75f),
            )
            // Idle glitch: two horizontal slices of the digits jump sideways for a quarter of a second.
            val split = glitch.value
            if (split > 0f) {
                repeat(2) { k ->
                    val top = .12f + ((now.minute * 7 + now.second * 3 + k * 29) % 60) / 100f
                    val band = .1f + k * .06f
                    BasicText(
                        time,
                        Modifier
                            .graphicsLayer {
                                translationX = stage.px((if (k == 0) 9f else -6f) * split)
                                alpha = split
                            }
                            .drawWithContent {
                                clipRect(top = size.height * top, bottom = size.height * (top + band)) { this@drawWithContent.drawContent() }
                            }
                            .background(Palette.Ground)
                            .padding(horizontal = stage.dp(5f)),
                        style = stage.face(67f, if (k == 0) Color(0xFFF1F7F3) else Palette.Mint, -5f, 83.75f),
                    )
                }
            }
        }

        Box(Modifier.at(stage, 39f, 275.8f - RING_Y, 332f, 22f), contentAlignment = Alignment.Center) {
            Row(
                Modifier.graphicsLayer {
                    val p = progress(reveal.value, 880, 360, EaseOut)
                    alpha = p * (1f - (exit.value / .3f).coerceIn(0f, 1f))
                    translationX = stage.px(-12f * (1 - p))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(stage.dp(4f)).background(Palette.Mint))
                Spacer(Modifier.width(stage.dp(8f)))
                BasicText(now.second.toString().padStart(2, '0'), style = stage.face(19f, Palette.Mint))
                Spacer(Modifier.width(stage.dp(8f)))
                BasicText("SECONDS / 60", style = stage.face(12f, Color(0xFF839B8B), .8f))
            }
        }
    }
}

@Composable
private fun Rails(stage: Stage, now: LocalDateTime, reveal: State<Long>, exit: State<Float>, dim: State<Float>) {
    val fadeOut = { e: Float -> (1f - (e / .35f).coerceIn(0f, 1f)) * (1f - dim.value) }
    // Below the dial's lower corner brackets (which end at y 380).
    Box(Modifier.at(stage, 39f, 386f, 332f, 17f)) {
        BasicText(
            now.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)).uppercase(Locale.ENGLISH),
            Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    val p = progress(reveal.value, 750, 380, EaseOut)
                    alpha = p * fadeOut(exit.value)
                    translationX = stage.px(-24f * (1 - p))
                },
            style = stage.face(13f, Palette.Mint, lineHeight = 17f),
        )
        BasicText(
            now.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)).uppercase(Locale.ENGLISH),
            Modifier
                .align(Alignment.CenterEnd)
                .graphicsLayer {
                    val p = progress(reveal.value, 800, 380, EaseOut)
                    alpha = p * fadeOut(exit.value)
                    translationX = stage.px(24f * (1 - p))
                },
            style = stage.face(13f, Color(0xFFC2CEC6), lineHeight = 17f),
        )
    }

    Canvas(Modifier.at(stage, 39f, 408f, 332f, 1f)) {
        val p = progress(reveal.value, 820, 380, EaseInOut) * fadeOut(exit.value)
        if (p > 0f) drawRect(Palette.Hair, size = Size(size.width * p, size.height))
    }
    Row(
        Modifier
            .at(stage, 39f, 413f, 332f, 17f)
            .graphicsLayer { alpha = progress(reveal.value, 950, 300) * fadeOut(exit.value) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            ZoneId.systemDefault().id.replace('_', ' '),
            Modifier.weight(1f),
            style = stage.face(13f, Dim, lineHeight = 17f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText("24H", style = stage.face(13f, Dim, lineHeight = 17f))
    }
}
