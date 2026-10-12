package co.fallsoft.pocket

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

/** Candidate shared native paragraph. Not enabled in production until links,
 * accessibility, multilingual shaping and frame evidence are qualified. */
@Composable internal fun SharedInlineImmersionText(
    from:AnnotatedString,to:AnnotatedString,progress:()->Float,
    modifier:Modifier=Modifier,fontSize:TextUnit=17.sp,lineHeight:TextUnit=26.sp,
    color:Color=Paper,fontWeight:FontWeight?=null,
    onTextLayout:(TextLayoutResult)->Unit={},
    onFragmentLayout:(TextLayoutResult)->Unit={}
){
    val runs=remember(from,to){immersionSharedRuns(listOf(from,to))}
    if(runs==null){
        // Invalid alignment is not evidence of shared geometry. Preserve normal
        // text rather than invent a phrase/word correspondence.
        BlendImmersionText(from,to,progress,to,modifier,fontSize,lineHeight,color,fontWeight)
        return
    }
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val uriHandler=LocalUriHandler.current
    val style=LocalTextStyle.current.merge(TextStyle(fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight))
    val incoming by remember(progress){derivedStateOf{immersionInkPhase(progress()).incoming}}
    BoxWithConstraints(modifier){
        val available=constraints.maxWidth.coerceAtLeast(1)
        val content=mutableMapOf<String,InlineTextContent>()
        val paragraph=buildAnnotatedString{
            runs.forEachIndexed{index,run->
                if(run.id==null){append(run.forms[if(incoming)1 else 0]);return@forEachIndexed}
                // Split at natural token boundaries without claiming translation
                // word alignment. A long phrase is not an unbreakable giant box.
                val chunks=run.forms.map{form->immersionAnnotatedChunks(form,available){value->
                    measurer.measure(immersionDrawText(value),style=style,softWrap=false).size.width
                }}
                repeat(chunks.maxOf{it.size}){part->
                    val forms=chunks.map{it.getOrElse(part){AnnotatedString("")}}
                    val natural=forms.map{measurer.measure(immersionDrawText(it),style=style,softWrap=false)}
                    val width=natural.maxOf{it.size.width}.coerceAtLeast(1).coerceAtMost(available)
                    // Native paragraph shaping handles an oversized token. Reserve
                    // its full wrapped height in every form instead of clipping
                    // a one-line child or splitting Unicode graphemes ourselves.
                    val measured=forms.map{measurer.measure(immersionDrawText(it),style=style,
                        constraints=Constraints(maxWidth=width))}
                    val height=measured.maxOf{it.size.height}.coerceAtLeast(1)
                    val key="shared-$index-$part"
                    content[key]=InlineTextContent(
                        Placeholder(with(density){width.toSp()},with(density){height.toSp()},PlaceholderVerticalAlign.TextCenter)
                    ){
                        BlendImmersionText(forms[0],forms[1],progress,forms[if(incoming)1 else 0],
                            modifier=Modifier.clearAndSetSemantics{},
                            fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,
                            onNativeTextLayout=onFragmentLayout,
                            maxLines=Int.MAX_VALUE,overflow=TextOverflow.Clip,fillWidth=true,reserve=forms)
                    }
                    val start=length
                    appendInlineContent(key,forms[if(incoming)1 else 0].text.ifEmpty{"\u200b"})
                    addStringAnnotation("immersion-reserve",run.id,start,length)
                }
            }
        }
        val visible=if(incoming)to else from
        val phraseRanges=visible.getStringAnnotations("immersion-reserve",0,visible.length)
        val actions=visible.getLinkAnnotations(0,visible.length).filter{link->
            phraseRanges.any{it.start<=link.start&&it.end>=link.end}
        }.map{range->
            CustomAccessibilityAction(visible.text.substring(range.start,range.end)){
                val link=range.item
                val listener=link.linkInteractionListener
                if(listener!=null){listener.onClick(link);true}
                else if(link is LinkAnnotation.Url){
                    try{uriHandler.openUri(link.url);true}catch(_:IllegalArgumentException){false}
                }else false
            }
        }
        Text(paragraph,inlineContent=content,style=style,onTextLayout=onTextLayout,
            modifier=Modifier.semantics{customActions=actions})
    }
}

internal fun immersionAnnotatedChunks(
    form:AnnotatedString,maxWidth:Int,width:(AnnotatedString)->Int
):List<AnnotatedString>{
    if(form.isEmpty())return emptyList()
    val result=mutableListOf<AnnotatedString>()
    var start=0;var end=0
    for(token in Regex("\\S+\\s*|\\s+").findAll(form.text)){
        if(end>start&&width(form.subSequence(start,token.range.last+1))>maxWidth){
            result+=form.subSequence(start,end);start=end
        }
        end=token.range.last+1
    }
    if(end>start)result+=form.subSequence(start,end)
    return result
}
