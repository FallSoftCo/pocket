package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
/** One fully readable native layout; a local background identifies the changing phrase. */
@Composable internal fun BlendImmersionText(
    from:AnnotatedString,to:AnnotatedString,progress:()->Float,snapshot:AnnotatedString,
    modifier:Modifier=Modifier,fontSize:TextUnit=17.sp,lineHeight:TextUnit=26.sp,
    color:Color=Paper,fontWeight:FontWeight?=null,maxLines:Int=Int.MAX_VALUE,
    overflow:TextOverflow=TextOverflow.Clip,textAlign:TextAlign=TextAlign.Start,
    fillWidth:Boolean=false,reserve:List<AnnotatedString> = emptyList(),
    onVisibleRanges:((Set<String>,Boolean)->Unit)?=null
){
    val measurer=rememberTextMeasurer()
    val density=androidx.compose.ui.platform.LocalDensity.current
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
        val value=immersionVisibleText(layout,coordinates)
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
            Text(visible,color=color,fontSize=fontSize,lineHeight=lineHeight,fontWeight=fontWeight,textAlign=textAlign,maxLines=maxLines,overflow=overflow,
                onTextLayout={layout=it;reportVisibility()},modifier=Modifier.fillMaxWidth().onGloballyPositioned{coordinates=it;reportVisibility()}.drawBehind{
                    val current=layout
                    val cue=immersionInkPhase(progress()).cue
                    if(cue>0f&&current?.layoutInput?.text==visible){
                        ranges.forEach{range->
                            if(range.start>=0&&range.end<=visible.length&&range.end>range.start){
                                // Native selection geometry follows each wrapped fragment; it
                                // never unions a phrase into a box covering unrelated words.
                                drawPath(current.getPathForRange(range.start,range.end),Color(0xffffc43a).copy(alpha=.22f*cue))
                            }
                        }
                    }
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

/** Native Text resolves link presentation internally; measurement needs it explicitly. */
internal fun immersionDrawText(text:AnnotatedString):AnnotatedString = buildAnnotatedString{
    append(text)
    text.getLinkAnnotations(0,text.length).forEach{range->
        val style=when(val link=range.item){is LinkAnnotation.Url->link.styles?.style;is LinkAnnotation.Clickable->link.styles?.style;else->null}
        if(style!=null)addStyle(style,range.start,range.end)
    }
}
