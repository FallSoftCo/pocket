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

private fun MarkdownInline.annotated(clickableLinks:Boolean=true):AnnotatedString=buildAnnotatedString {
    append(text)
    spans.forEach{span->
        when(span.kind){
            "bold"->addStyle(SpanStyle(fontWeight=FontWeight.Bold),span.start,span.end)
            "italic"->addStyle(SpanStyle(fontStyle=FontStyle.Italic),span.start,span.end)
            "code"->addStyle(SpanStyle(fontFamily=FontFamily.Monospace,background=Color(0xff292a30)),span.start,span.end)
            "link"->{
                val scheme=android.net.Uri.parse(span.destination).scheme?.lowercase()
                if(clickableLinks&&scheme in listOf("https","http","mailto"))addLink(LinkAnnotation.Url(span.destination,TextLinkStyles(style=SpanStyle(color=Mint,textDecoration=TextDecoration.Underline))),span.start,span.end)
            }
        }
    }
}
@Composable fun MarkdownPreview(raw:String,modifier:Modifier=Modifier,color:Color=Paper,fontSize:TextUnit=15.sp,lineHeight:TextUnit=24.sp,maxLines:Int=Int.MAX_VALUE,minLines:Int=1,overflow:TextOverflow=TextOverflow.Clip,fontFamily:FontFamily?=null){
    val content=remember(raw){MarkdownContent.preview(raw).annotated(clickableLinks=false)}
    Text(content,modifier=modifier,color=color,fontSize=fontSize,lineHeight=lineHeight,maxLines=maxLines,minLines=minLines,overflow=overflow,fontFamily=fontFamily)
}
@Composable fun RichText(raw:String){
    val blocks=remember(raw){MarkdownContent.blocks(raw)}
    Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
        blocks.forEach{block->
            when(block.kind){
                "code"->Surface(color=Ink,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()){
                    Column(Modifier.padding(14.dp)){
                        if(block.language.isNotBlank())Text(block.language,color=Muted,fontSize=10.sp)
                        Text(block.content.text,fontFamily=FontFamily.Monospace,fontSize=12.sp,color=Paper,lineHeight=18.sp,modifier=Modifier.horizontalScroll(rememberScrollState()))
                    }
                }
                "rule"->HorizontalDivider(color=Line)
                else->{
                    val size=if(block.kind=="heading")when(block.level){1->24.sp;2->21.sp;else->18.sp}else 15.sp
                    val content=remember(block){buildAnnotatedString{append(block.prefix);append(block.content.annotated())}}
                    val inset=if(block.level>0&&block.kind!="heading")((block.level-1)*12).dp else 0.dp
                    val modifier=Modifier.fillMaxWidth().padding(start=inset).then(if(block.kind=="quote")Modifier.background(Panel,RoundedCornerShape(6.dp)).padding(12.dp)else Modifier)
                    MarkdownImageFlow(content,block.content.images,block.prefix.length){part->
                        Text(part,fontSize=size,lineHeight=if(block.kind=="heading")size*1.3f else 24.sp,fontWeight=if(block.kind=="heading")FontWeight.SemiBold else FontWeight.Normal,color=if(block.kind=="quote")Muted else Paper.copy(alpha=.93f),modifier=modifier)
                    }
                }
            }
        }
    }
}

/** Slice already annotated content, so image placement cannot shift immersion/link offsets. */
@Composable internal fun MarkdownImageFlow(content:AnnotatedString,images:List<MarkdownImage>,prefixLength:Int=0,onText:@Composable (AnnotatedString)->Unit){
    var cursor=0
    val visible=mutableListOf<MarkdownImage>()
    images.forEach {image->key(image.destination){if(linkedImageAllowed(image))visible.add(image)}}
    visible.sortedBy{it.position}.forEach { image ->
        val at=if(image.position==Int.MAX_VALUE)content.length else (image.position+prefixLength).coerceIn(cursor,content.length)
        if(at>cursor)onText(content.subSequence(cursor,at))
        LinkedImagePreview(image)
        cursor=at
    }
    if(cursor<content.length)onText(content.subSequence(cursor,content.length))
}
