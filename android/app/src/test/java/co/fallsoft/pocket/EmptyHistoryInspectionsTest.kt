package co.fallsoft.pocket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EmptyHistoryInspectionsTest {
    private class Empty:Exception()
    @Test fun reopeningWhileAnOlderRequestIsPendingReceivesOneBoundedInspection()=runBlocking{
        val state=EmptyHistoryInspections<Pair<String,String>>()
        val key="pairing-A" to "session"
        val pending=CompletableDeferred<String>()
        val started=state.generation(key)
        var reads=1
        state.observe(key)
        val requested=state.generation(key)
        val reopened=async(start=CoroutineStart.UNDISPATCHED){state.await(key,started,requested,true,{it is Empty},
            read={pending.await()},reinspect={reads++;"Recovered"})}
        // The older request must not persist its availability after reinspection.
        assertFalse(state.current(key,started))
        pending.completeExceptionally(Empty())
        assertEquals("Recovered",reopened.await());assertEquals(2,reads)
        // New evidence during that bounded follow-up cannot create a retry loop.
        state.observe(key)
        val failure=runCatching{state.await(key,requested,state.generation(key),false,{it is Empty},
            read={throw Empty()},reinspect={reads++;"Unexpected"})}.exceptionOrNull()
        assertTrue(failure is Empty);assertEquals(2,reads)
    }
    @Test fun progressClearsOnlyItsSourceEmptyRefusalBeforeAdmission(){
        val state=EmptyHistoryInspections<Pair<String,String>>()
        val key="pairing-A" to "session";val other="pairing-B" to "session"
        val refusedAt=state.generation(key)
        assertFalse(state.clears(key,"THREAD_NOT_MATERIALIZED",refusedAt))
        state.observe(other)
        assertFalse(state.clears(key,"THREAD_NOT_MATERIALIZED",refusedAt))
        state.observe(key)
        assertTrue(state.clears(key,"THREAD_NOT_MATERIALIZED",refusedAt))
        assertFalse(state.clears(key,"THREAD_ARCHIVED",refusedAt))
        assertFalse(state.clears(key,"THREAD_HISTORY_UNAVAILABLE",refusedAt))
        // The one admitted recovery can refuse again; unchanged evidence stays stopped.
        val recoveryStarted=state.generation(key)
        assertTrue(state.current(key,recoveryStarted))
        assertFalse(state.clears(key,"THREAD_NOT_MATERIALIZED",recoveryStarted))
    }
}
