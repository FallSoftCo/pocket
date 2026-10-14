package co.fallsoft.pocket

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoomTranscriptProjectionTest {
    private fun event(id:Long,author:String,kind:String="message",item:String="",status:String="completed",thread:String="context")=
        JSONObject().put("id",id).put("author",author).put("kind",kind).put("body","Body$id")
            .put("data",JSONObject().put("itemId",item).put("thread",thread).put("status",status))

    @Test fun operationUpdatesKeepFirstIdentityAndAnswersQuestionsAndContextsStayOutsideRuns(){
        val source=listOf(event(1,"Violet","artifact","exec-one","inProgress"),event(2,"Violet"),
            event(3,"Violet","artifact","exec-one","failed"),event(4,"Violet","artifact","exec-two"),
            event(5,"Violet","attention"),event(6,"Amber","artifact","exec-three"),
            event(7,"Violet","artifact","exec-four",thread="other-context"))
        val original=source.map{it.toString()}
        val rows=roomTranscriptRows(source,"scope")
        assertEquals(6,rows.size)
        assertEquals("failed",rows.first().s("status"));assertEquals("Body3",rows.first().s("text"))
        val closed=commandRunProjection(rows,emptySet())
        assertEquals(4,closed.groups.size);assertTrue(closed.rows.any{it.s("text")=="Body2"&&it.s("kind")=="message"})
        assertTrue(closed.rows.any{it.optLong("roomEventId")==5L&&it.s("kind")=="message"})
        val operationId=rows.first().s("id")
        val opened=commandRunProjection(rows,setOf(operationId))
        assertTrue(opened.rows.any{it.s("id")==operationId&&it.s("status")=="failed"})
        assertEquals(original,source.map{it.toString()})
    }

    @Test fun namesAppearOncePerUninterruptedAuthorBlockAndUnstructuredArtifactsRemainVisible(){
        val rows=roomTranscriptRows(listOf(event(1,"Violet"),event(2,"Violet"),event(3,"Amber"),
            event(4,"Amber","artifact"),event(5,"Violet")),"scope")
        assertEquals(listOf(true,false,true,false,true),rows.indices.map{roomAuthorStartsBlock(rows,it)})
        assertEquals("message",rows[3].s("kind"));assertEquals("Body4",rows[3].s("text"))
    }

    @Test fun consecutiveDistinctRunsCollapseTogetherButNeverAcrossAnAuthorOrContextBoundary(){
        val rows=roomTranscriptRows(listOf(event(1,"Violet","artifact","one"),event(2,"Violet","artifact","two"),
            event(3,"Amber","artifact","three"),event(4,"Amber","artifact","four",thread="other")),"scope")
        val projection=commandRunProjection(rows,emptySet())
        assertEquals(listOf(2,1,1),projection.groups.values.map{it.size})
    }

    @Test fun loadingOlderStartEventCannotCollapseAnExpandedCompletedOperation(){
        val start=event(1,"Violet","artifact","same-operation","inProgress")
        val done=event(2,"Violet","artifact","same-operation","completed")
        val cold=roomTranscriptRows(listOf(done),"scope").single()
        val warm=roomTranscriptRows(listOf(start,done),"scope")
        assertEquals(cold.s("id"),warm.single().s("id"))
        assertTrue(commandRunProjection(warm,setOf(cold.s("id"))).rows.any{it.s("id")==cold.s("id")})
    }
}
