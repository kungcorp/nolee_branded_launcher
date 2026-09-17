package ai.nolee.customlauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

internal data class PersonaTranscriptEntry(val speaker: String, val text: String)

@Composable
internal fun PersonaTranscript(stage: Stage, entries: List<PersonaTranscriptEntry>, partial: String,
    listening: Boolean, onClose: () -> Unit) {
    val scroll=rememberLazyListState()
    LaunchedEffect(entries.size,entries.lastOrNull()?.text,partial){
        val count=entries.size+if(partial.isNotBlank())1 else 0
        if(count>0)scroll.animateScrollToItem(count-1)
    }
    val body=TextStyle(fontFamily=Spline,fontSize=stage.sp(16f),lineHeight=stage.sp(23f),color=Palette.Ink)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.60f))
        .pointerInput(onClose){detectTapGestures { onClose() }}) {
        Column(Modifier.fillMaxSize().padding(horizontal=stage.dp(39f),vertical=stage.dp(64f))) {
            BasicText("TRANSCRIPT",style=body.copy(fontSize=stage.sp(18f)))
            Spacer(Modifier.height(stage.dp(24f)))
            if(entries.isEmpty() && partial.isBlank()){
                BasicText(if(listening) "Listening…\nYour words will appear here." else "No speech captured yet.",
                    style=body.copy(color=Palette.Sub))
            }else LazyColumn(state=scroll,modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(stage.dp(18f))){
                items(entries){entry ->
                    Column {
                        BasicText(entry.speaker,style=body.copy(fontSize=stage.sp(11f),color=Palette.Mint))
                        Spacer(Modifier.height(stage.dp(5f)))
                        BasicText(entry.text,style=body)
                    }
                }
                if(partial.isNotBlank())item {
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
