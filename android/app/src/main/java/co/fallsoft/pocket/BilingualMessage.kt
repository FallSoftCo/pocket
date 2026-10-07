package co.fallsoft.pocket

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*

private fun hybridAnnotated(content:MarkdownInline,ranges:List<ImmersionDisplayRange>,offset:Int,selected:ImmersionSpan?,help:Boolean,links:Boolean=true,onHelp:(ImmersionSpan)->Unit):AnnotatedString = buildAnnotatedString {
    append(content.text)
    content.spans.forEach { span -> when(span.kind){
        "bold"->addStyle(SpanStyle(fontWeight=FontWeight.Bold),span.start,span.end)
        "italic"->addStyle(SpanStyle(fontStyle=FontStyle.Italic),span.start,span.end)
        "code"->addStyle(SpanStyle(fontFamily=FontFamily.Monospace,background=Color(0xff292a30)),span.start,span.end)
        "link"->{val scheme=android.net.Uri.parse(span.destination).scheme?.lowercase();if(links&&scheme in listOf("https","http","mailto"))addLink(LinkAnnotation.Url(span.destination,TextLinkStyles(style=SpanStyle(color=Mint,textDecoration=TextDecoration.Underline))),span.start,span.end)}
    } }
    ranges.forEach { range ->
        val span=range.span
        val at=range.start-offset;val end=range.end-offset
        if(at>=0&&end>at&&end<=content.text.length&&span.target!=span.source){
            addStringAnnotation("immersion-reserve","${span.start}:${span.end}",at,end)
            if(help&&content.spans.none{it.kind=="link"&&it.start<end&&it.end>at})addLink(LinkAnnotation.Clickable("immersion:${span.start}:${span.end}",TextLinkStyles(style=SpanStyle()),linkInteractionListener={onHelp(span)}),at,end)
        }
    }
}

/** One native text flow. Help substitutes the selected phrase; it never adds another line. */
@Composable private fun ImmersionReading(content:AnnotatedString,selected:ImmersionSpan?,fontSize:TextUnit,lineHeight:TextUnit,color:Color=Paper,fontWeight:FontWeight?=null,maxLines:Int=Int.MAX_VALUE,overflow:TextOverflow=TextOverflow.Clip,cycle:ImmersionCycleRender?=null,plan:ImmersionPresentation?=null,onClose:()->Unit){
    if(cycle!=null&&plan!=null){Box(if(selected==null)Modifier.fillMaxWidth()else Modifier.fillMaxWidth().clickable(onClick=onClose)){ReservedImmersionText(content,plan,cycle.alpha,fontSize,lineHeight,color,fontWeight,maxLines,overflow,cycle)};return}
    Text(content,fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,maxLines=maxLines,overflow=overflow,modifier=if(selected==null)Modifier.fillMaxWidth()else Modifier.fillMaxWidth().clickable(onClick=onClose))
}

