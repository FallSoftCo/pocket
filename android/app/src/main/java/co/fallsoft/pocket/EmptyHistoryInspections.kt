package co.fallsoft.pocket

/** Read availability observations, scoped to pairing/environment and session.
 * Generations are never consumed by a join of an older shared request.
 */
internal class EmptyHistoryInspections<K> {
    private val generations=java.util.concurrent.ConcurrentHashMap<K,Long>()
    fun generation(key:K)=generations[key]?:0L
    fun observe(key:K){generations.compute(key){_,old->(old?:0L)+1L}}
    fun current(key:K,started:Long)=generation(key)==started
    fun clears(key:K,code:String?,refusedAt:Long)=code=="THREAD_NOT_MATERIALIZED"&&generation(key)>refusedAt
    suspend fun <T> await(key:K,started:Long,requested:Long,followupAllowed:Boolean,isEmpty:(Throwable)->Boolean,
                         read:suspend()->T,reinspect:suspend()->T):T {
        try{return read()}
        catch(error:Exception){
            if(!followupAllowed||!isEmpty(error)||requested<=started||requested>generation(key))throw error
            // Exactly one bounded inspection after newer evidence. The caller
            // disables another follow-up; this never starts work or a timer.
            return reinspect()
        }
    }
}
