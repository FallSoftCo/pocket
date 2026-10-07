package co.fallsoft.pocket

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
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
@Composable fun BilingualLabel(text:String,modifier:Modifier=Modifier,color:Color=LocalContentColor.current,fontSize:TextUnit=14.sp,fontWeight:FontWeight?=null,maxLines:Int=1,centered:Boolean=true,lineHeight:TextUnit=TextUnit.Unspecified,onActivate:(()->Unit)?=null){
    fun actionText(value:AnnotatedString):AnnotatedString=if(onActivate==null||value.isEmpty())value else buildAnnotatedString{
        append(value)
        // Native link hit testing covers the visible text, not reserved blank width.
        addLink(LinkAnnotation.Clickable("label-action",TextLinkStyles(),linkInteractionListener={onActivate()}),0,length)
    }
    LaunchedEffect(text,PocketImmersion.enabled,PocketImmersion.density){PocketImmersion.offerLabel(text)}
    val target=PocketImmersion.label(text)
    val key="label-rescue:$text"
    val supported=PocketImmersion.enabled&&PocketImmersion.supportEnabled&&target!=text
    val expanded=supported&&PocketImmersion.originalShown(key)
    if(!supported){
        Text(actionText(AnnotatedString(target)),modifier=modifier,color=color,fontSize=fontSize,fontWeight=fontWeight,maxLines=maxLines,lineHeight=lineHeight,
            overflow=TextOverflow.Ellipsis,textAlign=if(centered)androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start)
        return
    }
    var nativeVisible by remember(text,target){mutableStateOf(false)}
    val ready=immersionMotionReady()&&nativeVisible
    val transitionAnimated=immersionTransitionAnimated()
    var original by remember(text,target){mutableStateOf(false)}
    val deadline=remember(text,target){ImmersionCueDeadline()}
    val owner=remember(text,target){"control:"+java.util.UUID.randomUUID()}
    var from by remember(text,target){mutableStateOf(AnnotatedString(target))}
    var to by remember(text,target){mutableStateOf(AnnotatedString(target))}
    var cueActive by remember(text,target){mutableStateOf(false)}
    val progress=remember(text,target){Animatable(1f)}
    val shown=if(expanded||(original&&PocketImmersion.motionEnabled))text else target
    LaunchedEffect(text,target,ready,transitionAnimated,expanded,PocketSpeech.displayedOwner){
        if(!ready||expanded||PocketSpeech.displayedOwner!=null)return@LaunchedEffect
        // Separate queues replace the old synchronous screen-wide label clock.
        val cadence=immersionLabelCadence(text)
        delay(deadline.waitMs(cadence.initialMs))
        while(isActive){
            val next=!original
            runImmersionCue(owner,false,onBegin={
                from=AnnotatedString(if(original)text else target)
                to=AnnotatedString(if(next)text else target)
                progress.snapTo(if(transitionAnimated)0f else 1f)
                cueActive=transitionAnimated
            },onHandoff={original=next},onFinish={cueActive=false},animate={
                if(transitionAnimated)progress.animateTo(1f,tween(IMMERSION_HANDOFF_DURATION_MS.toInt(),easing=LinearEasing))
                else progress.snapTo(1f)
            },awaitHandoff={if(transitionAnimated)snapshotFlow{progress.value}.first{it>=IMMERSION_HANDOFF_AT_MS.toFloat()/IMMERSION_HANDOFF_DURATION_MS}},returningToTarget=!next)
            val hold=if(original)4000L else cadence.targetMs
            deadline.hold(hold)
            delay(hold)
        }
    }
    val rescue=if(supported)Modifier.semantics{customActions=listOf(CustomAccessibilityAction(if(expanded)"Chiudi spiegazione" else "Spiega in inglese"){PocketImmersion.revealOriginal(key);true})}else Modifier
    Box(modifier.then(rescue)){
        BlendImmersionText(actionText(if(cueActive)from else AnnotatedString(shown)),actionText(if(cueActive)to else AnnotatedString(shown)),{if(cueActive)progress.value else 1f},actionText(AnnotatedString(shown)),reserve=listOf(AnnotatedString(text),AnnotatedString(target)),color=color,fontSize=fontSize,
            lineHeight=lineHeight.takeIf{it!=TextUnit.Unspecified}?:LocalTextStyle.current.lineHeight.takeIf{it!=TextUnit.Unspecified}?:fontSize*1.3f,
            fontWeight=fontWeight,maxLines=maxLines,overflow=TextOverflow.Ellipsis,
            textAlign=if(centered)androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
            onVisibleRanges={_,readable->nativeVisible=readable},trackWholeTextVisibility=true)
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
        BilingualLabel("How much Italian?",fontSize=17.sp,centered=false)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("starter" to "Gradual","balanced" to "Balanced","strong" to "Intense").forEach{(value,label)->FilterChip(selected=PocketImmersion.density==value,onClick={PocketImmersion.setDensity(value)},label={BilingualLabel(label,fontSize=14.sp)},modifier=Modifier.weight(1f))}
        }
    }
}

internal data class ImmersionLabelCadence(val initialMs:Long,val targetMs:Long)
internal fun immersionLabelCadence(label:String):ImmersionLabelCadence{
    fun mix(value:Int):Int{var seed=value;seed=seed xor(seed ushr 16);seed*= -2048144789;seed=seed xor(seed ushr 13);seed*= -1028477387;return seed xor(seed ushr 16)}
    return ImmersionLabelCadence(6000L+Math.floorMod(mix(label.hashCode()),3001),10000L+Math.floorMod(mix(label.hashCode() xor 0x517cc1b7),5001))
}
