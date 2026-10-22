package co.fallsoft.pocket

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeTranscriptStoreNativeTest{
    private fun entry(n:Int,text:String="Native entry "+n,kind:String="message",id:String="item-"+n)=
        JSONObject().put("id","item:t:"+id).put("key",JSONArray().put(n).put(1).put(id))
            .put("row",JSONObject().put("id","t/"+id).put("turnId","t").put("itemId",id).put("kind",kind).put("text",text))
    private fun page(entries:List<JSONObject>,before:String?,version:Long=1)=JSONObject()
        .put("thread",JSONObject().put("id","same-thread").put("name","Native store"))
        .put("timeline",JSONObject().put("native",1).put("entries",JSONArray(entries)).put("before",before))
        .put("historyHead",JSONObject().put("threadId","same-thread").put("epoch","native-store-test").put("version",version))
        .put("revision",version).put("pending",JSONArray().put(JSONObject().put("id","must-not-restore")))
        .put("outgoing",JSONArray().put(JSONObject().put("id","must-not-replay")))
    @Test fun canonicalPagesLiveOverlapAndRestartKeepOneAuthority(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="owned-native-store-"+java.util.UUID.randomUUID()+".sqlite"
        var store=NativeTranscriptStore(context,name)
        try{
            var end=700;var cursor:String?=null
            while(end>0){
                val start=maxOf(1,end-63);val next=if(start==1)null else "opaque-before-"+start
                store.commit("a","same-thread",page((start..end).map{entry(it)},next),cursor)
                cursor=next;end=start-1
            }
            val head=requireNotNull(store.snapshot("a","same-thread"))
            val rows=head.getJSONObject("timeline").getJSONArray("rows").objects()
            assertTrue(rows.size<=512);assertEquals("t/item-700",rows.last().s("id"))
            val first=rows.first().s("id");val oldCursor=head.getJSONObject("timeline").s("before")
            assertTrue(oldCursor.startsWith("native-local:older:"))
            store.navigate("a","same-thread",oldCursor)
            val older=requireNotNull(store.snapshot("a","same-thread"))
            assertTrue(older.getJSONObject("timeline").getJSONArray("rows").objects().any{it.s("id")==first})
            assertNotEquals(head.s("nativeWindow"),older.s("nativeWindow"))
            assertEquals("Native entry 700",older.getJSONObject("nativePreview").s("text"))
            val unknown=JSONObject().put("version",50).put("historyHead",head.getJSONObject("historyHead")).put("row",entry(699,"Late native user","user","late-user").getJSONObject("row"))
            assertFalse("Unknown placement was admitted as chronology",store.live("a","same-thread",unknown))
            val live=JSONObject().put("version",50).put("historyHead",JSONObject(head.getJSONObject("historyHead").toString()).put("version",50))
                .put("row",entry(700,"New streamed payload").getJSONObject("row"))
            assertTrue(store.live("a","same-thread",live))
            store.commit("a","same-thread",page((637..700).map{entry(it)}, "opaque-before-637",1),null)
            store.focus("a","same-thread",null)
            var snapshot=requireNotNull(store.snapshot("a","same-thread"))
            assertEquals("New streamed payload",snapshot.getJSONObject("nativePreview").s("text"))
            store.commit("a","same-thread",page(listOf(entry(699,"Late native user","user","late-user"),entry(700,"Canonical final answer")), "opaque-before-699",60),null)
            snapshot=requireNotNull(store.snapshot("a","same-thread"))
            val ids=snapshot.getJSONObject("timeline").getJSONArray("rows").objects().map{it.s("id")}
            assertTrue(ids.indexOf("t/late-user")<ids.indexOf("t/item-700"))
            assertEquals(1,ids.count{it=="t/late-user"})
            store.commit("b","same-thread",page(listOf(entry(700,"Other environment")),null),null)
            assertEquals("Canonical final answer",store.snapshot("a","same-thread")!!.getJSONObject("nativePreview").s("text"))
            assertEquals("Other environment",store.snapshot("b","same-thread")!!.getJSONObject("nativePreview").s("text"))
            store.close();store=NativeTranscriptStore(context,name)
            snapshot=requireNotNull(store.snapshot("a","same-thread"))
            assertEquals("Canonical final answer",snapshot.getJSONObject("nativePreview").s("text"))
            assertFalse("Disk restored command/question authority",snapshot.has("pending")||snapshot.has("outgoing"))
            val keys=snapshot.getJSONObject("timeline").getJSONArray("rows").objects().filter{it.has("_nativePosition")}.map{NativeTranscriptKey(it.getLong("_nativePosition"),it.getInt("_nativeRank"),it.s("_nativeId"))}
            assertTrue(keys.zipWithNext().all{(a,b)->a<b})
        }finally{store.close();context.deleteDatabase(name)}
    }
}
