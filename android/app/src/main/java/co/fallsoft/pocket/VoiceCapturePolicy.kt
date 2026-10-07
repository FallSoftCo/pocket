package co.fallsoft.pocket

internal enum class CaptureControl { START, STOP_SEND, PROCESSING, CHECK_SAVED }
internal fun captureControl(active:Boolean,state:String,pending:Boolean):CaptureControl=when {
    active&&state in setOf("Listening","Starting microphone")->CaptureControl.STOP_SEND
    active&&state in setOf("Finishing recording","Transcribing","Thinking","Connecting","Starting voice","Sending","Preparing speech")->CaptureControl.PROCESSING
    pending->CaptureControl.CHECK_SAVED
    else->CaptureControl.START
}
internal fun foregroundCaptureKeys(foreground:Boolean,paired:Boolean,team:Boolean,eligible:Boolean)=foreground&&paired&&!team&&eligible
internal class CaptureKeyGesture {
    private val held=mutableSetOf<Int>()
    fun down(code:Int,repeat:Int):Boolean=repeat==0&&held.add(code)
    fun up(code:Int){held.remove(code)}
    fun reset(){held.clear()}
}
internal fun capturePcmLevel(bytes:ByteArray,length:Int):Float {
    var peak=0;var i=0
    while(i+1<length.coerceAtMost(bytes.size)){val sample=((bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)).toShort().toInt();peak=maxOf(peak,kotlin.math.abs(sample));i+=2}
    return (peak/12000f).coerceIn(0f,1f)
}

internal fun captureFeedbackVisible(active:Boolean,launching:Boolean,state:String,problem:String)=problem.isNotBlank()||launching||active&&state !in setOf("Ready","Paused","Speaking")
