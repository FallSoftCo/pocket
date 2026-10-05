package co.fallsoft.pocket

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
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
private fun hybridAnnotated(content:MarkdownInline,plan:ImmersionPresentation,help:Boolean,onHelp:(ImmersionSpan)->Unit):AnnotatedString = buildAnnotatedString {
    append(content.text)
    content.spans.forEach { span -> when(span.kind){
        "bold"->addStyle(SpanStyle(fontWeight=FontWeight.Bold),span.start,span.end)
        "italic"->addStyle(SpanStyle(fontStyle=FontStyle.Italic),span.start,span.end)
        "code"->addStyle(SpanStyle(fontFamily=FontFamily.Monospace,background=Color(0xff292a30)),span.start,span.end)
        "link"->{val scheme=android.net.Uri.parse(span.destination).scheme?.lowercase();if(scheme in listOf("https","http","mailto"))addLink(LinkAnnotation.Url(span.destination,TextLinkStyles(style=SpanStyle(color=Mint,textDecoration=TextDecoration.Underline))),span.start,span.end)}
    } }
    val whole=MarkdownContent.preview(plan.text).text
    plan.spans.forEachIndexed { index,span ->
        val at=content.text.indexOf(span.target)
        if(at>=0&&content.text.lastIndexOf(span.target)==at&&whole.indexOf(span.target)==whole.lastIndexOf(span.target)&&span.target!=span.source&&alignedTargetValid(span)){
            val agreement=agreementSegments(span)
            span.targetSegments.forEachIndexed { segmentIndex,segment ->
                if(segment.role !in listOf("separator","punctuation"))addStyle(SpanStyle(color=grammarColor(segment),fontWeight=FontWeight.Medium,textDecoration=if(segmentIndex in agreement)TextDecoration.Underline else null),at+segment.start,at+segment.end)
            }
            if(help)addLink(LinkAnnotation.Clickable("immersion:$index",TextLinkStyles(style=SpanStyle()),linkInteractionListener={onHelp(span)}),at,at+span.target.length)
        }
    }
}

/** Matching shape/line identifies only teacher-validated agreement, without naming grammar. */
@Composable private fun AgreementMarker(group:Int){
    val accent=listOf(Color(0xff9de3ed),Color(0xffffc06b),Color(0xffd4b0ff),Color(0xffb5e5b7))[group%4]
    Canvas(Modifier.fillMaxWidth().height(9.dp)){
        val r=2.5.dp.toPx();val center=Offset(r+1.dp.toPx(),size.height/2)
        when(group%3){
            0->drawCircle(accent,r,center)
            1->drawRect(accent,Offset(center.x-r,center.y-r),Size(r*2,r*2))
            else->{val diamond=Path().apply{moveTo(center.x,center.y-r);lineTo(center.x+r,center.y);lineTo(center.x,center.y+r);lineTo(center.x-r,center.y);close()};drawPath(diamond,accent)}
        }
        drawLine(accent,Offset(center.x+r+3.dp.toPx(),center.y),Offset(size.width,center.y),1.5.dp.toPx())
    }
}

