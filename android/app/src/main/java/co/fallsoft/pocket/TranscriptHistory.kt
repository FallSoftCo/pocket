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

/** A message identity and offset, never a transient pagination/loading item index. */
internal data class TranscriptReadPosition(val rowId:String,val offset:Int,val follow:Boolean)
internal fun transcriptAnchorIndex(rowIds:List<String>,position:TranscriptReadPosition)=rowIds.indexOf(position.rowId)

/** Source navigation waits for an existing page; only completed, advancing pages use its budget. */
internal suspend fun locateTranscriptSource(
    owns:()->Boolean,loading:()->Boolean,hasEarlier:()->Boolean,cursor:()->String?,find:()->Int,
    page:suspend ()->Unit,maxPages:Int=12,wait:suspend ()->Unit={kotlinx.coroutines.delay(32)}
):Int{
    var pages=0
    while(owns()){
        val found=find();if(found>=0)return found
        while(loading()&&owns())wait()
        if(!owns())return -1
        val afterWait=find();if(afterWait>=0)return afterWait
        if(!hasEarlier()||pages>=maxPages)return -1
        val before=cursor();page()
        if(!owns())return -1
        val afterPage=find();if(afterPage>=0)return afterPage
        if(cursor()==before)return -1 // Failed/nonadvancing page is not twelve fake attempts.
        pages++
    }
    return -1
}

/** Capture a key only from the same viewport item that owns firstVisibleItemScrollOffset. */
internal data class TranscriptVisibleRow(val index:Int,val rowId:String)
internal fun transcriptReadPosition(visibleRows:List<TranscriptVisibleRow>,firstViewportIndex:Int,firstViewportOffset:Int,follow:Boolean)=
    visibleRows.firstOrNull{it.index==firstViewportIndex}?.let{TranscriptReadPosition(it.rowId,firstViewportOffset,follow)}
