package co.fallsoft.pocket

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FleetIdentityTest {
    private fun id(n:Int)="00000000-0000-4000-8000-"+n.toString().padStart(12,'0')
    private fun descriptor()=JSONObject().put("version",1).put("id",id(1)).put("accountId",id(2))
        .put("nodeId",id(3)).put("sessionPath","/api/fleet/session").put("members",JSONArray()
            .put(JSONObject().put("id",id(3)).put("name","CPU").put("origin","https://cpu.example.test:19426"))
            .put(JSONObject().put("id",id(4)).put("name","GPU").put("origin","https://gpu.example.test:19426")))
    @Test fun discoveryRejectsAmbiguousOrCredentialBearingDestinations(){
        assertEquals(2,FleetDescriptor.read(descriptor()).members.size)
        for(origin in listOf("http://cpu.example.test","https://user:password@cpu.example.test","https://cpu.example.test/#other","https://cpu.example.test/path","https://cpu.example.test:443","https://CPU.example.test")){
            val value=descriptor();value.getJSONArray("members").getJSONObject(0).put("origin",origin)
            assertTrue(runCatching{FleetDescriptor.read(value)}.isFailure)
        }
        val duplicate=descriptor();duplicate.getJSONArray("members").put(duplicate.getJSONArray("members").getJSONObject(0))
        assertTrue(runCatching{FleetDescriptor.read(duplicate)}.isFailure)
    }
    @Test fun refreshAndGatewayChangesDoNotChangeRoomOwnership(){
        val descriptor=FleetDescriptor.read(descriptor());val now=100000L
        val login=JSONObject().put("version",1).put("fleetId",descriptor.id).put("accountId",descriptor.accountId)
            .put("deviceId",id(5)).put("token","a".repeat(64)).put("expiresAt",now+60000)
        val first=FleetSession.read(login,descriptor,now)
        val renewed=FleetSession.read(JSONObject(login.toString()).put("token","b".repeat(64)),descriptor,now)
        assertNotEquals(first.token,renewed.token)
        val room=FleetWorkIdentity(descriptor.id,descriptor.accountId,id(6),WorkKind.ROOM,id(7),id(8))
        assertEquals(room.key,room.copy().key)
        assertNotEquals(room.key,room.copy(accountId=id(9)).key)
        val session=room.copy(kind=WorkKind.SESSION,namespace=id(3))
        assertNotEquals(session.key,session.copy(namespace=id(4)).key)
        assertTrue(runCatching{FleetSession.read(JSONObject(login.toString()).put("accountId",id(9)),descriptor,now)}.isFailure)
        assertTrue(runCatching{FleetSession.read(JSONObject(login.toString()).put("expiresAt",now),descriptor,now)}.isFailure)
        assertFalse(first.toString().contains(first.token))
    }
}
