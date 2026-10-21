package co.fallsoft.pocket

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranscriptWindowProjectionTest {
    private fun row(id:String,text:String=id)=JSONObject().put("id",id).put("kind","message").put("text",text)
    private fun gap(id:String,before:String)=TranscriptHistoryGap(id,"cursor-"+id,before,false,"")
    @Test fun alpha110IslandIsRetainedButNeverPaintedAsThePredecessorOfCurrentWork(){
        val rows=listOf(row("legacy","Alpha110 is installed"),row("latest","Latest canonical response"))
        val gaps=listOf(gap("missing","latest"))
        val range=connectedTranscriptRange(rows,gaps,null)
        val visible=connectedTranscriptWindow(rows,gaps,range)
        assertEquals(listOf("ui:history-gap:missing","latest"),visible.map{it.getString("id")})
        assertEquals(2,rows.size)
        assertFalse(visible.any{it.optString("text").contains("Alpha110")})
        val resumed=connectedTranscriptWindow(rows,gaps,connectedTranscriptRange(rows,gaps,"legacy"))
        assertEquals(listOf("legacy","ui:history-gap:missing"),resumed.map{it.getString("id")})
        assertEquals("newer",resumed.last().optString("historyDirection"))
        assertEquals(rows,connectedTranscriptWindow(rows,emptyList(),connectedTranscriptRange(rows,emptyList(),null)))
    }
    @Test fun repeatedHeadingCannotSwitchTheViewportBackToTheStaleIsland(){
        val rows=listOf(row("legacy","Alpha110 is installed"),
            JSONObject().put("id","current/header").put("kind","turn").put("turnId","current"),
            row("current/body").put("turnId","current"))
        val gaps=listOf(gap("missing","current/body"))
        val visible=connectedTranscriptWindow(rows,gaps,connectedTranscriptRange(rows,gaps,"current/header"))
        assertEquals(listOf("current/header","ui:history-gap:missing","current/body"),visible.map{it.getString("id")})
    }
    @Test fun newestConnectedComponentWinsAcrossMultipleRetainedIslands(){
        val rows=listOf(row("old"),row("middle"),row("current"),row("tail"))
        val gaps=listOf(gap("a","middle"),gap("b","current"))
        assertEquals(listOf("ui:history-gap:b","current","tail"),
            connectedTranscriptWindow(rows,gaps,connectedTranscriptRange(rows,gaps,null)).map{it.getString("id")})
        assertEquals(listOf("ui:history-gap:a","middle","ui:history-gap:b"),
            connectedTranscriptWindow(rows,gaps,connectedTranscriptRange(rows,gaps,"middle")).map{it.getString("id")})
    }
    @Test fun viewportHydrationHasBoundedWorkAndRejectsUnchangedCursors(){
        val b=TranscriptViewportBudget(100)
        repeat(8){assertTrue(b.next("page-"+it,101))}
        assertFalse(b.next("page-9",101))
        val stalled=TranscriptViewportBudget(0)
        assertTrue(stalled.next("same",1));assertFalse(stalled.next("same",2))
        val slow=TranscriptViewportBudget(0)
        assertTrue(slow.next("first",0));assertFalse(slow.next("second",2000))
    }
}
