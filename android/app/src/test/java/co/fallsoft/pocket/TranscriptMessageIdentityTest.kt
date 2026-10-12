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

 @Test fun lateSendIdentityEnrichmentCollapsesExactProviderInEitherOrder(){
  val bare=user("same").removeClient()
  val identified=user("same",version=2)
  listOf(listOf(bare,identified),listOf(identified,bare)).forEach{input->
   val rows=normalizeTranscriptMessages(input)
   assertEquals(1,rows.size);assertEquals("send",rows.single().s("clientId"))
  }
 }
 @Test fun exactProviderIdentityCannotCrossTurnsOrAmbiguousSendClaims(){
  val bare=user("same").removeClient()
  val other=user("same").put("turnId","other")
  assertEquals(2,normalizeTranscriptMessages(listOf(bare,other)).size)
  val ambiguous=normalizeTranscriptMessages(listOf(bare,user("same","one"),user("same","two")))
  assertEquals(3,ambiguous.size)
 }
 @Test fun pendingBodyUsesExactProviderEchoButRetainsDistinctRepeatedSends(){
  val rows=listOf(user("one","send-one"),user("two","send-two"))
  assertTrue(transcriptContainsReply(rows,"send-one"))
  assertTrue(transcriptContainsReply(rows,"send-two"))
  assertFalse(transcriptContainsReply(rows,"not-received"))
  assertFalse(transcriptContainsReply(rows,""))
  assertFalse(transcriptContainsReply(listOf(user("one","send-one").put("kind","message")),"send-one"))
 }
 @Test fun enrichedIdentityPreservesReadingOffsetAndFollowIntent(){
  val rows=normalizeTranscriptMessages(listOf(user("same").removeClient(),user("same",version=2)))
  val saved=TranscriptReadPosition("turn/same",73,false)
  val restored=resolveTranscriptReadPosition(rows,saved)!!
  assertEquals("turn/client-user/send",restored.rowId)
  assertEquals(73,restored.offset);assertFalse(restored.follow)
  assertEquals(saved.copy(rowId="missing"),resolveTranscriptReadPosition(rows,saved.copy(rowId="missing")))
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
 @Test fun legacyReadingAnchorSurvivesBeyondRetainedProviderAliases(){
  val original=(1..100).map{user("item-"+it)}
  val collapsed=normalizeTranscriptMessages(original)
  val migrated=migrateTranscriptAnchors(original,original.map{it.s("id")}.toSet())
  original.forEach{assertEquals("Lost legacy anchor "+it.s("id"),collapsed.single().s("id"),migrated[it.s("id")])}
 }
 @Test fun unknownAndConflictingAnchorsAreNotGuessed(){
  val original=listOf(user("shared","one"),user("shared","two"))
  val migrated=migrateTranscriptAnchors(original,setOf("turn/shared","turn/missing"))
  assertEquals("turn/shared",migrated["turn/shared"])
  assertEquals("turn/missing",migrated["turn/missing"])
 }
 @Test fun cacheAnchorMigrationKeepsNonUserAnchors(){
  val row=org.json.JSONObject().put("id","turn/answer").put("itemId","answer").put("turnId","turn").put("kind","message")
  assertEquals("turn/answer",migrateTranscriptAnchors(listOf(row),setOf("turn/answer"))["turn/answer"])
 }
 @Test fun noPersistedAnchorsNeedNoMigrationState(){assertTrue(migrateTranscriptAnchors(emptyList(),emptySet()).isEmpty())}
 @Test fun repeatedProviderEventsAreNotNewSendsOrPositionEvidence(){
  val raw=user("uuid",version=10);val previous=listOf(normalizeTranscriptMessage(raw))
  val evidence=transcriptInputEvent(previous,raw)
  assertFalse(evidence.newInput);assertFalse(evidence.needsPosition)
 }
 @Test fun newProviderReferenceUsesExistingSendIdentity(){
  val previous=listOf(normalizeTranscriptMessage(user("uuid",version=10)))
  val evidence=transcriptInputEvent(previous,user("item-183",version=11))
  assertFalse(evidence.newInput);assertTrue(evidence.needsPosition)
  assertEquals(previous.single().s("id"),evidence.row?.s("id"))
  assertEquals("item-183",transcriptProviderItem(requireNotNull(evidence.row)))
 }
 @Test fun olderProviderEvidenceCannotCausePositionReads(){
  val previous=listOf(normalizeTranscriptMessage(user("uuid",version=10)))
  assertFalse(transcriptInputEvent(previous,user("older",version=9)).needsPosition)
 }
 @Test fun separateSendIsNewEvenWithIdenticalContents(){
  val previous=listOf(normalizeTranscriptMessage(user("uuid",version=10)))
  val evidence=transcriptInputEvent(previous,user("new","other",11))
  assertTrue(evidence.newInput);assertTrue(evidence.needsPosition)
 }
 @org.junit.Test fun replacementRetainsAliasesWithoutRegressingContent(){
  val old=normalizeTranscriptMessage(user("first",version=10))
  val newest=normalizeTranscriptMessage(user("second",version=11)).put("text","Updated input")
  val merged=mergeTranscriptMessageEvidence(old,newest)
  assertEquals("Updated input",merged.s("text"))
  assertEquals(merged.s("id"),transcriptDisplayAnchor(listOf(merged),"turn/first"))
  assertEquals(merged.s("id"),transcriptDisplayAnchor(listOf(merged),"turn/second"))
  val stale=normalizeTranscriptMessage(user("third",version=9)).put("text","Stale input")
  val retained=mergeTranscriptMessageEvidence(merged,stale)
  assertEquals("Updated input",retained.s("text"))
  assertEquals(retained.s("id"),transcriptDisplayAnchor(listOf(retained),"turn/third"))
 }
 @Test fun fullAliasCacheRetainsNewestReplacementReference(){
  val original=normalizeTranscriptMessages((1..100).map{user("item-"+it)}).single()
  val incoming=normalizeTranscriptMessage(user("new-provider",version=1))
  val merged=mergeTranscriptMessageEvidence(original,incoming)
  assertEquals("turn/client-user/send",transcriptDisplayAnchor(listOf(merged),"turn/new-provider"))
  assertEquals(32,merged.getJSONArray("providerRowIds").length())
  assertEquals("turn/new-provider",merged.getJSONArray("providerRowIds").getString(31))
 }
}
