package co.fallsoft.pocket

/** Keep all loaded rows; pages overlap by identity and stale versions cannot erase live text. */
internal fun <T> mergeTranscriptPage(current:List<T>,incoming:List<T>,older:Boolean,id:(T)->String,version:(T)->Long,insertBefore:String?=null):List<T>{
    val ordered=linkedMapOf<String,T>()
    val first=if(older)incoming else current
    val second=if(older)current else incoming
    val anchor=insertBefore?.let{key->current.indexOfFirst{id(it)==key}}?:-1
    val sequence=if(anchor>=0)current.take(anchor)+incoming+current.drop(anchor) else first+second
    sequence.forEach{row->
        val key=id(row);val previous=ordered[key]
        if(previous==null||version(row)>=version(previous))ordered[key]=row
    }
    return ordered.values.toList()
}

/** Preload one page near the start only while reading upward, not while following live output. */
internal fun shouldPrefetchTranscript(follow:Boolean,filtered:Boolean,firstVisible:Int,earlier:Boolean,loading:Boolean)=
    !follow&&!filtered&&firstVisible<=4&&earlier&&!loading

internal data class TranscriptCursor(val before:String?,val hasEarlier:Boolean)
/** Recent/bridge pages cannot overwrite the cursor preceding the oldest retained local row. */
internal fun retainedTranscriptCursor(current:TranscriptCursor,hadRows:Boolean,older:Boolean,bridge:Boolean,page:TranscriptCursor)=
    if(older||(!bridge&&!hadRows))page else current
