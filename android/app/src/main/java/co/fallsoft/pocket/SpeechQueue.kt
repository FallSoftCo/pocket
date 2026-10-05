package co.fallsoft.pocket

/** Playback state contains text and a cursor, never credentials or cloud audio. */
data class SpokenMessage(val id:Long,val title:String,val kind:String,var text:String,val needsFetch:Boolean=false)
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
