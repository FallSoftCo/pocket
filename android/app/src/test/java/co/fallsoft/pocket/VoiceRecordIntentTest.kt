package co.fallsoft.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceRecordIntentTest {
    @Test fun secondTapStopsEvenIfOtherWorkIsActive(){assertEquals(VoiceRecordIntent.STOP_AND_SEND,voiceRecordIntent(true,true,true,"Listening",false))}
    @Test fun idleTapStartsWithoutRequiringNetworkBootFirst(){assertEquals(VoiceRecordIntent.START,voiceRecordIntent(false,false,false,"Retry needed",false))}
    @Test fun pendingTurnIsRecoveredRatherThanOverwritten(){assertEquals(VoiceRecordIntent.RECOVER_PENDING,voiceRecordIntent(false,false,false,"Retry needed",true))}
    @Test fun activeSubmissionIsNotCancelledByMicTap(){assertEquals(VoiceRecordIntent.BUSY,voiceRecordIntent(false,false,true,"Sending",true));assertEquals(VoiceRecordIntent.BUSY,voiceRecordIntent(false,true,false,"Connecting",false))}
    @Test fun speechCanBeInterruptedToStartRecording(){assertEquals(VoiceRecordIntent.START,voiceRecordIntent(false,false,true,"Speaking",false))}
    @Test fun allBusyTextStatesKeepTheDraft(){
        val text="Do not publish\nuntil checked"
        for(state in listOf(listOf(true,false,false,false),listOf(false,true,false,false),listOf(false,false,true,false),listOf(false,false,false,true))){
            val blocked=voiceTextBlock(state[0],state[1],state[2],state[3])
            org.junit.Assert.assertNotNull(blocked)
            assertEquals("Do not publish until checked",voiceDraftAfterSubmit(text,blocked==null))
        }
    }
    @Test fun acceptedTextClearsOnlyAfterServiceAcceptsIt(){assertEquals(null,voiceTextBlock(false,false,false,false));assertEquals("",voiceDraftAfterSubmit("Checked",true))}
    @Test fun rejectedTypedMessageDoesNotInvalidateTheRecordedTurn(){
        org.junit.Assert.assertNotNull(voiceTextBlock(true,false,false,false))
        assertEquals(null,voiceRecordingProblem(true,null))
        assertEquals("Actual microphone failure",voiceRecordingProblem(true,"Actual microphone failure"))
        org.junit.Assert.assertNotNull(voiceRecordingProblem(false,null))
    }
}
