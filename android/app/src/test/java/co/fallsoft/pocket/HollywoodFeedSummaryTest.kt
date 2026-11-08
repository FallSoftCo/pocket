package co.fallsoft.pocket

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic contract/adapter checks only; no network, pairing or model calls. */
class HollywoodFeedSummaryTest {
    private val uuid="00000000-0000-4000-8000-000000000111"
    private val other="00000000-0000-4000-8000-000000000222"
    private fun event(id:String,seq:Long,at:Long,textKey:String,text:Any=JSONObject.NULL)=
        JSONObject().put("eventId",id).put("seq",seq).put("occurredAt",at)
            .put(textKey,text).put("truncated",false)
    private fun summary(message:JSONObject?=null,activity:JSONObject?=null):JSONObject {
        val latest=listOfNotNull(message,activity).maxByOrNull{it.getLong("seq")}
        return JSONObject().put("version",1).put("latestMessage",message?:JSONObject.NULL)
            .put("latestActivity",activity?:JSONObject.NULL).put("recency",latest?.let{
                JSONObject().put("eventId",it.getString("eventId")).put("seq",it.getLong("seq"))
                    .put("occurredAt",it.getLong("occurredAt"))
            }?:JSONObject.NULL)
    }
    private fun room(feed:JSONObject?=null)=JSONObject().put("id",uuid).put("authority",uuid)
        .put("name","Global").put("purpose","Purpose fallback")
        .put("updated_at",9999999999999L).put("activity",JSONObject().put("observedAt",9999999999999L))
        .apply{if(feed!=null)put("feedSummary",feed)}

    @Test fun highestSequenceWinsEvenWhenItsClockIsOlderAndMillisStayExact(){
        val feed=summary(event(uuid,4,1700000000999,"body","Message"),
            event(other,5,1700000000001,"title","Compiling catalog"))
        val parsed=HollywoodRoomFeedSummary.read(room(feed))!!
        assertEquals(1700000000001L,parsed.workAt)
        assertEquals("Compiling catalog",parsed.preview)
        assertEquals("Compiling catalog",hollywoodRoomPreview(room(feed),true))
        assertEquals("Purpose fallback",hollywoodRoomPreview(room(feed),false))
        val early=summary(event(uuid,1,1234,"body","Early event"))
        assertEquals(1234L,HollywoodRoomFeedSummary.read(room(early))!!.workAt)
    }

    @Test fun legacyEmptyAndUnsupportedNeverBorrowMetadataOrObservationTime(){
        assertNull(HollywoodRoomFeedSummary.read(room()))
        assertEquals(HollywoodRoomFeedSummary(0,null),HollywoodRoomFeedSummary.read(room(summary())))
        assertNull(HollywoodRoomFeedSummary.read(room(summary().put("version",2))))
        assertEquals("Purpose fallback",hollywoodRoomPreview(room(summary()),true))
    }

    @Test fun numbersRequireNonnegativeSafeJsonIntegersWithoutCoercion(){
        for(value in listOf<Any>("1",1.0,true,-1,9007199254740992L)){
            val feed=summary(event(uuid,1,1000,"body","message")).put("version",value)
            assertNull("version=$value",HollywoodRoomFeedSummary.read(room(feed)))
        }
        for(key in listOf("seq","occurredAt")){
            for(value in listOf<Any>("123",123.5,true,-1,9007199254740992L)){
                val feed=summary(event(uuid,1,1000,"body","message"))
                feed.getJSONObject("latestMessage").put(key,value)
                assertNull("$key=$value",HollywoodRoomFeedSummary.read(room(feed)))
            }
        }
        val feed=summary(event(uuid,1,1000,"body","message"))
        feed.getJSONObject("latestMessage").put("seq",0)
        assertNull(HollywoodRoomFeedSummary.read(room(feed)))
    }

    @Test fun recencyMustMatchProjectedEventIdentitySequenceAndTime(){
        for((key,value) in listOf("eventId" to other,"seq" to 2,"occurredAt" to 9999)){
            val feed=summary(event(uuid,1,1000,"body","message"))
            feed.getJSONObject("recency").put(key,value)
            assertNull(HollywoodRoomFeedSummary.read(room(feed)))
        }
        assertNull(HollywoodRoomFeedSummary.read(room(summary().put("latestActivity","not an event"))))
        assertNull(HollywoodRoomFeedSummary.read(room(summary().put("recency",event(uuid,1,1000,"","")))))
        val feed=summary(event(uuid,1,1000,"body","message"))
        feed.getJSONObject("latestMessage").put("eventId","not-a-canonical-event")
        assertNull(HollywoodRoomFeedSummary.read(room(feed)))
    }

