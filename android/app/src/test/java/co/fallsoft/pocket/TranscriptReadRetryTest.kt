package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class TranscriptReadRetryTest {
 @Test fun recoverableTransportFailuresCanRetry(){
  assertTrue(retryableTranscriptRead(java.net.SocketTimeoutException()))
  for(status in listOf(408,429,500,502,503,504))assertTrue("status$status",retryableTranscriptRead(PocketApiException(status,"synthetic")))
 }
 @Test fun unchangedPermissionOrCursorFailureMustNotPoll(){
  for(status in listOf(400,401,403,404,409,410,422))assertFalse("status$status",retryableTranscriptRead(PocketApiException(status,"synthetic")))
  assertFalse(retryableTranscriptRead(IllegalArgumentException("malformed history")))
 }
}
