package ai.nolee.brandedlauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.sin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

internal data class PersonaTranscriptEntry(val speaker: String, val text: String, val turnId: Int = 0)

@Composable
internal fun PersonaTranscript(stage: Stage, entries: List<PersonaTranscriptEntry>, partial: String,
    listening: Boolean, busy: Boolean, enabled: Boolean, onListen: () -> Unit) {
    val scroll=rememberScrollState()
    var followLatest by remember { mutableStateOf(true) }
    val userScroll=remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if(source==NestedScrollSource.UserInput && available.y!=0f)followLatest=false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(entries.lastOrNull()?.turnId){ followLatest=true }
    LaunchedEffect(scroll,followLatest){
        if(followLatest)snapshotFlow { scroll.maxValue }.collectLatest { bottom ->
            if(bottom>scroll.value)scroll.animateScrollTo(bottom,tween(180,easing=FastOutSlowInEasing))
        }
    }
    val body=TextStyle(fontFamily=Spline,fontSize=stage.sp(20f),lineHeight=stage.sp(28f),color=Palette.Ink)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.60f))) {
        Column(Modifier.fillMaxSize().padding(start=stage.dp(39f),end=stage.dp(39f),top=stage.dp(64f),bottom=stage.dp(24f))) {
            BasicText("TRANSCRIPT",style=body.copy(fontSize=stage.sp(18f),lineHeight=stage.sp(23f)))
            Spacer(Modifier.height(stage.dp(24f)))
            Column(modifier=Modifier.weight(1f).nestedScroll(userScroll).verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(stage.dp(18f))){
                if(entries.isEmpty() && partial.isBlank()){
                    BasicText(if(listening) "Listening…\nYour words will appear here." else "No speech captured yet.",
                        style=body.copy(color=Palette.Sub))
                }else {
                    entries.forEachIndexed { index,entry -> key(entry.turnId,entry.speaker,index) {
                        Column {
                            BasicText(entry.speaker,style=body.copy(color=Palette.Mint))
                            Spacer(Modifier.height(stage.dp(5f)))
                            if(entry.speaker=="NOLEE" && index==entries.lastIndex) {
                                // Existing text is already history when this row mounts (including reopening).
                                // Animate only new streamed characters, never replay the saved answer.
                                var shown by remember { mutableStateOf(entry.text) }
                                LaunchedEffect(entry.text) {
                                    if(!entry.text.startsWith(shown))shown=""
                                    while(shown.length<entry.text.length) {
                                        shown=entry.text.take(entry.text.offsetByCodePoints(shown.length,1))
                                        delay(30)
                                    }
                                }
                                BasicText(shown,style=body)
                            } else BasicText(entry.text,style=body)
                        }
                    } }
                    // Do not append/remove a follow-up listening row below a revealing answer.
                    if(partial.isNotBlank() && (entries.isEmpty() || partial!="Listening…")) {
                        Column {
                            BasicText("STATUS",style=body.copy(color=Palette.Mint))
                            Spacer(Modifier.height(stage.dp(5f)))
                            BasicText(partial,style=body.copy(color=Palette.Sub))
                        }
                    }
                }
            }
            Spacer(Modifier.height(stage.dp(12f)))
            Box(Modifier.width(stage.dp(200f)).height(stage.dp(48f)).align(Alignment.CenterHorizontally)
                .drawBehind {
                    // Leave room for antialiasing; clip only the touch ripple, not the outline.
                    val width=1.5f
                    val inset=width/2f+1f
                    drawRoundRect(Color.White,topLeft=Offset(inset,inset),
                        size=Size(size.width-2f*inset,size.height-2f*inset),
                        cornerRadius=CornerRadius((size.height-2f*inset)/2f),style=Stroke(width))
                }
                .clip(RoundedCornerShape(stage.dp(24f)))
                .clickable(enabled=enabled && !listening,role=Role.Button,onClick=onListen),
                contentAlignment=Alignment.Center) {
                if(listening) ListeningWaveform(stage)
                else BasicText(if(busy) "Interrupt" else "Ask",
                    style=body.copy(fontSize=stage.sp(16f),color=Color.White))
            }
        }
    }
}

@Composable
private fun ListeningWaveform(stage: Stage) {
    val transition=rememberInfiniteTransition(label="listeningWaveform")
    val phase by transition.animateFloat(0f, (Math.PI*2).toFloat(),
        infiniteRepeatable(tween(1200,easing=LinearEasing)),label="wavePhase")
    Canvas(Modifier.size(stage.dp(76f),stage.dp(24f))
        .semantics { contentDescription="Listening" }) {
        repeat(9) { index ->
            val pulse=(sin(phase+index*.8f)+1f)/2f
            val height=size.height*(.2f+.8f*pulse)
            val x=size.width*(index+.5f)/9f
            drawLine(Color.White,Offset(x,(size.height-height)/2f),
                Offset(x,(size.height+height)/2f),strokeWidth=stage.px(2f),cap=StrokeCap.Round)
        }
    }
}
