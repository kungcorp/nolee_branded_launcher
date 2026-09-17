package ai.nolee.customlauncher

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

/** Shared native overlay: remains mounted when a watch hold commits to the persona page. */
@Composable
fun PersonaScreen(stage: Stage, seconds: Float, startedAt: java.time.LocalDateTime, emotion: PersonaEmotion, listening: Boolean, returning: Boolean, cameraAngled: Boolean, cameraRevision: Int) {
    val context = LocalContext.current
    val aurora = remember { BallPersonaView(context) }
    var now by remember { mutableStateOf(java.time.LocalDateTime.now()) }
    var targetAngle by remember(startedAt) { mutableStateOf(cameraAngled) }
    var automaticMove by remember(startedAt) { mutableStateOf(false) }
    val ready=seconds>=PERSONA_ENTER_SECONDS && !returning
    LaunchedEffect(context,startedAt,cameraRevision,ready) {
        if(!ready)return@LaunchedEffect
        targetAngle=cameraAngled
        automaticMove=false
        (context as? LifecycleOwner)?.lifecycle?.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // No polling while holding a view. A swipe cancels/restarts this idle timer.
            while(true){
                delay(8_000L)
                automaticMove=true
                targetAngle=!targetAngle
            }
        }
    }
    val cameraPosition by animateFloatAsState(
        if(targetAngle)2.6f else 0f,
        tween(if(automaticMove)1500 else 750,easing=FastOutSlowInEasing),label="personaCamera")
    val camera=cameraPosition*auroraSmooth(seconds/PERSONA_ENTER_SECONDS)
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
            translationX=stage.px(22f)*camera;translationY=stage.px(16f)*camera
            rotationY=-10f*camera;rotationX=7f*camera;rotationZ=-2.5f*camera
            scaleX=1f+.20f*camera;scaleY=1f+.20f*camera
            cameraDistance=stage.px(1600f)
        }) {
            IdleArt(stage, now, artAlpha, showLabels = false, showCorners = false,
                brightness = 1f+1.6f*auroraSmooth(seconds/PERSONA_BODY_SECONDS))
        }
        AndroidView(
            factory = { aurora },
            modifier = Modifier.fillMaxSize(),
            update = { it.update(stage, seconds, startedAt, emotion, listening, returning, camera) },
        )
    }
}

internal fun auroraSmooth(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
