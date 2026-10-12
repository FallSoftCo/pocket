package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class ImmersionReadingWaveTest {
    private val wave=ImmersionReadingWave()

    @Test fun laterRowsStartAndFinishLaterWithoutAccelerating() {
        // Same viewport locations whether these rows belong to one paragraph
        // or separate controls: no paragraph count enters the policy.
        for(row in listOf(0,1,3,9)) {
            val y=row*26.0
            val start=wave.onsetMs(y)
            assertEquals(row*350L,start)
            assertEquals(0f,wave.frame(start,y).progress,0f)
            assertEquals(.5f,wave.frame(start+600,y).progress,0f)
            assertEquals(1f,wave.frame(start+1200,y).progress,0f)
        }
        assertTrue(wave.frame(600,0.0).progress>wave.frame(600,26.0).progress)
    }

    @Test fun bothLanguagesHoldLocallyAndWavesRepeatWithoutACompletionBarrier() {
        for(y in listOf(0.0,26.0,234.0,900.0)) {
            val onset=wave.onsetMs(y)
            for(delta in listOf(1200L,2400L,4199L)) {
                val frame=wave.frame(onset+delta,y)
                assertTrue(frame.towardSupport)
                assertEquals(1f,frame.progress,0f)
            }
            assertFalse(wave.frame(onset+4200,y).towardSupport)
            assertEquals(1f,wave.frame(onset+5400,y).progress,0f)
            for(delta in listOf(0L,600L,1200L,4199L,4800L,8399L))
                assertEquals(wave.frame(onset+delta,y),wave.frame(onset+delta+wave.periodMs*10000,y))
        }
    }

    @Test fun complementaryInkNeverHasABlackGapAndKeepsOneHorizontalFrontier() {
        for(ms in 0L..1200L step 100L) {
            val forward=wave.frame(ms,0.0)
            val reverse=wave.frame(ms+wave.halfPeriodMs,0.0)
            for(x in listOf(0f,.1f,.5f,.9f,1f)) {
                val support=forward.supportCoverage(x)
                assertEquals(1f,support+reverse.supportCoverage(x),.00001f)
                assertTrue(support in 0f..1f)
            }
        }
        assertEquals(.5f,wave.frame(600,0.0).supportCoverage(.5f),.00001f)
    }

    @Test fun clockIsSafeAtLongRunningAndNegativeEpochs() {
        for(t in listOf(Long.MIN_VALUE,Long.MAX_VALUE,-1L,0L)) {
            val frame=wave.frame(t,100.0)
            assertEquals(wave.frame(Math.floorMod(t,wave.periodMs),100.0),frame)
            assertTrue(frame.progress in 0f..1f)
        }
    }
}
