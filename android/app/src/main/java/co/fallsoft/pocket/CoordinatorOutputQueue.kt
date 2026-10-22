package co.fallsoft.pocket

/** One output owner. Replies outrank coalesced work updates; nothing is heard at enqueue. */
internal data class CoordinatorUtterance(
    val id:String,val text:String,val title:String,val turnId:String?=null,
    val recordingKey:String?=null,val environmentId:String="current",val threadId:String?=null,
    val seq:Long?=null,val urgent:Boolean=false
)
internal class CoordinatorOutputQueue {
    private val replies=ArrayDeque<CoordinatorUtterance>()
    private val updates=linkedMapOf<String,CoordinatorUtterance>()
    private val heard=linkedSetOf<String>()
    var current:CoordinatorUtterance?=null;private set
    val pending get()=replies.size+updates.size
    fun response(value:CoordinatorUtterance){
        if(value.text.isBlank()||value.id in heard||current?.id==value.id||replies.any{it.id==value.id})return
        check(replies.size<64){"Spoken response queue is full; the response remains available as text."}
        replies.addLast(value)
    }
    fun update(value:CoordinatorUtterance){
        if(value.text.isBlank()||value.id in heard||current?.id==value.id)return
        val key=value.environmentId+":"+value.threadId+if(value.urgent)":question:"+value.seq else ":outcome"
        val previous=updates[key]
        if(previous==null||(value.seq?:0)>=(previous.seq?:0))updates[key]=value
        while(updates.size>3)updates.remove(updates.keys.first())
    }
    fun next():CoordinatorUtterance? {
        if(current!=null)return null
        current=if(replies.isNotEmpty())replies.removeFirst()else {
            val entry=updates.entries.firstOrNull{it.value.urgent}?:updates.entries.firstOrNull()
            entry?.also{updates.remove(it.key)}?.value
        }
        return current
    }
    fun complete(id:String):CoordinatorUtterance? {
        val value=current?.takeIf{it.id==id}?:return null
        heard.add(id);while(heard.size>512)heard.remove(heard.first());current=null;return value
    }
    fun discardResponse(key:String):Boolean {
        replies.removeAll{it.recordingKey==key}
        if(current?.recordingKey!=key)return false
        current=null;return true
    }
    fun quietUpdates(){updates.clear()}
    fun bargeIn(){current=null;replies.clear();updates.clear()}
    fun includes(id:String)=id in heard||current?.id==id||replies.any{it.id==id}||updates.values.any{it.id==id}
}
