package co.fallsoft.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechPlaybackMeterTest {
    @Test fun samplesDoNotDoubleCountAndPausedTimeIsExcluded(){
        val meter=SpeechPlaybackMeter()
        assertEquals(0L,meter.sample(100))
        meter.start(100);meter.start(200)
        assertEquals(400L,meter.sample(500))
        assertEquals(200L,meter.stop(700))
        assertEquals(0L,meter.stop(1700))
        meter.start(2000)
        assertEquals(100L,meter.stop(2100))
    }
}
