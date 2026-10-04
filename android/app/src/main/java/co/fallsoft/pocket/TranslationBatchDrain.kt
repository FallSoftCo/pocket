package co.fallsoft.pocket

/** Keep draining visible updates that arrive while the previous request is suspended. */
internal suspend fun <T> drainTranslationBatches(
    isCurrent:()->Boolean,
    coalesce:suspend ()->Unit,
    nextBatch:()->List<T>,
    submit:suspend (List<T>)->Unit
) {
    while(isCurrent()) {
        coalesce()
        if(!isCurrent())return
        val batch=nextBatch()
        if(batch.isEmpty())return
        submit(batch)
    }
}
