package co.fallsoft.pocket

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
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
    var nativeVisible by remember(text,target){mutableStateOf(false)}
    val ready=immersionMotionReady()&&nativeVisible
    var original by remember(text,target){mutableStateOf(false)}
    val owner=remember(text,target){"control:"+java.util.UUID.randomUUID()}
    var from by remember(text,target){mutableStateOf(AnnotatedString(target))}
    var to by remember(text,target){mutableStateOf(AnnotatedString(target))}
    var cueActive by remember(text,target){mutableStateOf(false)}
    val progress=remember(text,target){Animatable(1f)}
    val shown=if(expanded||original)text else target
    LaunchedEffect(text,target,ready,expanded,PocketSpeech.displayedOwner){
        if(!ready||expanded||PocketSpeech.displayedOwner!=null)return@LaunchedEffect
        // Separate queues replace the old synchronous screen-wide label clock.
        delay(18000L+Math.floorMod(text.hashCode(),6000))
        while(isActive){
            val next=!original
            runImmersionCue(owner,false,onBegin={
                from=AnnotatedString(if(original)text else target)
                to=AnnotatedString(if(next)text else target)
                progress.snapTo(0f)
                cueActive=true
            },onHandoff={original=next},onFinish={cueActive=false},animate={
                progress.animateTo(1f,tween(IMMERSION_HANDOFF_DURATION_MS.toInt(),easing=LinearEasing))
            },awaitHandoff={snapshotFlow{progress.value}.first{it>=IMMERSION_HANDOFF_AT_MS.toFloat()/IMMERSION_HANDOFF_DURATION_MS}})
            delay(if(original)6000L else 18000L)
        }
    }
    val rescue=if(supported)Modifier.semantics{customActions=listOf(CustomAccessibilityAction(if(expanded)"Chiudi spiegazione" else "Spiega in inglese"){PocketImmersion.revealOriginal(key);true})}else Modifier
    Box(modifier.then(rescue)){
        BlendImmersionText(if(cueActive)from else AnnotatedString(shown),if(cueActive)to else AnnotatedString(shown),{if(cueActive)progress.value else 1f},AnnotatedString(shown),reserve=listOf(AnnotatedString(text),AnnotatedString(target)),color=color,fontSize=fontSize,
            lineHeight=LocalTextStyle.current.lineHeight.takeIf{it!=TextUnit.Unspecified}?:fontSize*1.3f,
            fontWeight=fontWeight,maxLines=maxLines,overflow=TextOverflow.Ellipsis,
            textAlign=if(centered)androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
            onVisibleRanges={_,readable->nativeVisible=readable})
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
