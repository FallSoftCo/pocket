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
}
