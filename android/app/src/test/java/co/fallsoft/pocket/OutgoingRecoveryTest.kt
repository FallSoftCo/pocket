package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class OutgoingRecoveryTest {
    private fun row(state:String)=OutgoingRowState("reply",state,"Original","Rejected")
    @Test fun removalImmediatelyClearsAcknowledgedRowWithoutHistoryRefresh(){
        val original=listOf(row("failed"),row("unknown").copy(id="other"))
        val updated=original.mapNotNull{applyOutgoingAcknowledgement(it,"reply","remove","","cancelled")}
        assertEquals(1,updated.size);assertEquals("other",updated[0].id)
        assertEquals(2,original.size)
    }
    @Test fun editAndRetryApplyAcknowledgedTextAndClearStaleFailure(){
        val edited=applyOutgoingAcknowledgement(row("unknown"),"reply","edit"," Corrected ","unknown")!!
        assertEquals("Corrected",edited.text);assertEquals("unknown",edited.state)
        val retried=applyOutgoingAcknowledgement(edited,"reply","retry","","queued")!!
        assertEquals("queued",retried.state);assertNull(retried.result)
    }
    @Test fun liveAcceptanceCannotBeRegressedByOlderRetryResponse(){
        for(state in listOf("accepted","sending")) {
            assertEquals(state,applyOutgoingAcknowledgement(row(state),"reply","retry","","queued")!!.state)
            assertEquals(row(state),applyOutgoingAcknowledgement(row(state),"reply","remove","","cancelled"))
        }
    }
}