/** Context-aware target phrases remain inline at ordinary reading size. */
@Composable fun BilingualMessage(id:String,original:String,modifier:Modifier=Modifier,onDisplayedText:((String)->Unit)?=null){
    LaunchedEffect(id,original,PocketImmersion.enabled,PocketImmersion.density){PocketImmersion.offer(id,original)}
    if(!PocketImmersion.enabled){ReportDisplayedImmersion(id,original,onDisplayedText);Column(modifier){RichText(original)};return}
    val plan=PocketImmersion.presentation(id,original).takeIf{it.source==original}?:ImmersionPresentation(original,original,false)
    val readingKey=PocketImmersion.readingKey(id,plan)
    val selected=PocketImmersion.readingSelection(readingKey,plan)
    val originalOnly=PocketImmersion.supportEnabled&&PocketImmersion.originalShown(id)
    val cycle=rememberImmersionCycle(plan,readingKey,selected.takeIf{PocketImmersion.supportEnabled},PocketImmersion.supportEnabled&&!originalOnly)
    val shown=if(originalOnly)plan.source else cycle.plan.text
    ReportDisplayedImmersion(id,shown,onDisplayedText)
    val blocks=remember(shown){MarkdownContent.blocks(shown)}
    val ranges=remember(plan,shown,cycle.plan,originalOnly){if(originalOnly)emptyList()else MarkdownContent.replacementRanges(cycle.plan,shown,null).map{range->range.copy(span=plan.spans.first{it.start==range.span.start&&it.end==range.span.end})}}
    val offsets=remember(blocks){var offset=0;blocks.map{block->if(block.kind=="rule")-1 else {if(offset>0)offset++;offset+=block.prefix.length;val at=offset;offset+=block.content.text.length;at}}}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(9.dp)){
        blocks.forEachIndexed { index,block -> when(block.kind){
            "code"->Surface(color=Ink,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){if(block.language.isNotBlank())Text(block.language,color=Muted,fontSize=12.sp);Text(block.content.text,fontFamily=FontFamily.Monospace,fontSize=14.sp,lineHeight=21.sp,color=Paper,modifier=Modifier.horizontalScroll(rememberScrollState()))}}
            "rule"->HorizontalDivider(color=Line)
            else->{val content=hybridAnnotated(block.content,ranges,offsets[index],selected,!originalOnly&&PocketImmersion.supportEnabled){PocketImmersion.selectReading(readingKey,if(selected==it)null else it)};val size=if(block.kind=="heading")when(block.level){1->24.sp;2->21.sp;else->18.sp}else 17.sp
                MarkdownImageFlow(buildAnnotatedString{append(block.prefix);append(content)},block.content.images,block.prefix.length){part->
                    ImmersionReading(part,selected.takeIf{PocketImmersion.supportEnabled},fontSize=size,lineHeight=if(block.kind=="heading")size*1.3f else 26.sp,fontWeight=if(block.kind=="heading")FontWeight.SemiBold else FontWeight.Normal,cycle=cycle.takeIf{PocketImmersion.supportEnabled&&!originalOnly},plan=plan,onClose={PocketImmersion.selectReading(readingKey,null)})
                }}
        } }
    }
}

/** Dense/tappable cards leave phraseRescue=false, so their native actions retain every tap. */
@Composable fun ImmersionText(id:String,original:String,modifier:Modifier=Modifier,color:Color=Paper,fontSize:TextUnit=16.sp,lineHeight:TextUnit=24.sp,maxLines:Int=Int.MAX_VALUE,fontWeight:FontWeight?=null,kind:String="public display",rescue:Boolean=true,overflow:TextOverflow=TextOverflow.Ellipsis,phraseRescue:Boolean=rescue,links:Boolean=true,onDisplayedText:((String)->Unit)?=null){
    LaunchedEffect(id,original,PocketImmersion.enabled,PocketImmersion.density){PocketImmersion.offer(id,original,kind)}
    val plan=PocketImmersion.presentation(id,original).takeIf{it.source==original}?:ImmersionPresentation(original,original,false)
    val readingKey=PocketImmersion.readingKey(id,plan)
    val selected=PocketImmersion.readingSelection(readingKey,plan)
    val cycle=rememberImmersionCycle(plan,readingKey,selected.takeIf{phraseRescue&&PocketImmersion.supportEnabled},PocketImmersion.enabled&&PocketImmersion.supportEnabled)
    val shownText=cycle.plan.text
    ReportDisplayedImmersion(id,shownText,onDisplayedText)
    val inline=remember(shownText){MarkdownContent.preview(shownText)}
    val ranges=remember(plan,shownText,cycle.plan){MarkdownContent.replacementRanges(cycle.plan,shownText,null).map{range->range.copy(span=plan.spans.first{it.start==range.span.start&&it.end==range.span.end})}}
    val content=hybridAnnotated(inline,if(PocketImmersion.enabled)ranges else emptyList(),0,selected,phraseRescue&&PocketImmersion.supportEnabled,links){PocketImmersion.selectReading(readingKey,if(selected==it)null else it)}
    Column(modifier){
        ImmersionReading(content,selected.takeIf{phraseRescue&&PocketImmersion.supportEnabled},color=color,fontSize=fontSize,lineHeight=lineHeight,maxLines=maxLines,fontWeight=fontWeight,overflow=overflow,cycle=cycle.takeIf{PocketImmersion.enabled&&PocketImmersion.supportEnabled},plan=plan,onClose={PocketImmersion.selectReading(readingKey,null)})
    }
}

/** Report the one visible linguistic flow without restarting effects for recreated callbacks. */
@Composable private fun ReportDisplayedImmersion(id:String,text:String,callback:((String)->Unit)?){
    val latest by rememberUpdatedState(callback)
    LaunchedEffect(id,text,callback!=null){latest?.invoke(text)}
}
