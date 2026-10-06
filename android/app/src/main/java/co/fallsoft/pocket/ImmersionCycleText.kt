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
    var from by remember(plan.source){mutableStateOf(content)}
    var to by remember(plan.source){mutableStateOf(content)}
    val progress=remember(plan.source){Animatable(1f)}
    LaunchedEffect(content.text,ready,PocketSpeech.displayedOwner){
        if(content.text!=to.text){
            from=to;to=content
            if(ready&&PocketSpeech.displayedOwner==null){progress.snapTo(0f);progress.animateTo(1f,tween(360))}else progress.snapTo(1f)
        }else if(!ready||PocketSpeech.displayedOwner!=null)progress.snapTo(1f)
    }
    Box(Modifier.fillMaxWidth()){
        val marks=content.getStringAnnotations("immersion-reserve",0,content.length)
        fun form(original:Boolean):AnnotatedString?=immersionAnnotatedParagraph(content,marks.mapNotNull{range->
            val span=plan.spans.firstOrNull{range.item=="${it.start}:${it.end}"}?:return@mapNotNull null
            val parsed=MarkdownContent.preview(if(original)span.source else span.target)
            val replacement=buildAnnotatedString{
                append(parsed.text)
                parsed.spans.forEach{mark->when(mark.kind){
                    "bold"->addStyle(SpanStyle(fontWeight=FontWeight.Bold),mark.start,mark.end)
                    "italic"->addStyle(SpanStyle(fontStyle=FontStyle.Italic),mark.start,mark.end)
                    "code"->addStyle(SpanStyle(fontFamily=FontFamily.Monospace),mark.start,mark.end)
                }}
            }
            Triple(range.start,range.end,replacement)
        })
        val forms=listOf(content,form(true)?:content,form(false)?:content)
        BlendImmersionText(from,to,{progress.value},content,modifier=Modifier,fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,maxLines=maxLines,overflow=overflow,fillWidth=true,reserve=forms)
    }
}

/** Replacements retain surrounding rich styles; internal styles come from their own exact Markdown. */
internal fun immersionAnnotatedParagraph(content:AnnotatedString,replacements:List<Triple<Int,Int,AnnotatedString>>):AnnotatedString? {
    var cursor=0
    return buildAnnotatedString{
        replacements.sortedBy{it.first}.forEach{(start,end,replacement)->
            if(start<cursor||end<start||end>content.length)return null
            append(content.subSequence(cursor,start))
            val at=length;append(replacement)
            val inherited=content.subSequence(start,end)
            inherited.spanStyles.filter{it.start==0&&it.end==inherited.length}.forEach{addStyle(it.item,at,length)}
            inherited.getLinkAnnotations(0,inherited.length).filter{it.start==0&&it.end==inherited.length}.forEach{annotation->when(val link=annotation.item){is LinkAnnotation.Url->addLink(link,at,length);is LinkAnnotation.Clickable->addLink(link,at,length)}}
            cursor=end
        }
        append(content.subSequence(cursor,content.length))
    }
}
