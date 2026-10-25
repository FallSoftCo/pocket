package co.fallsoft.pocket

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
@Composable fun BilingualLabel(text:String,modifier:Modifier=Modifier,color:Color=LocalContentColor.current,fontSize:TextUnit=14.sp,fontWeight:FontWeight?=null,maxLines:Int=1,centered:Boolean=true,lineHeight:TextUnit=TextUnit.Unspecified,onActivate:(()->Unit)?=null,overflow:TextOverflow=TextOverflow.Ellipsis,targetOverride:String?=null,scaffoldOverride:ImmersionSpan?=null){
    fun actionText(value:AnnotatedString):AnnotatedString=if(onActivate==null||value.isEmpty())value else buildAnnotatedString{
        append(value)
        // Native link hit testing covers the visible text, not reserved blank width.
        addLink(LinkAnnotation.Clickable("label-action",TextLinkStyles(),linkInteractionListener={onActivate()}),0,length)
    }
    LaunchedEffect(text,PocketImmersion.enabled,PocketImmersion.planVersion,PocketImmersion.density,targetOverride){if(targetOverride==null)PocketImmersion.offerLabel(text)}
    val target=if(PocketImmersion.enabled&&targetOverride!=null)targetOverride else PocketImmersion.label(text)
    val plan=scaffoldOverride?.takeIf{it.source==text&&it.target==target&&alignedTargetValid(it)}
        ?.let{ImmersionPresentation(target,text,true,listOf(it))}?:PocketImmersion.labelPresentation(text,target)
    val support=plan?.let{immersionCyclePlan(it,it.spans.indices.toSet()).text}?:target
    fun form(value:String)=plan?.let{immersionGrammarPlanForm(it,value==support)}?:AnnotatedString(value)
    val key="label-rescue:$text"
    val supported=PocketImmersion.enabled&&target!=text
    val expanded=supported&&PocketImmersion.supportEnabled&&PocketImmersion.originalShown(key)
    if(!supported){
        Text(actionText(AnnotatedString(target)),modifier=modifier,color=color,fontSize=fontSize,fontWeight=fontWeight,maxLines=maxLines,lineHeight=lineHeight,
            overflow=overflow,textAlign=if(centered)androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start)
        return
    }
    val readingWave=LocalImmersionReadingWave.current
    if(readingWave!=null&&plan!=null&&support!=target){
        val waveAllowed=LocalImmersionFlip.current!=null||(!expanded&&PocketImmersion.motionEnabled&&immersionTransitionAnimated())
        val current=if(expanded&&LocalImmersionFlip.current==null)support else target
        val rescue=Modifier.semantics{customActions=listOf(CustomAccessibilityAction(if(expanded)"Chiudi spiegazione" else "Spiega in inglese"){PocketImmersion.revealOriginal(key);true})}
        SharedInlineImmersionText(actionText(form(current)),actionText(form(if(waveAllowed)support else current)),{1f},
            modifier=modifier.then(rescue),color=color,fontSize=fontSize,fontWeight=fontWeight,maxLines=maxLines,
            lineHeight=lineHeight.takeIf{it!=TextUnit.Unspecified}?:LocalTextStyle.current.lineHeight.takeIf{it!=TextUnit.Unspecified}?:fontSize*1.3f,
            overflow=overflow,textAlign=if(centered)androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
            snapshot=actionText(form(current)),reserve=listOf(form(target),form(support)),
            readingWave=if(waveAllowed)readingWave else null,trackWholeTextVisibility=true,phraseTaps=false)
        return
    }
    var nativeVisible by remember(text,target,plan){mutableStateOf(false)}
    val ready=immersionMotionReady()&&nativeVisible&&plan!=null&&support!=target
    val transitionAnimated=immersionTransitionAnimated()
    var original by remember(text,target,plan){mutableStateOf(false)}
    // One spatial wave for visible controls. Paragraph cues keep their separate
    // attention queue; adding controls cannot postpone another label indefinitely.
    val density=LocalDensity.current
    val configuration=LocalConfiguration.current
    val screenHeight=with(density){configuration.screenHeightDp.dp.toPx()}.coerceAtLeast(1f)
    val screenWidth=with(density){configuration.screenWidthDp.dp.toPx()}.coerceAtLeast(1f)
    val rtl=LocalLayoutDirection.current==LayoutDirection.Rtl
    var waveOffset by remember{mutableLongStateOf(0L)}
    var from by remember(text,target,plan){mutableStateOf(AnnotatedString(target))}
    var to by remember(text,target,plan){mutableStateOf(AnnotatedString(target))}
    var cueActive by remember(text,target,plan){mutableStateOf(false)}
    val progress=remember(text,target,plan){Animatable(1f)}
    val shown=if(expanded||(original&&PocketImmersion.motionEnabled))support else target
    LaunchedEffect(text,target,plan,ready,transitionAnimated,expanded,waveOffset){
        if(!ready||expanded)return@LaunchedEffect
        try{
            while(isActive){
                val phase=immersionControlPhase(ImmersionControlWave.elapsedMs()-waveOffset)
                if(phase.original!=original){
                    val next=phase.original
                    from=form(if(original)support else target)
                    to=form(if(next)support else target)
                    progress.snapTo(if(transitionAnimated)0f else 1f)
                    cueActive=transitionAnimated
                    if(transitionAnimated)coroutineScope{
                        launch{progress.animateTo(1f,tween(IMMERSION_HANDOFF_DURATION_MS.toInt(),easing=LinearEasing))}
                        snapshotFlow{progress.value}.first{it>=IMMERSION_HANDOFF_AT_MS.toFloat()/IMMERSION_HANDOFF_DURATION_MS}
                        original=next
                    }else original=next
                    cueActive=false
                }
                val current=immersionControlPhase(ImmersionControlWave.elapsedMs()-waveOffset)
                // Entering near a boundary can finish the wipe in the next phase.
                // Reconcile immediately instead of holding the stale language for
                // that entire next phase.
                if(current.original!=original)continue
                delay(current.remainingMs.coerceAtLeast(1L))
            }
        }finally{cueActive=false}
    }
    val rescue=if(supported&&plan!=null&&support!=target)Modifier.semantics{customActions=listOf(CustomAccessibilityAction(if(expanded)"Chiudi spiegazione" else "Spiega in inglese"){PocketImmersion.revealOriginal(key);true})}else Modifier
    Box(modifier.then(rescue).onGloballyPositioned{coordinates->
        val position=coordinates.positionInWindow()
        waveOffset=immersionControlWaveOffset(position.y/screenHeight,position.x/screenWidth,rtl)
    }){
        SharedInlineImmersionText(actionText(if(cueActive)from else form(shown)),actionText(if(cueActive)to else form(shown)),{if(cueActive)progress.value else 1f},snapshot=actionText(form(shown)),reserve=listOf(form(support),form(target)),color=color,fontSize=fontSize,
            lineHeight=lineHeight.takeIf{it!=TextUnit.Unspecified}?:LocalTextStyle.current.lineHeight.takeIf{it!=TextUnit.Unspecified}?:fontSize*1.3f,
            fontWeight=fontWeight,maxLines=maxLines,overflow=overflow,
            textAlign=if(centered)androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
            onVisibleRanges={_,readable->nativeVisible=readable},trackWholeTextVisibility=true,phraseTaps=false)
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

/** Authored interface prose uses the same bounded language layer as controls.
 * Typography remains native; executable strings and identifiers use literal Text.
 */
@Composable fun InterfaceText(
    text:String,modifier:Modifier=Modifier,color:Color=Color.Unspecified,
    fontSize:TextUnit=TextUnit.Unspecified,fontWeight:FontWeight?=null,
    maxLines:Int=Int.MAX_VALUE,lineHeight:TextUnit=TextUnit.Unspecified,
    overflow:TextOverflow=TextOverflow.Clip,letterSpacing:TextUnit=TextUnit.Unspecified,
    style:androidx.compose.ui.text.TextStyle=LocalTextStyle.current,targetOverride:String?=null,scaffoldOverride:ImmersionSpan?=null
){
    val resolved=LocalTextStyle.current.merge(style).merge(androidx.compose.ui.text.TextStyle(letterSpacing=letterSpacing))
    CompositionLocalProvider(LocalTextStyle provides resolved){
        BilingualLabel(text,modifier=modifier,
            color=if(color!=Color.Unspecified)color else resolved.color.takeIf{it!=Color.Unspecified}?:LocalContentColor.current,
            fontSize=fontSize.takeIf{it!=TextUnit.Unspecified}?:resolved.fontSize.takeIf{it!=TextUnit.Unspecified}?:14.sp,
            fontWeight=fontWeight?:resolved.fontWeight,maxLines=maxLines,centered=false,overflow=overflow,targetOverride=targetOverride,scaffoldOverride=scaffoldOverride,
            lineHeight=lineHeight.takeIf{it!=TextUnit.Unspecified}?:resolved.lineHeight)
    }
}
