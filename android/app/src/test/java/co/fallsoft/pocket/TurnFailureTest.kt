package co.fallsoft.pocket

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnFailureTest {
    @Test fun runtimePermissionFailureIsNotCapacityOrPhoneDisconnection(){
        assertEquals(TurnFailureKind.NETWORK_PERMISSION,turnFailureKind("Error running remote compact task: Fatal error: application network permission was revoked"))
        assertEquals(TurnFailureKind.NETWORK_PERMISSION,turnFailureKind("Fatal error","application network permission was revoked"))
    }
    @Test fun onlyActualCapacityEvidenceClassifiesCapacity(){
        assertEquals(TurnFailureKind.CAPACITY,turnFailureKind("Selected model is at capacity"))
        assertEquals(TurnFailureKind.CAPACITY,turnFailureKind("systemError","Selected model is at capacity"))
        assertEquals(TurnFailureKind.OTHER,turnFailureKind("systemError"))
        assertEquals(TurnFailureKind.OTHER,turnFailureKind("Unable to connect to workstation"))
    }
    @Test fun recoveryPreservesExistingDraftAndDoesNotDuplicateInstruction(){
        val original="Do not publish anything yet."
        assertEquals("$original\n\n$SAFE_CONTINUATION",continuationDraft(original))
        assertEquals(SAFE_CONTINUATION,continuationDraft(""))
        val once=continuationDraft(original)
        assertEquals(once,continuationDraft(once))
    }
}
