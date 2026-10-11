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

}
