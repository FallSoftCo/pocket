package co.fallsoft.pocket

/** Playback state contains text and a cursor, never credentials or cloud audio. */
data class SpokenMessage(val id:Long,val title:String,val kind:String,var text:String,val needsFetch:Boolean=false,val threadId:String?=null){val sourceKey:String? get()=if(kind=="preview")"@audio-preview" else threadId}
class SpeechQueue(
    val messages:MutableList<SpokenMessage> = mutableListOf(),
    var chunkIndex:Int=0,
    var positionMs:Int=0,
    var paused:Boolean=false,
    var reason:String=""
){
    val current get()=messages.firstOrNull()
    val chunk get()=current?.let{SpeechText.chunks(it.text).getOrNull(chunkIndex)}
    fun enqueue(message:SpokenMessage){if(messages.none{it.id==message.id})messages.add(message)}
    fun pause(position:Int,why:String){positionMs=position.coerceAtLeast(0);paused=true;reason=why}
    fun resume(){paused=false;reason=""}
    /** Ignore callbacks belonging to a player released during pause or skip. */
    fun completed(id:Long,index:Int):Boolean {
        if(paused||current?.id!=id||chunkIndex!=index)return false
        positionMs=0;chunkIndex++
        if(chunk==null){messages.removeAt(0);chunkIndex=0}
        return true
    }
}

/** Manual reading is temporary; notifications arriving meanwhile remain in the parked queue. */
class DisplayedSpeechSession {
    var owner:String?=null;private set
    var parked:SpeechQueue?=null;private set
    fun start(owner:String,original:SpeechQueue,message:SpokenMessage):SpeechQueue {
        if(parked==null){original.pause(original.positionMs,"Saved for later");parked=original}
        this.owner=owner
        return SpeechQueue(mutableListOf(message))
    }
    fun stop(owner:String):SpeechQueue? {
        if(this.owner!=owner)return null
        val restored=parked;parked=null;this.owner=null
        restored?.pause(restored.positionMs,"Saved for later")
        return restored
    }
}

/** Independent cursors, with one deliberately selected playback source. */
class SessionSpeechQueues {
    val queues=linkedMapOf<String?,SpeechQueue>()
    var activeSource:String?=null
    var explicitFocus=false
    val current:SpeechQueue get()=queues.getOrPut(activeSource){SpeechQueue()}
    val count get()=queues.values.sumOf{it.messages.size}
    fun enqueue(message:SpokenMessage):SpeechQueue=queues.getOrPut(message.sourceKey){SpeechQueue()}.also{it.enqueue(message)}
    fun select(source:String?,explicit:Boolean=true):SpeechQueue {
        current.pause(current.positionMs,"Saved for later")
        activeSource=source;explicitFocus=explicit
        return current
    }
    fun clearActive(){queues.remove(activeSource)}
}

/** Only confirmed full playback enters this ledger; fetching and clearing do not. */
class CompletedSpeechLedger(initial:List<Long> = emptyList(),private val limit:Int=2000){
    private val ids=linkedSetOf<Long>().apply{addAll(initial.filter{it>0}.takeLast(limit))}
    fun contains(id:Long)=id in ids
    fun completed(id:Long){if(id<=0)return;ids.remove(id);ids.add(id);while(ids.size>limit)ids.remove(ids.first())}
    fun snapshot()=ids.toList()
}

internal fun speechOriginMatches(notificationLocal:Boolean?,activeLocal:Boolean)=notificationLocal==null||notificationLocal==activeLocal

internal fun notificationSpeechHoldReason(voiceActive:Boolean):String?=if(voiceActive)"Voice mode is active · Stop voice to listen" else null
