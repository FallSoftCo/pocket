package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
/** Native hit testing with a draw-only complementary reading-direction wipe. */
@Composable internal fun BlendImmersionText(
    from:AnnotatedString,to:AnnotatedString,progress:()->Float,snapshot:AnnotatedString,
    modifier:Modifier=Modifier,fontSize:TextUnit=17.sp,lineHeight:TextUnit=26.sp,
    color:Color=Paper,fontWeight:FontWeight?=null,maxLines:Int=Int.MAX_VALUE,
    overflow:TextOverflow=TextOverflow.Clip,textAlign:TextAlign=TextAlign.Start,
    fillWidth:Boolean=false,reserve:List<AnnotatedString> = emptyList(),
    onVisibleRanges:((Set<String>,Boolean)->Unit)?=null,
    trackWholeTextVisibility:Boolean=false
){
    val measurer=rememberTextMeasurer()
    val density=androidx.compose.ui.platform.LocalDensity.current
    val rtl=LocalLayoutDirection.current==LayoutDirection.Rtl
    val style=LocalTextStyle.current.merge(TextStyle(fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,textAlign=textAlign))
    val incoming by remember(progress){derivedStateOf{immersionInkPhase(progress()).incoming}}
    // Fresh callbacks/styles when wording is unchanged; keep the previous visible
    // flow until the phase effect has actually installed the incoming text.
    val visible=if(incoming){if(to.text==snapshot.text)snapshot else to}else from
    val changed=remember(from,to){immersionChangedTextRanges(from,to)}
    val ranges=if(incoming)changed.second else changed.first
    var layout by remember {mutableStateOf<TextLayoutResult?>(null)}
    var coordinates by remember {mutableStateOf<LayoutCoordinates?>(null)}
    val visibilityCallback by rememberUpdatedState(onVisibleRanges)
    var lastVisibility by remember {mutableStateOf<Pair<Set<String>,Boolean>?>(null)}
    fun reportVisibility(){
        val value=immersionVisibleText(layout,coordinates,trackWholeTextVisibility)
        if(value!=lastVisibility){lastVisibility=value;visibilityCallback?.invoke(value.first,value.second)}
    }
    // The callback owner can change while geometry stays unchanged (navigation).
    SideEffect{visibilityCallback?.invoke(lastVisibility?.first?:emptySet(),lastVisibility?.second?:false)}
    DisposableEffect(Unit){onDispose{visibilityCallback?.invoke(emptySet(),false)}}
    BoxWithConstraints(modifier){
        val available=constraints.maxWidth.coerceAtLeast(1)
        fun measure(text:AnnotatedString,width:Int,fixed:Boolean=false)=measurer.measure(immersionDrawText(text),style=style,maxLines=maxLines,overflow=overflow,constraints=Constraints(minWidth=if(fixed)width else 0,maxWidth=width))
        val forms=listOf(from,to)+reserve
        val natural=forms.map{measure(it,available)}
        val width=if(fillWidth&&available!=Constraints.Infinity)available else natural.maxOf{it.size.width}.coerceAtLeast(1)
        val height=forms.maxOf{measure(it,width,true).size.height}.coerceAtLeast(1)
        Box(Modifier.size(with(density){width.toDp()},with(density){height.toDp()})){
            val outgoingLayout=measure(from,width,true)
            val incomingLayout=measure(to,width,true)
            Text(visible,color=color,fontSize=fontSize,lineHeight=lineHeight,fontWeight=fontWeight,textAlign=textAlign,maxLines=maxLines,overflow=overflow,
                onTextLayout={layout=it;reportVisibility()},modifier=Modifier.fillMaxSize().onGloballyPositioned{coordinates=it;reportVisibility()}.drawWithContent{
                    val p=progress().coerceIn(0f,1f)
                    if(from.text==to.text||p<=0f||p>=1f){drawContent();return@drawWithContent}
                    val firstChangedLine=(changed.first.map{outgoingLayout.getLineForOffset(it.start.coerceIn(0,(from.length-1).coerceAtLeast(0)))}+changed.second.map{incomingLayout.getLineForOffset(it.start.coerceIn(0,(to.length-1).coerceAtLeast(0)))}).minOrNull()?:0
                    val lines=(maxOf(outgoingLayout.lineCount,incomingLayout.lineCount)-firstChangedLine).coerceAtLeast(1)
                    fun layer(result:TextLayoutResult,incomingLayer:Boolean){
                        val canvas=drawContext.canvas
                        canvas.saveLayer(Rect(0f,0f,size.width,size.height),Paint())
                        drawText(result)
                        for(line in 0 until result.lineCount){
                            val phase=if(line<firstChangedLine)0f else immersionWipeLineProgress(p,line-firstChangedLine,lines)
                            val feather=(size.width*.12f).coerceIn(12f,48f)
                            val frontier=-feather+(size.width+2f*feather)*phase
                            val left=if(incomingLayer)1f else 0f
                            val right=1f-left
                            val colors=if(rtl)listOf(Color.Black.copy(alpha=right),Color.Black.copy(alpha=left))else listOf(Color.Black.copy(alpha=left),Color.Black.copy(alpha=right))
                            val center=if(rtl)size.width-frontier else frontier
                            drawRect(Brush.horizontalGradient(colors,center-feather,center+feather),topLeft=Offset(0f,result.getLineTop(line)),size=Size(size.width,result.getLineBottom(line)-result.getLineTop(line)),blendMode=BlendMode.DstIn)
                        }
                        canvas.restore()
                    }
                    layer(outgoingLayout,false);layer(incomingLayout,true)
                })
        }
    }
}

