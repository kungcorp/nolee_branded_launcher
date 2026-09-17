package ai.nolee.brandedlauncher

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Brief full-frame signal disruption: every scan band moves, with shared screen jitter. */
@Composable
fun PersonaGlitchSurface(stage: Stage, enabled: Boolean, content: @Composable () -> Unit) {
    val context=LocalContext.current
    val split=remember { mutableFloatStateOf(0f) }
    val seed=remember { mutableIntStateOf(0) }
    LaunchedEffect(enabled,context) {
        split.floatValue=0f
        if(!enabled)return@LaunchedEffect
        (context as? LifecycleOwner)?.lifecycle?.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                delay(8_000)
                while(true){
                    seed.intValue=Random.nextInt(1000)
                    val start=SystemClock.uptimeMillis()
                    do {
                        split.floatValue=(1f-(SystemClock.uptimeMillis()-start)/300f).coerceIn(0f,1f)
                        delay(16)
                    } while(split.floatValue>0f)
                    delay(9_700)
                }
            } finally { split.floatValue=0f }
        }
    }
    Box(Modifier.fillMaxSize().drawWithContent {
        val amount=if(enabled)split.floatValue else 0f
        if(amount<=0f)drawContent() else {
            // Cover the complete frame, rather than displacing two isolated strips.
            // Six abrupt signal changes decay back into the unmodified image.
            val tick=((1f-amount)*6f).toInt()
            val polarity=if(tick%2==0)1f else -1f
            val jitterX=stage.px(5f)*polarity*amount
            val jitterY=stage.px(if(tick%3==0)3f else -2f)*amount
            drawRect(Palette.Ground)
            val count=8
            repeat(count){band ->
                val top=size.height*band/count
                val bottom=size.height*(band+1)/count
                val hash=(seed.intValue+band*37+tick*19)%23
                val displacement=(7f+hash*.65f)*(if(band%2==0)1f else -1f)
                clipRect(top=top,bottom=bottom){
                    translate(left=jitterX+stage.px(displacement)*amount,top=jitterY){
                        scale(1f+.014f*amount){this@drawWithContent.drawContent()}
                    }
                }
            }
        }
    }) { content() }
}
