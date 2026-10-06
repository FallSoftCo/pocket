package co.fallsoft.pocket

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.max

/** Natural native typography; complementary layer weights never blank the entire flow. */
@Composable internal fun BlendImmersionText(
    from:AnnotatedString,to:AnnotatedString,progress:()->Float,snapshot:AnnotatedString,
    modifier:Modifier=Modifier,fontSize:TextUnit=17.sp,lineHeight:TextUnit=26.sp,
    color:Color=Paper,fontWeight:FontWeight?=null,maxLines:Int=Int.MAX_VALUE,
    overflow:TextOverflow=TextOverflow.Clip,textAlign:TextAlign=TextAlign.Start,fillWidth:Boolean=false,reserve:List<AnnotatedString> = emptyList()
){
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val inherited=LocalTextStyle.current
    val style=inherited.merge(TextStyle(fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,textAlign=textAlign))
    val normalPaint=remember{Paint()}
    val invisible=remember(snapshot){buildAnnotatedString {
        append(snapshot.text)
        snapshot.spanStyles.forEach{addStyle(it.item.copy(color=Color.Transparent,background=Color.Transparent,shadow=null,textDecoration=TextDecoration.None),it.start,it.end)}
        snapshot.paragraphStyles.forEach{addStyle(it.item,it.start,it.end)}
        val hidden=TextLinkStyles(style=SpanStyle(color=Color.Transparent,textDecoration=TextDecoration.None))
        snapshot.getLinkAnnotations(0,snapshot.length).forEach{annotation->when(val link=annotation.item){
            is LinkAnnotation.Url->addLink(LinkAnnotation.Url(link.url,hidden,link.linkInteractionListener),annotation.start,annotation.end)
            is LinkAnnotation.Clickable->addLink(LinkAnnotation.Clickable(link.tag,hidden,link.linkInteractionListener),annotation.start,annotation.end)
        }}
    }}
    BoxWithConstraints(modifier){
        val available=constraints.maxWidth.coerceAtLeast(1)
        fun measure(text:AnnotatedString,width:Int)=measurer.measure(immersionDrawText(text),style=style,maxLines=maxLines,overflow=overflow,constraints=Constraints(maxWidth=width))
        val naturalA=measure(from,available);val naturalB=measure(to,available)
        val reserved=reserve.map{measure(it,available)}
        val width=if(fillWidth&&available!=Constraints.Infinity)available else (listOf(naturalA.size.width,naturalB.size.width)+reserved.map{it.size.width}).max().coerceAtLeast(1)
        fun fitted(text:AnnotatedString)=measurer.measure(immersionDrawText(text),style=style,maxLines=maxLines,overflow=overflow,constraints=Constraints(minWidth=width,maxWidth=width))
        val a=fitted(from);val b=fitted(to)
        val height=(listOf(a.size.height,b.size.height)+reserve.map{fitted(it).size.height}).max().coerceAtLeast(1)
        val widthDp=with(density){width.toDp()};val heightDp=with(density){height.toDp()}
        Box(Modifier.size(widthDp,heightDp)){
            Canvas(Modifier.matchParentSize()){
                val p=progress().coerceIn(0f,1f)
                if(p<=0f)drawText(a)
                else if(p>=1f)drawText(b)
                else {
                    // Isolate additive composition from the opaque application background.
                    // Complementary weights keep coincident unchanged glyphs at full contrast.
                    drawContext.canvas.saveLayer(Rect(Offset.Zero,size),normalPaint)
                    drawText(a,alpha=1f-p)
                    drawText(b,alpha=p,blendMode=BlendMode.Plus)
                    drawContext.canvas.restore()
                }
            }
            // Transparent glyph colors retain normal native selection highlighting,
            // URL/phrase actions and one semantic text. Canvas has no duplicate text.
            Text(invisible,color=Color.Transparent,fontSize=fontSize,lineHeight=lineHeight,fontWeight=fontWeight,textAlign=textAlign,maxLines=maxLines,overflow=overflow,modifier=Modifier.fillMaxWidth())
        }
    }
}

/** Native Text resolves link presentation internally; the Canvas measurer needs it explicitly. */
internal fun immersionDrawText(text:AnnotatedString):AnnotatedString = buildAnnotatedString{
    append(text)
    text.getLinkAnnotations(0,text.length).forEach{range->
        val style=when(val link=range.item){is LinkAnnotation.Url->link.styles?.style;is LinkAnnotation.Clickable->link.styles?.style}
        if(style!=null)addStyle(style,range.start,range.end)
    }
}
