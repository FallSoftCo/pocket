package co.fallsoft.pocket

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ImmersionCueCoordinatorTest {
    @Test fun bodiesLeadQueuedControlsAndOnlyOneLeaseIsActive(){
        val queue=ImmersionCueQueue()
        queue.request("first-control",false)
        queue.request("second-control",false)
        queue.request("body",true)
        assertEquals("first-control",queue.active)
        queue.cancel("first-control")
        assertEquals("body",queue.active)
        queue.cancel("body")
        assertEquals("second-control",queue.active)
        queue.cancel("second-control")
        assertNull(queue.active)
    }
    @Test fun cancelledQueuedRequestNeverBecomesActive(){
        val queue=ImmersionCueQueue()
        queue.request("active",true);queue.request("old-content",true)
        queue.cancel("old-content");queue.cancel("active")
        assertNull(queue.active);assertTrue(queue.pending().isEmpty())
    }
    @Test fun lateHydrationKeepsUnknownPhrasesSourceAndChangesOnlyOneAtATime(){
        val source="Check two sessions and a question."
        val a=source.indexOf("two sessions");val b=source.indexOf("a question")
        val spans=listOf(ImmersionSpan(a,a+12,"two sessions","due sessioni"),ImmersionSpan(b,b+10,"a question","una domanda"))
        val known=mapOf(immersionPhraseIdentity(spans[0]) to false)
        val actual=immersionKnownOriginals(spans,known)
        assertEquals(setOf(1),actual)
        val incoming=immersionCueOriginals(actual,1,false)
        assertEquals(1,(actual-incoming).size)
        val plan=ImmersionPresentation(contextualHybridText(source,spans)!!,source,true,spans)
        assertEquals("Check due sessioni and a question.",immersionCyclePlan(plan,actual).text)
        assertEquals("Check due sessioni and una domanda.",immersionCyclePlan(plan,incoming).text)
        val reverse=immersionCueOriginals(incoming,0,true)
        assertEquals(setOf(0),reverse)
    }
    @Test fun cancellationBeforeHandoffPreservesVisibleSnapshot()=runBlocking {
        var actual="source";var finished=false
        val started=CompletableDeferred<Unit>()
        val job=launch{runImmersionCue("test-before",true,onBegin={started.complete(Unit)},onHandoff={actual="target"},onFinish={finished=true},animate={awaitCancellation()},handoffMs=10000)}
        started.await();job.cancelAndJoin()
        assertEquals("source",actual);assertTrue(finished)
        // The cancelled lease cannot block a later surface.
        assertTrue(withTimeout(500){ImmersionCueCoordinator.acquire("test-next",false)}>0)
        ImmersionCueCoordinator.release("test-next")
    }
    @Test fun explicitProgressGateControlsSemanticHandoffInsteadOfWallClock()=runBlocking {
        var actual="source"
        val progress=MutableStateFlow(0f)
        val started=CompletableDeferred<Unit>();val switched=CompletableDeferred<Unit>()
        val job=launch{runImmersionCue("test-progress",true,onBegin={started.complete(Unit)},onHandoff={actual="target";switched.complete(Unit)},onFinish={},animate={awaitCancellation()},handoffMs=0,awaitHandoff={progress.first{it>=.5f}})}
        started.await();yield()
        assertEquals("source",actual)
        progress.value=.25f;yield();assertEquals("source",actual)
        progress.value=.75f;switched.await();assertEquals("target",actual)
        job.cancelAndJoin()
    }
    @Test fun cancellationAfterHandoffKeepsAlreadyVisibleTarget()=runBlocking {
        var actual="source"
        val switched=CompletableDeferred<Unit>()
        val job=launch{runImmersionCue("test-after",true,onBegin={},onHandoff={actual="target";switched.complete(Unit)},onFinish={},animate={awaitCancellation()},handoffMs=0)}
        switched.await();job.cancelAndJoin()
        assertEquals("target",actual)
    }
}
