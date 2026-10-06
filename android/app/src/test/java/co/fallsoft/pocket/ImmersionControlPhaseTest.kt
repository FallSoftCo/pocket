package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class ImmersionControlPhaseTest {
    @Test fun controlsShareOneCalmTargetDominantLayer(){
        assertEquals(ImmersionControlPhase(false,18000),immersionControlPhase(0))
        assertEquals(ImmersionControlPhase(false,1),immersionControlPhase(17999))
        assertEquals(ImmersionControlPhase(true,6000),immersionControlPhase(18000))
        assertEquals(ImmersionControlPhase(false,18000),immersionControlPhase(24000))
        assertEquals(immersionControlPhase(3500),immersionControlPhase(3500+24000))
    }
    @Test fun handoverNeverBlanksOrRequiresTwoTextLayouts(){
        for(step in 0..100){
            val phase=immersionInkPhase(step/100f)
            assertTrue(phase.opacity>=.85f&&phase.opacity<=1f)
            assertEquals(step>=50,phase.incoming)
        }
        assertEquals(1f,immersionInkPhase(0f).opacity,0f)
        assertEquals(1f,immersionInkPhase(1f).opacity,0f)
        assertEquals(.85f,immersionInkPhase(.5f).opacity,0f)
    }

}
