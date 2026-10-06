package co.fallsoft.pocket

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One cue lease. Bodies precede queued controls; an already-visible cue is not preempted. */
internal class ImmersionCueQueue {
    private val waiting=linkedMapOf<String,Boolean>()
    var active:String?=null;private set
    var serial:Long=0;private set
    fun request(owner:String,body:Boolean){if(active!=owner)waiting[owner]=body;promote()}
    fun cancel(owner:String){waiting.remove(owner);if(active==owner)active=null;promote()}
    fun pending()=waiting.keys.toList()
    private fun promote(){if(active!=null)return;val next=waiting.entries.firstOrNull{it.value}?:waiting.entries.firstOrNull()?:return;active=next.key;waiting.remove(next.key);serial++}
}
internal object ImmersionCueCoordinator {
    private val queue=ImmersionCueQueue()
    private val changes=MutableStateFlow(0L)
    suspend fun acquire(owner:String,body:Boolean):Long{
        synchronized(queue){queue.request(owner,body);changes.value++}
        changes.first{synchronized(queue){queue.active==owner}}
        return synchronized(queue){queue.serial}
    }
    fun release(owner:String){synchronized(queue){queue.cancel(owner);changes.value++}}
}

/** Cancellation releases the lease; it never runs a missed semantic handoff from finally. */
internal suspend fun runImmersionCue(owner:String,body:Boolean,onBegin:suspend (Long)->Unit,onHandoff:()->Unit,onFinish:()->Unit,animate:suspend ()->Unit,handoffMs:Long=IMMERSION_HANDOFF_AT_MS,awaitHandoff:suspend ()->Unit={kotlinx.coroutines.delay(handoffMs)}){
    try{
        val serial=ImmersionCueCoordinator.acquire(owner,body)
        onBegin(serial)
        kotlinx.coroutines.coroutineScope{
            launch { animate() }
            awaitHandoff()
            onHandoff()
        }
    }finally{onFinish();ImmersionCueCoordinator.release(owner)}
}
