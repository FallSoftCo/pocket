package co.fallsoft.pocket

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FleetRoomProjectionTest {
    private fun id(n:Int)="00000000-0000-4000-8000-"+n.toString().padStart(12,'0')
    private fun snapshot(host:Int,seq:Int,available:Boolean=true,viewer:Int=7,voice:Boolean=false):WorkSourceSnapshot<WorkCatalogPayload> {
        val environment=EnvironmentIdentity("host$host","Host $host",fleet=FleetMembership(id(1),id(2),id(host),id(3)))
        val destination=EnvironmentRequest(environment,"https://host$host.test","private-$host")
        val source=WorkSourceKey(environment.id,"owner$host","lax")
        val event=JSONObject().put("eventId",id(100+seq)).put("seq",seq).put("occurredAt",seq*1000L)
        val summary=JSONObject().put("version",1).put("latestMessage",JSONObject(event.toString()).put("body","Message $seq").put("truncated",false))
            .put("latestActivity",JSONObject.NULL).put("recency",event)
        val room=JSONObject().put("id",id(8)).put("name","Shared room").put("feedSummary",summary)
            .put("activity",JSONObject().put("running",host))
        val binding=JSONObject().put("voice",voice).put("principalId",id(viewer)).put("authorityId",id(9))
        val entry=WorkEntry(WorkItemKey(source,WorkKind.ROOM,id(9),id(8)),seq*1000L,
            WorkCatalogPayload(destination,room.toString(),binding.toString()))
        return WorkSourceSnapshot(source,1,listOf(entry),observedAt=1000,error=if(available)null else "Unavailable")
    }
    @Test fun oneRoomRetainsNewerContentWhileUsingAnotherLiveRouteWithoutRebindingItsCredentials() {
        val oldLive=snapshot(4,10);val newOffline=snapshot(5,20,false)
        val projections=fleetRoomProjections(listOf(oldLive,newOffline))
        assertEquals(1,projections.size)
        val room=projections.single()
        assertEquals("host4",room.route.value.destination.environment.id)
        assertEquals("host5",room.content.value.destination.environment.id)
        assertEquals(20_000L,room.content.workAt)
        assertEquals(4,room.displayRoom().getJSONObject("activity").getInt("running"))
        assertEquals(room.identity,fleetRoomProjections(listOf(snapshot(5,20),snapshot(4,10,false))).single().identity)
        assertEquals(oldLive.entries.single().key,room.route.key)
        assertEquals("private-4",room.route.value.destination.token)
    }
    private fun native(snapshot:WorkSourceSnapshot<WorkCatalogPayload>,observedAt:Long,status:String):WorkSourceSnapshot<WorkCatalogPayload> {
        val entry=snapshot.entries.single()
        val state=JSONObject().put("version",1).put("observedAt",observedAt).put("runtimeCount",1)
            .put("agents",org.json.JSONArray().put(JSONObject().put("agentId",id(42)).put("nativeThread","existing")
                .put("expiresAt",observedAt+75_000).put("status",JSONObject().put("type",status).put("activeFlags",org.json.JSONArray()))))
        return snapshot.copy(entries=listOf(entry.copy(value=entry.value.copy(body=JSONObject(entry.value.body).put("nativeState",state).toString()))))
    }
    @Test fun latestPreviewCannotPinExpiredNativeStatusFromAnotherGateway() {
        val live=native(snapshot(4,10),50_000,"idle")
        val retained=native(snapshot(5,20,false),1_000,"unavailable")
        val room=fleetRoomProjections(listOf(live,retained)).single()
        val display=room.displayRoom()
        assertEquals(20,display.getJSONObject("feedSummary").getJSONObject("latestMessage").getInt("seq"))
        assertEquals(50_000L,display.getJSONObject("nativeState").getLong("observedAt"))
        assertTrue(HollywoodRoomActivity.read(display,true)!!.idle)
        assertEquals("private-4",room.route.value.destination.token)
        assertEquals(1_000L,JSONObject(retained.entries.single().value.body).getJSONObject("nativeState").getLong("observedAt"))
    }
    @Test fun missingCurrentNativeObservationCannotFallBackToRetainedUnknownOrLegacyIdle() {
        val retained=native(snapshot(5,20,false),1_000,"unavailable")
        val live=snapshot(4,10)
        val display=fleetRoomProjections(listOf(live,retained)).single().displayRoom()
        assertTrue(display.isNull("nativeState"))
        assertNull(HollywoodRoomActivity.read(display,true))
        assertTrue(HollywoodRoomActivity.usesNative(display))
    }
    private fun setup(snapshot:WorkSourceSnapshot<WorkCatalogPayload>,blocked:Boolean):WorkSourceSnapshot<WorkCatalogPayload> {
        val entry=snapshot.entries.single()
        val value=JSONObject().put("version",1).put("roomId",id(8)).put("initializationRequired",true)
            .put("generation",JSONObject().put("phase","active"))
            .put("agents",org.json.JSONArray().put(JSONObject().put("agent",id(42)).put("node",id(43))
                .put("ready",!blocked).put("readiness",if(blocked)"blocked" else "ready")))
        return snapshot.copy(entries=listOf(entry.copy(value=entry.value.copy(body=JSONObject(entry.value.body).put("roomAgents",value).toString()))))
    }
    @Test fun currentSetupReplacesRetainedBlockedSetupWithoutMovingPreviewOrReplyRoute() {
        val retained=setup(snapshot(5,20,false),true)
        val live=setup(snapshot(4,10),false)
        val projected=fleetRoomProjections(listOf(live,retained)).single()
        assertEquals("Agent setup blocked",roomOriginPendingLabel(JSONObject(retained.entries.single().value.body)))
        val display=projected.displayRoom()
        assertNull(roomOriginPendingLabel(display))
        assertEquals(20,display.getJSONObject("feedSummary").getJSONObject("recency").getInt("seq"))
        assertEquals("private-4",projected.route.value.destination.token)
        assertEquals("Agent setup blocked",roomOriginPendingLabel(JSONObject(retained.entries.single().value.body)))
        val nowBlocked=fleetRoomProjections(listOf(setup(live,true),setup(retained,false))).single().displayRoom()
        assertEquals("Agent setup blocked",roomOriginPendingLabel(nowBlocked))
    }
    @Test fun missingLiveSetupCannotResurrectRetainedFailureAndDifferentViewersRemainSeparate() {
        val retained=setup(snapshot(5,20,false),true)
        val live=snapshot(4,10)
        assertFalse(fleetRoomProjections(listOf(live,retained)).single().displayRoom().has("roomAgents"))
        val otherViewer=setup(snapshot(6,30,viewer=11),true)
        val separate=fleetRoomProjections(listOf(setup(live,false),otherViewer))
        assertEquals(2,separate.size)
        assertEquals(1,separate.count{roomOriginPendingLabel(it.displayRoom())=="Agent setup blocked"})
    }
    @Test fun availableVoiceGatewayKeepsTheFullInteractionWithoutFakingAnExecutionHost() {
        assertEquals("host5",fleetRoomProjections(listOf(snapshot(4,20),snapshot(5,10,voice=true))).single().route.value.destination.environment.id)
        assertEquals("host4",fleetRoomProjections(listOf(snapshot(4,20),snapshot(5,10,available=false,voice=true))).single().route.value.destination.environment.id)
    }
    @Test fun differentViewersAndUnenrolledLegacyConnectionsAreNeverCollapsed() {
        assertEquals(2,fleetRoomProjections(listOf(snapshot(4,10),snapshot(5,20,viewer=11))).size)
        val first=snapshot(4,10);val entry=first.entries.single();val legacy=first.copy(entries=listOf(entry.copy(value=entry.value.copy(
            destination=entry.value.destination.copy(environment=EnvironmentIdentity("legacy","Legacy"))))))
        assertEquals(2,fleetRoomProjections(listOf(legacy,snapshot(5,20))).size)
    }
    private fun proof(row:WorkSourceSnapshot<WorkCatalogPayload>,viewer:Char):WorkSourceSnapshot<WorkCatalogPayload> =
        row.copy(entries=row.entries.map{it.copy(value=it.value.copy(viewer=HollywoodCatalogViewer(id(9),viewer.toString().repeat(64))))})

    @Test fun federatedHumanHasOneRoomEvenWhenGatewayPrincipalsDifferAndPhysicalRoutesStayIntact() {
        val first=proof(snapshot(4,10,viewer=7,voice=true),'a')
        val second=proof(snapshot(5,20,viewer=11),'a')
        val projected=fleetRoomProjections(listOf(first,second)).single()
        assertEquals("host4",projected.route.value.destination.environment.id)
        assertEquals(id(7),JSONObject(projected.route.value.binding!!).getString("principalId"))
        assertEquals("private-4",projected.route.value.destination.token)
        assertEquals("host5",projected.content.value.destination.environment.id)
        assertEquals(id(11),JSONObject(projected.content.value.binding!!).getString("principalId"))
        assertEquals(projected.identity,fleetRoomProjections(listOf(second,first)).single().identity)
        assertEquals(2,fleetRoomProjections(listOf(first,proof(second,'b'))).size)
        // Legacy/unverified routes never acquire another human's audience by account alone.
        assertEquals(2,fleetRoomProjections(listOf(first,snapshot(5,20,viewer=11))).size)
    }
    @Test fun catalogProofIsOptionalButPresentMalformedOrWrongAuthorityIsNotTreatedAsVerified() {
        val binding=JSONObject().put("authorityId",id(9))
        assertNull(HollywoodCatalogViewer.read(JSONObject(),binding))
        val proof=JSONObject().put("version",1).put("authorityId",id(9)).put("viewerId","a".repeat(64))
        assertEquals("a".repeat(64),HollywoodCatalogViewer.read(JSONObject().put("viewerIdentity",proof),binding)!!.id)
        for(value in listOf(JSONObject.NULL,JSONObject(proof.toString()).put("viewerId",""),
            JSONObject(proof.toString()).put("authorityId",id(10)),JSONObject(proof.toString()).put("version",2))) {
            assertTrue(runCatching{HollywoodCatalogViewer.read(JSONObject().put("viewerIdentity",value),binding)}.isFailure)
        }
    }

}
