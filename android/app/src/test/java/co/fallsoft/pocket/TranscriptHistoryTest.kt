package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class TranscriptHistoryTest {
    private data class Row(val id:String,val text:String="",val version:Long=0)
    private fun merge(old:List<Row>,page:List<Row>,older:Boolean=false)=mergeTranscriptPage(old,page,older,{it.id},{it.version})
    @Test fun olderPagesPrependWithoutHidingRecentlyReadMessages(){
        val recent=(8..15).map{Row("$it")}
        val all=merge(recent,(0..7).map{Row("$it")},true)
        assertEquals((0..15).map{"$it"},all.map{it.id})
        assertEquals(all,merge(all,recent))
    }
    @Test fun currentRefreshAndStreamingRetainFarMoreThanPreviousFiveHundredRowCap(){
        val read=(0..799).map{Row("$it")}
        val next=merge(read,(792..820).map{Row("$it")})
        assertEquals(821,next.size)
        assertEquals("0",next.first().id)
        assertEquals("820",next.last().id)
    }
    @Test fun olderResponseCannotRollBackLiveOutput(){
        val live=listOf(Row("a","fresh",20),Row("b","live tail",21))
        val page=listOf(Row("past"),Row("a","stale",19))
        val result=merge(live,page,true)
        assertEquals(listOf("past","a","b"),result.map{it.id})
        assertEquals("fresh",result[1].text)
    }
    @Test fun overlappingPagesDeduplicateByMessageIdentityAndAcceptUpdatedText(){
        val result=merge(listOf(Row("a","draft",1),Row("b")),listOf(Row("a","complete",2),Row("b"),Row("c")))
        assertEquals(listOf("a","b","c"),result.map{it.id})
        assertEquals("complete",result.first().text)
    }
    @Test fun thumbPrefetchNeverRunsWhileFollowingLatestFilteringOrAlreadyLoading(){
        assertTrue(shouldPrefetchTranscript(false,false,4,true,false))
        assertFalse(shouldPrefetchTranscript(true,false,0,true,false))
        assertFalse(shouldPrefetchTranscript(false,true,0,true,false))
        assertFalse(shouldPrefetchTranscript(false,false,5,true,false))
        assertFalse(shouldPrefetchTranscript(false,false,0,true,true))
        assertFalse(shouldPrefetchTranscript(false,false,0,false,false))
    }
    @Test fun reopeningAfterManyNewTurnsFillsTheMiddleWithoutReorderingReadHistory(){
        val retained=(0..7).map{Row("$it/header")}
        val recent=(16..23).map{Row("$it/header")}
        val quick=merge(retained,recent)
        val filled=mergeTranscriptPage(quick,(8..15).map{Row("$it/header")},false,{it.id},{it.version},"16/header")
        assertEquals((0..23).map{"$it/header"},filled.map{it.id})
    }
    @Test fun recentRefreshAndMiddleBridgeKeepOldestLoadedCursorOnReopen(){
        val oldest=TranscriptCursor("turn-8",true)
        val fresh=TranscriptCursor("turn-24",true)
        assertEquals(oldest,retainedTranscriptCursor(oldest,true,false,false,fresh))
        assertEquals(oldest,retainedTranscriptCursor(oldest,true,false,true,fresh))
        assertEquals(TranscriptCursor("turn-0",false),retainedTranscriptCursor(oldest,true,true,false,TranscriptCursor("turn-0",false)))
        assertEquals(fresh,retainedTranscriptCursor(TranscriptCursor(null,false),false,false,false,fresh))
    }
}
