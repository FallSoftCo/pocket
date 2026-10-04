package co.fallsoft.pocket
object NotificationReadPolicy {
    fun preserve(kind:String,needsAttention:Boolean?)=kind in setOf("question","approval","error")&&needsAttention!=false
    fun read(id:Long,watermark:Long,kind:String,needsAttention:Boolean?)=id>0&&id<=watermark&&!preserve(kind,needsAttention)
}
