package ai.nolee.brandedlauncher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.abs
import kotlin.math.max

private enum class PersonaCameraView(val yaw: Float, val pitch: Float) {
    Front(0f,0f), UpperLeft(2.6f,2.6f), Right(-2.6f,.7f), Top(0f,3.2f);
    fun next()=entries[(ordinal+1)%entries.size]
}

/** Shared native overlay: remains mounted when a watch hold commits to the persona page. */
@Composable
fun PersonaScreen(stage: Stage, seconds: Float, startedAt: java.time.LocalDateTime, emotion: PersonaEmotion, listening: Boolean, returning: Boolean, cameraView: Int, cameraRevision: Int, exitProgress: () -> Float = { 0f }, progressLabel: String = "THINKING", onCameraView: (Int) -> Unit) {
    val context = LocalContext.current
    val aurora = remember { BallPersonaView(context) }
    var now by remember { mutableStateOf(java.time.LocalDateTime.now()) }
    val targetView = PersonaCameraView.entries[cameraView.coerceIn(0,3)]
    val currentView by rememberUpdatedState(cameraView)
    val changeView by rememberUpdatedState(onCameraView)
    var automaticMove by remember(startedAt) { mutableStateOf(false) }
    val ready=seconds>=PERSONA_ENTER_SECONDS && !returning
    LaunchedEffect(context,startedAt,cameraRevision,ready) {
        if(!ready)return@LaunchedEffect
        automaticMove=false
        (context as? LifecycleOwner)?.lifecycle?.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // No polling while holding a view. A swipe cancels/restarts this idle timer.
            while(true){
                delay(15_000L)
                automaticMove=true
                changeView((currentView+1)%PersonaCameraView.entries.size)
            }
        }
    }
    val cameraYaw by animateFloatAsState(targetView.yaw,
        tween(if(automaticMove)1800 else 750,easing=FastOutSlowInEasing),label="personaCameraYaw")
    val cameraPitch by animateFloatAsState(targetView.pitch,
        tween(if(automaticMove)1800 else 750,easing=FastOutSlowInEasing),label="personaCameraPitch")
    val presence=auroraSmooth(seconds/PERSONA_ENTER_SECONDS)
    val yaw=cameraYaw*presence
    val pitch=cameraPitch*presence
    LaunchedEffect(context) {
        (context as? LifecycleOwner)?.lifecycle?.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(true){
                now=java.time.LocalDateTime.now()
                delay(1_000L-System.currentTimeMillis()%1_000L)
            }
        }
    }
    // Complement the watch artwork fade to carry the full-screen background across.
    val artAlpha = rememberUpdatedState(auroraSmooth(seconds / PERSONA_BODY_SECONDS / .28f))
    DisposableEffect(aurora) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> aurora.running = true
                Lifecycle.Event.ON_PAUSE -> aurora.running = false
                else -> Unit
            }
        }
        aurora.running = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            aurora.running = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Background parallax is stronger than the near-centered sphere's projection.
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX=stage.px(22f)*yaw;translationY=stage.px(16f)*pitch
            rotationY=-10f*yaw;rotationX=7f*pitch;rotationZ=-2.5f*yaw
            val cover=1f+.20f*max(abs(yaw),abs(pitch))
            scaleX=cover;scaleY=cover
            cameraDistance=stage.px(1600f)
        }) {
            IdleArt(stage, now, artAlpha, showLabels = false, showCorners = false,
                brightness = 1f+1.6f*auroraSmooth(seconds/PERSONA_BODY_SECONDS))
        }
        AndroidView(
            factory = { aurora },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                // Only the native ball contracts. The star field remains full size.
                val collapse = kioskExitCollapse(exitProgress())
                scaleX = 1f - .94f * collapse
                scaleY = 1f - .94f * collapse
            },
            update = { it.update(stage, seconds, startedAt, emotion, listening, returning, yaw, pitch, progressLabel) },
        )
    }
}

internal fun auroraSmooth(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
