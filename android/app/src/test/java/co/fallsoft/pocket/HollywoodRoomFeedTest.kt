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


    @Test fun anOlderStatusReadCannotEraseANewValidatedQueueReceipt()=runBlocking {
        val started=CompletableDeferred<Unit>();val old=CompletableDeferred<JSONObject>();var reads=0
        fun quiet()=JSONObject().put("room",JSONObject().put("id",room)).put("deliveries",JSONArray())
        val feed=HollywoodRoomFeed(room){if(reads++==0){started.complete(Unit);old.await()}else quiet()}
        val waiting=async{feed.refreshDetail()};started.await()
        val accepted=event(200).put("room",room).put("kind","request")
        val delivery=JSONObject().put("id","00000000-0000-4000-8000-000000000201").put("event",accepted.s("id"))
            .put("agent","00000000-0000-4000-8000-000000000202").put("state","pending")
        val receipt=JSONObject().put("accepted",true).put("event",accepted).put("deliveries",JSONArray().put(delivery))
        feed.accepted(receipt);old.complete(quiet());waiting.await()
        assertEquals("Queued",hollywoodRoomWork(feed.detail,"",emptyList(),feed.justAccepted).label)
        assertFalse(feed.justAccepted.single().has("can_cancel"))
        feed.refreshDetail()
        assertTrue(feed.justAccepted.isEmpty());assertEquals("Idle",hollywoodRoomWork(feed.detail,"",emptyList()).label)
        // An optional presentation hint may never turn a validated acceptance into an exception.
        delivery.put("state","completed");feed.accepted(receipt);assertTrue(feed.justAccepted.isEmpty())
    }

    @Test fun projectionsKeepIndependentCursorsAndRejectAnUnfilteredResponse()=runBlocking{
        val paths=mutableListOf<String>()
        val chat=HollywoodRoomFeed(room,"conversation"){path->paths+=path;page(7,next=400).put("view","conversation")}
        val actions=HollywoodRoomFeed(room,"activity"){path->paths+=path;page(390,391,next=401).put("view","activity")}
        coroutineScope{launch{chat.refreshHistory()};launch{actions.refreshHistory()}}
        assertEquals(400L,chat.cursor);assertEquals(401L,actions.cursor)
        assertEquals(listOf(7L),chat.events.map{it.optLong("seq")})
        assertTrue(paths.any{"view=conversation" in it});assertTrue(paths.any{"view=activity" in it})
        val unfiltered=HollywoodRoomFeed(room,"conversation"){page(50).put("view","all")}
        unfiltered.refreshHistory()
        assertTrue(unfiltered.events.isEmpty());assertNull(unfiltered.cursor)
        assertTrue(unfiltered.historyError.contains("projection"))
    }

}
