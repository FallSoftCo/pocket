package co.fallsoft.pocket

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoomActorIdentityTest {
    private val agent="11111111-1111-4111-8111-111111111111"
    private val host="22222222-2222-4222-8222-222222222222"
    private fun actor(name:String)=JSONObject().put("id",agent).put("name","Global").put("kind","agent")
        .put("identity",JSONObject().put("version",1).put("agentId",agent).put("host",JSONObject().put("id",host).put("name",name)))
    @Test fun canonicalHostDisambiguatesWithoutRenamingPrincipal() {
        val a=actor("MacBook Pro native host");assertEquals("Global · MacBook Pro native host",roomActorLabel(a));assertEquals("Global",a.s("name"))
        assertEquals("Global · Mac development VM",roomActorLabel(actor("Mac development VM")))
        val named=actor("MacBook Pro").put("name","Example room · MacBook Pro")
        assertEquals("Example room · MacBook Pro",roomActorLabel(named))
    }
    @Test fun wrongIdentityCannotLabelDifferentAuthor() {
        val a=actor("Wrong host");a.getJSONObject("identity").put("agentId",host)
        assertEquals("Global",roomActorLabel(a))
    }
    @Test fun privateNotificationKeepsOriginalRoomAndVerifiedAuthor() {
        val original=JSONObject().put("hint",JSONObject().put("roomName","8bar Development"))
            .put("event",JSONObject().put("author",agent).put("audience","direct"))
            .put("author",actor("MacBook Pro native host").getJSONObject("identity").put("name","Global").put("kind","agent"))
        val before=original.toString()
        assertEquals("Private · 8bar Development · Global · MacBook Pro native host",roomNotificationTitle(original));assertEquals(before,original.toString())
        original.getJSONObject("author").put("agentId",host)
        assertEquals("Private · 8bar Development",roomNotificationTitle(original))
    }
    @Test fun stagedNativeReadyIsNotCompletedHandoff() {
        val detail=JSONObject().put("roomAgents",JSONObject().put("version",1).put("generation",JSONObject().put("phase","staging"))
            .put("agents",JSONArray().put(JSONObject().put("ready",true))))
        assertEquals("Room agents: 1/1 ready · Handoff required",roomOriginSummary(detail))
        assertEquals("Room agent status unavailable",roomOriginSummary(detail,false))
    }
    private val roomId="33333333-3333-4333-8333-333333333333"
    private fun setup(ready:Boolean=false)=JSONObject().put("room",JSONObject().put("id",roomId)).put("deliveries",JSONArray())
        .put("roomAgents",JSONObject().put("version",1).put("roomId",roomId)
            .put("generation",JSONObject().put("phase","active"))
            .put("agents",JSONArray().put(JSONObject().put("agent",agent).put("node",host).put("ready",ready))))
    @Test fun setupIsVisibleWithoutHidingActualWork() {
        val pending=setup();assertEquals("Preparing agents · 0/1",hollywoodRoomWork(pending,"",emptyList()).label)
        pending.getJSONArray("deliveries").put(JSONObject().put("id",agent).put("state","running"))
        assertEquals("Running",hollywoodRoomWork(pending,"",emptyList()).label)
        assertNull(roomOriginPendingLabel(setup(true)))
    }
    @Test fun foreignOrDuplicateEnvironmentCannotPretendReady() {
        val duplicate=setup();val agents=duplicate.getJSONObject("roomAgents").getJSONArray("agents")
        agents.put(JSONObject().put("agent",roomId).put("node",host).put("ready",true))
        assertEquals("Room agent status unavailable",roomOriginPendingLabel(duplicate))
        val foreign=setup();foreign.getJSONObject("roomAgents").put("roomId",host)
        assertEquals("Room agent status unavailable",roomOriginPendingLabel(foreign))
        val malformed=setup();malformed.getJSONObject("roomAgents").getJSONArray("agents").getJSONObject(0).put("ready","true")
        assertEquals("Room agent status unavailable",roomOriginPendingLabel(malformed))
    }

    @Test fun retainedLegacyProjectionDoesNotInventInitialization() {
        val retained=setup().apply{getJSONObject("roomAgents").put("initializationRequired",false).put("agents",JSONArray())}
        assertNull(roomOriginPendingLabel(retained));assertEquals("Room participants",roomOriginSummary(retained))
        val stale=setup(true);assertTrue(hollywoodRoomWork(stale,"Unavailable",emptyList()).setupAgents.isEmpty())
    }

}