    @Test fun previewsUseOnlyBoundedStringsAndKeepUnicodeCodePoints(){
        val unicode="😀".repeat(320)
        assertEquals(unicode,HollywoodRoomFeedSummary.read(room(summary(event(uuid,1,1000,"body",unicode))))!!.preview)
        for(text in listOf<Any>(JSONObject().put("private","not a string"),JSONObject.NULL," ","x".repeat(321))){
            val feed=summary(event(uuid,1,1000,"body",text))
            assertEquals(1000L,HollywoodRoomFeedSummary.read(room(feed))!!.workAt)
            assertEquals("Purpose fallback",hollywoodRoomPreview(room(feed),true))
        }
        val feed=summary(event(uuid,1,1000,"body","Answer"),event(other,2,900,"title"))
        assertEquals("Answer",HollywoodRoomFeedSummary.read(room(feed))!!.preview)
        feed.getJSONObject("latestMessage").put("truncated","false")
        assertEquals("Purpose fallback",hollywoodRoomPreview(room(feed),true))
    }

    @Test fun realRoomReaderKeepsOriginalBindingAndRanksCanonicalRoomAboveOlderSession()=runBlocking {
        val host=EnvironmentRequest(EnvironmentIdentity("rooms","Rooms"),"https://rooms.invalid","synthetic")
        val stock=EnvironmentRequest(EnvironmentIdentity("stock","Stock"),"https://stock.invalid","synthetic")
        val binding=JSONObject().put("bindingId","a".repeat(64)).put("installationId",uuid).put("authorityId",uuid)
            .put("principalId",uuid).put("defaultRoomId",uuid)
        val row=room(summary(event(uuid,1,1700000001001,"body","Latest work")))
        val reader=WorkCatalogReader(host,"lax",binding){origin,path,audience->
            assertEquals(host,origin);assertEquals("/api/hollywood/v3/rooms",path)
            assertEquals(binding.s("bindingId"),audience)
            JSONObject().put("binding",binding).put("rooms",JSONArray().put(row))
        }
        val entry=reader.head().single()
        assertEquals(WorkKind.ROOM,entry.key.kind);assertEquals(uuid,entry.key.id)
        assertEquals(host,entry.value.destination);assertEquals(binding.toString(),entry.value.binding)
        assertEquals(1700000001001L,entry.workAt)
        val session=WorkCatalogReader(stock,"codex",read={_,_,_->
            JSONObject().put("threads",JSONArray().put(JSONObject().put("id","older-session")
                .put("name","Old work").put("activityAt",1700000000L)))
        }).head().single()
        val state=WorkCatalogState<WorkCatalogPayload>()
        state.reconcile(listOf(entry.key.source,session.key.source))
        state.accept(entry.key.source,state.begin(entry.key.source)!!,listOf(entry),1)
        state.accept(session.key.source,state.begin(session.key.source)!!,listOf(session),1)
        assertEquals(listOf(entry.key,session.key),state.ordered(emptyList()).map{it.key})
        row.remove("feedSummary")
        assertEquals(0L,reader.head().single().workAt)
    }

    @Test fun feedSummaryCannotOverrideAnUnconfirmedRoomAudience()=runBlocking {
        val host=EnvironmentRequest(EnvironmentIdentity("rooms","Rooms"),"https://rooms.invalid","synthetic")
        val binding=JSONObject().put("bindingId","a".repeat(64)).put("installationId",uuid).put("authorityId",uuid)
            .put("principalId",uuid).put("defaultRoomId",uuid)
        val reader=WorkCatalogReader(host,"lax",binding){_,_,_->
            JSONObject().put("binding",JSONObject(binding.toString()).put("principalId",other))
                .put("rooms",JSONArray().put(room(summary(event(uuid,1,1000,"body","message")))))
        }
        try{reader.head();fail("foreign viewer accepted")}catch(_:IllegalArgumentException){}
    }
}
