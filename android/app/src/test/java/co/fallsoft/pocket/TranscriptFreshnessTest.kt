package co.fallsoft.pocket
import org.junit.Test
import org.junit.Assert.*
class TranscriptFreshnessTest {
 @Test fun unseenPublicPreviewAndCompletionInvalidateCachedConversation(){
  assertTrue(transcriptPresentationChanged("old reply","message","active","new reply","message","active"))
  assertTrue(transcriptPresentationChanged("same","thinking","active","same","message","idle"))
  assertTrue(transcriptPresentationChanged(null,null,null,"result","message","idle"))
 }
 @Test fun identicalInventoryAndRepeatedPublicUpdatesDoNotPoll(){
  assertFalse(transcriptPresentationChanged("reply","message","idle","reply","message","idle"))
 }

 private data class Row(val id:String,val text:String,val version:Long)
 @Test fun authoritativeHistoryReplacesUnchangedStreamedCacheAfterCounterReset(){
  val cached=Row("answer","unfinished streamed text",80)
  val snapshot=Row("answer","finished persisted response",0)
  val base=authoritativeSnapshotBase(listOf(cached),mapOf(cached.id to cached),setOf(snapshot.id),{it.id}){it.copy(version=0)}
  assertEquals(snapshot,mergeTranscriptPage(base,listOf(snapshot),false,{it.id},{it.version}).single())
 }
 @Test fun outputReceivedDuringSlowReadStillWinsOverItsSnapshot(){
  val cached=Row("answer","earlier",80);val live=Row("answer","newly streamed response",82)
  val base=authoritativeSnapshotBase(listOf(live),mapOf(cached.id to cached),setOf("answer"),{it.id}){it.copy(version=0)}
  val snapshot=Row("answer","older snapshot",81)
  assertEquals(live,mergeTranscriptPage(base,listOf(snapshot),false,{it.id},{it.version}).single())
 }
}
