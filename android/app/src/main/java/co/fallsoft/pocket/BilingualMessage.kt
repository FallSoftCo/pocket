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

// Function colors remain consistent across utterances, independently of lexical meaning.
private fun grammarColor(segment:ImmersionTargetSegment):Color = when(grammarCue(segment)) {
    "negation"->Color(0xffff8585)
    "past"->Color(0xffffc06b)
    "aspect"->Color(0xffffe079)
    "modality"->Color(0xffd4b0ff)
    "preposition","conjunction"->Color(0xff9de3ed)
    "determiner","pronoun"->Color(0xffb5e5b7)
    else->Paper
}
private fun hybridAnnotated(content:MarkdownInline,ranges:List<ImmersionDisplayRange>,offset:Int,selected:ImmersionSpan?,help:Boolean,onHelp:(ImmersionSpan)->Unit):AnnotatedString = buildAnnotatedString {
    append(content.text)
    content.spans.forEach { span -> when(span.kind){
        "bold"->addStyle(SpanStyle(fontWeight=FontWeight.Bold),span.start,span.end)
        "italic"->addStyle(SpanStyle(fontStyle=FontStyle.Italic),span.start,span.end)
        "code"->addStyle(SpanStyle(fontFamily=FontFamily.Monospace,background=Color(0xff292a30)),span.start,span.end)
        "link"->{val scheme=android.net.Uri.parse(span.destination).scheme?.lowercase();if(scheme in listOf("https","http","mailto"))addLink(LinkAnnotation.Url(span.destination,TextLinkStyles(style=SpanStyle(color=Mint,textDecoration=TextDecoration.Underline))),span.start,span.end)}
    } }
    ranges.forEach { range ->
        val span=range.span
        val rescued=span==selected
        val at=range.start-offset;val end=range.end-offset
        if(at>=0&&end>at&&end<=content.text.length&&span.target!=span.source){
            val visible=content.text.substring(at,end)
            val agreement=agreementSegments(span)
            if(!rescued&&visible==span.target&&alignedTargetValid(span))span.targetSegments.forEachIndexed { segmentIndex,segment ->
                if(segment.role !in listOf("separator","punctuation"))addStyle(SpanStyle(color=grammarColor(segment),fontWeight=FontWeight.Medium,textDecoration=if(segmentIndex in agreement)TextDecoration.Underline else null),at+segment.start,at+segment.end)
            }
            if(rescued)addStyle(SpanStyle(color=Paper,background=Color(0xff292a30)),at,end)
            if(help)addLink(LinkAnnotation.Clickable("immersion:${span.start}:${span.end}",TextLinkStyles(style=SpanStyle()),linkInteractionListener={onHelp(span)}),at,end)
        }
    }
}

/** One native text flow. Help substitutes the selected phrase; it never adds another line. */
@Composable private fun ImmersionReading(content:AnnotatedString,selected:ImmersionSpan?,fontSize:TextUnit,lineHeight:TextUnit,color:Color=Paper,fontWeight:FontWeight?=null,maxLines:Int=Int.MAX_VALUE,overflow:TextOverflow=TextOverflow.Clip,onClose:()->Unit){
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
    val shown=immersionReplacementText(plan,selected.takeIf{PocketImmersion.supportEnabled},originalOnly)
    ReportDisplayedImmersion(id,shown,onDisplayedText)
    val blocks=remember(shown){MarkdownContent.blocks(shown)}
    val ranges=remember(plan,shown,selected,originalOnly){if(originalOnly)emptyList()else MarkdownContent.replacementRanges(plan,shown,selected.takeIf{PocketImmersion.supportEnabled})}
    val offsets=remember(blocks){var offset=0;blocks.map{block->if(block.kind=="rule")-1 else {if(offset>0)offset++;offset+=block.prefix.length;val at=offset;offset+=block.content.text.length;at}}}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(9.dp)){
        blocks.forEachIndexed { index,block -> when(block.kind){
            "code"->Surface(color=Ink,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){if(block.language.isNotBlank())Text(block.language,color=Muted,fontSize=12.sp);Text(block.content.text,fontFamily=FontFamily.Monospace,fontSize=14.sp,lineHeight=21.sp,color=Paper,modifier=Modifier.horizontalScroll(rememberScrollState()))}}
            "rule"->HorizontalDivider(color=Line)
            else->{val content=hybridAnnotated(block.content,ranges,offsets[index],selected,!originalOnly&&PocketImmersion.supportEnabled){PocketImmersion.selectReading(readingKey,if(selected==it)null else it)};val size=if(block.kind=="heading")when(block.level){1->24.sp;2->21.sp;else->18.sp}else 17.sp
                MarkdownImageFlow(buildAnnotatedString{append(block.prefix);append(content)},block.content.images,block.prefix.length){part->
                    ImmersionReading(part,selected.takeIf{PocketImmersion.supportEnabled},fontSize=size,lineHeight=if(block.kind=="heading")size*1.3f else 26.sp,fontWeight=if(block.kind=="heading")FontWeight.SemiBold else FontWeight.Normal,onClose={PocketImmersion.selectReading(readingKey,null)})
                }}
        } }
    }
}

/** Dense/tappable cards leave phraseRescue=false, so their native actions retain every tap. */
@Composable fun ImmersionText(id:String,original:String,modifier:Modifier=Modifier,color:Color=Paper,fontSize:TextUnit=16.sp,lineHeight:TextUnit=24.sp,maxLines:Int=Int.MAX_VALUE,fontWeight:FontWeight?=null,kind:String="public display",rescue:Boolean=true,overflow:TextOverflow=TextOverflow.Ellipsis,phraseRescue:Boolean=rescue,onDisplayedText:((String)->Unit)?=null){
    LaunchedEffect(id,original,PocketImmersion.enabled,PocketImmersion.density){PocketImmersion.offer(id,original,kind)}
    val plan=PocketImmersion.presentation(id,original).takeIf{it.source==original}?:ImmersionPresentation(original,original,false)
    val readingKey=PocketImmersion.readingKey(id,plan)
    val selected=PocketImmersion.readingSelection(readingKey,plan)
    val shownText=immersionReplacementText(plan,selected.takeIf{phraseRescue&&PocketImmersion.supportEnabled})
    ReportDisplayedImmersion(id,shownText,onDisplayedText)
    val inline=remember(shownText){MarkdownContent.preview(shownText)}
    val ranges=remember(plan,shownText,selected){MarkdownContent.replacementRanges(plan,shownText,selected.takeIf{phraseRescue&&PocketImmersion.supportEnabled})}
    val content=if(PocketImmersion.enabled)hybridAnnotated(inline,ranges,0,selected,phraseRescue&&PocketImmersion.supportEnabled){PocketImmersion.selectReading(readingKey,if(selected==it)null else it)}else buildAnnotatedString{append(inline.text)}
    Column(modifier){
        ImmersionReading(content,selected.takeIf{phraseRescue&&PocketImmersion.supportEnabled},color=color,fontSize=fontSize,lineHeight=lineHeight,maxLines=maxLines,fontWeight=fontWeight,overflow=overflow,onClose={PocketImmersion.selectReading(readingKey,null)})
    }
}

/** Report the one visible linguistic flow without restarting effects for recreated callbacks. */
@Composable private fun ReportDisplayedImmersion(id:String,text:String,callback:((String)->Unit)?){
    val latest by rememberUpdatedState(callback)
    LaunchedEffect(id,text,callback!=null){latest?.invoke(text)}
}
