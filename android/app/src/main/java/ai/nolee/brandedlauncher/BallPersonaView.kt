package ai.nolee.brandedlauncher

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.toArgb
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import kotlin.random.Random

enum class PersonaEmotion(val pose: FloatArray) {
    Regular(floatArrayOf(0f,-12f,0f,0f,0f,12f,9.6672f)),
    Speaking(floatArrayOf(0f,-12f,0f,0f,0f,12f,9.6672f)),
    Thinking(floatArrayOf(0f,-12f,0f,0f,0f,12f,9.6672f));
}

internal const val PERSONA_ENTER_SECONDS = 1.9f
internal const val PERSONA_BODY_SECONDS = 1.15f
private const val PERSONA_EYE_SECONDS = 1.25f

private const val EYE_WEIGHT = 1.18f
private const val PERSONA_RADIUS = FULL_WATCH_OUTLINE_RADIUS * .95f

/** Native port of the approved preview. Cached light textures avoid per-frame blur on the watch. */
class BallPersonaView(context: Context): View(context) {
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private val mintColor=Palette.Mint.toArgb()
    private val glowTint=PorterDuffColorFilter(mintColor,PorterDuff.Mode.SRC_IN)
    private val rimWidths=floatArrayOf(9f,4f,1.5f)
    private val rimAlphas=floatArrayOf(.045f,.14f,.48f)
    private val ribbonU=FloatArray(41){it/40f*2f-1f}
    private val ribbonEdge=FloatArray(41){sqrt(max(0f,1f-ribbonU[it]*ribbonU[it]))}
    private val ribbonV=FloatArray(13){it/12f*2f-1f}
    private val shadeCos=FloatArray(13){cos(-.6f+ribbonV[it]*.35f)}
    private val shadeSin=FloatArray(13){sin(-.6f+ribbonV[it]*.35f)}
    private val path=Path()
    private val rect=RectF()
    private var stage: Stage?=null
    private var elapsed=0f
    private var liveStart=0L
    private var lastFrame=0L
    private var clockTime:LocalDateTime?=null
    private var emotion=PersonaEmotion.Regular
    private val pose=PersonaEmotion.Regular.pose.copyOf()
    private var returning=false
    private var cameraYaw=0f
    private var cameraPitch=0f
    private var lastCx=Design.WIDTH/2
    private var lastCy=Design.HEIGHT/2
    private var lastScaleX=1f
    private var lastScaleY=1f
    private var returnFrom=PERSONA_ENTER_SECONDS
    private var listening=false
    private var listeningAlpha=0f
    private var listeningReveal=-1f
    private var ribbonPhase=0f
    private val listeningFont by lazy { Typeface.create(mono,Typeface.BOLD) }
    private var speaking=0f
    private var progressLabel="THINKING"
    private var thinkingLabel=0f
    private var thinking=0f
    private var thinkingCenter=0f
    private var bouncePhase=.5f
    private var bounceDuration=2.3f
    private var bounceHeight=64f
    private var particleAge=10f
    private var particleX=0f
    private var particleY=0f
    private val ribbonVertices=FloatArray(41*13*2)
    private val ribbonColors=IntArray(41*13)
    // Crisp translucent sheet with satin shading and fine flowing highlights baked into its surface.
    private val ribbonTexture=Bitmap.createBitmap(160,96,Bitmap.Config.ARGB_8888).also { b ->
        for(y in 0 until b.height)for(x in 0 until b.width){
            val u=x/(b.width-1f)*2f-1f;val v=y/(b.height-1f)*2f-1f
            val edge=((1f-abs(v))/.22f).coerceIn(0f,1f)*((1f-abs(u))/.12f).coerceIn(0f,1f)
            val light=(.5f+.5f*cos(v*2f+u*2.5f)).coerceIn(0f,1f)
            val sheen=exp(-((v-.25f*sin(u*3.2f))/.16f).pow(2))*.30f
            val filament=exp(-((v+.48f-.08f*sin(u*4f))/.025f).pow(2))*.16f
            val detail=(sheen+filament+.025f*cos(v*65f+u*5f)).coerceAtLeast(0f)
            b.setPixel(x,y,Color.argb((edge*235).toInt(),
                (65+100*light+90*detail).toInt().coerceAtMost(255),
                (190+60*light+20*detail).toInt().coerceAtMost(255),
                (159+63*light+65*detail).toInt().coerceAtMost(255)))
        }
    }
    private val random=Random(System.nanoTime())
    private var gazeX=0f
    private var gazeY=0f
    private var tilt=0f
    private var fromX=0f
    private var fromY=0f
    private var fromTilt=0f
    private var targetX=0f
    private var targetY=0f
    private var targetTilt=0f
    private var gazeWait=.35f
    private var gazeElapsed=0f
    private var gazeDuration=1f
    private var gestureWait=1.1f
    private var gestureTime=0f
    private var gestureDuration=.9f
    private var gestureNod=true
    private var nod=0f
    private var shake=0f
    private fun moveGesture(dt:Float){
        if(gestureWait>0f){
            gestureWait-=dt
            if(gestureWait>0f)return
            gestureTime=0f;gestureDuration=.75f+random.nextFloat()*.3f
            gestureNod=random.nextBoolean()
        }
        gestureTime+=dt
        val u=(gestureTime/gestureDuration).coerceIn(0f,1f)
        val wave=sin(u*PI.toFloat()*4f)*sin(u*PI.toFloat()).pow(2)
        nod=if(gestureNod)wave else 0f;shake=if(gestureNod)0f else wave
        if(u>=1f){nod=0f;shake=0f;gestureWait=1.8f+random.nextFloat()*2f}
    }

