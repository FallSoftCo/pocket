package co.fallsoft.pocket

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

/** Shared native paragraph for reserved inline replacements. Physical motion
 * and broader content qualification are tracked separately from integration. */
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
    onWipeGeometry:(ImmersionSharedWipeGeometry)->Unit={}
){
    val effectiveFrom=if(from.text==snapshot.text)snapshot else from
    val effectiveTo=if(to.text==snapshot.text)snapshot else to
    val runs=remember(effectiveFrom,effectiveTo,reserve){immersionSharedRuns(listOf(effectiveFrom,effectiveTo)+reserve)}
    if(runs==null){
        // Invalid alignment is not evidence of shared geometry. Preserve normal
        // text rather than invent a phrase/word correspondence.
        BlendImmersionText(from,to,progress,snapshot,modifier,fontSize,lineHeight,color,fontWeight,
            maxLines=maxLines,overflow=overflow,fillWidth=fillWidth,reserve=reserve,onVisibleRanges=onVisibleRanges)
        return
    }
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val uriHandler=LocalUriHandler.current
    val rtl=LocalLayoutDirection.current==LayoutDirection.Rtl
    val style=LocalTextStyle.current.merge(TextStyle(fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight))
    val nativeLineHeight=measurer.measure(AnnotatedString("M"),style=style).let{it.getLineBottom(0)-it.getLineTop(0)}
    var nativeLayout by remember {mutableStateOf<TextLayoutResult?>(null)}
    var coordinates by remember {mutableStateOf<LayoutCoordinates?>(null)}
    var lastVisibility by remember {mutableStateOf<Pair<Set<String>,Boolean>?>(null)}
    val visibilityCallback by rememberUpdatedState(onVisibleRanges)
    fun reportVisibility(){
        val value=immersionVisibleText(nativeLayout,coordinates,false)
        if(value!=lastVisibility){lastVisibility=value;visibilityCallback?.invoke(value.first,value.second)}
    }
    SideEffect{visibilityCallback?.invoke(lastVisibility?.first?:emptySet(),false)}
    DisposableEffect(Unit){onDispose{visibilityCallback?.invoke(emptySet(),false)}}
    val incoming by remember(progress){derivedStateOf{immersionInkPhase(progress()).incoming}}
    BoxWithConstraints(modifier){
        val available=constraints.maxWidth.coerceAtLeast(1)
        val content=mutableMapOf<String,InlineTextContent>()
        // Parent ellipsis needs physical room beside an atomic placeholder.
        // Children clip at that boundary so the paragraph supplies one ellipsis.
        val ellipsisAllowance=if(maxLines<Int.MAX_VALUE&&overflow==TextOverflow.Ellipsis)
            measurer.measure(AnnotatedString("…"),style=style,softWrap=false).size.width+1 else 0
        val slotLimit=(available-ellipsisAllowance).coerceAtLeast(1)
        val fragmentOverflow=if(ellipsisAllowance>0)TextOverflow.Clip else overflow
        val slotKeys=mutableListOf<String>()
        val changedKeys=mutableSetOf<String>()
        var wipeGeometry by remember(from,to,reserve,available,fontSize,lineHeight){
            mutableStateOf<ImmersionSharedWipeGeometry?>(null)
        }
        val paragraph=buildAnnotatedString{
            runs.forEachIndexed{index,run->
                if(run.id==null){append(run.forms[if(incoming)1 else 0]);return@forEachIndexed}
                // Split at natural token boundaries without claiming translation
                // word alignment. A long phrase is not an unbreakable giant box.
                val chunks=run.forms.map{form->immersionAnnotatedChunks(form,available){value->
                    measurer.measure(immersionDrawText(value),style=style,softWrap=false).size.width
                }}
                repeat(chunks.maxOf{it.size}){part->
                    val forms=chunks.map{it.getOrElse(part){AnnotatedString("")}}
                    val natural=forms.map{measurer.measure(immersionDrawText(it),style=style,softWrap=false)}
                    val width=natural.maxOf{it.size.width}.coerceAtLeast(1).coerceAtMost(slotLimit)
                    // Native paragraph shaping handles an oversized token. Reserve
                    // its full wrapped height in every form instead of clipping
                    // a one-line child or splitting Unicode graphemes ourselves.
                    val measured=forms.map{measurer.measure(immersionDrawText(it),style=style,
                        constraints=Constraints(maxWidth=width),maxLines=maxLines,overflow=fragmentOverflow)}
                    val height=measured.maxOf{it.size.height}.coerceAtLeast(1)
                    val key="shared-$index-$part"
                    slotKeys+=key
                    if(forms[0].text!=forms[1].text)changedKeys+=key
                    content[key]=InlineTextContent(
                        Placeholder(with(density){width.toSp()},with(density){height.toSp()},PlaceholderVerticalAlign.TextCenter)
                    ){
                        BlendImmersionText(forms[0],forms[1],progress,forms[if(incoming)1 else 0],
                            modifier=Modifier.clearAndSetSemantics{},
                            fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,
                            onNativeTextLayout=onFragmentLayout,
                            sharedWipeMask={p,top->wipeGeometry?.mask(key,top,p,rtl)
                                ?:ImmersionSharedWipeMask(if(rtl)width+48f else -48f,48f)},
                            maxLines=maxLines,overflow=fragmentOverflow,fillWidth=true,reserve=forms)
                    }
                    val start=length
                    appendInlineContent(key,forms[if(incoming)1 else 0].text.ifEmpty{"\u200b"})
                    addStringAnnotation("immersion-reserve",run.id,start,length)
                }
            }
        }
        val visible=if(incoming)effectiveTo else effectiveFrom
        val phraseRanges=visible.getStringAnnotations("immersion-reserve",0,visible.length)
        val actions=visible.getLinkAnnotations(0,visible.length).filter{link->
            phraseRanges.any{it.start<=link.start&&it.end>=link.end&&it.item in lastVisibility?.first.orEmpty()}
        }.map{range->
            CustomAccessibilityAction(visible.text.substring(range.start,range.end)){
                val link=range.item
                val listener=link.linkInteractionListener
                if(listener!=null){listener.onClick(link);true}
                else if(link is LinkAnnotation.Url){
                    try{uriHandler.openUri(link.url);true}catch(_:IllegalArgumentException){false}
                }else false
            }
        }
        Text(paragraph,inlineContent=content,style=style,maxLines=maxLines,overflow=overflow,onTextLayout={layout->
            nativeLayout=layout
            val regions=slotKeys.mapIndexedNotNull{index,key->
                layout.placeholderRects.getOrNull(index)?.takeIf{key in changedKeys}?.let{key to it}
            }.toMap()
            wipeGeometry=ImmersionSharedWipeGeometry(regions,layout.size.width.toFloat(),nativeLineHeight)
            onWipeGeometry(wipeGeometry!!)
            onTextLayout(layout)
            reportVisibility()
        },
            modifier=(if(fillWidth)Modifier.fillMaxWidth()else Modifier)
                .then(if(maxLines<Int.MAX_VALUE)Modifier.heightIn(max=with(density){(nativeLineHeight*maxLines).toDp()})else Modifier)
                .onGloballyPositioned{coordinates=it;reportVisibility()}
                .semantics{customActions=actions})
    }
}

internal fun immersionAnnotatedChunks(
    form:AnnotatedString,maxWidth:Int,width:(AnnotatedString)->Int
):List<AnnotatedString>{
    if(form.isEmpty())return emptyList()
    val result=mutableListOf<AnnotatedString>()
    var start=0;var end=0
    for(token in Regex("\\S+\\s*|\\s+").findAll(form.text)){
        if(end>start&&width(form.subSequence(start,token.range.last+1))>maxWidth){
            result+=form.subSequence(start,end);start=end
        }
        end=token.range.last+1
    }
    if(end>start)result+=form.subSequence(start,end)
    return result
}
