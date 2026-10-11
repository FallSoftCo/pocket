package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
class ClientReplyJournalTest{
 private fun intent()=ClientReplyIntent("saved-id","environment-a","account-scope","thread-a","Original draft","steer","draft-key")
 @Test fun interruptionPreservesOriginalIdentityAndDestination(){
  var disk="[]";val first=ClientReplyJournal({disk},{disk=it;true});first.put(intent());first.update("saved-id"){it.copy(phase="dispatched",state="unconfirmed")}
  val restarted=ClientReplyJournal({disk},{disk=it;true});val recovered=restarted.all().single()
  assertEquals("saved-id",recovered.id);assertEquals("environment-a",recovered.environment);assertEquals("account-scope",recovered.owner);assertEquals("thread-a",recovered.thread);assertEquals("Original draft",recovered.text);assertEquals("dispatched",recovered.phase)
 }
 @Test fun failedPersistenceDoesNotClaimDispatchableIntent(){
  val j=ClientReplyJournal({"[]"},{false});try{j.put(intent());fail("Failed write accepted")}catch(expected:IOException){};assertTrue(j.all().isEmpty())
 }
 @Test fun corruptJournalCannotSilentlyDiscardSavedMessages(){
  var wrote=false;val j=ClientReplyJournal({"broken"},{wrote=true;true});try{j.put(intent());fail("Corrupt journal overwritten")}catch(expected:IOException){};assertFalse(wrote)
 }
 @Test fun onlyMatchingKnownReceiptCanClearDraft(){
  val i=intent()
  for(r in listOf(JSONObject(),JSONObject().put("error","not JSON history"),JSONObject().put("id","other-id").put("state","accepted"),JSONObject().put("id",i.id).put("state","complete"),JSONObject().put("id",i.id).put("state","accepted").put("thread_id","another-thread"))){try{checkedReplyReceipt(r,i,lookup=true);fail("Invalid receipt accepted")}catch(expected:IOException){}}
  assertEquals("accepted",checkedReplyReceipt(JSONObject().put("id",i.id).put("state","accepted").put("thread_id",i.thread),i,lookup=true))
  assertEquals("queued",checkedReplyReceipt(JSONObject().put("id",i.id).put("state","queued"),i))
  assertFalse("Unknown cleared draft","unknown" in admittedReplyStates);assertFalse("Failed cleared draft","failed" in admittedReplyStates)
 }
 @Test fun removingOneSavedCopyPreservesOtherEnvironments(){
  var disk="[]";val j=ClientReplyJournal({disk},{disk=it;true});j.put(intent());j.put(intent().copy(id="second",environment="environment-b"));j.remove("saved-id");assertEquals("environment-b",ClientReplyJournal({disk},{false}).all().single().environment)
 }
}
