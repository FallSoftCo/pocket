package co.fallsoft.pocket

internal data class TranscriptHead(val epoch:String,val version:Long?)
/** Ignore an older status response after a concurrent stream changed runtime. */
internal fun transcriptHeadAdvanced(current:TranscriptHead?,requestBaseline:TranscriptHead?,incoming:TranscriptHead):Boolean {
    if(current==null)return true
    if(current.epoch!=incoming.epoch)return current==requestBaseline
    val version=incoming.version?:return false
    return current.version==null||version>current.version
}
internal fun observeTranscriptHead(current:TranscriptHead?,incoming:TranscriptHead):TranscriptHead =
    if(current!=null&&current.epoch==incoming.epoch&&(incoming.version==null||current.version!=null&&current.version>=incoming.version))current else incoming
/** A slow snapshot cannot replace the head of newer output received during that read. */
internal fun snapshotTranscriptHead(current:TranscriptHead?,readBaseline:TranscriptHead?,incoming:TranscriptHead):TranscriptHead =
    if(current!=null&&current!=readBaseline&&current.epoch!=incoming.epoch)current else observeTranscriptHead(current,incoming)
