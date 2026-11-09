package co.fallsoft.pocket

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

/** Intrinsic phrase cells: fixed native placeholders, ordinary glyph sizes, no frame loop. */
@Composable internal fun StableImmersionPhrases(
    forms:List<AnnotatedString>,phase:Int,modifier:Modifier=Modifier,color:Color=Paper,
    fontSize:TextUnit=17.sp,lineHeight:TextUnit=26.sp,fontWeight:FontWeight?=null,
    maxLines:Int=Int.MAX_VALUE,overflow:TextOverflow=TextOverflow.Clip,
    textAlign:TextAlign=TextAlign.Start,phraseTaps:Boolean=true,
    onTextLayout:(TextLayoutResult)->Unit={}
){
    val runs=remember(forms){requireNotNull(immersionSharedRuns(forms))}
    val style=LocalTextStyle.current.merge(TextStyle(color=color,fontSize=fontSize,lineHeight=lineHeight,fontWeight=fontWeight,textAlign=textAlign))
    val measurer=rememberTextMeasurer(cacheSize=128);val density=LocalDensity.current
    val inputMode=LocalInputModeManager.current
    val overrides=remember(forms){mutableStateMapOf<String,Int>()}
    LaunchedEffect(phase){overrides.clear()}
    BoxWithConstraints(modifier.then(immersionIntrinsicLayout(measurer,forms,style,maxLines,overflow))){
        val available=constraints.maxWidth.takeIf{it!=Constraints.Infinity}?:with(density){600.dp.roundToPx()}
        val pad=with(density){4.dp.roundToPx()}
        val innerVertical=with(density){1.dp.roundToPx()}
        val gutter=if(phraseTaps)2.dp else 0.dp
        val gutterPixels=with(density){gutter.roundToPx()}
        val dimensions=remember(runs,available,style,density,gutterPixels,innerVertical){runs.associate{run->
            val layouts=run.forms.map{measurer.measure(it,style=style,constraints=Constraints(maxWidth=(available-pad*2).coerceAtLeast(1)))}
            run.id to (layouts.maxOf{it.size.width}.plus(pad*2).coerceAtMost(available).coerceAtLeast(1) to layouts.maxOf{it.size.height}.plus((gutterPixels+innerVertical)*2).coerceAtLeast(1))
        }}
        val inline=mutableMapOf<String,InlineTextContent>()
        val text=buildAnnotatedString{
            for(run in runs){
                if(run.id==null){append(run.forms[phase]);continue}
                val id=requireNotNull(run.id);val chosen=overrides[id]?:phase;val raw=run.forms[chosen]
                val shown=buildAnnotatedString{
                    append(raw.text);raw.spanStyles.forEach{addStyle(it.item,it.start,it.end)};raw.paragraphStyles.forEach{addStyle(it.item,it.start,it.end)}
                    raw.getStringAnnotations(0,raw.length).forEach{addStringAnnotation(it.tag,it.item,it.start,it.end)}
                    raw.getLinkAnnotations(0,raw.length).filterNot{(it.item as? LinkAnnotation.Clickable)?.tag?.startsWith("immersion:")==true}.forEach{range->when(val link=range.item){is LinkAnnotation.Url->addLink(link,range.start,range.end);is LinkAnnotation.Clickable->addLink(link,range.start,range.end)}}
                }
                val (width,height)=requireNotNull(dimensions[id]);val start=length
                appendInlineContent(id,shown.text.ifEmpty{" "});addStringAnnotation("immersion-reserve",id,start,length)
                // Compose Android 1.9.3 PlaceholderSpan scales SP linearly by
                // density * fontScale, unlike the nonlinear conversion used for
                // glyphs. These dimensions are already measured in pixels with
                // accessible glyph scaling applied; invert the placeholder's
                // conversion, not Density.toSp(), to preserve that exact reserve.
                val placeholderScale=density.density*density.fontScale
                inline[id]=InlineTextContent(Placeholder((width/placeholderScale).sp,(height/placeholderScale).sp,PlaceholderVerticalAlign.TextBottom)){
                    val linked=shown.getLinkAnnotations(0,shown.length).isNotEmpty()
                    val interact=phraseTaps&&!linked&&run.forms.map{it.text}.distinct().size>1
                    val interactions=remember{MutableInteractionSource()}
                    val focused by interactions.collectIsFocusedAsState()
                    val keyboardFocus=interact&&focused&&inputMode.inputMode==InputMode.Keyboard
                    val shape=RoundedCornerShape(3.dp)
                    val action=if(interact)Modifier.clickable(interactionSource=interactions,indication=LocalIndication.current){overrides[id]=(1..forms.size).map{(chosen+it)%forms.size}.first{run.forms[it].text!=shown.text}} else Modifier
                    // A quiet inline highlight keeps grammatical groups legible
                    // without outlining every phrase. Only keyboard focus has a
                    // ring; native indication supplies touch feedback. Neither
                    // interaction nor language phase changes reserved geometry.
                    val wash=if(phraseTaps)color.copy(alpha=if(keyboardFocus).14f else .06f)else Color.Transparent
                    val focusRing=if(keyboardFocus)Modifier.border(1.dp,color.copy(alpha=.7f),shape)else Modifier
                    DisableSelection{
                        Box(Modifier.fillMaxSize().padding(vertical=gutter).background(wash,shape).then(focusRing).then(action).padding(horizontal=4.dp,vertical=1.dp).semantics{
                            if(interact){role=Role.Button;contentDescription=shown.text}else if(!linked)hideFromAccessibility()
                        }){Text(shown,style=style,modifier=Modifier.fillMaxSize(),overflow=TextOverflow.Clip)}
                    }
                }
            }
        }
        // A fixed paragraph line-height can override inline placeholder metrics and
        // overlap reserved cells. Natural line boxes honor each row's measured
        // phrase height, including its gutter; inner text retains its leading.
        val paragraphStyle=if(phraseTaps&&inline.isNotEmpty())style.copy(lineHeight=TextUnit.Unspecified)else style
        Text(text,inlineContent=inline,style=paragraphStyle,maxLines=maxLines,overflow=overflow,onTextLayout=onTextLayout)
    }
}
