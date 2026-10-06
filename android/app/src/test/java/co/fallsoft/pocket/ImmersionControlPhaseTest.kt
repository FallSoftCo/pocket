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
}
