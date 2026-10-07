package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class VoiceCapturePolicyTest {
 @Test fun onePressStartsNextStopsThenProcessingCannotRecord(){assertEquals(CaptureControl.START,captureControl(false,"Ready",false));assertEquals(CaptureControl.STOP_SEND,captureControl(true,"Listening",false));assertEquals(CaptureControl.PROCESSING,captureControl(true,"Finishing recording",true));assertEquals(CaptureControl.PROCESSING,captureControl(true,"Thinking",true))}
 @Test fun savedTurnIsAnExplicitCheckNotAnotherCapture(){assertEquals(CaptureControl.CHECK_SAVED,captureControl(true,"Retry needed",true));assertEquals(CaptureControl.START,captureControl(true,"Ready",false));assertEquals(CaptureControl.START,captureControl(true,"Paused",false))}
 @Test fun holdAndBalancedReleaseNeverCreateExtraTurns(){val g=CaptureKeyGesture();assertTrue(g.down(24,0));assertFalse(g.down(24,1));assertFalse(g.down(24,0));g.up(24);assertTrue(g.down(24,0));g.reset();assertTrue(g.down(25,0))}
 @Test fun foregroundScopeDoesNotHijackBackgroundSystemVolume(){assertTrue(foregroundCaptureKeys(true,true,false,true));assertFalse(foregroundCaptureKeys(false,true,false,true));assertFalse(foregroundCaptureKeys(true,false,false,true));assertFalse(foregroundCaptureKeys(true,true,true,true));assertFalse(foregroundCaptureKeys(true,true,false,false))}
 @Test fun meterRespectsPcmBoundaryAndSaturates(){assertEquals(0f,capturePcmLevel(byteArrayOf(0,0,99),2));assertEquals(1f,capturePcmLevel(byteArrayOf(0,-128),2));assertEquals(0f,capturePcmLevel(byteArrayOf(99),1))}
 @Test fun captureFeedbackIsConditionalNotAnotherIdleToolbar(){assertFalse(captureFeedbackVisible(true,false,"Ready",""));assertFalse(captureFeedbackVisible(true,false,"Speaking",""));assertTrue(captureFeedbackVisible(true,false,"Listening",""));assertTrue(captureFeedbackVisible(false,false,"Ready","Microphone denied"))}
 @Test fun microphoneStartupStillHonorsSecondTap(){assertEquals(VoiceRecordIntent.STOP_AND_SEND,voiceRecordIntent(true,true,true,"Starting microphone",false));assertEquals(CaptureControl.STOP_SEND,captureControl(true,"Starting microphone",false));assertEquals(VoiceRecordIntent.BUSY,voiceRecordIntent(false,true,true,"Connecting",false))}
}
