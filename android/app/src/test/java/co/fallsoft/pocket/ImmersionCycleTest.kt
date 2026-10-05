package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class ImmersionCycleTest {
    @Test fun longerTargetDwellAndStableIndependentStaggers(){
        val a=immersionCycleTiming("message:10","new sessions","nuove sessioni")
        assertTrue(a.targetMs>a.sourceMs)
        assertEquals(a,immersionCycleTiming("message:10","new sessions","nuove sessioni"))
        val phases=(10..20).map{immersionCycleTiming("message:$it","new sessions","nuove sessioni").staggerMs}
        assertTrue(phases.distinct().size>=9)
        assertTrue(phases.maxOrNull()!!-phases.minOrNull()!!>2000)
    }
    @Test fun phaseBoundariesAreDeterministicWithoutFrameTicking(){
        val timing=ImmersionCycleTiming(2500,4000,0)
        assertEquals(ImmersionCyclePhase(false,4000),immersionCyclePhase(0,timing))
        assertEquals(ImmersionCyclePhase(true,2500),immersionCyclePhase(4000,timing))
        assertEquals(ImmersionCyclePhase(false,4000),immersionCyclePhase(6500,timing))
    }
    @Test fun oneFlowPreservesProtectedContentAndIndependentChoices(){
        val source="Check `git status`: two new sessions and an important question."
        val a=source.indexOf("two new sessions");val b=source.indexOf("an important question")
        val spans=listOf(ImmersionSpan(a,a+16,"two new sessions","due nuove sessioni"),ImmersionSpan(b,b+21,"an important question","una domanda importante"))
        val plan=ImmersionPresentation(contextualHybridText(source,spans)!!,source,true,spans)
        assertEquals("Check `git status`: two new sessions and una domanda importante.",immersionCyclePlan(plan,setOf(0)).text)
        assertEquals(source,immersionCyclePlan(plan,setOf(0,1)).text)
        assertEquals(plan.text,immersionCyclePlan(plan,emptySet()).text)
        assertFalse(immersionCyclePlan(plan,setOf(0)).text.contains('\n'))
    }
    @Test fun reserveSlotsKeepExactFormsAndWrapAtWordBoundaries(){
        val words=immersionReserveWords("to the new session","alla nuova sessione")
        assertEquals("to the new session",words.joinToString(""){it.source})
        assertEquals("alla nuova sessione",words.joinToString(""){it.target})
        assertEquals(4,words.size)
        for(scale in listOf(1,2)){
            val reserved=words.map{maxOf(it.source.length,it.target.length)*scale}
            words.forEachIndexed{index,word->assertTrue(reserved[index]>=word.source.length*scale);assertTrue(reserved[index]>=word.target.length*scale)}
            assertTrue(reserved.maxOrNull()!!<maxOf("to the new session".length,"alla nuova sessione".length)*scale)
        }
    }
    @Test fun recreatedCompositionRecoversCapturedMixedSnapshot(){
        val source="Read new sessions and important questions now."
        val a=source.indexOf("new sessions");val b=source.indexOf("important questions")
        val spans=listOf(ImmersionSpan(a,a+12,"new sessions","nuove sessioni"),ImmersionSpan(b,b+19,"important questions","domande importanti"))
        val plan=ImmersionPresentation(contextualHybridText(source,spans)!!,source,true,spans)
        val captured=immersionCyclePlan(plan,setOf(1)).text
        assertEquals(setOf(1),immersionCapturedOriginals(plan,"Context: $captured"))
        assertEquals(setOf(0,1),immersionCapturedOriginals(plan,source))
        assertEquals(emptySet<Int>(),immersionCapturedOriginals(plan,plan.text))
        assertNull(immersionCapturedOriginals(plan,captured.replace("now.","later.")))
    }
    @Test fun invalidCanonicalPlanCannotCycleUnverifiedText(){
        val plan=ImmersionPresentation("wrong","original",true,listOf(ImmersionSpan(0,8,"original","nuovo")))
        assertEquals("original",immersionCyclePlan(plan,emptySet()).text)
        assertTrue(immersionCyclePlan(plan,emptySet()).spans.isEmpty())
    }
}
