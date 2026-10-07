package co.fallsoft.pocket

internal data class ForegroundCaptureTarget(val threadId:String?,val eligible:Boolean)
internal fun foregroundCaptureTarget(inbox:Boolean,coordinator:Boolean,selected:String?,identityKnown:Boolean,directInput:Boolean,recording:Boolean=false,recordingThread:String?=null):ForegroundCaptureTarget {
    if(recording)return ForegroundCaptureTarget(recordingThread,true)
    if(inbox||coordinator||selected==null)return ForegroundCaptureTarget(null,true)
    return ForegroundCaptureTarget(selected,identityKnown&&directInput)
}
internal fun currentForegroundCaptureTarget():ForegroundCaptureTarget {
    val selected=if(PocketVoice.active&&!PocketVoice.inPlace)PocketVoice.targetThread else Pocket.selected
    val task=Pocket.tasks.firstOrNull{it.id==selected}
    val thread=Pocket.detail?.optJSONObject("thread")?.takeIf{it.s("id")==selected}
    val direct=if(thread!=null&&thread.has("canAcceptDirectInput")&&!thread.isNull("canAcceptDirectInput"))thread.optBoolean("canAcceptDirectInput")else task?.canAcceptDirectInput==true
    return foregroundCaptureTarget(PocketWorkUpdates.visible,PocketCoordinator.visible,selected,selected==null||task!=null||thread!=null,direct,captureControl(PocketVoice.active,PocketVoice.state,false)==CaptureControl.STOP_SEND,PocketVoice.targetThread)
}
