package co.fallsoft.pocket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class CodexOutgoingTest {
 private fun snapshot(revision:Long,text:String?=null)=JSONObject().put("version",1).put("revision",revision).put("items",JSONArray().apply{if(text!=null)put(JSONObject().put("id","same-queue-id").put("state","queued").put("mode","queue").put("text",text))})
 @Test fun cancelledSnapshotCannotBeRolledBackByLateHistory(){
  val store=CodexOutgoing();val key=CodexConversationKey("a","backend","same-thread")
  assertTrue(store.accept(key,snapshot(1,"Saved request")));assertTrue(store.accept(key,snapshot(3)))
  assertFalse(store.accept(key,snapshot(1,"Saved request")))
  val metadata=JSONObject().put("thread",JSONObject().put("id","same-thread")).put("outbox",snapshot(1,"Saved request"))
  assertEquals(0,store.overlay(key,metadata)!!.getJSONArray("outgoing").length())
 }
 @Test fun matchingThreadIdsAcrossHostsKeepIndependentQueues(){
  val store=CodexOutgoing();val a=CodexConversationKey("a","backend-a","same-thread");val b=CodexConversationKey("b","backend-b","same-thread")
  store.accept(a,snapshot(4,"Host A"));store.accept(b,snapshot(1,"Host B"))
  assertEquals("Host A",store.overlay(a,null)!!.getJSONArray("outgoing").getJSONObject(0).getString("text"))
  assertEquals("Host B",store.overlay(b,null)!!.getJSONArray("outgoing").getJSONObject(0).getString("text"))
 }
}
