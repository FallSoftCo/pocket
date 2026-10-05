package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class TeamScopeTest {
    @Test fun draftsNeverFollowAnotherTaskRecipientVisibilityOrReply(){
        val scope=TeamScope("team","task","maya",false,3)
        val key=scope.draftKey("environment")
        assertNotEquals(key,scope.copy(task="other").draftKey("environment"))
        assertNotEquals(key,scope.copy(team="other").draftKey("environment"))
        assertNotEquals(key,scope.copy(recipient="theo").draftKey("environment"))
        assertNotEquals(key,scope.copy(direct=true).draftKey("environment"))
        assertNotEquals(key,scope.copy(replyTo=4).draftKey("environment"))
        assertNotEquals(key,scope.draftKey("another environment"))
        assertEquals(key,scope.copy().draftKey("environment"))
    }
    @Test fun repairingOrChangingAPairingCannotReadThePreviousBackendDrafts(){
        assertNotEquals(backendOwner("https://host","first-token"),backendOwner("https://host","second-token"))
        assertNotEquals(backendOwner("https://first-host","token"),backendOwner("https://second-host","token"))
    }
    @Test fun delayedHistoryIsRejectedAfterAnyHistoryDestinationChanges(){
        val captured=TeamScope("team","task","maya",true,1)
        assertTrue(sameTeamHistoryScope(captured.copy(replyTo=2),captured))
        assertFalse(sameTeamHistoryScope(captured.copy(team="other"),captured))
        assertFalse(sameTeamHistoryScope(captured.copy(task="other"),captured))
        assertFalse(sameTeamHistoryScope(captured.copy(recipient="theo"),captured))
        assertFalse(sameTeamHistoryScope(captured.copy(direct=false),captured))
    }
    @Test fun replyingToOwnOrSystemDirectMessageKeepsTheOtherParticipant(){
        val direct=TeamScope("team","task","maya",true)
        assertEquals("maya",teamReplyRecipient(direct,"you"))
        assertEquals("maya",teamReplyRecipient(direct,"system"))
        assertEquals("theo",teamReplyRecipient(direct,"theo"))
        assertEquals("",teamReplyRecipient(direct.copy(direct=false),"you"))
    }
    @Test fun physicalEnterSendsWhileShiftEnterRemainsAnEditingKey(){
        assertTrue(teamEnterSends(true,false));assertFalse(teamEnterSends(true,true))
        assertFalse(teamEnterSends(false,false));assertFalse(teamEnterSends(false,true))
    }
}
