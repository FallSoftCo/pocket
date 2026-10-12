package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class TranscriptReadRetryTest {
 @Test fun invalidSuccessfulResponseCannotBecomeFreshHistory(){
  for(snapshot in listOf(org.json.JSONObject(),org.json.JSONObject().put("error","Unexpected response"),org.json.JSONObject().put("thread",org.json.JSONObject().put("id","another-thread")).put("timeline",org.json.JSONObject().put("rows",org.json.JSONArray())))){
   try{validateTranscriptSnapshot(snapshot,"requested-thread");fail("Invalid snapshot accepted")}catch(expected:java.io.IOException){assertTrue(retryableTranscriptRead(expected))}
  }
 }
 @Test fun validEmptyConversationIsAccepted(){
  validateTranscriptSnapshot(org.json.JSONObject().put("thread",org.json.JSONObject().put("id","requested-thread")).put("timeline",org.json.JSONObject().put("rows",org.json.JSONArray())),"requested-thread")
 }
 @Test fun recoverableTransportFailuresCanRetry(){
  assertTrue(retryableTranscriptRead(java.net.SocketTimeoutException()))
  for(status in listOf(408,429,500,502,503,504))assertTrue("status$status",retryableTranscriptRead(PocketApiException(status,"synthetic")))
 }
 @Test fun unchangedPermissionOrCursorFailureMustNotPoll(){
  for(status in listOf(400,401,403,404,409,410,422))assertFalse("status$status",retryableTranscriptRead(PocketApiException(status,"synthetic")))
  assertFalse(retryableTranscriptRead(IllegalArgumentException("malformed history")))
 }
}
