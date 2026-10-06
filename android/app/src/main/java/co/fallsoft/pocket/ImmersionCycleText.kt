package co.fallsoft.pocket

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal class ImmersionCycleRender(val plan:ImmersionPresentation,val alpha:(ImmersionSpan)->Float)

/** Readable stationary holds; auto motion pauses for interaction, keyboard and accessibility. */
@Composable internal fun rememberImmersionCycle(plan:ImmersionPresentation,readingKey:ImmersionReadingKey,selected:ImmersionSpan?,enabled:Boolean):ImmersionCycleRender {
    val ready=immersionMotionReady()
    var original by remember(readingKey){mutableStateOf(false)}
    val selectedIndex=plan.spans.indexOf(selected)
    val effective=(if(original)plan.spans.indices.toSet()else emptySet())+listOf(selectedIndex).filter{it>=0}
    val frozen=remember(readingKey,PocketSpeech.displayedOwner,PocketSpeech.displayedText){
        if(PocketSpeech.displayedOwner==null)null else immersionCapturedOriginals(plan,PocketSpeech.displayedText)
    }
    val rendered=immersionCyclePlan(plan,frozen?:effective)
    LaunchedEffect(readingKey,enabled,ready,frozen!=null,selectedIndex){
        if(!enabled||!ready||frozen!=null||selectedIndex>=0||plan.spans.isEmpty())return@LaunchedEffect
        val timing=immersionCycleTiming(readingKey.toString(),plan.source,plan.text)
        var first=true
        while(isActive){delay((if(original)timing.sourceMs else timing.targetMs)+(if(first)timing.staggerMs%6000L else 0L));first=false;original=!original}
    }
    return ImmersionCycleRender(rendered){1f}
}

/** Native paragraphs keep natural whitespace and baselines; reserve height, never word padding. */
@Composable internal fun ReservedImmersionText(content:AnnotatedString,plan:ImmersionPresentation,alpha:(ImmersionSpan)->Float,fontSize:TextUnit,lineHeight:TextUnit,color:Color,fontWeight:FontWeight?,maxLines:Int,overflow:TextOverflow){
    val ready=immersionMotionReady()
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val style=LocalTextStyle.current.merge(TextStyle(fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight))
    var from by remember(plan.source){mutableStateOf(content)}
    var to by remember(plan.source){mutableStateOf(content)}
    val progress=remember(plan.source){Animatable(1f)}
    LaunchedEffect(content.text,ready){
        if(content.text!=to.text){
            from=to;to=content
            if(ready&&PocketSpeech.displayedOwner==null){progress.snapTo(0f);progress.animateTo(1f,tween(600))}else progress.snapTo(1f)
        }else if(!ready)progress.snapTo(1f)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()){
        val width=with(density){maxWidth.roundToPx()}.coerceAtLeast(1)
        val marks=content.getStringAnnotations("immersion-reserve",0,content.length)
        fun form(original:Boolean):String?=immersionParagraphForm(content.text,marks.mapNotNull{range->
            val span=plan.spans.firstOrNull{range.item=="${it.start}:${it.end}"}?:return@mapNotNull null
            Triple(range.start,range.end,MarkdownContent.preview(if(original)span.source else span.target).text)
        })
        // Natural line layout; the envelope is deliberately vertical, not max-width lexical slots.
        val forms=listOf(content,AnnotatedString(form(true)?:content.text),AnnotatedString(form(false)?:content.text))
        val height=forms.maxOf{measurer.measure(it,style=style,maxLines=maxLines,overflow=overflow,constraints=Constraints(maxWidth=width)).size.height}
        BlendImmersionText(from,to,{progress.value},content,modifier=Modifier.heightIn(min=with(density){height.toDp()}),fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,maxLines=maxLines,overflow=overflow,fillWidth=true)
    }
}
