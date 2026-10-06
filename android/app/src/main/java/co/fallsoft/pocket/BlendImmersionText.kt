package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.abs

internal data class ImmersionInkPhase(val incoming:Boolean,val opacity:Float)
internal fun immersionInkPhase(progress:Float):ImmersionInkPhase {
    val p=progress.coerceIn(0f,1f)
    return ImmersionInkPhase(p>=.5f,.85f+.15f*abs(2f*p-1f))
}

/** One native layout at every frame. A shallow ink fade never blanks or overlays languages. */
@Composable internal fun BlendImmersionText(
    from:AnnotatedString,to:AnnotatedString,progress:()->Float,snapshot:AnnotatedString,
    modifier:Modifier=Modifier,fontSize:TextUnit=17.sp,lineHeight:TextUnit=26.sp,
    color:Color=Paper,fontWeight:FontWeight?=null,maxLines:Int=Int.MAX_VALUE,
    overflow:TextOverflow=TextOverflow.Clip,textAlign:TextAlign=TextAlign.Start,
    fillWidth:Boolean=false,reserve:List<AnnotatedString> = emptyList()
){
    val measurer=rememberTextMeasurer()
    val density=androidx.compose.ui.platform.LocalDensity.current
    val style=LocalTextStyle.current.merge(TextStyle(fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,textAlign=textAlign))
    val incoming by remember(progress){derivedStateOf{immersionInkPhase(progress()).incoming}}
    // Fresh callbacks/styles when wording is unchanged; keep the previous visible
    // flow until the phase effect has actually installed the incoming text.
    val visible=if(incoming){if(to.text==snapshot.text)snapshot else to}else from
    BoxWithConstraints(modifier){
        val available=constraints.maxWidth.coerceAtLeast(1)
        fun measure(text:AnnotatedString,width:Int,fixed:Boolean=false)=measurer.measure(immersionDrawText(text),style=style,maxLines=maxLines,overflow=overflow,constraints=Constraints(minWidth=if(fixed)width else 0,maxWidth=width))
        val forms=listOf(from,to)+reserve
        val natural=forms.map{measure(it,available)}
        val width=if(fillWidth&&available!=Constraints.Infinity)available else natural.maxOf{it.size.width}.coerceAtLeast(1)
        val height=forms.maxOf{measure(it,width,true).size.height}.coerceAtLeast(1)
        Box(Modifier.size(with(density){width.toDp()},with(density){height.toDp()})){
            Text(visible,color=color,fontSize=fontSize,lineHeight=lineHeight,fontWeight=fontWeight,textAlign=textAlign,maxLines=maxLines,overflow=overflow,
                modifier=Modifier.fillMaxWidth().graphicsLayer{alpha=immersionInkPhase(progress()).opacity})
        }
    }
}

/** Native Text resolves link presentation internally; measurement needs it explicitly. */
internal fun immersionDrawText(text:AnnotatedString):AnnotatedString = buildAnnotatedString{
    append(text)
    text.getLinkAnnotations(0,text.length).forEach{range->
        val style=when(val link=range.item){is LinkAnnotation.Url->link.styles?.style;is LinkAnnotation.Clickable->link.styles?.style;else->null}
        if(style!=null)addStyle(style,range.start,range.end)
    }
}
