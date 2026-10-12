package co.fallsoft.pocket
import org.junit.Test
import org.junit.Assert.*
class TranscriptMessageIdentityTest {
 @Test fun reconstructedProviderIdsKeepOneSendIdentity(){
  val live=transcriptMessageIdentity("user","turn","send","turn/uuid")
  assertEquals(live,transcriptMessageIdentity("user","turn","send","turn/item-1"))
  assertEquals(live,transcriptMessageIdentity("user","turn","send","turn/item-183"))
 }
 @Test fun separateSendsAndTurnsRemainSeparate(){
  assertNotEquals(transcriptMessageIdentity("user","turn","one","x"),transcriptMessageIdentity("user","turn","two","y"))
  assertNotEquals(transcriptMessageIdentity("user","one","send","x"),transcriptMessageIdentity("user","two","send","y"))
 }
 @Test fun absentEvidenceAndNonUserItemsKeepProviderIdentity(){
  assertEquals("provider",transcriptMessageIdentity("user","turn","","provider"))
  assertEquals("provider",transcriptMessageIdentity("message","turn","send","provider"))
 }

 private fun user(item:String,send:String="send",version:Long=0)=org.json.JSONObject()
  .put("id","turn/"+item).put("itemId",item).put("turnId","turn")
  .put("clientId",send).put("kind","user").put("text","Deliberately repeated text").put("version",version)
 @Test fun liveAndReconstructedSnapshotArrivalOrdersConverge(){
  val variants=listOf(user("uuid",version=1),user("item-1",version=2),user("item-183",version=3))
  val permutations=listOf(listOf(0,1,2),listOf(0,2,1),listOf(1,0,2),listOf(1,2,0),listOf(2,0,1),listOf(2,1,0))
  permutations.forEach{order->
   var rows=emptyList<org.json.JSONObject>()
   order.forEach{n->rows=mergeTranscriptPage(rows,normalizeTranscriptMessages(listOf(variants[n])),false,{it.s("id")},{it.optLong("version")})}
   assertEquals("Arrival order $order",1,rows.size)
   assertEquals("item-183",rows.single().s("itemId"))
  }
 }
 @Test fun duplicateCacheRepairRetainsAllProviderBookmarks(){
  val raw=listOf(user("uuid"),user("item-1"),user("item-183"))
  val repaired=normalizeTranscriptMessages(raw)
  assertEquals(1,repaired.size)
  raw.forEach{assertEquals(repaired.single().s("id"),transcriptDisplayAnchor(repaired,it.s("id")))}
  val restored=normalizeTranscriptMessages(repaired.map{org.json.JSONObject(it.toString())})
  assertEquals(repaired.single().s("id"),restored.single().s("id"))
  assertEquals(repaired.single().s("itemId"),restored.single().s("itemId"))
  raw.forEach{assertEquals(restored.single().s("id"),transcriptDisplayAnchor(restored,it.s("id")))}
 }
 @Test fun newProviderIdsDoNotMoveInputBehindItsAnswer(){
  val answer=org.json.JSONObject().put("id","turn/answer").put("kind","message").put("text","Result")
  val old=normalizeTranscriptMessages(listOf(user("uuid"),answer))
  val refreshed=mergeTranscriptPage(old,normalizeTranscriptMessages(listOf(user("item-183"),answer)),false,{it.s("id")},{it.optLong("version")})
  assertEquals(listOf("user","message"),refreshed.map{it.s("kind")})
  assertEquals(2,refreshed.size)
 }
 @Test fun identicalTextNeverSuppliesMissingIdentityEvidence(){
  val rows=normalizeTranscriptMessages(listOf(user("one").removeClient(),user("two").removeClient()))
  assertEquals(2,rows.size)
 }
 private fun org.json.JSONObject.removeClient()=apply{remove("clientId")}
 @Test fun savedSendIdentitySurvivesJsonRoundTrip(){
  val row=normalizeTranscriptMessage(user("uuid"))
  val restored=normalizeTranscriptMessage(org.json.JSONObject(row.toString()))
  assertEquals(row.s("id"),restored.s("id"));assertEquals("uuid",restored.s("itemId"))
 }
 @Test fun distinctQueuedSendsStayDistinctEvenWhenContentsMatch(){
  val rows=normalizeTranscriptMessages(listOf(user("one","first"),user("two","second"),user("rebuilt","first")))
  assertEquals(2,rows.size)
  assertEquals(listOf("first","second"),rows.map{it.s("clientId")})
 }
 @Test fun absentTurnAndOtherRowKindsNeverShareSendIdentity(){
  val a=user("one").put("turnId","")
  val b=user("two").put("kind","message")
  assertEquals("turn/one",normalizeTranscriptMessage(a).s("id"))
  assertEquals("turn/two",normalizeTranscriptMessage(b).s("id"))
 }
 @Test fun cacheAliasRetentionIsBounded(){
  val rows=normalizeTranscriptMessages((1..100).map{user("item-"+it)})
  assertEquals(1,rows.size)
  assertEquals(32,rows.single().getJSONArray("providerRowIds").length())
  assertEquals(rows.single().s("id"),transcriptDisplayAnchor(rows,"turn/item-100"))
 }
}
