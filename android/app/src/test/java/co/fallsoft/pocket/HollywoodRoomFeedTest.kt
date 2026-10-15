package co.fallsoft.pocket

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HollywoodRoomFeedTest {
    private val room="00000000-0000-4000-8000-000000000001"
    private fun event(seq:Int)=JSONObject().put("id","00000000-0000-4000-8000-"+seq.toString().padStart(12,'0')).put("seq",seq).put("body","Canonical $seq")
    private fun page(vararg seq:Int,next:Int=seq.lastOrNull()?:0,older:Int?=null)=JSONObject().put("roomId",room).put("events",JSONArray(seq.map(::event))).put("nextCursor",next).put("olderCursor",older?:JSONObject.NULL)

    @Test fun historyPublishesWhileAuxiliaryReadsAreStillBlocked()=runBlocking {
        val release=CompletableDeferred<Unit>();val started=CompletableDeferred<Unit>()
        val feed=HollywoodRoomFeed(room){path->if(path.contains("/history?"))page(100,101,older=100)else{started.complete(Unit);release.await();throw java.io.IOException("Status unavailable")}}
        val metadata=async{feed.refreshDetail()};started.await()
        feed.refreshHistory()
        assertEquals(listOf(100L,101L),feed.events.map{it.optLong("seq")})
        assertFalse(feed.loading);assertFalse(metadata.isCompleted)
        release.complete(Unit);metadata.await()
        assertEquals("Status unavailable",feed.detailError);assertEquals("",feed.historyError)
        assertEquals(101L,feed.cursor)
    }

    @Test fun olderReadCannotRewindOrEraseUpdatesReceivedDuringItsRequest()=runBlocking {
        val olderStarted=CompletableDeferred<Unit>();val olderResult=CompletableDeferred<JSONObject>()
        val feed=HollywoodRoomFeed(room){path->when{
            "before=9007199254740991" in path->page(100,101,older=100)
            "before=100" in path->{olderStarted.complete(Unit);olderResult.await()}
            "after=101" in path->page(102)
            else->error("Unexpected read $path")
        }}
        feed.refreshHistory();val earlier=async{feed.loadOlder()};olderStarted.await()
        feed.refreshHistory();val verified=feed.verifiedAt
        olderResult.complete(page(80,90,next=102,older=80));earlier.await()
        assertEquals(listOf(80L,90L,100L,101L,102L),feed.events.map{it.optLong("seq")})
        assertEquals(102L,feed.cursor);assertEquals(80L,feed.older);assertEquals(verified,feed.verifiedAt)
    }

    @Test fun readFailurePreservesContentAndRetriesOnlyTheSameReadCursor()=runBlocking {
        val paths=mutableListOf<String>();var fail=true
        val feed=HollywoodRoomFeed(room){path->paths+=path;when{
            "before=" in path->page(100)
            fail->throw java.io.IOException("Temporary read failure")
            else->page(101)
        }}
        feed.refreshHistory();feed.refreshHistory()
        assertEquals(100L,feed.cursor);assertEquals(1,feed.events.size);assertTrue(feed.historyError.isNotBlank())
        fail=false;feed.refreshHistory()
        assertEquals(paths[1],paths[2]);assertEquals(101L,feed.cursor);assertEquals("",feed.historyError)
        assertTrue(paths.all{it.startsWith("rooms/$room/history?")})
    }

    @Test fun foreignHistoryCannotReplaceTheSelectedRoomOrAdvanceItsCursor()=runBlocking {
        val feed=HollywoodRoomFeed(room){page(100).put("roomId","00000000-0000-4000-8000-000000000002")}
        feed.refreshHistory()
        assertTrue(feed.events.isEmpty());assertNull(feed.cursor);assertTrue(feed.historyError.contains("another room"))
    }
    @Test fun overnightBacklogShowsTailBeforeIndependentGapBackfill()=runBlocking {
        var initial=true
        val feed=HollywoodRoomFeed(room){path->when{
            "before=" in path->if(initial){initial=false;page(1,2)}else page(*(600..699).toList().toIntArray())
            "after=2&" in path->page(*(3..102).toList().toIntArray())
            else->{val after=path.substringAfter("after=").substringBefore("&").toInt();page(*((after+1)..(after+100)).toList().toIntArray())}
        }}
        feed.refreshHistory();feed.refreshHistory()
        assertEquals(699L,feed.events.last().optLong("seq"));assertEquals(699L,feed.cursor)
        assertTrue(feed.catchingUp)
        repeat(5){feed.fillGapPage()}
        assertFalse(feed.catchingUp)
        assertEquals((1L..699L).toList(),feed.events.map{it.optLong("seq")})
        assertEquals(699L,feed.cursor)
    }

}
