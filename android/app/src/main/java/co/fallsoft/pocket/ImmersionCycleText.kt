package co.fallsoft.pocket

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class ImmersionCycleRender(
    val plan:ImmersionPresentation,val alpha:(ImmersionSpan)->Float={1f},
    val incomingPlan:ImmersionPresentation?=null,val cueProgress:()->Float={1f},
    val cueActive:Boolean=false,val cueId:Long=0L
)
internal fun immersionPhraseIdentity(span:ImmersionSpan)="${span.start}:${span.end}:${span.source}:${span.target}"

/** Exact actual snapshots; late replacements are staged, with one screen-wide cue at a time. */
@Composable internal fun rememberImmersionCycle(plan:ImmersionPresentation,readingKey:ImmersionReadingKey,selected:ImmersionSpan?,enabled:Boolean):ImmersionCycleRender {
    val ready=immersionMotionReady()
    val identity=readingKey.copy(targetHash="")
    val owner=remember(identity){"body:"+java.util.UUID.randomUUID()}
    val originals=remember(identity){mutableStateMapOf<String,Boolean>().apply{plan.spans.forEach{put(immersionPhraseIdentity(it),false)}}}
    val hydration=remember(identity){mutableStateListOf<String>()}
    var cursor by remember(identity){mutableIntStateOf(0)}
    val keys=plan.spans.map(::immersionPhraseIdentity)
    // A new cache plan defaults to the exact source already on screen before bookkeeping runs.
    val actualOriginals=immersionKnownOriginals(plan.spans,originals)
    SideEffect{
        keys.forEach{key->if(key !in originals){originals[key]=true;hydration.add(key)}}
        originals.keys.toList().filter{it !in keys}.forEach{originals.remove(it)}
        hydration.removeAll{it !in keys}
    }
    val selectedIndex=plan.spans.indexOf(selected)
    val effective=(if(enabled)actualOriginals else emptySet())+listOf(selectedIndex).filter{it>=0}
    val frozen=remember(readingKey,PocketSpeech.displayedOwner,PocketSpeech.displayedText){
        if(PocketSpeech.displayedOwner==null)null else immersionCapturedOriginals(plan,PocketSpeech.displayedText)
    }
    val rendered=immersionCyclePlan(plan,frozen?:effective)
    var incoming by remember(identity){mutableStateOf<ImmersionPresentation?>(null)}
    var cueActive by remember(identity){mutableStateOf(false)}
    var cueId by remember(identity){mutableLongStateOf(0L)}
    val progress=remember(identity){Animatable(1f)}
    LaunchedEffect(identity,readingKey.targetHash,enabled,ready,PocketSpeech.displayedOwner,selectedIndex){
        if(!enabled||!ready||PocketSpeech.displayedOwner!=null||selectedIndex>=0||plan.spans.isEmpty())return@LaunchedEffect
        suspend fun cue(index:Int,original:Boolean){
            runImmersionCue(owner,true,onBegin={serial->
                cueId=serial
                val current=immersionKnownOriginals(plan.spans,originals)
                incoming=immersionCyclePlan(plan,immersionCueOriginals(current,index,original))
                progress.snapTo(0f)
                cueActive=true
            },onHandoff={originals[keys[index]]=original},onFinish={cueActive=false;incoming=null},animate={
                progress.animateTo(1f,tween(IMMERSION_HANDOFF_DURATION_MS.toInt(),easing=LinearEasing))
            },awaitHandoff={snapshotFlow{progress.value}.first{it>=IMMERSION_HANDOFF_AT_MS.toFloat()/IMMERSION_HANDOFF_DURATION_MS}})
        }
        // Every resume receives a fresh reading hold, never a backlog of overdue handoffs.
        val timing=immersionCycleTiming(identity.toString(),plan.source,plan.text)
        delay((if(hydration.isNotEmpty())timing.sourceMs else timing.targetMs)+timing.staggerMs%6000L)
        while(isActive){
            val pending=hydration.firstOrNull()
            val index=if(pending!=null)keys.indexOf(pending)else Math.floorMod(cursor,plan.spans.size)
            if(index<0){pending?.let{hydration.remove(it)};continue}
            val span=plan.spans[index]
            val local=immersionCycleTiming(identity.toString()+span.start,span.source,span.target)
            if(pending!=null){cue(index,false);hydration.remove(pending);delay(local.targetMs)}
            else {
                if(originals[keys[index]]!=true)cue(index,true)
                delay(local.sourceMs)
                cue(index,false)
                delay(local.targetMs)
                cursor=(index+1)%plan.spans.size
            }
        }
    }
    return ImmersionCycleRender(rendered,incomingPlan=incoming,cueProgress={progress.value},cueActive=cueActive,cueId=cueId)
}

internal fun immersionKnownOriginals(spans:List<ImmersionSpan>,known:Map<String,Boolean>):Set<Int> = spans.indices.filter{known[immersionPhraseIdentity(spans[it])]?:true}.toSet()
internal fun immersionCueOriginals(current:Set<Int>,index:Int,original:Boolean):Set<Int> = if(original)current+index else current-index
