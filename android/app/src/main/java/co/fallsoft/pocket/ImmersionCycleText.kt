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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class ImmersionCycleRender(
    val plan:ImmersionPresentation,val alpha:(ImmersionSpan)->Float={1f},
    val incomingPlan:ImmersionPresentation?=null,val cueProgress:()->Float={1f},
    val cueActive:Boolean=false,val cueId:Long=0L,
    val reportVisible:(String,Set<String>)->Unit={_,_->}
)
internal fun immersionPhraseIdentity(span:ImmersionSpan)="${span.start}:${span.end}:${span.source}:${span.target}"

/** Exact actual snapshots; late replacements are staged, with one screen-wide cue at a time. */
@Composable internal fun rememberImmersionCycle(plan:ImmersionPresentation,readingKey:ImmersionReadingKey,selected:ImmersionSpan?,enabled:Boolean):ImmersionCycleRender {
    val ready=immersionMotionReady()
    val identity=readingKey.copy(targetHash="")
    val deadline=remember(identity){ImmersionCueDeadline()}
    val owner=remember(identity){"body:"+java.util.UUID.randomUUID()}
    val parts=remember(identity){mutableStateMapOf<String,Set<String>>()}
    val visible by remember(identity){derivedStateOf{immersionVisibleUnion(parts)}}
    val hasVisible=plan.spans.any{spanId(it) in visible}
    val reportVisible=remember(identity){ {part:String,ids:Set<String>->if(ids.isEmpty())parts.remove(part)else if(parts[part]!=ids)parts[part]=ids;Unit} }
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
    LaunchedEffect(identity,readingKey.targetHash,enabled,ready,hasVisible,PocketSpeech.displayedOwner,selectedIndex){
        if(!enabled||!ready||!hasVisible||PocketSpeech.displayedOwner!=null||selectedIndex>=0||plan.spans.isEmpty())return@LaunchedEffect
        suspend fun cue(index:Int,original:Boolean):Boolean=immersionWhileVisible(
            eligible={spanId(plan.spans[index]) in visible},
            awaitHidden={snapshotFlow{spanId(plan.spans[index]) in visible}.first{!it}}
        ){
            runImmersionCue(owner,true,onBegin={serial->
                if(spanId(plan.spans[index]) !in visible)throw ImmersionCueHidden()
                cueId=serial
                val current=immersionKnownOriginals(plan.spans,originals)
                incoming=immersionCyclePlan(plan,immersionCueOriginals(current,index,original))
                progress.snapTo(0f)
                cueActive=true
            },onHandoff={
                originals[keys[index]]=original
                val phase=immersionCycleTiming(identity.toString()+plan.spans[index].start,plan.spans[index].source,plan.spans[index].target)
                deadline.hold(if(original)phase.sourceMs else phase.targetMs)
            },onFinish={cueActive=false;incoming=null},animate={
                progress.animateTo(1f,tween(IMMERSION_HANDOFF_DURATION_MS.toInt(),easing=LinearEasing))
            },awaitHandoff={snapshotFlow{progress.value}.first{it>=IMMERSION_HANDOFF_AT_MS.toFloat()/IMMERSION_HANDOFF_DURATION_MS}})
        }
        // Preserve the due time through touch/keyboard pauses. A resumed overdue owner
        // gets a brief breath, and only one cue: no catch-up loop or frame ticker.
        val timing=immersionVisibleTiming(identity.toString(),plan.spans,cursor,visible)?:return@LaunchedEffect
        delay(deadline.waitMs((if(hydration.isNotEmpty())timing.sourceMs else timing.targetMs)+timing.staggerMs%1500L))
        while(isActive){
            val pending=hydration.firstOrNull{key->val index=keys.indexOf(key);index>=0&&spanId(plan.spans[index]) in visible}
            val index=if(pending!=null)keys.indexOf(pending)else immersionNextVisible(plan.spans,cursor,visible)
            if(index<0){snapshotFlow{visible}.first{it.isNotEmpty()};deadline.hold(timing.targetMs);delay(timing.targetMs);continue}
            val span=plan.spans[index]
            val local=immersionCycleTiming(identity.toString()+span.start,span.source,span.target)
            if(pending!=null){if(cue(index,false))hydration.remove(pending);deadline.hold(local.targetMs);delay(local.targetMs)}
            else {
                if(immersionNeedsSourceHandoff(originals[keys[index]]==true)){
                    if(!cue(index,true)){deadline.hold(timing.targetMs);delay(timing.targetMs);continue}
                    deadline.hold(local.sourceMs);delay(local.sourceMs)
                }
                // A resumed source phase already consumed its remaining deadline above.
                // It returns directly to target instead of scheduling a second source hold.
                if(!cue(index,false)){deadline.hold(timing.targetMs);delay(timing.targetMs);continue}
                deadline.hold(local.targetMs);delay(local.targetMs)
                cursor=(index+1)%plan.spans.size
            }
        }
    }
    return ImmersionCycleRender(rendered,incomingPlan=incoming,cueProgress={progress.value},cueActive=cueActive,cueId=cueId,reportVisible=reportVisible)
}

internal fun immersionKnownOriginals(spans:List<ImmersionSpan>,known:Map<String,Boolean>):Set<Int> = spans.indices.filter{known[immersionPhraseIdentity(spans[it])]?:true}.toSet()
internal fun immersionCueOriginals(current:Set<Int>,index:Int,original:Boolean):Set<Int> = if(original)current+index else current-index

private fun spanId(span:ImmersionSpan)="${span.start}:${span.end}"
internal fun immersionVisibleUnion(parts:Map<String,Set<String>>):Set<String> = parts.values.flatMap{it}.toSet()
internal fun immersionNextVisible(spans:List<ImmersionSpan>,cursor:Int,visible:Set<String>):Int = spans.indices.map{Math.floorMod(cursor+it,spans.size)}.firstOrNull{spanId(spans[it]) in visible}?:-1
internal class ImmersionCueHidden:CancellationException("Cue is no longer visible")
internal suspend fun immersionWhileVisible(eligible:()->Boolean,awaitHidden:suspend ()->Unit,block:suspend ()->Unit):Boolean{
    if(!eligible())return false
    return try{coroutineScope{
        val scope=this
        val watcher=launch{awaitHidden();scope.cancel(ImmersionCueHidden())}
        try{block()}finally{watcher.cancel()}
    };true}catch(hidden:CancellationException){currentCoroutineContext().ensureActive();false}
}

/** After the preserved reading deadline, an already-source phrase returns directly. */
internal fun immersionNeedsSourceHandoff(alreadyOriginal:Boolean)=!alreadyOriginal
