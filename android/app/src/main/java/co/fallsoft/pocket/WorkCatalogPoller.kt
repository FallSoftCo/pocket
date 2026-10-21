package co.fallsoft.pocket

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Read scheduling for the combined catalog. Each source publishes separately:
 * a hung host cannot hold back another host, nor a room lane its session lane.
 * Call from one owning dispatcher. There are no models, commands or retries here.
 */
internal class WorkCatalogPoller<T>(
    private val scope:CoroutineScope,private val state:WorkCatalogState<T>,
    private val publish:(List<WorkSourceSnapshot<T>>)->Unit,
    private val clock:()->Long=System::currentTimeMillis
) {
    private val jobs=mutableMapOf<WorkSourceKey,Job>()
    private var readers=emptyMap<WorkSourceKey,suspend ()->List<WorkEntry<T>>>()
    private val permits=Semaphore(4)
    fun reconcile(next:Map<WorkSourceKey,suspend ()->List<WorkEntry<T>>>) {
        jobs.keys.filter{it !in next}.toList().forEach{jobs.remove(it)?.cancel()}
        readers=next.toMap();state.reconcile(next.keys.toList());publish(state.snapshots())
    }
    fun refresh() {
        readers.forEach{(key,read)->
            if(jobs[key]?.isActive==true)return@forEach
            val generation=state.begin(key)?:return@forEach
            publish(state.snapshots())
            jobs[key]=scope.launch{
                try{
                    val entries=permits.withPermit{read()}
                    if(state.accept(key,generation,entries,clock()))publish(state.snapshots())
                }catch(cancelled:CancellationException){
                    if(state.cancelRead(key,generation))publish(state.snapshots())
                    throw cancelled
                }catch(error:Exception){
                    if(state.fail(key,generation,error.message?:"Catalog unavailable"))publish(state.snapshots())
                }
            }
        }
    }
    fun stop(){jobs.values.forEach{it.cancel()};jobs.clear()}
}
