package co.fallsoft.pocket

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FleetRoomProjectionTest {
    private fun id(n:Int)="00000000-0000-4000-8000-"+n.toString().padStart(12,'0')
    private fun snapshot(host:Int,seq:Int,available:Boolean=true,viewer:Int=7):WorkSourceSnapshot<WorkCatalogPayload> {
        val environment=EnvironmentIdentity("host$host","Host $host",fleet=FleetMembership(id(1),id(2),id(host),id(3)))
        val destination=EnvironmentRequest(environment,"https://host$host.test","private-$host")
        val source=WorkSourceKey(environment.id,"owner$host","lax")
        val event=JSONObject().put("eventId",id(100+seq)).put("seq",seq).put("occurredAt",seq*1000L)
        val summary=JSONObject().put("version",1).put("latestMessage",JSONObject(event.toString()).put("body","Message $seq").put("truncated",false))
            .put("latestActivity",JSONObject.NULL).put("recency",event)
        val room=JSONObject().put("id",id(8)).put("name","Shared room").put("feedSummary",summary)
            .put("activity",JSONObject().put("running",host))
        val binding=JSONObject().put("principalId",id(viewer)).put("authorityId",id(9))
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
    @Test fun differentViewersAndUnenrolledLegacyConnectionsAreNeverCollapsed() {
        assertEquals(2,fleetRoomProjections(listOf(snapshot(4,10),snapshot(5,20,viewer=11))).size)
        val first=snapshot(4,10);val entry=first.entries.single();val legacy=first.copy(entries=listOf(entry.copy(value=entry.value.copy(
            destination=entry.value.destination.copy(environment=EnvironmentIdentity("legacy","Legacy"))))))
        assertEquals(2,fleetRoomProjections(listOf(legacy,snapshot(5,20))).size)
    }
}
