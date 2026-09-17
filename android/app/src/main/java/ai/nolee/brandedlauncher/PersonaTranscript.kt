package ai.nolee.brandedlauncher

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

internal data class PersonaTranscriptEntry(val speaker: String, val text: String, val turnId: Int = 0)

@Composable
internal fun PersonaTranscript(stage: Stage, entries: List<PersonaTranscriptEntry>, partial: String,
    listening: Boolean) {
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
        Column(Modifier.fillMaxSize().padding(horizontal=stage.dp(39f),vertical=stage.dp(64f))) {
            BasicText("TRANSCRIPT",style=body.copy(fontSize=stage.sp(18f),lineHeight=stage.sp(23f)))
            Spacer(Modifier.height(stage.dp(24f)))
            if(entries.isEmpty() && partial.isBlank()){
                BasicText(if(listening) "Listening…\nYour words will appear here." else "No speech captured yet.",
                    style=body.copy(color=Palette.Sub))
            }else Column(modifier=Modifier.weight(1f).nestedScroll(userScroll).verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(stage.dp(18f))){
                entries.forEachIndexed { index,entry -> key(entry.turnId,entry.speaker,index) {
                    Column {
                        BasicText(entry.speaker,style=body.copy(fontSize=stage.sp(11f),color=Palette.Mint))
                        Spacer(Modifier.height(stage.dp(5f)))
                        if(entry.speaker=="NOLEE" && index==entries.lastIndex) {
                            var shown by remember { mutableStateOf("") }
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
                        BasicText("STATUS",style=body.copy(fontSize=stage.sp(11f),color=Palette.Mint))
                        Spacer(Modifier.height(stage.dp(5f)))
                        BasicText(partial,style=body.copy(color=Palette.Sub))
                    }
                }
            }
        }
    }
}