    private fun resetGaze(){
        gazeX=0f;gazeY=0f;tilt=0f;gazeWait=.35f;gazeElapsed=0f
        bouncePhase=.5f;bounceDuration=2.3f;bounceHeight=64f;particleAge=10f
        gestureWait=1.1f;gestureTime=0f;nod=0f;shake=0f
    }
    private fun moveGaze(dt:Float){
        if(gazeWait>0f){
            gazeWait-=dt
            if(gazeWait>0f)return
            fromX=gazeX;fromY=gazeY;fromTilt=tilt
            val rest=random.nextFloat()<.3f
            targetX=if(rest)0f else random.nextFloat()*2f-1f
            targetY=if(rest)0f else random.nextFloat()*1.6f-.8f
            targetTilt=if(rest)0f else targetX*3f+(random.nextFloat()*4f-2f)
            gazeDuration=.30f+random.nextFloat()*.28f;gazeElapsed=0f
        }
        gazeElapsed+=dt
        val u=(gazeElapsed/gazeDuration).coerceIn(0f,1f)
        // Zero speed and acceleration at each end; move, hold, then choose a new direction.
        val ease=u*u*u*(u*(u*6f-15f)+10f)
        gazeX=lerp(fromX,targetX,ease);gazeY=lerp(fromY,targetY,ease);tilt=lerp(fromTilt,targetTilt,ease)
        if(u>=1f)gazeWait=.45f+random.nextFloat()*.65f
    }
    private val mono=ResourcesCompat.getFont(context,R.font.spline_sans_mono) ?: Typeface.MONOSPACE
    private data class Dot(val x:Float,val y:Float,val ex:Float,val ey:Float,val tier:Int,val seed:Float)
    private val dots=ArrayList<Dot>()
    private val glowTexture=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888).also {
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader=RadialGradient(96f,96f,96f,intArrayOf(Color.WHITE,Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        Canvas(it).drawCircle(96f,96f,96f,p)
    }
    // Approved HTML defaults: eyes 190%, spacing 125%, rim 150%, blur 15%.
    // Alpha textures include padding for the exterior rim. Blur is computed only once.
    private val sphere=texture { c,p,r ->
        p.shader=RadialGradient(0f,0f,r,intArrayOf(mint(6),mint(13),mint(36),mint(26),Color.TRANSPARENT),floatArrayOf(0f,.65f,.87f,.96f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(0f,0f,r,p)
        p.reset();p.isAntiAlias=true;p.style=Paint.Style.STROKE;p.strokeWidth=r*FULL_WATCH_OUTLINE_STROKE/PERSONA_RADIUS
        p.shader=LinearGradient(-r,-r,r,r,intArrayOf(mint(255),mint(122),mint(138),mint(255)),floatArrayOf(0f,.28f,.65f,1f),Shader.TileMode.CLAMP)
        p.maskFilter=BlurMaskFilter(r*.012f*.15f,BlurMaskFilter.Blur.NORMAL);c.drawCircle(0f,0f,r,p)
        p.reset();p.isAntiAlias=true;c.save();val clip=Path();clip.addCircle(0f,0f,r,Path.Direction.CW);c.clipPath(clip)
        p.shader=RadialGradient(-r*.32f,-r*.82f,r*.8f,intArrayOf(mint(31),mint(15),Color.TRANSPARENT),floatArrayOf(0f,.35f,1f),Shader.TileMode.CLAMP);c.drawCircle(-r*.32f,-r*.82f,r*.8f,p)
        p.shader=RadialGradient(r*.2f,r*.89f,r*.64f,intArrayOf(mint(41),Color.TRANSPARENT),null,Shader.TileMode.CLAMP);c.drawCircle(r*.2f,r*.89f,r*.64f,p);c.restore()
    }
    private val rimLight=texture { c,p,r ->
        val ring=RectF(-r,-r,r,r)
        for(pass in 0..2){
            p.reset();p.isAntiAlias=true;p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND
            p.strokeWidth=r*floatArrayOf(.06f,.024f,.009f)[pass]
            p.maskFilter=BlurMaskFilter(r*floatArrayOf(.038f,.015f,.004f)[pass],BlurMaskFilter.Blur.NORMAL)
            for(i in 0 until 32){
                val envelope=sin(PI.toFloat()*(i+.5f)/32f).pow(2)
                p.color=mint((255*envelope*floatArrayOf(.25f,.48f,.65f)[pass]).toInt())
                c.drawArc(ring,-16f+i,1.4f,false,p)
            }
        }
    }
    private val face=texture { c,p,r ->
        for(layer in arrayOf(floatArrayOf(.23f,.20f,1.14f),floatArrayOf(.16f,.30f,1f),floatArrayOf(.10f,.38f,.90f))){
            val (blur,alpha,scale)=layer;p.reset();p.isAntiAlias=true
            p.shader=LinearGradient(-r*.5f,-r*.2f,r*.5f,r*.3f,intArrayOf(mint((255*alpha).toInt()),mint((255*alpha*.87f).toInt()),mint((255*alpha*.82f).toInt())),floatArrayOf(0f,.48f,1f),Shader.TileMode.CLAMP)
            p.maskFilter=BlurMaskFilter(r*blur,BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(-r*.61f*scale,-r*.40f*scale,r*.61f*scale,r*.40f*scale,r*.24f,r*.24f,p)
        }
    }
    private fun mint(alpha:Int):Int { val color=mintColor;return Color.argb(alpha,Color.red(color),Color.green(color),Color.blue(color)) }
    private fun texture(draw:(Canvas,Paint,Float)->Unit):Bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888).also {
        val c=Canvas(it);c.translate(256f,256f);draw(c,Paint(Paint.ANTI_ALIAS_FLAG),192f)
    }
    private fun layer(c:Canvas,b:Bitmap,r:Float,a:Float){if(a<=.001f)return;fill(Color.WHITE,a);ink.isFilterBitmap=true;val half=r*256f/192f;rect.set(-half,-half,half,half);c.drawBitmap(b,null,rect,ink)}
    var running=false
        set(value){field=value;lastFrame=0L;if(value)invalidate()}
    init {setWillNotDraw(false);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}

    fun update(stage:Stage,seconds:Float,startedAt:LocalDateTime,emotion:PersonaEmotion,listening:Boolean,returning:Boolean,cameraYaw:Float,cameraPitch:Float,progressLabel:String="THINKING"){
        if(returning && !this.returning){returnFrom=elapsed.coerceAtLeast(.001f);sampleDigits(LocalDateTime.now())}
        this.progressLabel=progressLabel
        this.returning=returning
        this.cameraYaw=cameraYaw
        this.cameraPitch=cameraPitch
        this.stage=stage
        this.listening=listening
        if(clockTime!=startedAt){clockTime=startedAt;sampleDigits(startedAt);liveStart=0L;thinking=0f;thinkingCenter=0f;listeningReveal=-1f;listeningAlpha=0f;ribbonPhase=0f;resetGaze()}
        if(seconds<PERSONA_ENTER_SECONDS)liveStart=0L else if(liveStart==0L)liveStart=SystemClock.uptimeMillis()
        elapsed=seconds;this.emotion=emotion;invalidate()
    }
    private fun sampleDigits(now:LocalDateTime){
        dots.clear();val b=Bitmap.createBitmap(360,120,Bitmap.Config.ARGB_8888)
        val c=Canvas(b);val p=Paint(Paint.ANTI_ALIAS_FLAG);p.typeface=mono;p.textSize=67f*IDLE_SCALE;p.color=Color.WHITE
        val time=now.format(DateTimeFormatter.ofPattern("HH:mm"));val spacing=-5f*IDLE_SCALE
        val widths=time.map{p.measureText(it.toString())};var x=(360f-widths.sum()-spacing*(time.length-1))/2
        val baseline=60f-(p.ascent()+p.descent())/2
        time.forEachIndexed{i,ch->if(ch!=':')c.drawText(ch.toString(),x,baseline,p);x+=widths[i]+spacing}
        val left=ArrayList<Pair<Float,Float>>();val right=ArrayList<Pair<Float,Float>>()
        for(y in 4 until 116 step 5)for(xx in 5 until 355 step 5)if(Color.alpha(b.getPixel(xx,y))>100)
            (if(xx<180)left else right).add((xx-180f) to (y-66.5f))
        b.recycle()
        for(side in -1..1 step 2){
            val source=if(side<0)left else right
            source.forEachIndexed { index,from ->
                val seed=index+if(side>0)source.size else 0
                val u=((seed*37)%101)/100f;val v=((seed*61)%103)/102f
                val halfWidth=96f*.053f*1.9f*EYE_WEIGHT/2f-1.35f
                val ey=(v*2-1)*(12f+halfWidth);val capY=max(0f,abs(ey)-12f)
                val span=sqrt(max(0f,halfWidth*halfWidth-capY*capY))
                dots.add(Dot(from.first,from.second,side*24f+(u*2-1)*span,ey,0,seed*2.399f))
            }
        }
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c);val s=stage ?: return;if(elapsed<=0f)return
        val now=SystemClock.uptimeMillis();val dt=if(lastFrame==0L)0f else ((now-lastFrame)/1000f).coerceAtMost(.1f);lastFrame=now
        if(listeningReveal>=0f && listening)listeningReveal+=dt
        // Keep the rapid entry tumble running at 4.5 turns/second, independent of morph alpha.
        ribbonPhase=(ribbonPhase+dt*28f)%(2f*PI.toFloat())
        val blend=1f-exp(-dt*12f)
        listeningAlpha=lerp(listeningAlpha,if(listening && listeningReveal>=0f && emotion==PersonaEmotion.Regular)1f else 0f,1f-exp(-dt*3f))
        pose.indices.forEach{pose[it]=lerp(pose[it],emotion.pose[it],blend)}
        speaking=lerp(speaking,if(emotion==PersonaEmotion.Speaking && !returning)1f else 0f,blend)
        thinkingLabel=lerp(thinkingLabel,if(emotion==PersonaEmotion.Thinking && !returning)1f else 0f,1f-exp(-dt*5f))
        thinkingCenter=lerp(thinkingCenter,if(emotion!=PersonaEmotion.Regular)1f else 0f,1f-exp(-dt*6f))
        if(thinkingCenter>.995f)thinkingCenter=1f
        // Morph the face during the same easing window as the move to center.
        thinking=lerp(thinking,if(emotion==PersonaEmotion.Thinking && !returning)1f else 0f,1f-exp(-dt*6f))
        val p=(elapsed/PERSONA_ENTER_SECONDS).coerceIn(0f,1f);val ep=(elapsed/PERSONA_EYE_SECONDS).coerceIn(0f,1f)
        val settled=if(liveStart==0L)0f else (now-liveStart)/1000f
        val t=elapsed+settled*1.05f;val ready=p>=1f
        if(ready){
            if(emotion!=PersonaEmotion.Thinking){moveGaze(dt*.72f);moveGesture(dt*.72f)}
            particleAge+=dt
            if(emotion!=PersonaEmotion.Thinking)bouncePhase+=dt/bounceDuration
            if(bouncePhase>=1f){
                bouncePhase-=1f
                bounceDuration=1.95f+random.nextFloat()*.70f
                bounceHeight=48f+random.nextFloat()*28f
                particleAge=0f
                if(listeningReveal<0f)listeningReveal=0f
                particleX=Design.WIDTH/2+(sin(t*.72f)*4f+sin(t*.40f+1.2f))+shake*2f
                particleY=Design.HEIGHT/2+PERSONA_RADIUS+44f-PERSONA_RADIUS*1.925f
            }
        } else resetGaze()
        val condense=smooth((elapsed/PERSONA_BODY_SECONDS-.08f)/.82f)
        val material=smooth(p/.55f);val r=lerp(FULL_WATCH_OUTLINE_RADIUS,PERSONA_RADIUS,condense)
        val awaken=smooth((p-.85f)/.15f)
        // Fixed ground; flight is parabolic and squash is anchored to the contact point.
        val groundY=Design.HEIGHT/2+PERSONA_RADIUS+44f
        val bounceOn=smooth(settled/.55f)
        val phase=bouncePhase
        val height=bounceHeight*4f*phase*(1f-phase)
        val contact=min(phase,1f-phase)
        val impact=exp(-(contact/.065f).pow(2))*bounceOn
        val stretch=sin(phase*PI.toFloat())*.02f*bounceOn
        val thinkingScale=1f+.018f*sin(t*2.6f)*thinking
        var bodyScaleX=lerp(1f+.065f*impact-stretch*.5f,thinkingScale,thinkingCenter)
        var bodyScaleY=lerp(1f-.075f*impact+stretch,thinkingScale,thinkingCenter)
        val driftX=(sin(t*.72f)*4f+sin(t*.40f+1.2f))*awaken
        var cx=Design.WIDTH/2+(driftX+shake*2f)*(1f-thinkingCenter)+cameraYaw*10f
        val landedY=groundY-r*bodyScaleY-height+nod*1.5f*(height/bounceHeight)
        var cy=lerp(Design.HEIGHT/2,landedY,bounceOn*(1f-thinkingCenter))
        if(returning){
            val restore=1f-(elapsed/returnFrom).coerceIn(0f,1f)
            cx=lerp(lastCx,Design.WIDTH/2,restore);cy=lerp(lastCy,Design.HEIGHT/2,restore)
            bodyScaleX=lerp(lastScaleX,1f,restore);bodyScaleY=lerp(lastScaleY,1f,restore)
        }else{lastCx=cx;lastCy=cy;lastScaleX=bodyScaleX;lastScaleY=bodyScaleY}
        c.save();c.scale(s.scale,s.scale)
        path.reset();path.addRoundRect((2-s.originX)/s.scale,(2-s.originY)/s.scale,
            (s.widthPx-2-s.originX)/s.scale,(s.heightPx-2-s.originY)/s.scale,
            SafeZone.CORNER_RADIUS_PX/s.scale,SafeZone.CORNER_RADIUS_PX/s.scale,Path.Direction.CW);c.clipPath(path)
        // Slow ambient light and faint twinkles enliven the existing clock star field.
        val atmosphere=smooth((p-.65f)/.35f)
        glow(c,80f+sin(t*.19f)*24f,170f+cos(t*.23f)*30f,170f,230f,mint(255),(.018f+.009f*sin(t*.7f))*atmosphere)
        glow(c,335f+sin(t*.21f)*18f,320f+cos(t*.17f)*25f,160f,210f,mint(255),(.016f+.008f*cos(t*.6f))*atmosphere)
        repeat(14){i ->
            val x=25f+(i*97.3f)%360f;val y=24f+(i*71.7f)%450f
            val twinkle=(.5f+.5f*sin(t*(.6f+(i%4)*.13f)+i*2.4f)).pow(3)
            glow(c,x,y,3.5f,3.5f,mint(255),(.05f+.24f*twinkle)*atmosphere)
        }
        // Harness's vertical listening backdrop: above the starfield, beneath the orb.
        val statusAlpha=max(listeningAlpha,thinkingLabel)
        if(statusAlpha>.002f){
            // Follow the camera smoothly: a left-positioned ball gets its label on the right.
            val labelSide=smooth((-cameraYaw-.15f)/.8f)
            val labelAlpha=statusAlpha*(1f-smooth(min(labelSide,1f-labelSide)/.32f))
            c.save();c.translate(lerp(44f,Design.WIDTH-44f,labelSide),Design.HEIGHT/2+30f);c.rotate(-90f)
            fill(Color.WHITE,.34f*labelAlpha);ink.typeface=listeningFont;ink.textSize=52f
            ink.setShadowLayer(24f,0f,0f,Color.argb((.30f*labelAlpha*255).toInt(),255,255,255))
            val isProgress=thinkingLabel>listeningAlpha
            val word=if(isProgress)progressLabel else "LISTENING";val spacing=-2f
            var x=-(ink.measureText(word)+spacing*(word.length-1))/2f
            val baseline=-(ink.ascent()+ink.descent())/2f
            for((i,ch) in word.withIndex()){
                val glyph=ch.toString()
                val reveal=if(isProgress)1f else smooth((listeningReveal-i*.045f)/.07f)
                ink.alpha=(255*.34f*labelAlpha*reveal).toInt()
                if(reveal>0f)c.drawText(glyph,x,baseline,ink)
                x+=ink.measureText(glyph)+spacing
            }
            c.restore()
        }
        // One anchored teal pool reacts to actual clearance, including the impact squash.
        val gap=max(0f,groundY-(cy+r*bodyScaleY))
        val lift=(gap/76f).coerceIn(0f,1f)
        val shadowX=Design.WIDTH/2+cameraYaw*8f
        val shadowAlpha=smooth((p-.45f)/.4f)
        glow(c,shadowX,groundY,lerp(PERSONA_RADIUS*.62f,PERSONA_RADIUS*1.14f,lift),24f,mint(255),lerp(.52f,.24f,lift)*shadowAlpha)
        // Leave the shared watch Backdrop visible through the transparent overlay.
        c.save();c.translate(cx,cy);c.scale(bodyScaleX,bodyScaleY)
        drawBody(c,r,material,p,ep,t,settled)
        c.restore()
        // Impact plume has its own world origin and lifetime, independent of head scale.
        // It disappears during ascent; nothing is emitted while the ball hangs in the air.
        if(particleAge<.72f && emotion!=PersonaEmotion.Thinking && thinking<.05f){
            repeat(36){i ->
                val delay=((i*17)%13)/130f
                val life=(particleAge-delay)/(.43f+((i*7)%11)/65f)
                if(life>0f && life<1f){
                    val x=particleX+sin(i*127.1f)*r*.40f+sin(i*2.399f)*life*24f
                    val y=particleY-life*(145f+((i*19)%46))
                    val opacity=smooth(life/.10f)*(1f-smooth((life-.16f)/.84f))*.8f
                    fill(mint(255),opacity)
                    c.drawCircle(x,y,(1.5f+((i*37)%17)/13f)*(1f-life*.35f),ink)
                }
            }
        }
        c.restore()
        if(running && isAttachedToWindow && windowVisibility==VISIBLE)postInvalidateDelayed(33L)
    }
    private fun drawBody(c:Canvas,r:Float,material:Float,p:Float,ep:Float,t:Float,settled:Float){
        c.save();c.rotate(tilt*.5f);layer(c,sphere,r,material);c.restore()
        // Fixed upper-left light source: highlights respond to orientation, not elapsed time.
        val reflection=-135f-gazeX*18f+gazeY*9f+tilt*.5f+shake*3f-cameraYaw*18f-cameraPitch*6f
        val reflectionAlpha=(.60f+.18f*abs(gazeX)+.10f*abs(nod))*smooth((p-.55f)/.45f)
        c.save();c.rotate(reflection);layer(c,rimLight,r,reflectionAlpha);c.restore()
        c.save();c.rotate(reflection+180f);layer(c,rimLight,r,reflectionAlpha*.22f);c.restore()
        c.save();path.reset();path.addCircle(0f,0f,r*.985f,Path.Direction.CW);c.clipPath(path)
        c.save();c.rotate(tilt+shake*2f-cameraYaw*6f)
        c.translate(cameraYaw*r*.14f,cameraPitch*r*.09f);c.scale(1f-abs(cameraYaw)*.095f,1f-abs(cameraPitch)*.06f)
        c.translate(gazeX*r*.09f+shake*r*.07f,gazeY*r*.065f+nod*r*.06f)
        layer(c,face,r,smooth((p-.28f)/.5f)*(1f-thinking))
        c.save();c.scale(1f,1f-thinking*.55f)
        drawEyes(c,ep,t,settled,r,1f-thinking);c.restore()
        c.restore()
        c.save();c.translate(cameraYaw*r*.10f,cameraPitch*r*.065f)
        c.scale(1f-abs(cameraYaw)*.09f,1f-abs(cameraPitch)*.06f);c.rotate(-cameraYaw*6f)
        drawThinking(c,r,t,thinking*smooth(ep));c.restore();c.restore()
        // Thinking: anchored light lobes trade energy while a faint halo expands outward.
        // This keeps the directional reflection rather than introducing a spinning rim.
        if(thinking>.002f){
            val pulse=.5f+.5f*sin(t*3.2f)
            rect.set(-r,-r,r,r)
            for(side in 0..1){
                val charge=if(side==0)pulse else 1f-pulse
                val sweep=24f+charge*42f
                val center=if(side==0)-135f else 45f
                for(pass in 0..2){
                    stroke(mint(255),rimWidths[pass],
                        thinking*(.3f+.7f*charge)*rimAlphas[pass])
                    c.drawArc(rect,center-sweep/2f,sweep,false,ink)
                }
            }
            val ripple=(t*.65f)%1f
            stroke(mint(255),1.2f,thinking*.13f*(1f-ripple).pow(2))
            c.drawCircle(0f,0f,r*(1.008f+.045f*ripple),ink)
        }
    }
    private fun drawEyes(c:Canvas,ep:Float,t:Float,settled:Float,r:Float,opacity:Float){
        if(opacity<=.001f)return
        val travel=((ep-.05f)/.95f).coerceIn(0f,1f)
        val gather=travel*travel/(.15f+.85f*travel);val solid=smooth((ep-.62f)/.38f)
        val blink=1f-smooth(settled/1.2f)*max(0f,cos(t*.65f+1.9f)).pow(48)
        val look=if(emotion==PersonaEmotion.Thinking)6f else 0f
        val eyeY=if(emotion==PersonaEmotion.Thinking)-8f else 0f
        if(ep<1f)for(dot in dots){
            val scatter=sin(gather*PI.toFloat())
            val xx=lerp(dot.x,dot.ex*r/96f,gather)+cos(dot.seed)*scatter*96f*.12f
            val yy=lerp(dot.y,dot.ey*r/96f,gather)+sin(dot.seed)*scatter*96f*.09f
            fill(rgb("e5fff5"),smooth(ep/.14f)*(1-solid)*opacity);c.drawCircle(xx,yy,1.35f*(1-.25f*solid),ink)
        }
        if(solid>0)for(side in -1..1 step 2){
            // A soft syllabic rhythm, with slightly offset eyes and smooth entry/exit.
            val syllable=(.5f+.5f*sin(t*11f+side*.35f))*(.65f+.35f*sin(t*3.7f).pow(2))
            val voiceHeight=1f+speaking*(syllable*.40f-.12f)
            val voiceWidth=1f-speaking*syllable*.06f
            c.save();c.translate(side*r*.25f+look,eyeY);c.scale((if(side==1)-1f else 1f)*r/96f*voiceWidth,blink*r/96f*voiceHeight)
            glow(c,0f,0f,12f,24f,mint(255),.17f*solid*opacity)
            path.reset();path.moveTo(pose[0],pose[1]);path.quadTo(pose[2],pose[3],pose[4],pose[5]);stroke(rgb("e5fff5"),pose[6]*EYE_WEIGHT,solid*opacity);c.drawPath(path,ink);c.restore()
        }
    }
    /** Reference motion: one luminous filled sheet twisting around its horizontal axis. */
    private fun drawThinking(c:Canvas,r:Float,t:Float,alpha:Float){
        if(alpha<.002f)return
        val morph=smooth(thinking)
        val tumble=ribbonPhase
        val lift=(sin(t*1.35f)*.17f+sin(t*2.17f+.8f)*.065f)*morph
        val width=lerp(.63f,.975f,morph)
        val fullness=.44f+.065f*sin(t*2.3f)
        val twist=2.15f+.35f*sin(t*.9f)
        val wave=t*2.1f
        // Angle and projection are shared by all 13 vertices in each column.
        for(column in 0..40){
            val u=ribbonU[column];val edge=ribbonEdge[column]
            val angle=tumble+(u*twist+.25f*sin(wave+u*3f))*morph
            val sa=sin(angle);val ca=cos(angle)
            val halfHeight=r*fullness*edge
            val center=(lift+sin(u*3f+tumble)*.09f*morph)*r*edge
            for(row in 0..12){
                val v=ribbonV[row];val vertex=row*41+column;val index=vertex*2
                val depth=v*halfHeight*sa
                val shade=(225f+30f*(ca*shadeCos[row]-sa*shadeSin[row])).toInt()
                ribbonColors[vertex]=Color.rgb(shade,shade,shade)
                ribbonVertices[index]=u*r*width+depth*.08f*morph
                ribbonVertices[index+1]=center+v*halfHeight*ca
            }
        }
        c.save()
        path.reset();path.addCircle(0f,0f,r*.985f,Path.Direction.CW);c.clipPath(path)
        c.rotate(sin(t*.83f)*9f*morph)
        glow(c,0f,lift*r,r*.96f,r*.60f,mint(255),(.10f+.035f*sin(t*2.3f))*alpha)
        fill(Color.WHITE,alpha*.68f);ink.isFilterBitmap=true
        c.drawBitmapMesh(ribbonTexture,40,12,ribbonVertices,0,ribbonColors,0,ink)
        c.restore()
    }
    private fun text(c:Canvas,value:String,x:Float,y:Float,size:Float,color:Int,alpha:Float){fill(color,alpha);ink.typeface=mono;ink.textSize=size;ink.textAlign=Paint.Align.CENTER;c.drawText(value,x,y-(ink.ascent()+ink.descent())/2,ink)}
    private fun fill(color:Int,alpha:Float=1f){ink.reset();ink.isAntiAlias=true;ink.style=Paint.Style.FILL;ink.color=color;ink.alpha=(alpha.coerceIn(0f,1f)*255).toInt()}
    private fun stroke(color:Int,width:Float,alpha:Float){fill(color,alpha);ink.style=Paint.Style.STROKE;ink.strokeWidth=width;ink.strokeCap=Paint.Cap.ROUND}
    private fun line(c:Canvas,x:Float,y:Float,xx:Float,yy:Float,col:Int,w:Float,a:Float){stroke(col,w,a);c.drawLine(x,y,xx,yy,ink)}
    private fun glow(c:Canvas,x:Float,y:Float,rx:Float,ry:Float,col:Int,a:Float){if(a<=.001f)return;fill(Color.WHITE,a);ink.isFilterBitmap=true;ink.colorFilter=if(col==mintColor)glowTint else PorterDuffColorFilter(col,PorterDuff.Mode.SRC_IN);rect.set(x-rx,y-ry,x+rx,y+ry);c.drawBitmap(glowTexture,null,rect,ink)}
    private fun smooth(x:Float)=auroraSmooth(x)
    private fun lerp(a:Float,b:Float,t:Float)=a+(b-a)*t
    companion object {private fun rgb(hex:String)=Color.parseColor("#$hex")}
}