/** Expand the actual reading position. The original Italian stays above its aligned meaning. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ImmersionReading(content:AnnotatedString,selected:ImmersionSpan?,fontSize:TextUnit,lineHeight:TextUnit,color:Color=Paper,fontWeight:FontWeight?=null,maxLines:Int=Int.MAX_VALUE,overflow:TextOverflow=TextOverflow.Clip,onClose:()->Unit){
    if(selected==null||immersionReadingTokens(content.text,selected)==null){Text(content,fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,maxLines=maxLines,overflow=overflow,modifier=Modifier.fillMaxWidth());return}
    // Preserve explicit line breaks; a selected phrase never spans a newline.
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
        var offset=0
        content.text.split('\n').forEach { line ->
            val annotated=content.subSequence(offset,offset+line.length)
            val tokens=immersionReadingTokens(line,selected)
            if(tokens==null){Text(annotated,fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,modifier=Modifier.fillMaxWidth())}
            else BoxWithConstraints(Modifier.fillMaxWidth()){
                val available=maxWidth
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    tokens.forEach { token ->
                        val segment=token.segment?.let{selected.targetSegments[it]}
                        Column(Modifier.widthIn(max=available).width(IntrinsicSize.Max)){
                            Text(annotated.subSequence(token.start,token.end),fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight)
                            if(token.meaning.isNotBlank()&&segment!=null){
                                val meaning=buildAnnotatedString {
                                    append(token.meaning)
                                    addStyle(SpanStyle(color=grammarColor(segment),textDecoration=if(token.segment in agreementSegments(selected))TextDecoration.Underline else null),0,length)
                                    addLink(LinkAnnotation.Clickable("immersion-close",TextLinkStyles(style=SpanStyle()),linkInteractionListener={onClose()}),0,length)
                                }
                                Text(meaning,fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=FontWeight.Normal)
                            }
                            val group=token.segment?.let{agreementGroups(selected)[it]}
                            if(group!=null)AgreementMarker(group)
                        }
                    }
                }
            }
            offset+=line.length+1
        }
    }
}

/** Context-aware target phrases remain inline at ordinary reading size. */
@Composable fun BilingualMessage(id:String,original:String,modifier:Modifier=Modifier){
    LaunchedEffect(id,original,PocketImmersion.enabled,PocketImmersion.density){PocketImmersion.offer(id,original)}
    if(!PocketImmersion.enabled){Column(modifier){RichText(original)};return}
    val plan=PocketImmersion.presentation(id,original)
    var selected by remember(id,plan.text,plan.source,plan.spans,PocketImmersion.density){mutableStateOf<ImmersionSpan?>(null)}
    val blocks=remember(plan.text){MarkdownContent.blocks(plan.text)}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(9.dp)){
        if(!plan.current&&plan.source!=original)Text("Ultimo testo reso · si aggiorna",fontSize=14.sp,lineHeight=21.sp,color=Muted)
        blocks.forEach { block -> when(block.kind){
            "code"->Surface(color=Ink,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){if(block.language.isNotBlank())Text(block.language,color=Muted,fontSize=12.sp);Text(block.content.text,fontFamily=FontFamily.Monospace,fontSize=14.sp,lineHeight=21.sp,color=Paper,modifier=Modifier.horizontalScroll(rememberScrollState()))}}
            "rule"->HorizontalDivider(color=Line)
            else->{val content=hybridAnnotated(block.content,plan,PocketImmersion.supportEnabled){selected=if(selected==it)null else it};val size=if(block.kind=="heading")when(block.level){1->24.sp;2->21.sp;else->18.sp}else 17.sp
                ImmersionReading(buildAnnotatedString{append(block.prefix);append(content)},selected.takeIf{PocketImmersion.supportEnabled},fontSize=size,lineHeight=if(block.kind=="heading")size*1.3f else 26.sp,fontWeight=if(block.kind=="heading")FontWeight.SemiBold else FontWeight.Normal,onClose={selected=null})}
        } }
        if(PocketImmersion.supportEnabled&&PocketImmersion.originalShown(id))RichText(original)
    }
}

/** Dense/tappable cards leave phraseRescue=false, so their native actions retain every tap. */
@Composable fun ImmersionText(id:String,original:String,modifier:Modifier=Modifier,color:Color=Paper,fontSize:TextUnit=16.sp,lineHeight:TextUnit=24.sp,maxLines:Int=Int.MAX_VALUE,fontWeight:FontWeight?=null,kind:String="public display",rescue:Boolean=true,overflow:TextOverflow=TextOverflow.Ellipsis,phraseRescue:Boolean=rescue){
    LaunchedEffect(id,original,PocketImmersion.enabled,PocketImmersion.density){PocketImmersion.offer(id,original,kind)}
    val plan=PocketImmersion.presentation(id,original)
    var selected by remember(id,plan.text,plan.source,plan.spans,PocketImmersion.density){mutableStateOf<ImmersionSpan?>(null)}
    var originalOpen by remember(id,original){mutableStateOf(false)}
    val inline=remember(plan.text){MarkdownContent.preview(plan.text)}
    val content=if(PocketImmersion.enabled)hybridAnnotated(inline,plan,phraseRescue&&PocketImmersion.supportEnabled){selected=if(selected==it)null else it}else buildAnnotatedString{append(inline.text)}
    val shown=if(!plan.current&&plan.source!=original)buildAnnotatedString{append("↻ ");append(content)}else content
    Column(modifier){
        ImmersionReading(shown,selected.takeIf{phraseRescue&&PocketImmersion.supportEnabled},color=color,fontSize=fontSize,lineHeight=lineHeight,maxLines=maxLines,fontWeight=fontWeight,overflow=overflow,onClose={selected=null})
        if(rescue&&PocketImmersion.enabled&&PocketImmersion.supportEnabled){
            TextButton({originalOpen=!originalOpen},modifier=Modifier.heightIn(min=48.dp)){Text(if(originalOpen)"Continua" else "Testo originale",fontSize=16.sp)}
            if(originalOpen)Text(original,fontSize=fontSize.value.coerceAtLeast(16f).sp,lineHeight=lineHeight.value.coerceAtLeast(24f).sp,color=color)
        }
    }
}

/** Meaning is contextual, while shell syntax and copied commands remain exact. */
@Composable fun BilingualCommandGloss(command:String,modifier:Modifier=Modifier){
    if(!PocketImmersion.enabled)return
    val meaning=remember(command){ImmersionCommands.meaning(command)}?:return
    ImmersionText("command-meaning:$command",meaning.second,modifier=modifier,color=Mint,fontSize=16.sp,lineHeight=24.sp,rescue=false,phraseRescue=true,kind="command meaning")
}
