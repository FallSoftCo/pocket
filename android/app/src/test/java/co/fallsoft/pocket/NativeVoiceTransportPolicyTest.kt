package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test

class NativeVoiceTransportPolicyTest {
    @Test fun delayedOfferAfterSilentStopCannotRestoreAnOrphanedServerId(){
        val offerEpoch=1L
        val currentEpoch=2L // Silent capture disconnected while SDP was still in flight.
        var connectionId:String?=null
        val stopped=mutableListOf<String>()
        if(nativeNegotiationCurrent(offerEpoch,currentEpoch,true,false))connectionId="old-offer" else stopped.add("old-offer")
        assertNull(connectionId)
        assertEquals(listOf("old-offer"),stopped)
        assertTrue(nativeTransportNeedsConnection(connectionId,false))
    }
    @Test fun oldNegotiationCompletionCannotReplaceOrStopANewerHealthyConnection(){
        var connectionId="new-offer"
        val stopped=mutableListOf<String>()
        if(nativeNegotiationCurrent(1,3,true,true))connectionId="old-offer" else stopped.add("old-offer")
        assertEquals("new-offer",connectionId)
        assertEquals(listOf("old-offer"),stopped)
        assertFalse(nativeTransportNeedsConnection(connectionId,true))
    }
    @Test fun missingTransportRequiresReconnectEvenWithAStaleServerId(){
        assertTrue(nativeTransportNeedsConnection("stale",false))
        assertTrue(nativeTransportNeedsConnection(null,true))
        assertFalse(nativeNegotiationCurrent(1,1,false,true))
        assertTrue(nativeNegotiationCurrent(2,2,true,true))
    }
}
