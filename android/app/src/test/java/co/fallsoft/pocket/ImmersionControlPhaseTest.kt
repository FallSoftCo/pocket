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
        assertEquals(0L,immersionControlWaveOffset(0f,0f,false))
        assertEquals(1800L,immersionControlWaveOffset(1f,1f,false))
        assertEquals(0L,immersionControlWaveOffset(0f,1f,true))
        assertTrue(immersionControlWaveOffset(.8f,0f,false)>immersionControlWaveOffset(.2f,1f,false))
        // Every visible position shares the same bounded period regardless of label count.
        for(y in 0..10)for(x in 0..10){
            val offset=immersionControlWaveOffset(y/10f,x/10f,false)
            assertTrue(immersionControlPhase(20000-offset).original)
            assertFalse(immersionControlPhase(10000-offset).original)
        }
    }
    @Test fun handoverNeverBlanksOrRequiresTwoTextLayouts(){
        for(step in 0..100){
            val phase=immersionInkPhase(step/100f)
            assertEquals(1f,phase.opacity,0f)
            assertTrue(phase.cue in 0f..1f)
        }
        fun at(ms:Long)=immersionInkPhase(ms.toFloat()/IMMERSION_HANDOFF_DURATION_MS)
        assertEquals(0f,at(0).cue,0f)
        assertEquals(1f,at(700).cue,0f)
        assertFalse(at(1499).incoming)
        assertTrue(at(1501).incoming)
        assertEquals(1f,at(3699).cue,0f)
        assertEquals(0f,at(4200).cue,0f)
        assertTrue(at(200).cue<at(500).cue)
        assertTrue(at(3900).cue>at(4100).cue)
    }

}
