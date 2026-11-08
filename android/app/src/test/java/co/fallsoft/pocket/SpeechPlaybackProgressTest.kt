package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class SpeechPlaybackProgressTest {
    @Test fun longAdvancingPlaybackStaysLiveAndAFrozenPlayerExpires(){
        val progress=SpeechPlaybackProgress()
        progress.restart(0,0)
        for(second in 2L..60L step 2L)assertTrue(progress.remaining(second*1000,(second*1000).toInt())>0)
        assertEquals(1L,progress.remaining(74_999,60_000))
        assertEquals(0L,progress.remaining(75_000,60_000))
    }
    @Test fun resumedPlaybackStartsANewDeadlineWithoutCountingTheIntentionalPause(){
        val progress=SpeechPlaybackProgress()
        progress.restart(0,1000);assertEquals(0L,progress.remaining(20_000,1000))
        progress.restart(200_000,1000)
        assertEquals(15_000L,progress.remaining(202_000,3000))
        assertEquals(0L,progress.remaining(217_000,3000))
    }
}
