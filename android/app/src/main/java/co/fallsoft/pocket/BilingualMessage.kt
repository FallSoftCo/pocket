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

private fun hybridAnnotated(content:MarkdownInline,plan:ImmersionPresentation,help:Boolean,expanded:ImmersionSpan?,onHelp:(ImmersionSpan)->Unit):AnnotatedString {
    val base=buildAnnotatedString {
    append(content.text)
    content.spans.forEach { span -> when(span.kind){
        "bold"->addStyle(SpanStyle(fontWeight=FontWeight.Bold),span.start,span.end)
        "italic"->addStyle(SpanStyle(fontStyle=FontStyle.Italic),span.start,span.end)
        "code"->addStyle(SpanStyle(fontFamily=FontFamily.Monospace,background=Color(0xff292a30)),span.start,span.end)
        "link"->{val scheme=android.net.Uri.parse(span.destination).scheme?.lowercase();if(scheme in listOf("https","http","mailto"))addLink(LinkAnnotation.Url(span.destination,TextLinkStyles(style=SpanStyle(color=Mint,textDecoration=TextDecoration.Underline))),span.start,span.end)}
    } }
    // Only unambiguous rendered phrases get interactive inline help. Never guess a repeated match.
    val whole=MarkdownContent.preview(plan.text).text
    plan.spans.forEachIndexed { index,span ->
        val phrase=span.target;val at=content.text.indexOf(phrase)
        if(at>=0&&content.text.lastIndexOf(phrase)==at&&whole.indexOf(phrase)==whole.lastIndexOf(phrase)&&phrase!=span.source){
            addStyle(SpanStyle(color=Mint,fontWeight=FontWeight.Medium),at,at+phrase.length)
            if(help)addLink(LinkAnnotation.Clickable("immersion:$index",TextLinkStyles(style=SpanStyle(textDecoration=TextDecoration.Underline)),linkInteractionListener={onHelp(span)}),at,at+phrase.length)
        }
    }
    }
    val expansion=expanded?.takeIf{help}?.let{inlineImmersionHelp(content.text,it)}?:return base
    return buildAnnotatedString {
        append(base.subSequence(0,expansion.first))
        withStyle(SpanStyle(color=Paper,fontWeight=FontWeight.Normal)){append(expansion.second)}
        append(base.subSequence(expansion.first,base.length))
    }
}

/** Context-aware target phrases remain inline at ordinary reading size. */
@Composable fun BilingualMessage(id:String,original:String,modifier:Modifier=Modifier){
    LaunchedEffect(id,original,PocketImmersion.enabled,PocketImmersion.density){PocketImmersion.offer(id,original)}
    if(!PocketImmersion.enabled){Column(modifier){RichText(original)};return}
    val plan=PocketImmersion.presentation(id,original)
    var selected by remember(id,plan.text,plan.source,PocketImmersion.density){mutableStateOf<ImmersionSpan?>(null)}
    val blocks=remember(plan.text){MarkdownContent.blocks(plan.text)}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(9.dp)){
        if(!plan.current&&plan.source!=original)Text("Ultimo testo reso · si aggiorna",fontSize=14.sp,lineHeight=21.sp,color=Muted)
        blocks.forEach { block -> when(block.kind){
            "code"->Surface(color=Ink,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){if(block.language.isNotBlank())Text(block.language,color=Muted,fontSize=12.sp);Text(block.content.text,fontFamily=FontFamily.Monospace,fontSize=14.sp,lineHeight=21.sp,color=Paper,modifier=Modifier.horizontalScroll(rememberScrollState()))}}
            "rule"->HorizontalDivider(color=Line)
            else->{val content=hybridAnnotated(block.content,plan,PocketImmersion.supportEnabled,selected){selected=if(selected==it)null else it};val size=if(block.kind=="heading")when(block.level){1->24.sp;2->21.sp;else->18.sp}else 17.sp
                Text(buildAnnotatedString{append(block.prefix);append(content)},fontSize=size,lineHeight=if(block.kind=="heading")size*1.3f else 26.sp,fontWeight=if(block.kind=="heading")FontWeight.SemiBold else FontWeight.Normal,color=Paper,modifier=Modifier.fillMaxWidth())}
        } }
        if(PocketImmersion.supportEnabled&&PocketImmersion.originalShown(id))RichText(original)
    }
}

/** Dense/tappable cards leave phraseRescue=false, so their native actions retain every tap. */
@Composable fun ImmersionText(id:String,original:String,modifier:Modifier=Modifier,color:Color=Paper,fontSize:TextUnit=16.sp,lineHeight:TextUnit=24.sp,maxLines:Int=Int.MAX_VALUE,fontWeight:FontWeight?=null,kind:String="public display",rescue:Boolean=true,overflow:TextOverflow=TextOverflow.Ellipsis,phraseRescue:Boolean=rescue){
    LaunchedEffect(id,original,PocketImmersion.enabled,PocketImmersion.density){PocketImmersion.offer(id,original,kind)}
    val plan=PocketImmersion.presentation(id,original)
    var selected by remember(id,plan.text,plan.source,PocketImmersion.density){mutableStateOf<ImmersionSpan?>(null)}
    var originalOpen by remember(id,original){mutableStateOf(false)}
    val inline=remember(plan.text){MarkdownContent.preview(plan.text)}
    val content=if(PocketImmersion.enabled)hybridAnnotated(inline,plan,phraseRescue&&PocketImmersion.supportEnabled,selected){selected=if(selected==it)null else it}else buildAnnotatedString{append(inline.text)}
    val shown=if(!plan.current&&plan.source!=original)buildAnnotatedString{append("↻ ");append(content)}else content
    Column(modifier){
        Text(shown,color=color,fontSize=fontSize,lineHeight=lineHeight,maxLines=if(selected!=null&&phraseRescue&&PocketImmersion.supportEnabled)Int.MAX_VALUE else maxLines,fontWeight=fontWeight,overflow=overflow)
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