/** Stable phrase IDs identify both layouts, even when earlier replacements move offsets. */
internal fun immersionChangedTextRanges(from:AnnotatedString,to:AnnotatedString):Pair<List<TextRange>,List<TextRange>> {
    if(from.text==to.text)return emptyList<TextRange>() to emptyList()
    fun marks(text:AnnotatedString)=text.getStringAnnotations("immersion-reserve",0,text.length).associateBy{it.item}
    val a=marks(from);val b=marks(to)
    if(a.isEmpty()&&b.isEmpty())return listOf(TextRange(0,from.length)) to listOf(TextRange(0,to.length))
    val changed=(a.keys+b.keys).filter{key->
        val left=a[key];val right=b[key]
        left==null||right==null||from.text.substring(left.start,left.end)!=to.text.substring(right.start,right.end)
    }
    return changed.mapNotNull{a[it]?.let{range->TextRange(range.start,range.end)}} to
        changed.mapNotNull{b[it]?.let{range->TextRange(range.start,range.end)}}
}

// Native Text resolves link styles into its own annotations. Those changes do
// not make the lexical layout stale; full AnnotatedString equality hides cues.
internal fun immersionNativeLayoutMatches(layout:AnnotatedString,visible:AnnotatedString)=layout.text==visible.text

/** Native Text resolves link presentation internally; measurement needs it explicitly. */
internal fun immersionDrawText(text:AnnotatedString):AnnotatedString = buildAnnotatedString{
    append(text)
    text.getLinkAnnotations(0,text.length).forEach{range->
        val style=when(val link=range.item){is LinkAnnotation.Url->link.styles?.style;is LinkAnnotation.Clickable->link.styles?.style;else->null}
        if(style!=null)addStyle(style,range.start,range.end)
    }
}

internal fun immersionWipeLineProgress(progress:Float,line:Int,lines:Int):Float {
    if(progress<=0f)return 0f
    if(progress>=1f)return 1f
    val overlap=1.6f
    return ((progress.coerceIn(0f,1f)*(lines.coerceAtLeast(1)-1+overlap)-line)/overlap).coerceIn(0f,1f)
}
