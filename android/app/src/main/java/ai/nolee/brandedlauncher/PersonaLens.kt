package ai.nolee.brandedlauncher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

/** Non-interactive CRT lens above all persona content, including the listening edge light. */
@Composable
fun PersonaLens(stage: Stage, progress: Float) {
    val context=LocalContext.current
    val frame=remember { mutableLongStateOf(0L) }
    val start=remember { SystemClock.uptimeMillis() }
    LaunchedEffect(context) {
        (context as? LifecycleOwner)?.lifecycle?.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(true){
                val elapsed=SystemClock.uptimeMillis()-start
                frame.longValue=elapsed
                // Grain only changes every 132 ms; sample faster just around interference.
                val burst=elapsed%7_700L
                delay(if(burst in 6_750L..7_100L)33L else 132L-elapsed%132L)
            }
        }
    }
    val paint=remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val matrix=remember { Matrix() }
    val noise=remember {
        val random=Random(81)
        Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).also { b ->
            for(y in 0..63)for(x in 0..63){
                val bright=random.nextBoolean()
                b.setPixel(x,y,Color.argb(random.nextInt(3,15),if(bright)180 else 0,if(bright)220 else 0,if(bright)210 else 0))
            }
        }
    }
    val grain=remember { BitmapShader(noise,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT) }
    // Static scanlines and vignette are rasterized once, rather than rebuilt every lens tick.
    val staticLens=remember(stage.widthPx,stage.heightPx,stage.scale) {
        Bitmap.createBitmap(stage.widthPx,stage.heightPx,Bitmap.Config.ARGB_8888).also { b ->
            val c=android.graphics.Canvas(b);val p=Paint(Paint.ANTI_ALIAS_FLAG)
            p.style=Paint.Style.STROKE;p.strokeWidth=stage.px(.65f);p.color=Color.argb(19,0,0,0)
            val lines=Path();var y=0f
            while(y<b.height){lines.moveTo(0f,y);lines.lineTo(b.width.toFloat(),y);y+=stage.px(3f)}
            c.drawPath(lines,p);p.style=Paint.Style.FILL;p.alpha=255
            p.shader=RadialGradient(b.width*.5f,b.height*.48f,b.height*.67f,
                intArrayOf(Color.TRANSPARENT,Color.TRANSPARENT,Color.argb(55,0,8,9)),
                floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
            c.drawRect(0f,0f,b.width.toFloat(),b.height.toFloat(),p)
        }
    }
    val lensBounds=remember { android.graphics.RectF() }
    val clip=remember(stage.widthPx,stage.heightPx) { Path().apply {
        addRoundRect(2f,2f,stage.widthPx-2f,stage.heightPx-2f,
            SafeZone.CORNER_RADIUS_PX,SafeZone.CORNER_RADIUS_PX,Path.Direction.CW)
    } }

    Canvas(Modifier.fillMaxSize()) {
        val strength=auroraSmooth(progress)
        if(strength<=0f)return@Canvas
        val t=frame.longValue/1000f
        drawIntoCanvas { target ->
            val c=target.nativeCanvas
            c.save()
            c.clipPath(clip)
            paint.reset();paint.isAntiAlias=true;paint.alpha=(255*strength).toInt()
            lensBounds.set(0f,0f,size.width,size.height)
            c.drawBitmap(staticLens,null,lensBounds,paint)
            matrix.setTranslate(((frame.longValue/132*17)%64).toFloat(),((frame.longValue/132*29)%64).toFloat())
            grain.setLocalMatrix(matrix);paint.shader=grain;paint.alpha=(255*strength).toInt()
            c.drawRect(0f,0f,size.width,size.height,paint)
            paint.shader=null
            // Brief thin interference bands; no full-screen flashes or content displacement.
            val burst=t%7.7f
            if(burst>6.9f && burst<7.06f){
                val lineY=size.height*(.27f+.38f*((floor(t/7.7f).toInt()%3)/2f))
                paint.color=Color.argb((22*strength).toInt(),150,245,225)
                c.drawRect(0f,lineY,size.width*.82f,lineY+stage.px(1.3f),paint)
                paint.color=Color.argb((12*strength).toInt(),160,170,245)
                c.drawRect(size.width*.13f,lineY+stage.px(3f),size.width,lineY+stage.px(4f),paint)
            }
            c.restore()
        }
    }
}
