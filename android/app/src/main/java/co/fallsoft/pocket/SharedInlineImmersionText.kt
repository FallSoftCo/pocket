package co.fallsoft.pocket

import androidx.compose.foundation.text.selection.DisableSelection
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
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
    onWipeGeometry:(ImmersionSharedWipeGeometry)->Unit={},
    textAlign:androidx.compose.ui.text.style.TextAlign=androidx.compose.ui.text.style.TextAlign.Start,
    trackWholeTextVisibility:Boolean=false
){
    val projection=LocalImmersionProjectionMarkers.current
    val emptySlots=remember(projection){mutableMapOf<String,String>()}
    val effectiveFrom=if(from.text==snapshot.text)snapshot else from
    val effectiveTo=if(to.text==snapshot.text)snapshot else to
    val runs=remember(effectiveFrom,effectiveTo,reserve){immersionSharedRuns(listOf(effectiveFrom,effectiveTo)+reserve)}
    if(runs==null){
        // Invalid alignment is not evidence of shared geometry. Preserve normal
        // text rather than invent a phrase/word correspondence.
        BlendImmersionText(from,to,progress,snapshot,modifier,fontSize,lineHeight,color,fontWeight,
            maxLines=maxLines,overflow=overflow,fillWidth=fillWidth,reserve=reserve,onVisibleRanges=onVisibleRanges,textAlign=textAlign,trackWholeTextVisibility=trackWholeTextVisibility)
        return
    }
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val uriHandler=LocalUriHandler.current
    val rtl=LocalLayoutDirection.current==LayoutDirection.Rtl
    val style=LocalTextStyle.current.merge(TextStyle(fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,textAlign=textAlign))
    val nativeLineHeight=measurer.measure(AnnotatedString("M"),style=style).let{it.getLineBottom(0)-it.getLineTop(0)}
    var nativeLayout by remember {mutableStateOf<TextLayoutResult?>(null)}
    var coordinates by remember {mutableStateOf<LayoutCoordinates?>(null)}
    var lastVisibility by remember {mutableStateOf<Pair<Set<String>,Boolean>?>(null)}
    val visibilityCallback by rememberUpdatedState(onVisibleRanges)
    fun reportVisibility(){
        val value=immersionVisibleText(nativeLayout,coordinates,trackWholeTextVisibility)
        if(value!=lastVisibility){lastVisibility=value;visibilityCallback?.invoke(value.first,value.second)}
    }
    SideEffect{visibilityCallback?.invoke(lastVisibility?.first?:emptySet(),lastVisibility?.second?:false)}
    DisposableEffect(Unit){onDispose{visibilityCallback?.invoke(emptySet(),false)}}
    val incoming by remember(progress){derivedStateOf{immersionInkPhase(progress()).incoming}}
    BoxWithConstraints(modifier.then(immersionIntrinsicLayout(measurer,listOf(effectiveFrom,effectiveTo)+reserve,style,maxLines,overflow))){
        val available=constraints.maxWidth.coerceAtLeast(1)
        val content=mutableMapOf<String,InlineTextContent>()
        // Parent ellipsis needs physical room beside an atomic placeholder.
        // Children clip at that boundary so the paragraph supplies one ellipsis.
        val ellipsisAllowance=if(maxLines<Int.MAX_VALUE&&overflow==TextOverflow.Ellipsis)
            measurer.measure(AnnotatedString("…"),style=style,softWrap=false).size.width+1 else 0
        val slotLimit=(available-ellipsisAllowance).coerceAtLeast(1)
        val fragmentOverflow=if(ellipsisAllowance>0)TextOverflow.Clip else overflow
        val slotKeys=mutableListOf<String>()
        val placeholderRanges=mutableListOf<AnnotatedString.Range<Placeholder>>()
        val changedKeys=mutableSetOf<String>()
        var wipeGeometry by remember(from,to,reserve,available,fontSize,lineHeight){
            mutableStateOf<ImmersionSharedWipeGeometry?>(null)
        }
        val paragraph=buildAnnotatedString{
            runs.forEachIndexed{index,run->
                if(run.id==null){append(run.forms[if(incoming)1 else 0]);return@forEachIndexed}
                // Split at natural token boundaries without claiming translation
                // word alignment. A long phrase is not an unbreakable giant box.
                val prefix=toAnnotatedString()
                val used=if(prefix.isEmpty())0 else {
                    val shaped=measurer.measure(prefix,style=style,placeholders=placeholderRanges,
                        constraints=Constraints(minWidth=available,maxWidth=available))
                    val caret=shaped.getHorizontalPosition(prefix.length,true)
                    (if(rtl)available-caret else caret).toInt().coerceIn(0,available)
                }
                val firstWidth=(slotLimit-used).coerceAtLeast(1)
                val chunks=run.forms.map{form->immersionAnnotatedChunks(form,slotLimit,firstWidth){value->
                    measurer.measure(immersionDrawText(value),style=style,softWrap=false).size.width
                }}
                val partCount=chunks.maxOf{it.size}
                // A wrapped clause ends at the line edge in every rendition.
                // Put unavoidable reservation at a natural reading boundary,
                // rather than leaving a large hole before the following clause.
                val clauseBoundary=partCount>1&&run.forms.all{it.text.trimEnd().lastOrNull()?.let{last->last in ",;:.!?…"}==true}&&
                    runs.getOrNull(index+1)?.let{it.id==null&&it.forms.any{form->form.text.isNotBlank()}}==true
                repeat(partCount){part->
                    val forms=chunks.map{it.getOrElse(part){AnnotatedString("")}}
                    val natural=forms.map{measurer.measure(immersionDrawText(it),style=style,softWrap=false)}
                    val width=if(clauseBoundary&&part==partCount-1)slotLimit
                        else natural.maxOf{it.size.width}.coerceAtLeast(1).coerceAtMost(slotLimit)
                    // Native paragraph shaping handles an oversized token. Reserve
                    // its full wrapped height in every form instead of clipping
                    // a one-line child or splitting Unicode graphemes ourselves.
                    val measured=forms.map{measurer.measure(immersionDrawText(it),style=style,
                        constraints=Constraints(maxWidth=width),maxLines=maxLines,overflow=fragmentOverflow)}
                    val height=measured.maxOf{it.size.height}.coerceAtLeast(1)
                    val key="shared-$index-$part"
                    slotKeys+=key
                    if(forms[0].text!=forms[1].text)changedKeys+=key
                    val placeholder=Placeholder(with(density){width.toSp()},with(density){height.toSp()},PlaceholderVerticalAlign.TextCenter)
                    content[key]=InlineTextContent(placeholder){
                        fun renderedLink(position:Offset):LinkAnnotation? {
                            val p=progress().coerceIn(0f,1f)
                            val current=if(immersionInkPhase(p).incoming)1 else 0
                            val candidates=if(forms[0].text==forms[1].text)listOf(current)
                                else if(p<=0f)listOf(0)else if(p>=1f)listOf(1)else listOf(0,1)
                            for(index in candidates){
                                val layout=measured[index]
                                val text=layout.layoutInput.text
                                if(text.isEmpty())continue
                                // Native hit lookup returns the nearest insertion
                                // caret, which can be after the glyph being touched.
                                val caret=layout.getOffsetForPosition(position)
                                val offset=listOf(caret,caret-1).firstOrNull{
                                    it in 0 until text.length&&layout.getLineForOffset(it) in 0 until layout.lineCount&&
                                        it<layout.getLineEnd(layout.getLineForOffset(it),visibleEnd=true)&&
                                        layout.getBoundingBox(it).contains(position)
                                }?:continue
                                val line=layout.getLineForOffset(offset)
                                if(line !in 0 until layout.lineCount)continue
                                if(p>0f&&p<1f&&forms[0].text!=forms[1].text){
                                    val mask=wipeGeometry?.mask(key,layout.getLineTop(line),p,rtl)
                                        ?:ImmersionSharedWipeMask(if(rtl)width+48f else -48f,48f)
                                    val incomingCoverage=(if(rtl)(position.x-mask.center+mask.feather)
                                        else(mask.center+mask.feather-position.x))/(2f*mask.feather)
                                    val coverage=if(index==1)incomingCoverage.coerceIn(0f,1f)
                                        else 1f-incomingCoverage.coerceIn(0f,1f)
                                    if(coverage<.5f)continue
                                }
                                val link=text.getLinkAnnotations(offset,offset+1).firstOrNull()?.item?:continue
                                // A phrase's stable clickable tag retains current help
                                // context even while outgoing glyphs are still drawn.
                                return if(link is LinkAnnotation.Clickable)
                                    forms[current].getLinkAnnotations(0,forms[current].length)
                                        .map{it.item}.filterIsInstance<LinkAnnotation.Clickable>()
                                        .firstOrNull{it.tag==link.tag}?:link
                                    else link
                            }
                            return null
                        }
                        val hit by rememberUpdatedState<(Offset)->LinkAnnotation?>(::renderedLink)
                        val dispatch by rememberUpdatedState<(LinkAnnotation)->Unit>{initial->
                            val current=if(immersionInkPhase(progress()).incoming)1 else 0
                            val link=if(initial is LinkAnnotation.Clickable)
                                forms[current].getLinkAnnotations(0,forms[current].length)
                                    .map{it.item}.filterIsInstance<LinkAnnotation.Clickable>()
                                    .firstOrNull{it.tag==initial.tag}?:initial
                                else initial
                            val listener=link.linkInteractionListener
                            if(listener!=null)listener.onClick(link)
                            else if(link is LinkAnnotation.Url)uriHandler.openUri(link.url)
                        }
                        val hasLinks=forms.take(2).any{it.getLinkAnnotations(0,it.length).isNotEmpty()}
                        val interaction=if(!hasLinks)Modifier else Modifier.pointerInput(run.id,part){
                            awaitEachGesture{
                                val down=awaitFirstDown()
                                val intended=hit(down.position)?:return@awaitEachGesture
                                down.consume()
                                val up=waitForUpOrCancellation()?:return@awaitEachGesture
                                val link=hit(up.position)?:intended
                                up.consume()
                                dispatch(link)
                            }
                        }
                        // Parent paragraph owns selection and clipboard order.
                        // A drawable child must not register a second text copy.
                        DisableSelection{
                        BlendImmersionText(forms[0],forms[1],progress,forms[if(incoming)1 else 0],
                            modifier=Modifier.clearAndSetSemantics{}.then(interaction),
                            nativeLinkInteractions=false,
                            fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,
                            onNativeTextLayout=onFragmentLayout,
                            sharedWipeMask={p,top->wipeGeometry?.mask(key,top,p,rtl)
                                ?:ImmersionSharedWipeMask(if(rtl)width+48f else -48f,48f)},
                            maxLines=maxLines,overflow=fragmentOverflow,fillWidth=true,reserve=forms)
                        }
                    }
                    val start=length
                    appendInlineContent(key,forms[if(incoming)1 else 0].text.ifEmpty{
                        if(projection==null)"\u200b" else emptySlots.getOrPut(key){projection.create((listOf(from,to,snapshot)+reserve).map{it.text})}
                    })
                    placeholderRanges+=AnnotatedString.Range(placeholder,start,length)
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
                .semantics{customActions=actions;if(projection!=null)text=visible})
    }
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
