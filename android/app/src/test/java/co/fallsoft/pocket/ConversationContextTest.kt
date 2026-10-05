package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class ConversationContextTest {
    @Test fun onlyTheSameKnownSessionSharesContext(){
        assertTrue(canMergeConversationContext("current", "current"))
        assertFalse(canMergeConversationContext("current", "other"))
        assertFalse(canMergeConversationContext("current", null))
        assertFalse(canMergeConversationContext(null, null))
    }
    @Test fun formattingDoesNotCreateDuplicateContext(){
        assertTrue(sameConversationContext("**The workstation is ready.**", "The workstation is ready."))
        assertTrue(sameConversationContext("The workstation\n is ready.", "The workstation is ready."))
    }
    @Test fun aSpokenExcerptDoesNotHideAdditionalContent(){
        assertFalse(sameConversationContext("The workstation temperature is normal. Fans are running correctly.", "The workstation temperature is normal."))
        assertFalse(sameConversationContext("The workstation temperature is normal.", "The workstation temperature is normal. An approval is required."))
    }
    @Test fun shortOverlapDoesNotHideAnotherMessage(){
        assertFalse(sameConversationContext("Ready", "Ready for the next workstation check."))
    }
    @Test fun unrelatedAndEmptySpeechRemainDistinct(){
        assertFalse(sameConversationContext("The workstation temperature is normal.", "A different session needs your approval."))
        assertFalse(sameConversationContext("", ""))
    }
    @Test fun mergedLiveAnswerPreservesRetainedActionsAndSpeechIdentity(){
        val note=org.json.JSONObject()
        val merged=uniqueConversationContexts(listOf(
            ConversationContextEntry("activity:t","Ready","Current action","t"),
            ConversationContextEntry("note:answer","Ready","Saved answer","t","answer",note),
            ConversationContextEntry("caption:42","Ready","Speech","t",captionId=42),
            ConversationContextEntry("manual","Ready","Manual","t",owner="reader"))).single()
        assertEquals("note:answer",merged.id);assertSame(note,merged.note)
        assertEquals("answer",merged.sourceId);assertEquals(42L,merged.captionId)
        assertEquals("reader",merged.owner)
    }
    @Test fun distinctSessionsAndExtendedPassagesRemainSelectable(){
        assertEquals(3,uniqueConversationContexts(listOf(
            ConversationContextEntry("a","Ready","Answer","t"),
            ConversationContextEntry("b","Ready","Speech","other"),
            ConversationContextEntry("c","Ready. Approval required.","Speech","t"))).size)
    }
}
