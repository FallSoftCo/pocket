package co.fallsoft.pocket

/** Retain read rows, but place newly discovered rows in their canonical page order. */
internal fun <T> mergeTranscriptPage(current:List<T>,incoming:List<T>,older:Boolean,id:(T)->String,version:(T)->Long,insertBefore:String?=null):List<T>{
    val values=linkedMapOf<String,T>()
    current.forEach{values[id(it)]=it}
    val existing=values.keys.toHashSet()
    val page=incoming.distinctBy(id)
    page.forEach{row->val key=id(row);val previous=values[key];if(previous==null||version(row)>=version(previous))values[key]=row}
    val order=current.map(id).distinct().toMutableList()
    if(page.none{id(it) in existing}){
        val anchor=insertBefore?.let{order.indexOf(it)}?:-1
        order.addAll(if(anchor>=0)anchor else if(older)0 else order.size,page.map(id))
    }else{
        val pending=mutableListOf<String>();var previous:String?=null
        page.forEach{row->val key=id(row)
            if(key in existing){
                // Example: a streamed answer discovered between user and existing
                // turnEnd belongs before turnEnd, never at the end of the transcript.
                if(pending.isNotEmpty()){order.addAll(order.indexOf(key),pending);pending.clear()}
                previous=key
            }else pending.add(key)
        }
        if(pending.isNotEmpty()){
            val at=previous?.let{order.indexOf(it)+1}?:if(older)0 else order.size
            order.addAll(at,pending)
        }
    }
    return order.map{values.getValue(it)}
}

/** Preload one page near the start only while reading upward, not while following live output. */
internal fun shouldPrefetchTranscript(follow:Boolean,filtered:Boolean,firstVisible:Int,earlier:Boolean,loading:Boolean)=
    !follow&&!filtered&&firstVisible<=4&&earlier&&!loading

internal data class TranscriptCursor(val before:String?,val hasEarlier:Boolean)
/** Recent/bridge pages cannot overwrite the cursor preceding the oldest retained local row. */
internal fun retainedTranscriptCursor(current:TranscriptCursor,hadRows:Boolean,older:Boolean,bridge:Boolean,page:TranscriptCursor)=
    if(older||(!bridge&&!hadRows))page else current
