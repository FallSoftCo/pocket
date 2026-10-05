package co.fallsoft.pocket

import android.os.SystemClock
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max

internal class ImmersionCycleRender(val plan:ImmersionPresentation,val alpha:(ImmersionSpan)->Float)

/** Timers live only in visible composition; alpha reads stay in graphics layers, not parent state. */
@Composable internal fun rememberImmersionCycle(plan:ImmersionPresentation,readingKey:ImmersionReadingKey,selected:ImmersionSpan?,enabled:Boolean):ImmersionCycleRender {
    val context=LocalContext.current
    val owner=remember(context){var c=context;while(c is ContextWrapper&&c !is Activity)c=c.baseContext;c as? LifecycleOwner}
    val lifecycle=owner?.lifecycle
    var active by remember(lifecycle){mutableStateOf(lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true)}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,_->active=lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true};lifecycle?.addObserver(observer);onDispose{lifecycle?.removeObserver(observer)}}
    val animated=motionAllowed()
    val originals=remember(readingKey){mutableStateMapOf<Int,Boolean>().apply{plan.spans.forEachIndexed{index,span->put(index,immersionCyclePhase(SystemClock.elapsedRealtime(),immersionCycleTiming(readingKey.toString()+":"+span.start,span.source,span.target)).original)}}}
    val alphas=remember(readingKey){mutableMapOf<Int,Animatable<Float,androidx.compose.animation.core.AnimationVector1D>>()}
    val selectedIndex=plan.spans.indexOf(selected)
    val effective=originals.filterValues{it}.keys.toSet()+listOf(selectedIndex).filter{it>=0}
    val proposed=if(enabled)immersionCyclePlan(plan,effective)else immersionCyclePlan(plan,if(selectedIndex>=0)setOf(selectedIndex)else emptySet())
    val frozen=remember(readingKey,PocketSpeech.displayedOwner,PocketSpeech.displayedText){
        val text=PocketSpeech.displayedText
        if(PocketSpeech.displayedOwner==null)null else immersionCapturedOriginals(plan,text)
    }
    val rendered=if(frozen!=null)immersionCyclePlan(plan,frozen)else proposed
    val speaking=frozen!=null
    plan.spans.forEachIndexed { index,span ->
        val alpha=alphas.getOrPut(index){Animatable(1f)}
        val timing=remember(readingKey,span.start,span.end,span.source,span.target){immersionCycleTiming(readingKey.toString()+":"+span.start,span.source,span.target)}
        LaunchedEffect(readingKey,index,timing,enabled,active,animated,selectedIndex==index,speaking){
            alpha.snapTo(1f)
            if(!enabled||!active||selectedIndex==index||speaking)return@LaunchedEffect
            while(isActive){
                val phase=immersionCyclePhase(SystemClock.elapsedRealtime(),timing)
                originals[index]=phase.original
                delay((phase.untilChangeMs-75L).coerceAtLeast(1L))
                if(animated)alpha.animateTo(0f,tween(75))else delay(75)
                originals[index]=immersionCyclePhase(SystemClock.elapsedRealtime(),timing).original
                if(animated)alpha.animateTo(1f,tween(75))
            }
        }
    }
    return ImmersionCycleRender(rendered){span->alphas[plan.spans.indexOfFirst{it.start==span.start&&it.end==span.end&&it.source==span.source}]?.value?:1f}
}

/** Natural phrases reserve their maximum geometry; only one variant is ever composed. */
@Composable internal fun ReservedImmersionText(content:AnnotatedString,plan:ImmersionPresentation,alpha:(ImmersionSpan)->Float,fontSize:TextUnit,lineHeight:TextUnit,color:Color,fontWeight:FontWeight?,maxLines:Int,overflow:TextOverflow){
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val baseStyle=LocalTextStyle.current
    BoxWithConstraints(Modifier.fillMaxWidth()){
        val widthPx=with(density){maxWidth.roundToPx()}.coerceAtLeast(1)
        val inline=linkedMapOf<String,InlineTextContent>()
        val built=buildAnnotatedString {
            var cursor=0
            val links=content.getStringAnnotations("immersion-reserve",0,content.length).sortedBy{it.start}
            for(link in links){
                val tag=link.item
                val span=plan.spans.firstOrNull{tag=="${it.start}:${it.end}"}?:continue
                val visible=content.text.substring(link.start,link.end)
                val source=MarkdownContent.preview(span.source).text;val target=MarkdownContent.preview(span.target).text
                if(link.start<cursor||visible!=source&&visible!=target)continue
                append(content.subSequence(cursor,link.start))
                val inherited=content.subSequence(link.start,link.end).spanStyles.map{it.item}
                val weight=(inherited.mapNotNull{it.fontWeight}+listOfNotNull(fontWeight,FontWeight.Medium)).maxBy{it.weight}
                val italic=if(inherited.any{it.fontStyle==FontStyle.Italic})FontStyle.Italic else FontStyle.Normal
                val style=baseStyle.merge(TextStyle(fontSize=fontSize,lineHeight=lineHeight,fontWeight=weight,fontStyle=italic))
                fun naturalWidth(text:String)=if(text.isEmpty())0 else measurer.measure(AnnotatedString(text),style=style,softWrap=false).size.width
                fun measure(text:String)=measurer.measure(AnnotatedString(text.ifEmpty{" "}),style=style,constraints=Constraints(maxWidth=widthPx))
                val words=immersionReserveChunks(source,target,widthPx,::naturalWidth)
                var wordOffset=link.start
                words.forEachIndexed{index,word->
                    val shown=if(visible==source)word.source else word.target
                    val styled=if(shown.isNotEmpty())content.subSequence(wordOffset,wordOffset+shown.length)else AnnotatedString("")
                    wordOffset+=shown.length
                    val a=measure(word.source);val b=measure(word.target)
                    val slotWidth=max(a.size.width,b.size.width).coerceIn(1,widthPx)
                    val slotHeight=max(a.size.height,b.size.height).coerceAtLeast(1)
                    val name="${span.start}:${span.end}:$index"
                    val start=length
                    appendInlineContent(name,shown.ifEmpty{"\u200B"})
                    // Parent owns link interaction/accessibility; decorative child has no duplicate text.
                    styled.getLinkAnnotations(0,styled.length).forEach{annotation->when(val link=annotation.item){is LinkAnnotation.Url->addLink(link,start,length);is LinkAnnotation.Clickable->addLink(link,start,length)}}
                    inline[name]=InlineTextContent(Placeholder(with(density){slotWidth.toSp()},with(density){slotHeight.toSp()},PlaceholderVerticalAlign.TextTop)){
                        Text(buildAnnotatedString{append(styled.text);styled.spanStyles.forEach{addStyle(it.item,it.start,it.end)};styled.getLinkAnnotations(0,styled.length).forEach{link->if(link.item is LinkAnnotation.Url)addStyle(SpanStyle(textDecoration=androidx.compose.ui.text.style.TextDecoration.Underline),link.start,link.end)}},fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,modifier=Modifier.fillMaxSize().graphicsLayer{this.alpha=alpha(span)}.clearAndSetSemantics{})
                    }
                }
                cursor=link.end
            }
            append(content.subSequence(cursor,content.length))
        }
        Text(built,inlineContent=inline,fontSize=fontSize,lineHeight=lineHeight,color=color,fontWeight=fontWeight,maxLines=maxLines,overflow=overflow,modifier=Modifier.fillMaxWidth().semantics{text=content})
    }
}
