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
        val split=listOf(rows[1],row("same-turn-old").put("turnId","current"),rows[2])
        assertEquals(listOf("current/header","ui:history-gap:missing","current/body"),
            connectedTranscriptWindow(split,gaps,connectedTranscriptRange(split,gaps,"current/header")).map{it.getString("id")})
    }
    @Test fun newestConnectedComponentWinsAcrossMultipleRetainedIslands(){
        val rows=listOf(row("old"),row("middle"),row("current"),row("tail"))
        val gaps=listOf(gap("a","middle"),gap("b","current"))
        assertEquals(listOf("ui:history-gap:b","current","tail"),
            connectedTranscriptWindow(rows,gaps,connectedTranscriptRange(rows,gaps,null)).map{it.getString("id")})
        assertEquals(listOf("ui:history-gap:a","middle","ui:history-gap:b"),
            connectedTranscriptWindow(rows,gaps,connectedTranscriptRange(rows,gaps,"middle")).map{it.getString("id")})
    }
    @Test fun delayedInputPublishesOnceAfterItsPositionProofNotBefore(){
        val answer=row("turn/answer").put("turnId","turn")
        val input=JSONObject().put("id","turn/input").put("kind","user").put("turnId","turn").put("text","Request")
        val raw=listOf(answer,input)
        assertEquals(setOf("turn/input"),unplacedTranscriptInputs(raw))
        assertTrue(unplacedTranscriptInputs(listOf(input,answer)).isEmpty())
        val proven=listOf(positionedTranscriptInput(input,"turn/answer"),answer)
        assertTrue(unplacedTranscriptInputs(proven).isEmpty())
        val gaps=listOf(gap("unloaded-raw-items","turn/answer"))
        val visible=connectedTranscriptWindow(proven,gaps,connectedTranscriptRange(proven,gaps,"turn/input"))
        assertEquals(listOf("turn/input","ui:history-gap:unloaded-raw-items","turn/answer"),visible.map{it.getString("id")})
        val updated=mergeTranscriptMessageEvidence(proven.first(),JSONObject(input.toString()).put("version",999))
        assertEquals("canonical",updated.optString("_inputOrder"))
        assertEquals("turn/answer",updated.optString("_inputSuccessor"))
    }
    @Test fun savedViewportCannotPinFollowingReaderToAnOlderDisconnectedComponent(){
        val rows=listOf(row("old"),row("current"))
        val gaps=listOf(gap("missed","current"))
        val measured=TranscriptReadPosition("old",0,true)
        val following=connectedTranscriptRange(rows,gaps,transcriptWindowAnchor(measured,true))
        assertEquals(listOf("ui:history-gap:missed","current"),connectedTranscriptWindow(rows,gaps,following).map{it.s("id")})
        val reading=connectedTranscriptRange(rows,gaps,transcriptWindowAnchor(measured.copy(follow=false),false))
        assertEquals(listOf("old","ui:history-gap:missed"),connectedTranscriptWindow(rows,gaps,reading).map{it.s("id")})
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
