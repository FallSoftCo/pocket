package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

/** The cycle supplies its single clock; this renderer never starts a second handoff. */
@Composable internal fun ReservedImmersionText(
    content:AnnotatedString,plan:ImmersionPresentation,alpha:(ImmersionSpan)->Float,
    fontSize:TextUnit,lineHeight:TextUnit,color:Color,fontWeight:FontWeight?,maxLines:Int,
    overflow:TextOverflow,cycle:ImmersionCycleRender
){
    fun alternate(base:AnnotatedString,next:ImmersionPresentation):AnnotatedString {
        val marks=base.getStringAnnotations("immersion-reserve",0,base.length)
        val replacements=marks.mapNotNull{range->
            val span=next.spans.firstOrNull{range.item=="${it.start}:${it.end}"}?:return@mapNotNull null
            val parsed=MarkdownContent.preview(span.target)
            val replacement=buildAnnotatedString{
                append(parsed.text)
                if(length>0)addStringAnnotation("immersion-reserve",range.item,0,length)
                parsed.spans.forEach{mark->when(mark.kind){
                    "bold"->addStyle(SpanStyle(fontWeight=FontWeight.Bold),mark.start,mark.end)
                    "italic"->addStyle(SpanStyle(fontStyle=FontStyle.Italic),mark.start,mark.end)
                    "code"->addStyle(SpanStyle(fontFamily=FontFamily.Monospace),mark.start,mark.end)
                }}
            }
            Triple(range.start,range.end,replacement)
        }
        return immersionAnnotatedParagraph(base,replacements)?:base
    }
    // Capture the pending phrase at cue start; actual text updates at the same
    // clock's handoff and supplies fresh link callbacks without restarting it.
    val pending=remember(plan.source,cycle.cueId,cycle.cueActive){
        content to (cycle.incomingPlan?.let{alternate(content,it)}?:content)
    }
    val from=if(cycle.cueActive)pending.first else content
    val to=if(cycle.cueActive)pending.second else content
    val forms=remember(content,plan){
        val original=immersionCyclePlan(plan,plan.spans.indices.toSet())
        listOf(content,alternate(content,original),alternate(content,plan))
    }
    val progress:()->Float=if(cycle.cueActive)cycle.cueProgress else ({1f})
    BlendImmersionText(from,to,progress,content,
        fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,
        maxLines=maxLines,overflow=overflow,fillWidth=true,reserve=forms)
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
