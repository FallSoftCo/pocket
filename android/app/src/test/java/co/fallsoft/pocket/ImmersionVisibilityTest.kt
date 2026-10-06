package co.fallsoft.pocket

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ImmersionVisibilityTest {
    @Test fun paragraphPartsAggregateWithoutEmptyPartErasingAnother(){
        val parts=linkedMapOf("first" to setOf("0:12"),"second" to setOf("20:30"))
        assertEquals(setOf("0:12","20:30"),immersionVisibleUnion(parts))
        parts.remove("first")
        assertEquals(setOf("20:30"),immersionVisibleUnion(parts))
        val spans=listOf(ImmersionSpan(0,12,"new sessions","nuove sessioni"),ImmersionSpan(20,30,"a question","una domanda"))
        assertEquals(1,immersionNextVisible(spans,0,immersionVisibleUnion(parts)))
        assertEquals(-1,immersionNextVisible(spans,0,emptySet()))
    }
    @Test fun hiddenPhraseNeverStartsAQueuedCue()=runBlocking {
        var started=false
        assertFalse(immersionWhileVisible({false},{awaitCancellation()}){started=true})
        assertFalse(started)
    }
    @Test fun visibilityLossReleasesLeaseWithoutFutureSemanticCommit()=runBlocking {
        val visible=MutableStateFlow(true)
        val started=CompletableDeferred<Unit>()
        var actual="source"
        val job=async{immersionWhileVisible({visible.value},{visible.first{!it}}){
            runImmersionCue("visibility-test",true,onBegin={started.complete(Unit)},onHandoff={actual="target"},onFinish={},animate={awaitCancellation()},handoffMs=10000)
        }}
        started.await();visible.value=false
        assertFalse(withTimeout(1000){job.await()})
        assertEquals("source",actual)
        assertTrue(withTimeout(500){ImmersionCueCoordinator.acquire("visible-successor",true)}>0)
        ImmersionCueCoordinator.release("visible-successor")
    }
    @Test fun parentCancellationStillPropagatesInsteadOfBeingSwallowed()=runBlocking {
        val started=CompletableDeferred<Unit>()
        var returned=false
        val job=launch{immersionWhileVisible({true},{awaitCancellation()}){started.complete(Unit);awaitCancellation()};returned=true}
        started.await();job.cancelAndJoin();assertFalse(returned)
    }
}
