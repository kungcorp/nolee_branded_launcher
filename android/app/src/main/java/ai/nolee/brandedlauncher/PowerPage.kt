package ai.nolee.brandedlauncher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/** Each power action requires sliding the handle across and releasing at the end. */
@Composable
fun PowerPage(stage: Stage, device: DeviceState) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }

    fun fire(action: String) {
        if (pending != null) return
        pending = action
        error = false
        scope.launch {
            if (!device.power(action)) {
                error = true
                pending = null
            }
        }
    }

    PageTitle(stage, "Power", "SLIDE TO CONFIRM")
    PowerSlider(stage, "Restart", "reboot", 132f, 110L, pending) { fire("reboot") }
    PowerSlider(stage, "Shut down", "shutdown", 263f, 220L, pending) { fire("shutdown") }
    val clock = pageClock()
    Box(Modifier.at(stage, 44f, 388f, 322f, 36f).graphicsLayer {
        alpha = progress(clock.value, 340L, 380L, EaseOut)
    }, contentAlignment = Alignment.Center) {
        BasicText(
            if (error) "Couldn’t complete the action. Try again." else "Slide right, then release to confirm.",
            style = stage.text(12f, if (error) Palette.Mint else Palette.Notice, 17f),
        )
    }
}

@Composable
private fun PowerSlider(
    stage: Stage, title: String, action: String, y: Float, entranceDelay: Long,
    pending: String?, onConfirm: () -> Unit,
) {
    val clock = pageClock()
    var distance by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val confirmed = pending == action
    val enabled = pending == null
    val latestConfirm by rememberUpdatedState(onConfirm)
    val travel = 248f
    val position by animateFloatAsState(
        if (confirmed) travel else if (dragging) distance else 0f,
        tween(if (dragging) 0 else 260, easing = EaseOut), label = "powerSlide",
    )
    Box(Modifier.at(stage, 39f, y, 332f, 112f).graphicsLayer {
        val p = progress(clock.value, entranceDelay, 460L, EaseOut)
        alpha = p * if (enabled || confirmed) 1f else .4f
        translationY = stage.px(24f * (1f - p))
        scaleX = .95f + .05f * p
        scaleY = scaleX
    }.background(Palette.RowCard).border(stage.dp(1f), Palette.Hair)) {
        BasicText(
            if (confirmed) if (action == "reboot") "Restarting…" else "Shutting down…" else title,
            Modifier.at(stage, 14f, 9f), style = stage.text(23f, Palette.Ink, 29f, -.6f),
        )
        Box(Modifier.at(stage, 10f, 43f, 312f, 60f)
            .background(Color(0x1683F5D0))
            .pointerInput(enabled, stage) {
                if (!enabled) return@pointerInput
                var accepted = false
                detectHorizontalDragGestures(
                    onDragStart = { at ->
                        accepted = at.x >= 0f && at.x <= stage.px(64f)
                        distance = 0f
                        dragging = accepted
                    },
                    onHorizontalDrag = { change, delta ->
                        if (accepted) {
                            change.consume()
                            distance = (distance + delta / stage.scale).coerceIn(0f, travel)
                        }
                    },
                    onDragEnd = {
                        val complete = accepted && distance >= travel * .92f
                        accepted = false
                        dragging = false
                        if (complete) latestConfirm()
                        distance = 0f
                    },
                    onDragCancel = { accepted = false; dragging = false; distance = 0f },
                )
            }) {
            BasicText(
                if (dragging && distance >= travel * .92f) "RELEASE" else "SLIDE TO CONFIRM",
                Modifier.align(Alignment.Center).graphicsLayer {
                    alpha = if (confirmed) 0f else (1f - position / travel).coerceAtLeast(.2f)
                    translationX = stage.px(22f)
                }, style = stage.text(13f, Palette.Mint, 17f, .3f),
            )
            Canvas(Modifier.at(stage, 0f, 0f, 312f, 60f)) {
                val x = stage.px(position)
                drawRect(Palette.Mint.copy(alpha = .12f), size = Size(x + stage.px(64f), size.height))
                drawRect(Palette.Mint, Offset(x, 0f), Size(stage.px(64f), size.height))
                val cx = x + stage.px(32f)
                val cy = size.height / 2
                val stroke = stage.px(2.5f)
                drawLine(Palette.Ground, Offset(cx - stage.px(10f), cy), Offset(cx + stage.px(10f), cy), stroke, StrokeCap.Round)
                drawLine(Palette.Ground, Offset(cx + stage.px(3f), cy - stage.px(7f)), Offset(cx + stage.px(10f), cy), stroke, StrokeCap.Round)
                drawLine(Palette.Ground, Offset(cx + stage.px(3f), cy + stage.px(7f)), Offset(cx + stage.px(10f), cy), stroke, StrokeCap.Round)
            }
        }
    }
}
