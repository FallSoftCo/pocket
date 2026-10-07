package co.fallsoft.pocket

internal enum class VoiceRecordIntent { STOP_AND_SEND, BUSY, RECOVER_PENDING, START }
internal fun voiceRecordIntent(recording:Boolean,starting:Boolean,working:Boolean,state:String,pending:Boolean):VoiceRecordIntent=when {
    recording -> VoiceRecordIntent.STOP_AND_SEND
    starting || working&&state !in listOf("Speaking","Paused","Ready") -> VoiceRecordIntent.BUSY
    pending -> VoiceRecordIntent.RECOVER_PENDING
    else -> VoiceRecordIntent.START
}

internal fun voiceTextBlock(recording:Boolean,starting:Boolean,working:Boolean,pending:Boolean):String?=when {
    recording -> "Stop and send the recording before sending text. Your draft is kept."
    starting || working -> "Your current turn is still being processed. Your draft is kept."
    pending -> "Check the saved turn before sending another message. Your draft is kept."
    else -> null
}
internal fun voiceDraftAfterSubmit(draft:String,accepted:Boolean)=if(accepted)"" else draft.replace('\n',' ')
internal fun voiceRecordingProblem(hasSpeech:Boolean,captureProblem:String?):String?=captureProblem?:if(hasSpeech)null else "No speech was captured. Check microphone privacy, then tap Talk to record again."
