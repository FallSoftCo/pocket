package co.fallsoft.pocket

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.text.AnnotatedString
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

/** Ordinary action taps stay actions; English rescue replaces the same label in place. */
@Composable fun BilingualLabel(text:String,modifier:Modifier=Modifier,color:Color=LocalContentColor.current,fontSize:TextUnit=14.sp,fontWeight:FontWeight?=null,maxLines:Int=1,centered:Boolean=true){
    LaunchedEffect(text,PocketImmersion.enabled,PocketImmersion.density){PocketImmersion.offerLabel(text)}
    val target=PocketImmersion.label(text)
    val key="label-rescue:$text"
    val supported=PocketImmersion.enabled&&PocketImmersion.supportEnabled&&target!=text
    val expanded=supported&&PocketImmersion.originalShown(key)
    if(!supported){
        Text(target,modifier=modifier,color=color,fontSize=fontSize,fontWeight=fontWeight,maxLines=maxLines,
            overflow=TextOverflow.Ellipsis,textAlign=if(centered)androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start)
        return
    }
    val ready=immersionMotionReady()
    var original by remember(text,target){mutableStateOf(false)}
    LaunchedEffect(text,target,supported,ready,expanded){
        if(!supported||!ready||expanded)return@LaunchedEffect
        while(isActive){
            val phase=immersionControlPhase(System.currentTimeMillis())
            original=phase.original
            delay(phase.remainingMs)
        }
    }
    val shown=if(expanded||supported&&original)text else target
    var from by remember(text,target){mutableStateOf(AnnotatedString(shown))}
    var to by remember(text,target){mutableStateOf(AnnotatedString(shown))}
    val progress=remember(text,target){Animatable(1f)}
    LaunchedEffect(shown,ready){
        if(shown!=to.text){
            from=to;to=AnnotatedString(shown)
            if(ready){progress.snapTo(0f);progress.animateTo(1f,tween(360))}else progress.snapTo(1f)
        }else if(!ready)progress.snapTo(1f)
    }
    val rescue=if(supported)Modifier.semantics{customActions=listOf(CustomAccessibilityAction(if(expanded)"Chiudi spiegazione" else "Spiega in inglese"){PocketImmersion.revealOriginal(key);true})}else Modifier
    Box(modifier.then(rescue)){
        BlendImmersionText(from,to,{progress.value},AnnotatedString(shown),reserve=listOf(AnnotatedString(text),AnnotatedString(target)),color=color,fontSize=fontSize,
            lineHeight=LocalTextStyle.current.lineHeight.takeIf{it!=TextUnit.Unspecified}?:fontSize*1.3f,
            fontWeight=fontWeight,maxLines=maxLines,overflow=TextOverflow.Ellipsis,
            textAlign=if(centered)androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start)
    }
}

/** Parent keyboard/accessibility assistance toggles the same inline label, without intercepting taps. */
@Composable fun Modifier.immersionRescue(english:String):Modifier {
    if(!PocketImmersion.enabled||!PocketImmersion.supportEnabled)return this
    val key="label-rescue:$english"
    return this.onPreviewKeyEvent{if(it.key==Key.F1&&it.type==KeyEventType.KeyDown){PocketImmersion.revealOriginal(key);true}else false}
        .semantics{customActions=listOf(CustomAccessibilityAction("Spiega in inglese"){PocketImmersion.revealOriginal(key);true})}
}

@Composable fun ImmersionDensityControl(){
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Quanto italiano?",fontSize=17.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("starter" to "Graduale","balanced" to "Bilanciato","strong" to "Intenso").forEach{(value,label)->FilterChip(selected=PocketImmersion.density==value,onClick={PocketImmersion.setDensity(value)},label={Text(label,fontSize=14.sp)},modifier=Modifier.weight(1f))}
        }
    }
}
