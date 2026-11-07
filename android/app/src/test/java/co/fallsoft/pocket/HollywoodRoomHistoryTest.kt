package co.fallsoft.pocket

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HollywoodRoomHistoryTest {
    private val room="00000000-0000-4000-8000-000000000001"
    private fun event(seq:Int)=JSONObject().put("id","00000000-0000-4000-8000-"+seq.toString().padStart(12,'0')).put("seq",seq).put("body","Canonical $seq")
    private fun page(vararg seq:Int)=JSONObject().put("roomId",room).put("view","conversation").put("events",JSONArray(seq.map(::event)))
        .put("nextCursor",seq.last()).put("olderCursor",seq.first())
    @Test fun anotherGatewayRestoresLoadedContentThenReadsOnlyNewCanonicalEvents()=runBlocking {
        val key=UUID.randomUUID().toString();val first=HollywoodRoomFeed(room,"conversation",key){page(10,11)}
        first.refreshHistory();val paths=mutableListOf<String>();val migrated=UUID.randomUUID().toString()
        HollywoodRoomHistory.migrate(key,migrated)
        val second=HollywoodRoomFeed(room,"conversation",migrated){path->paths+=path;page(12)}
        assertEquals(listOf(10L,11L),second.events.map{it.optLong("seq")});assertFalse(second.loading)
        second.refreshHistory();assertTrue(paths.single().contains("after=11&"))
        assertEquals(listOf(10L,11L,12L),second.events.map{it.optLong("seq")})
        val offline=HollywoodRoomFeed(room,"conversation",migrated){throw java.io.IOException("Offline")};offline.refreshHistory()
        assertEquals(listOf(10L,11L,12L),offline.events.map{it.optLong("seq")});assertEquals("Offline",offline.historyError)
        val foreign=HollywoodRoomFeed(room,"conversation",UUID.randomUUID().toString()){page(20)};assertTrue(foreign.events.isEmpty())
    }
    @Test fun lateOldRouteCanAddAnOlderPageButCannotRewindTheNewHeadOrItsGaps() {
        val key=UUID.randomUUID().toString();val old=Any();val fresh=Any()
        HollywoodRoomHistory.put(key,HollywoodHistorySnapshot(listOf(event(10)),10,10,emptyList(),100,old))
        HollywoodRoomHistory.put(key,HollywoodHistorySnapshot(listOf(event(10),event(12)),12,10,listOf(10L to 12L),200,fresh))
        HollywoodRoomHistory.put(key,HollywoodHistorySnapshot(listOf(event(5),event(10)),10,5,emptyList(),100,old))
        val retained=HollywoodRoomHistory.get(key)!!
        assertEquals(12L,retained.cursor);assertEquals(5L,retained.older);assertEquals(listOf(10L to 12L),retained.gaps)
        assertEquals(listOf(5L,10L,12L),retained.events.map{it.optLong("seq")})
    }
}
