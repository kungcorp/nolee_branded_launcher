package ai.nolee.brandedlauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

internal fun kioskExitCollapse(progress: Float): Float =
    EaseInOut.transform(((progress - .34f) / .66f).coerceIn(0f, 1f))

/** Fade the full scene in place after the outer edge has disappeared. */
@Composable
internal fun KioskExitSurface(
    progress: Animatable<Float, AnimationVector1D>,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val p = kioskExitCollapse(progress.value)
            alpha = 1f - p
            clip = true
        }) { content() }
    }
}
