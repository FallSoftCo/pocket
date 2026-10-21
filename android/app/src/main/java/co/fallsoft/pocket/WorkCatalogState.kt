package co.fallsoft.pocket

/** A visible item is never identified by its server ID alone. */
internal enum class WorkKind { SESSION, ROOM }
internal data class WorkSourceKey(val environment:String,val owner:String,val backend:String="codex")
internal data class WorkItemKey(val source:WorkSourceKey,val kind:WorkKind,val authority:String,val id:String)
internal data class WorkEntry<T>(val key:WorkItemKey,val workAt:Long,val value:T)
internal data class WorkSourceSnapshot<T>(
    val key:WorkSourceKey,val generation:Long,val entries:List<WorkEntry<T>> = emptyList(),
    val observedAt:Long?=null,val refreshing:Boolean=false,val error:String?=null
) {
    val available get()=observedAt!=null&&error==null
}

/**
 * Source-incarnation reducer. Observation time is freshness, never work recency.
 * A late response cannot resurrect credentials that have been removed/replaced.
 * Entries retained through an outage remain inspectable with unavailable status.
 */
internal class WorkCatalogState<T> {
    private var serial=0L
    private val sources=linkedMapOf<WorkSourceKey,WorkSourceSnapshot<T>>()
    fun reconcile(keys:List<WorkSourceKey>):List<WorkSourceSnapshot<T>> {
        require(keys.distinct().size==keys.size)
        sources.keys.retainAll(keys.toSet())
        keys.forEach{key->sources.getOrPut(key){WorkSourceSnapshot(key,++serial)}}
        return snapshots()
    }
    fun snapshots():List<WorkSourceSnapshot<T>> = sources.values.toList()
    fun begin(key:WorkSourceKey):Long? {
        val old=sources[key]?:return null
        if(old.refreshing)return null
        val generation=++serial
        sources[key]=old.copy(generation=generation,refreshing=true)
        return generation
    }
    fun restore(key:WorkSourceKey,entries:List<WorkEntry<T>>):Boolean {
        val old=sources[key]?:return false
        if(old.observedAt!=null||old.refreshing||old.entries.isNotEmpty())return false
        validate(key,entries)
        sources[key]=old.copy(entries=entries,error="Saved work · awaiting connection")
        return true
    }
    fun accept(key:WorkSourceKey,generation:Long,entries:List<WorkEntry<T>>,observedAt:Long):Boolean {
        val old=sources[key]?:return false
        if(old.generation!=generation||!old.refreshing)return false
        require(observedAt>=0)
        validate(key,entries)
        sources[key]=old.copy(entries=entries,observedAt=observedAt,refreshing=false,error=null)
        return true
    }
    fun cancelRead(key:WorkSourceKey,generation:Long):Boolean {
        val old=sources[key]?:return false
        if(old.generation!=generation||!old.refreshing)return false
        sources[key]=old.copy(refreshing=false)
        return true
    }
    fun fail(key:WorkSourceKey,generation:Long,error:String):Boolean {
        val old=sources[key]?:return false
        if(old.generation!=generation||!old.refreshing)return false
        sources[key]=old.copy(refreshing=false,error=error.ifBlank{"Connection unavailable"})
        return true
    }
    fun ordered(previous:List<WorkItemKey>):List<WorkEntry<T>> {
        val rank=previous.withIndex().associate{it.value to it.index}
        return sources.values.flatMap{it.entries}.sortedWith(
            compareByDescending<WorkEntry<T>>{it.workAt}.thenBy{rank[it.key]?:Int.MAX_VALUE}
                .thenBy{it.key.source.environment}.thenBy{it.key.kind.name}.thenBy{it.key.id})
    }
    private fun validate(key:WorkSourceKey,entries:List<WorkEntry<T>>) {
        require(entries.all{it.key.source==key&&it.key.id.isNotBlank()&&it.workAt>=0})
        require(entries.map{it.key}.distinct().size==entries.size)
    }
}
