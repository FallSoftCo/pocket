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
