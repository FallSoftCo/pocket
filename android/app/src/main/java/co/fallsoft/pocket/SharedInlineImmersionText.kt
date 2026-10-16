package co.fallsoft.pocket

import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

/**
 * Two naturally shaped paragraphs, never maximum-width per-word boxes.
 * Only the enclosing paragraph retains a stable height across its two forms.
 * The global reading clock drives cached draw masks, not text measurement.
 */
@Composable internal fun SharedInlineImmersionText(
    from:AnnotatedString,to:AnnotatedString,progress:()->Float,
    modifier:Modifier=Modifier,fontSize:TextUnit=17.sp,lineHeight:TextUnit=26.sp,
    color:Color=Paper,fontWeight:FontWeight?=null,
    reserve:List<AnnotatedString> = emptyList(),
    snapshot:AnnotatedString=to,maxLines:Int=Int.MAX_VALUE,
    overflow:TextOverflow=TextOverflow.Clip,fillWidth:Boolean=false,
    onVisibleRanges:((Set<String>,Boolean)->Unit)?=null,
    onTextLayout:(TextLayoutResult)->Unit={},
    onFragmentLayout:(TextLayoutResult)->Unit={},
    onWipeGeometry:(ImmersionSharedWipeGeometry)->Unit={},
    textAlign:androidx.compose.ui.text.style.TextAlign=androidx.compose.ui.text.style.TextAlign.Start,
    trackWholeTextVisibility:Boolean=false,
    readingWave:ImmersionReadingWaveClock?=null
){
    val owner=remember{Any()}
    val uriHandler=LocalUriHandler.current
    var layouts by remember{mutableStateOf<Pair<TextLayoutResult,TextLayoutResult>?>(null)}
    val rtl=LocalLayoutDirection.current==LayoutDirection.Rtl
    var coordinates by remember{mutableStateOf<LayoutCoordinates?>(null)}
    var origin by remember{mutableStateOf<Offset?>(null)}
    var lines by remember{mutableStateOf(emptyList<Rect>())}
    var visible by remember{mutableStateOf(false)}
    val latestVisible by rememberUpdatedState(onVisibleRanges)
    val latestGeometry by rememberUpdatedState(onWipeGeometry)
    fun publishLines(){
        val clock=readingWave?:return
        val point=origin?.minus(clock.viewportOrigin)?:return
        clock.lines(owner,lines.map{it.translate(point)}.filter{
            it.bottom>0f&&it.top<clock.viewportHeightPx&&it.right>0f&&it.left<clock.viewportWidthPx
        })
    }
    DisposableEffect(readingWave,owner,visible){
        readingWave?.visible(owner,visible)
        onDispose{readingWave?.visible(owner,false)}
    }
    SideEffect{publishLines()}
    // Equality-clamped masks keep opaque holds identical across clock ticks.
    val masks=remember(readingWave,rtl){mutableMapOf<Float,State<ImmersionSharedWipeMask>>()}
    fun mask(top:Float):ImmersionSharedWipeMask =
        masks.getOrPut(top){derivedStateOf(structuralEqualityPolicy()){
            val point=origin?.minus(readingWave?.viewportOrigin?:Offset.Zero)
            val width=coordinates?.size?.width?.toFloat()?:0f
            val global=if(point!=null)readingWave?.lineMask(point.y+top,rtl)else null
            if(global==null||point==null)ImmersionSharedWipeMask(if(rtl)width+48f else -48f,48f,true)
            else global.copy(center=(global.center-point.x).coerceIn(-global.feather,width+global.feather))
        }}.value
    val incoming by remember(readingWave,rtl){derivedStateOf{
        val width=coordinates?.size?.width?.toFloat()?:0f
        val first=lines.minByOrNull{it.top}
        val m=mask(first?.top?:0f)
        val x=first?.let{(it.left+it.right)/2f}?:width/2f
        val coverage=(if(rtl)(x-m.center+m.feather)else(m.center+m.feather-x))/(2f*m.feather)
        (if(m.incomingAtReadingStart)coverage else 1f-coverage)>=.5f
    }}
    val linked=from.getLinkAnnotations(0,from.length).isNotEmpty()||to.getLinkAnnotations(0,to.length).isNotEmpty()
    fun linkAt(layout:TextLayoutResult,point:Offset):LinkAnnotation? {
        if(layout.layoutInput.text.isEmpty())return null
        val at=layout.getOffsetForPosition(point).coerceIn(0,layout.layoutInput.text.length-1)
        if(!layout.getBoundingBox(at).contains(point))return null
        return layout.layoutInput.text.getLinkAnnotations(at,at+1).firstOrNull()?.item
    }
    val hitTesting=if(readingWave==null||!linked)Modifier else Modifier.pointerInput(from,to,readingWave){
        awaitEachGesture{
            val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
            val shaped=layouts
            if(shaped!=null){
                val line=shaped.first.getLineForVerticalPosition(down.position.y)
                val m=mask(shaped.first.getLineTop(line))
                val coverage=(if(rtl)(down.position.x-m.center+m.feather)
                    else(m.center+m.feather-down.position.x))/(2f*m.feather)
                val incomingInk=(if(m.incomingAtReadingStart)coverage else 1f-coverage)>=.5f
                val selected=if(incomingInk)shaped.second else shaped.first
                val link=linkAt(selected,down.position)
                // A semantic paragraph may already use the new form while its
                // lower rows still show the old one. Never activate a link on
                // reserved/blank ink merely because the other layout has it.
                if(link!=null||linkAt(shaped.first,down.position)!=null||linkAt(shaped.second,down.position)!=null){
                    val up=withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis){
                        waitForUpOrCancellation(PointerEventPass.Initial)
                    }
                    if(up!=null){
                        up.consume()
                        when(link){
                            is LinkAnnotation.Url->link.linkInteractionListener?.onClick(link)?:uriHandler.openUri(link.url)
                            is LinkAnnotation.Clickable->link.linkInteractionListener?.onClick(link)
                            else->Unit
                        }
                    }
                }
            }
        }
    }
    BlendImmersionText(from,to,progress,snapshot,
        modifier=modifier.then(hitTesting).onGloballyPositioned{c->coordinates=c;origin=c.localToScreen(Offset.Zero);publishLines()},
        fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,maxLines=maxLines,
        overflow=overflow,textAlign=textAlign,fillWidth=fillWidth,reserve=reserve,
        onVisibleRanges={ranges,whole->
            visible=whole||ranges.isNotEmpty()
            latestVisible?.invoke(ranges,whole)
        },
        trackWholeTextVisibility=trackWholeTextVisibility,
        onNativeTextLayout={layout->onTextLayout(layout);onFragmentLayout(layout)},
        sharedWipeMask=if(readingWave!=null){{_,top->mask(top)}}else null,
        continuousWipe=readingWave!=null,incomingOverride=if(readingWave!=null)incoming else null,
        onShapedLayouts={a,b->
            if(layouts?.first!==a||layouts?.second!==b)layouts=a to b
        },
        onShapedLines={value->
            if(lines!=value)lines=value
            publishLines()
            val width=coordinates?.size?.width?.toFloat()?:0f
            latestGeometry(ImmersionSharedWipeGeometry(value.mapIndexed{i,r->"line:$i" to r}.toMap(),width,
                value.firstOrNull()?.height?:1f))
        })
}

internal fun immersionAnnotatedChunks(
    form:AnnotatedString,maxWidth:Int,firstWidth:Int=maxWidth,width:(AnnotatedString)->Int
):List<AnnotatedString>{
    if(form.isEmpty())return emptyList()
    val result=mutableListOf<AnnotatedString>()
    var start=0;var end=0
    for(token in Regex("\\S+\\s*|\\s+").findAll(form.text)){
        if(end>start&&width(form.subSequence(start,token.range.last+1))>(if(result.isEmpty())firstWidth else maxWidth)){
            result+=form.subSequence(start,end);start=end
        }
        end=token.range.last+1
    }
    if(end>start)result+=form.subSequence(start,end)
    return result
}
