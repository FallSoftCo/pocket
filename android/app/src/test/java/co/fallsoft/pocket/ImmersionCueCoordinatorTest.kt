package co.fallsoft.pocket

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ImmersionCueCoordinatorTest {
    @Test fun queuedControlsCannotStarveBehindBodiesAndOnlyOneLeaseIsActive(){
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
    @Test fun continuouslyArrivingBodyUpdatesCannotDisplaceAnAlreadyWaitingControl(){
        val queue=ImmersionCueQueue()
        queue.request("first-body",true)
        queue.request("visible-control",false)
        repeat(100){queue.request("live-body-$it",true)}
        queue.cancel("first-body")
        assertEquals("visible-control",queue.active)
        assertEquals(100,queue.pending().size)
    }
    @Test fun sourceReturnsDoNotWaitBehindEveryTargetControl(){
        val queue=ImmersionCueQueue()
        queue.request("active",false)
        repeat(100){queue.request("ordinary-$it",false)}
        queue.request("return",true,true)
        queue.cancel("active")
        assertEquals("return",queue.active)
        queue.cancel("return")
        assertEquals("ordinary-0",queue.active)
    }
    @Test fun continuousReturnsCannotStarveOldestOrdinaryWaiterAndReturnsKeepFifo(){
        val queue=ImmersionCueQueue()
        queue.request("active",false)
        queue.request("ordinary",false)
        repeat(5){queue.request("return-$it",true,true)}
        queue.cancel("active");assertEquals("return-0",queue.active)
        queue.cancel("return-0");assertEquals("return-1",queue.active)
        queue.request("late-return",true,true)
        queue.cancel("return-1");assertEquals("ordinary",queue.active)
        queue.cancel("ordinary");assertEquals("return-2",queue.active)
    }
    @Test fun cancelledReturnCannotTakePriorityOverVisibleWork(){
        val queue=ImmersionCueQueue()
        queue.request("active",false);queue.request("ordinary",false)
        queue.request("hidden-return",true,true)
        queue.cancel("hidden-return");queue.cancel("active")
        assertEquals("ordinary",queue.active)
    }
    @Test fun manyEarlierControlsCannotHideTheFirstVisibleBody(){
        val queue=ImmersionCueQueue()
        queue.request("active-control",false)
        repeat(100){queue.request("control-$it",false)}
        queue.request("body",true)
        queue.cancel("active-control")
        assertEquals("body",queue.active)
        queue.cancel("body")
        assertEquals("control-0",queue.active)
    }
    @Test fun continuousBodyArrivalsStillGrantControlsEveryOtherOrdinaryTurn(){
        val queue=ImmersionCueQueue()
        queue.request("active-control",false)
        queue.request("control-first",false);queue.request("control-second",false)
        repeat(100){queue.request("body-$it",true)}
        queue.cancel("active-control");assertEquals("body-0",queue.active)
        queue.request("late-body",true)
        queue.cancel("body-0");assertEquals("control-first",queue.active)
        queue.cancel("control-first");assertEquals("body-1",queue.active)
        queue.cancel("body-1");assertEquals("control-second",queue.active)
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
    @Test fun completedCueEnforcesMonotonicQuietGapAndPromptReturn(){
        var now=100L
        val queue=ImmersionCueQueue(clock={now},quietGapMs=42)
        queue.request("active",false);queue.request("source",false);queue.request("return",false,true);queue.request("body",true)
        queue.cancel("active",completed=true)
        assertNull(queue.active);assertEquals(42L,queue.quietRemainingMs())
        now+=41;queue.refresh();assertNull(queue.active)
        now++;queue.refresh();assertEquals("return",queue.active)
        queue.cancel("return")
        assertEquals("body",queue.active)
        queue.cancel("body")
        assertEquals("source",queue.active)
    }
    @Test fun waitingDuringQuietIntervalIsCancellableWithoutStartingCue()=runBlocking {
        val broker=ImmersionCueBroker()
        runImmersionCue("completed-local",false,onBegin={},onHandoff={},onFinish={},animate={},handoffMs=0,cooldownMs=10000,broker=broker)
        var began=false
        val job=launch(start=CoroutineStart.UNDISPATCHED){runImmersionCue("waiting-local",false,onBegin={began=true},onHandoff={},onFinish={},animate={},handoffMs=0,cooldownMs=0,broker=broker)}
        job.cancelAndJoin()
        assertFalse(began)
    }
    @Test fun labelsBecomeEligibleWithinSecondsAndKeepDispersedStartup(){
        val labels=(0..40).map{"Control $it"}
        val cadences=labels.map(::immersionLabelCadence)
        assertEquals(immersionLabelCadence("Important"),immersionLabelCadence("Important"))
        assertTrue(cadences.all{it.initialMs in 6000L..9000L&&it.targetMs in 10000L..15000L})
        assertTrue(cadences.map{it.initialMs}.distinct().size>35)
        assertTrue(cadences.maxOf{it.initialMs}-cadences.minOf{it.initialMs}>2000)
    }
    @Test fun cancellationBeforeHandoffPreservesVisibleSnapshot()=runBlocking {
        var actual="source";var finished=false
        val started=CompletableDeferred<Unit>()
        val job=launch{runImmersionCue("test-before",true,onBegin={started.complete(Unit)},onHandoff={actual="target"},onFinish={finished=true},animate={awaitCancellation()},handoffMs=10000,cooldownMs=0)}
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
        val job=launch{runImmersionCue("test-progress",true,onBegin={started.complete(Unit)},onHandoff={actual="target";switched.complete(Unit)},onFinish={},animate={awaitCancellation()},handoffMs=0,awaitHandoff={progress.first{it>=.5f}},cooldownMs=0)}
        started.await();yield()
        assertEquals("source",actual)
        progress.value=.25f;yield();assertEquals("source",actual)
        progress.value=.75f;switched.await();assertEquals("target",actual)
        job.cancelAndJoin()
    }
    @Test fun cancellationAfterHandoffKeepsAlreadyVisibleTarget()=runBlocking {
        var actual="source"
        val switched=CompletableDeferred<Unit>()
        val job=launch{runImmersionCue("test-after",true,onBegin={},onHandoff={actual="target";switched.complete(Unit)},onFinish={},animate={awaitCancellation()},handoffMs=0,cooldownMs=0)}
        switched.await();job.cancelAndJoin()
        assertEquals("target",actual)
    }
}
