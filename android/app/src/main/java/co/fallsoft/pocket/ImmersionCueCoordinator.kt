package co.fallsoft.pocket

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private fun immersionMonotonicMs()=System.nanoTime()/1000000L
private data class ImmersionCueRequest(val body:Boolean,val returningToTarget:Boolean)

/** One lease, then a quiet interval. Bodies precede target returns, then source controls. */
internal class ImmersionCueQueue(private val clock:()->Long=::immersionMonotonicMs,private val quietGapMs:Long=4200L) {
    private val waiting=linkedMapOf<String,ImmersionCueRequest>()
    private var quietUntil=clock()
    var active:String?=null;private set
    var serial:Long=0;private set
    fun request(owner:String,body:Boolean,returningToTarget:Boolean=false){if(active!=owner)waiting[owner]=ImmersionCueRequest(body,returningToTarget);refresh()}
    fun cancel(owner:String,completed:Boolean=false,cooldownMs:Long=quietGapMs){
        waiting.remove(owner)
        if(active==owner){active=null;if(completed)quietUntil=clock()+cooldownMs.coerceAtLeast(0)}
        refresh()
    }
    fun pending()=waiting.keys.toList()
    fun quietRemainingMs()=(quietUntil-clock()).coerceAtLeast(0)
    fun refresh(){
        if(active!=null||quietRemainingMs()>0)return
        val next=waiting.entries.firstOrNull{it.value.body}
            ?:waiting.entries.firstOrNull{it.value.returningToTarget}
            ?:waiting.entries.firstOrNull()?:return
        active=next.key;waiting.remove(next.key);serial++
    }
}
internal class ImmersionCueBroker {
    private val queue=ImmersionCueQueue()
    private val changes=MutableStateFlow(0L)
    suspend fun acquire(owner:String,body:Boolean,returningToTarget:Boolean=false):Long{
        synchronized(queue){queue.request(owner,body,returningToTarget);changes.value++}
        while(true){
            var granted:Long?=null;var waitMs=0L;var observed=0L
            synchronized(queue){
                val before=queue.serial;queue.refresh();if(queue.serial!=before)changes.value++
                if(queue.active==owner)granted=queue.serial
                waitMs=if(queue.active==null)queue.quietRemainingMs()else 0L
                observed=changes.value
            }
            granted?.let{return it}
            if(waitMs>0)withTimeoutOrNull(waitMs){changes.first{it!=observed}}
            else changes.first{it!=observed}
        }
    }
    fun release(owner:String,completed:Boolean=false,cooldownMs:Long=4200L){synchronized(queue){queue.cancel(owner,completed,cooldownMs);changes.value++}}
}
internal object ImmersionCueCoordinator {
    internal val broker=ImmersionCueBroker()
    suspend fun acquire(owner:String,body:Boolean,returningToTarget:Boolean=false)=broker.acquire(owner,body,returningToTarget)
    fun release(owner:String)=broker.release(owner)
}

/** Cancellation never commits a missed handoff or starts a completed-cue cooldown. */
internal suspend fun runImmersionCue(owner:String,body:Boolean,onBegin:suspend (Long)->Unit,onHandoff:()->Unit,onFinish:()->Unit,animate:suspend ()->Unit,handoffMs:Long=IMMERSION_HANDOFF_AT_MS,awaitHandoff:suspend ()->Unit={kotlinx.coroutines.delay(handoffMs)},returningToTarget:Boolean=false,cooldownMs:Long=4200L,broker:ImmersionCueBroker=ImmersionCueCoordinator.broker){
    var completed=false
    try{
        val serial=broker.acquire(owner,body,returningToTarget)
        onBegin(serial)
        kotlinx.coroutines.coroutineScope{
            launch { animate() }
            awaitHandoff()
            onHandoff()
        }
        completed=true
    }finally{onFinish();broker.release(owner,completed,cooldownMs)}
}
