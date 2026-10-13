package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class ParallelTranscriptNativeTest {
 private val i=InstrumentationRegistry.getInstrumentation()
 private val context get()=i.targetContext
 private val device get()=UiDevice.getInstance(i)
 private fun main(b:()->Unit)=i.runOnMainSync(b)
 private fun await(message:String="Parallel loading condition timed out",b:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(b())return;Thread.sleep(50)};assertTrue(message,b())}
 private fun qa(path:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+path,JSONObject())}
 private fun start(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Parallel loading draft").commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()}
 }
 private fun seed():Pair<TranscriptDiskCache,String>{
  val scope=PocketTranscript.scopeKey();val cache=TranscriptDiskCache(java.io.File(context.noBackupFilesDir,"transcript-cache-v1"))
  val rows=JSONArray().put(JSONObject().put("id","retained-local").put("kind","message").put("text","Earlier retained local passage")).put(JSONObject().put("id","same-message").put("kind","message").put("text","Stale cached response").put("version",9000))
  val body=JSONObject().put("schema",1).put("scope",scope).put("id","same-thread").put("rows",rows).put("detail",JSONObject().put("thread",JSONObject().put("id","same-thread"))).put("earlier",true).put("before","older1").put("position",JSONObject().put("row","retained-local").put("offset",0).put("follow",false)).toString()
  assertTrue(cache.write(scope,"same-thread",body));return cache to body
 }
 @Test fun freshHistoryPaintsBeforeSlowCacheAndLateRestoreRetainsLocalHistory(){
  start();val (cache,original)=seed();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  val started=android.os.SystemClock.elapsedRealtime()
  main{Pocket.selected="same-thread";PocketTranscript.reset("same-thread");Pocket.scope.launch{PocketTranscript.load(beforeCacheRead={entered.complete(Unit);release.await()})}}
  await{entered.isCompleted&&PocketTranscript.rows.any{it.s("text")=="Fresh response 1"}&&!PocketTranscript.loading}
  assertTrue("Current text stayed invisible behind cache IO",device.wait(Until.hasObject(By.text("Fresh response 1")),3000))
  val freshMs=android.os.SystemClock.elapsedRealtime()-started
  assertFalse("Cache was not independently held",release.isCompleted)
  assertTrue("Cache restoration has no visible updating state",PocketTranscript.updating)
  assertFalse("Pending disk restoration falsely certified seen history",PocketTranscript.currentHistoryVerified)
  assertEquals("Fresh network read overwrote unopened local history",original,cache.read(PocketTranscript.scopeKey(),"same-thread"))
  main{PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","synthetic-turn").put("version",900).put("row",JSONObject().put("id","same-message").put("kind","message").put("turnId","synthetic-turn").put("text","New live passage").put("version",900)))}
  release.complete(Unit)
  await{!PocketTranscript.updating&&PocketTranscript.rows.any{it.s("id")=="retained-local"}}
  assertEquals("Late cache replaced live progress","New live passage",PocketTranscript.rows.single{it.s("id")=="same-message"}.s("text"))
  assertTrue("Saved passage was not restored",device.wait(Until.hasObject(By.text("Earlier retained local passage")),4000))
  assertEquals("Parallel loading draft",Pocket.prefs.getString("draft:same-thread",null))
  assertEquals(0,qa("state").getJSONArray("replies").length())
  java.io.File(context.cacheDir,"parallel-loading-native.json").writeText(JSONObject().put("freshVisibleMs",freshMs).put("cacheStillHeldAtFreshPaint",true).put("retainedLocal",true).toString())
 }
 @Test fun readingGestureOwnsItsPassageWhenTheLateCacheArrives(){
  start();seed();qa("quiet-reading");val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  main{Pocket.selected="same-thread";PocketTranscript.reset("same-thread");Pocket.scope.launch{PocketTranscript.load(beforeCacheRead={entered.complete(Unit);release.await()})}}
  await{entered.isCompleted&&!PocketTranscript.loading&&PocketTranscript.rows.size>=20}
  assertTrue(device.wait(Until.hasObject(By.text("Fresh response 1")),4000))
  requireNotNull(device.findObject(By.scrollable(true))).scroll(Direction.UP,0.65f)
  Thread.sleep(600);device.waitForIdle(1500)
  val passage=requireNotNull(device.findObjects(By.textStartsWith("Visible passage ")).firstOrNull{val b=it.visibleBounds;!b.isEmpty&&b.top>=60&&b.bottom<device.displayHeight*3/4})
  val text=passage.text;val top=passage.visibleBounds.top
  release.complete(Unit);await{!PocketTranscript.updating&&PocketTranscript.rows.any{it.s("id")=="retained-local"}}
  device.waitForIdle(1500)
  val after=requireNotNull(device.findObject(By.text(text))).visibleBounds
  assertTrue("Late saved position overrode the thumb's reading passage",kotlin.math.abs(after.top-top)<=24)
  assertFalse("Late restoration resumed following",PocketTranscript.readPosition?.follow?:true)
  assertEquals(0,qa("state").getJSONArray("replies").length())
 }
 @Test fun switchingDuringSlowCacheCannotRestoreTheOldConversation(){
  start();seed();qa("show-second");val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  main{Pocket.selected="same-thread";PocketTranscript.reset("same-thread");Pocket.scope.launch{PocketTranscript.load(beforeCacheRead={entered.complete(Unit);release.await()})}}
  await{entered.isCompleted&&PocketTranscript.rows.any{it.s("text")=="Fresh response 1"}}
  main{Pocket.open("second-thread")};await{Pocket.selected=="second-thread"&&PocketTranscript.rows.any{it.s("text")=="Other response 1"}&&!PocketTranscript.updating}
  release.complete(Unit);Thread.sleep(500)
  assertFalse(PocketTranscript.rows.any{it.s("id")=="retained-local"})
  assertEquals("second-thread",Pocket.detail?.optJSONObject("thread")?.s("id"))
  assertEquals(0,qa("state").getJSONArray("replies").length())
 }
}
