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

    @Test fun stoppedOperationsStayCompactWithoutHidingPrivacyGuidanceOrHealthyWork(){
        val id="00000000-0000-4000-8000-000000000001"
        fun stop(n:Long)=event(n,"Violet","control").put("audience","room").put("recipient","Amber")
            .put("data",JSONObject().put("deliveryId",id).put("state","cancelled"))
        val first=stop(1);val second=stop(2)
        val private=event(3,"Violet").put("audience","direct").put("recipient","Amber")
        val guidance=stop(4).put("data",JSONObject().put("deliveryId",id).put("state","cancelled").put("nativeControlId",id))
        val source=listOf(first,second,private,guidance);val original=source.map{it.toString()}
        val rows=roomTranscriptRows(source,"scope")
        val folded=commandRunProjection(rows,emptySet())
        assertEquals(2,folded.groups.values.single().size)
        assertEquals("direct",rows[2].s("roomAudience"));assertEquals("Amber",rows[2].s("roomRecipient"))
        assertTrue(roomAuthorStartsBlock(rows,2));assertEquals("message",rows[3].s("kind"))
        assertEquals(original,source.map{it.toString()})
        val detail=JSONObject().put("room",JSONObject().put("id",id)).put("deliveries",org.json.JSONArray()
            .put(JSONObject().put("id","old").put("state","unknown"))
            .put(JSONObject().put("id","new").put("state","running")))
        assertEquals("Running",hollywoodRoomWork(detail,"",emptyList()).label)
        assertEquals("Status unavailable",hollywoodRoomWork(detail,"Read failed",emptyList()).label)
        detail.getJSONArray("deliveries").remove(0);detail.getJSONArray("deliveries").getJSONObject(0).put("state","cancel_requested")
        assertEquals("Stop requested",hollywoodRoomWork(detail,"",emptyList()).label)
    }

    @Test fun lateStartedObservationCannotRestartCompletedStableActionAndKeepsBothReferences(){
        fun native(n:Long,status:String)=event(n,"Violet","progress","tool-one",status)
            .put("authority","authority-a").put("seq",n).apply{
                getJSONObject("data").put("type","executionActivity").put("nativeType","commandExecution")
                    .put("activityVersion",1).put("actionId","action-stable").put("phase",if(status=="completed")"completed" else "started")
            }
        val done=native(1,"completed");val late=native(2,"inProgress")
        val rows=roomTranscriptRows(listOf(done,late),"scope")
        assertEquals(1,rows.size);assertEquals("completed",rows.single().s("status"))
        assertEquals("action-stable",rows.single().s("actionId"))
        assertEquals(2,rows.single().getJSONArray("actionObservations").length())
        assertEquals(1L,rows.single().getJSONArray("actionObservations").getJSONObject(0).getLong("eventId"))
        assertEquals(2L,rows.single().getJSONArray("actionObservations").getJSONObject(1).getLong("eventId"))
        val peer=native(3,"inProgress").put("author","Amber").apply{getJSONObject("data").put("actionId","action-peer")}
        val source=roomTranscriptRows(listOf(done,late,peer),"scope")
        val folded=commandRunProjection(source,emptySet(),mergeRoomActivity=true)
        assertEquals(1,folded.groups.size);assertEquals(2,folded.groups.values.single().size)
        assertEquals("",folded.rows.single().s("roomAuthor"))
        val opened=commandRunProjection(source,source.map{it.s("id")}.toSet(),mergeRoomActivity=true)
        assertEquals(setOf("Violet","Amber"),opened.rows.filter{it.s("kind")=="activity"}.map{it.s("roomAuthor")}.toSet())
    }

}
