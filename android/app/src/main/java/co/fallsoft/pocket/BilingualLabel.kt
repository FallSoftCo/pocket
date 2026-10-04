package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

/** Ordinary action taps stay actions. English help lives at the point of need. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BilingualLabel(text:String,modifier:Modifier=Modifier,color:Color=LocalContentColor.current,fontSize:TextUnit=14.sp,fontWeight:FontWeight?=null,maxLines:Int=1,centered:Boolean=true){
    LaunchedEffect(text,PocketImmersion.enabled,PocketImmersion.density){PocketImmersion.offerLabel(text)}
    val target=PocketImmersion.label(text)
    var help by remember(text){mutableStateOf(false)}
    val supported=PocketImmersion.enabled&&PocketImmersion.supportEnabled&&target!=text
    val size=if(PocketImmersion.enabled)fontSize.value.coerceAtLeast(14f).sp else fontSize
    val label:@Composable ()->Unit={Column(horizontalAlignment=if(centered)Alignment.CenterHorizontally else Alignment.Start,verticalArrangement=Arrangement.Center){Text(target,color=color,fontSize=size,fontWeight=fontWeight,maxLines=maxLines,overflow=TextOverflow.Ellipsis)}}
    if(supported&&text.lowercase() !in setOf("send","send message","steer","queue","stop & send")){
        TooltipBox(positionProvider=TooltipDefaults.rememberPlainTooltipPositionProvider(),tooltip={PlainTooltip{Text(text,fontSize=18.sp,lineHeight=26.sp)}},state=rememberTooltipState(),modifier=modifier.semantics{customActions=listOf(CustomAccessibilityAction("Spiega in inglese"){help=true;true})}){label()}
    }else Box(modifier.then(if(supported)Modifier.semantics{customActions=listOf(CustomAccessibilityAction("Spiega in inglese"){help=true;true})}else Modifier)){label()}
    if(help)ImmersionPhraseHelp(target,text,"",{help=false})
}

/** Put on the focusable parent control, not its text child: F1 and accessibility rescue. */
@Composable fun Modifier.immersionRescue(english:String):Modifier {
    var help by remember(english){mutableStateOf(false)}
    val target=PocketImmersion.label(english)
    if(help)ImmersionPhraseHelp(target,english,"",{help=false})
    if(!PocketImmersion.enabled||!PocketImmersion.supportEnabled)return this
    return this.onPreviewKeyEvent{if(it.key==Key.F1&&it.type==KeyEventType.KeyDown){help=true;true}else false}
        .semantics{customActions=listOf(CustomAccessibilityAction("Spiega in inglese"){help=true;true})}
}

@Composable fun ImmersionPhraseHelp(target:String,source:String,note:String,onDismiss:()->Unit){
    AlertDialog(onDismissRequest=onDismiss,title={Text(target,fontSize=21.sp,lineHeight=29.sp)},text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)){Text(source,fontSize=18.sp,lineHeight=27.sp);if(note.isNotBlank())Text(note,fontSize=16.sp,lineHeight=24.sp,color=Muted)}},confirmButton={TextButton(onDismiss,modifier=Modifier.heightIn(min=48.dp)){Text("Continua",fontSize=16.sp)}})
}

@Composable fun ImmersionDensityControl(){
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Quanto italiano?",fontSize=17.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("starter" to "Graduale","balanced" to "Bilanciato","strong" to "Intenso").forEach{(value,label)->FilterChip(selected=PocketImmersion.density==value,onClick={PocketImmersion.setDensity(value)},label={Text(label,fontSize=14.sp)},modifier=Modifier.weight(1f))}
        }
    }
}
