package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class TeamScopeTest {
    @Test fun lobbyDefaultIsOnlyCoordinatorWhileExplicitRepliesAndOtherRoomsStayCaptured(){
        val room=org.json.JSONObject("""{"task":"team-enterprise","members":[{"agent":"ozzz-marketing-owner"},{"agent":"global-steward"}]}""")
        val scope=TeamScope("enterprise","team-enterprise")
        val root=scopedRoomMessage(scope,"root","Human Global message",room,"team-enterprise","global-steward")
        assertEquals(listOf("global-steward"),root.getJSONArray("targets").objectsOrStrings())
        assertFalse(root.has("replyTo"))
        val reply=scopedRoomMessage(scope.copy(recipient="ozzz-marketing-owner",replyTo=8),"reply","Explicit reply",room,"team-enterprise","global-steward")
        assertEquals(listOf("ozzz-marketing-owner"),reply.getJSONArray("targets").objectsOrStrings());assertEquals(8L,reply.optLong("replyTo"))
        val captured=root.toString();room.put("members",org.json.JSONArray());assertEquals(captured,root.toString())
        assertEquals(scope,notificationTeamScope("enterprise","team-enterprise","ozzz-marketing-owner","room"))
        assertEquals(scope.copy(recipient="ozzz-marketing-owner",direct=true),notificationTeamScope("enterprise","team-enterprise","ozzz-marketing-owner","direct:ozzz-marketing-owner"))
    }
    private fun org.json.JSONArray.objectsOrStrings()=(0 until length()).map{getString(it)}
    @Test fun addressedRoomOwnerNeverFallsBackToGlobalOrChangedMembership(){
        val source=TeamScope("owner-team","enterprise-lax","lax-owner")
        val unrelated=org.json.JSONObject("""{"task":"enterprise-nextcomp","members":[{"agent":"global-steward"}]}""")
        val saved=scopedRoomMessage(source,"same-command","Direct room instruction",unrelated)
        val wire=saved.toString()
        assertEquals("enterprise-lax",saved.getString("task"));assertEquals("same-command",saved.getString("commandId"))
        assertEquals(1,saved.getJSONArray("targets").length());assertEquals("lax-owner",saved.getJSONArray("targets").getString(0))
        assertEquals("room",saved.getString("visibility"))
        unrelated.put("members",org.json.JSONArray());assertEquals(wire,saved.toString())
        assertEquals("same-command",org.json.JSONObject(wire).getString("commandId"))
    }
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
        assertTrue(sameTeamHistoryScope(captured.copy(direct=false),captured.copy(direct=false,recipient="theo")))
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
    @Test fun collectiveSendCapturesOnlyItsSelectedDestinationsAndPrivateReplyStaysIndependent(){
        val room=org.json.JSONObject("""{"id":"one","task":"task","members":[{"agent":"maya","thread":"first"},{"agent":"theo","thread":"second"}]}""")
        val scope=TeamScope("team","task")
        val group=roomSessionGroup(room)
        assertEquals(2,group.children.size);assertTrue(group.children.all{!it.isChild&&it.parentThreadId==null})
        val saved=scopedRoomMessage(scope,"captured","Work",room)
        assertEquals(listOf("maya","theo"),(0 until saved.getJSONArray("targets").length()).map{saved.getJSONArray("targets").getString(it)})
        room.put("members",org.json.JSONArray().put(org.json.JSONObject().put("agent","other")))
        assertEquals("maya",saved.getJSONArray("targets").getString(0))
        val direct=scopedRoomMessage(scope.copy(recipient="maya",direct=true,replyTo=7),"private","Guide",room)
        assertEquals("direct",direct.s("visibility"));assertEquals(7L,direct.optLong("replyTo"));assertEquals("maya",direct.getJSONArray("targets").getString(0))
        assertNotEquals(roomSessionGroup(room).task.id,roomSessionGroup(org.json.JSONObject(room.toString()).put("id","two")).task.id)
    }

}
