package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class TranscriptDiskCacheTest {
 @Test fun sameHostCredentialsAndThreadNeverCrossNamedEnvironments(){
  val directory=Files.createTempDirectory("transcript-cache-test").toFile()
  try{
   val a=transcriptCacheOwner("environment-a","https://same.example","private-test-token")
   val b=transcriptCacheOwner("environment-b","https://same.example","private-test-token")
   val cache=TranscriptDiskCache(directory);assertTrue(cache.write(a,"same-thread","earlier local history"))
   assertNull(cache.read(b,"same-thread"));assertNull(cache.read(a,"other-thread"))
   assertEquals("earlier local history",TranscriptDiskCache(directory).read(a,"same-thread"))
   assertFalse(directory.listFiles()!!.any{it.name.contains("private-test-token")||it.name.contains("same-thread")})
  }finally{directory.deleteRecursively()}
 }
 @Test fun interruptedAndSupersededWritesKeepLastCompleteHistory(){
  val directory=Files.createTempDirectory("transcript-cache-test").toFile()
  try{
   val scope=transcriptCacheOwner("a","https://example","synthetic");val cache=TranscriptDiskCache(directory)
   assertTrue(cache.write(scope,"thread","complete history"))
   val file=directory.listFiles()!!.single();java.io.File(directory,file.name+".tmp").writeText("unfinished")
   assertEquals("complete history",TranscriptDiskCache(directory).read(scope,"thread"))
   assertFalse(cache.write(scope,"thread","superseded history"){false})
   assertEquals("complete history",cache.read(scope,"thread"))
  }finally{directory.deleteRecursively()}
 }
 @Test fun budgetEvictsWholeInactiveSnapshotsAndRejectsOversizedReplacement(){
  val directory=Files.createTempDirectory("transcript-cache-test").toFile()
  try{
   val scope=transcriptCacheOwner("a","https://example","synthetic");val cache=TranscriptDiskCache(directory,10,10)
   assertTrue(cache.write(scope,"one","123456"));assertTrue(cache.write(scope,"two","abcdef"))
   assertNull(cache.read(scope,"one"));assertEquals("abcdef",cache.read(scope,"two"))
   assertFalse(cache.write(scope,"two","12345678901"));assertEquals("abcdef",cache.read(scope,"two"))
  }finally{directory.deleteRecursively()}
 }
 @Test fun cachedPresentationCannotRestorePendingDecisionsOrExecutionSettings(){
  val input=org.json.JSONObject().put("thread",org.json.JSONObject().put("id","thread").put("name","Useful work").put("cwd","/synthetic").put("status",org.json.JSONObject().put("type","active")).put("approvalPolicy","never").put("sandboxPolicy","full"))
   .put("pending",org.json.JSONArray().put("old-request")).put("outgoing",org.json.JSONArray().put("old-command")).put("catchup",org.json.JSONObject().put("token","old-view")).put("notes",org.json.JSONArray().put("useful answer"))
  val cached=transcriptPresentationDetail("thread",input)
  assertEquals("Useful work",cached.getJSONObject("thread").getString("name"))
  assertEquals("useful answer",cached.getJSONArray("notes").getString(0))
  for(field in listOf("pending","outgoing","catchup","status","approvalPolicy","sandboxPolicy"))assertFalse("Cached authority: $field",cached.toString().contains("\"$field\""))
 }

 private fun point(offset:Int)=org.json.JSONObject().put("row","retained-message").put("offset",offset).put("follow",false).toString()
 @Test fun positionWritesDoNotRewriteMegabyteHistoryAndSurviveRestart(){
  val directory=Files.createTempDirectory("position-cache-test").toFile()
  try{
   val scope=transcriptCacheOwner("a","https://example","synthetic");val cache=TranscriptDiskCache(directory)
   val history="retained history ".repeat(65536);assertTrue(cache.write(scope,"thread",history))
   val original=directory.listFiles()!!.single().readBytes()
   for(n in 1..20)assertTrue(cache.writePosition(scope,"thread",point(n),n.toLong()))
   assertArrayEquals(original,directory.listFiles()!!.single{it.extension=="json"}.readBytes())
   val restored=TranscriptDiskCache(directory)
   assertEquals(history,restored.read(scope,"thread"));assertEquals(20,org.json.JSONObject(restored.readPosition(scope,"thread")!!).getInt("offset"))
  }finally{directory.deleteRecursively()}
 }
 @Test fun delayedSnapshotCannotRollBackNewerPosition(){
  val directory=Files.createTempDirectory("position-cache-test").toFile()
  try{
   val scope=transcriptCacheOwner("a","https://example","synthetic");val cache=TranscriptDiskCache(directory)
   assertTrue(cache.writePosition(scope,"thread",point(20),20))
   assertTrue(cache.write(scope,"thread","delayed full snapshot"))
   assertFalse(cache.writePosition(scope,"thread",point(10),10))
   assertEquals(20,org.json.JSONObject(cache.readPosition(scope,"thread")!!).getInt("offset"))
   // A fresh runtime owns a new sequence epoch; existing metadata remains readable.
   val restarted=TranscriptDiskCache(directory);assertTrue(restarted.writePosition(scope,"thread",point(30),1))
   assertEquals(30,org.json.JSONObject(restarted.readPosition(scope,"thread")!!).getInt("offset"))
  }finally{directory.deleteRecursively()}
 }
 @Test fun positionMetadataCannotCrossEnvironmentOrReplaceHistoryWhenMalformed(){
  val directory=Files.createTempDirectory("position-cache-test").toFile()
  try{
   val a=transcriptCacheOwner("a","https://example","synthetic");val b=transcriptCacheOwner("b","https://example","synthetic");val cache=TranscriptDiskCache(directory)
   assertTrue(cache.write(a,"thread","last complete history"));assertTrue(cache.writePosition(a,"thread",point(8),1))
   assertNull(cache.readPosition(b,"thread"));assertNull(cache.readPosition(a,"other"))
   assertFalse(cache.writePosition(a,"thread",org.json.JSONObject().put("row","x".repeat(20000)).toString(),2))
   directory.listFiles()!!.single{it.extension=="position"}.writeText("partial metadata")
   assertNull(cache.readPosition(a,"thread"));assertEquals("last complete history",cache.read(a,"thread"))
  }finally{directory.deleteRecursively()}
 }
 @Test fun wholeCheckpointEvictionAlsoRemovesPositionMetadata(){
  val directory=Files.createTempDirectory("position-cache-test").toFile()
  try{
   val scope=transcriptCacheOwner("a","https://example","synthetic");val cache=TranscriptDiskCache(directory,1024,500)
   assertTrue(cache.write(scope,"one","a".repeat(300)));assertTrue(cache.writePosition(scope,"one",point(1),1))
   assertTrue(cache.write(scope,"two","b".repeat(300)));assertTrue(cache.writePosition(scope,"two",point(2),2))
   assertNull(cache.read(scope,"one"));assertNull(cache.readPosition(scope,"one"))
   assertNotNull(cache.read(scope,"two"));assertNotNull(cache.readPosition(scope,"two"))
   assertEquals(2,directory.listFiles()!!.size)
  }finally{directory.deleteRecursively()}
 }

}
