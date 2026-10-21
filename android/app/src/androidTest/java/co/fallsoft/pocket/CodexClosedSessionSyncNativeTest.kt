package co.fallsoft.pocket

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodexClosedSessionSyncNativeTest {
 @Test fun closedSessionRefreshesBeforeOpeningAndLiveRowsDoNotWaitForHistory(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  fun main(action:()->Unit)=i.runOnMainSync(action)
  fun await(label:String,condition:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+15000
   while(android.os.SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(30)};assertTrue(label,condition())}
  fun qa(name:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+name,JSONObject())}
  c.deleteDatabase("codex-session-messages-v1.sqlite") // Owned synthetic emulator only.
  val prefs=c.getSharedPreferences("pocket",0)
  prefs.edit().clear().putString("server","http://127.0.0.1:18903").putString("token","synthetic-system76")
   .putString("deviceId","synthetic-device-a").putString("draft:same-thread","Keep my unsent draft").commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}
  await("Initial canonical cache missing"){PocketTranscript.currentHistoryVerified&&PocketTranscript.rows.any{it.s("text")=="Fresh response 1"}}
  try{
   main{Pocket.closeTask();PocketLive.stop();PocketVoice.foreground=false;CodexConversations.onForeground(false)}
   qa("silent")
   main{Pocket.acceptNotification(JSONObject().put("id",2002).put("thread_id","same-thread").put("kind","complete").put("title","Synthetic result").put("body","Fresh response 2").put("created_at",System.currentTimeMillis()),"fcm-normal")}
   await("Closed session never synchronized from background notification"){
    CodexSessionRepository.peek("same-thread")?.optJSONObject("timeline")?.optJSONArray("rows")?.objects()?.any{it.s("text")=="Fresh response 2"}==true&&!CodexSessionRepository.isDirty("same-thread")
   }
   assertNull("Background synchronization selected a chat",Pocket.selected)
   qa("hold-history")
   val opened=android.os.SystemClock.elapsedRealtime()
   var firstTexts=listOf<String>()
   main{PocketVoice.foreground=true;CodexConversations.onForeground(true);Pocket.open("same-thread");firstTexts=CodexConversations.snapshot.rows.map{it.s("text")}}
   assertTrue("First selected snapshot showed stale cached answer",firstTexts.contains("Fresh response 2"))
   assertFalse("Stale answer flashed before current one",firstTexts.contains("Fresh response 1"))
   assertTrue("Current answer did not paint while history remained held",device.wait(Until.hasObject(By.text("Fresh response 2")),3000))
   val paintMs=android.os.SystemClock.elapsedRealtime()-opened
   qa("release-history")
   await("Foreground head reconciliation failed"){PocketTranscript.currentHistoryVerified&&!PocketTranscript.loading}
   main{Pocket.closeTask();PocketLive.stop();PocketVoice.foreground=false;CodexConversations.onForeground(false)}
   Thread.sleep(400)
   val before=qa("state").getJSONArray("reads").length()
   main{Pocket.event(JSONObject().put("type","timeline").put("threadId","same-thread").put("turnId","synthetic-turn").put("version",300)
    .put("row",JSONObject().put("id","synthetic-turn/live").put("turnId","synthetic-turn").put("kind","message").put("version",300).put("text","Newest streamed answer"))
    .put("historyHead",JSONObject().put("threadId","same-thread").put("epoch",requireNotNull(CodexSessionRepository.peek("same-thread")).getJSONObject("historyHead").s("epoch")).put("version",300)))}
   await("Inactive session ignored public live row"){CodexSessionRepository.peek("same-thread")?.getJSONObject("timeline")?.getJSONArray("rows")?.objects()?.any{it.s("text")=="Newest streamed answer"}==true}
   Thread.sleep(500)
   assertEquals("Live text caused a redundant history round trip",before,qa("state").getJSONArray("reads").length())
   assertEquals("Read synchronization admitted worker input",0,qa("state").getJSONArray("replies").length())
   assertEquals("Draft lost", "Keep my unsent draft",prefs.getString("draft:same-thread",null))
   c.cacheDir.resolve("closed-session-sync.json").writeText(JSONObject().put("passed",true).put("firstSnapshotWasCurrent",true).put("nativePaintMs",paintMs).put("liveUpdateHistoryReads",0).put("workerSubmissions",0).put("draftPreserved",true).toString())
  }finally{qa("release-history");main{Pocket.closeTask()}}
 }

 @Test fun searchPageAdvancesSharedMessagesBeforeReopeningWithoutNotification(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  fun main(action:()->Unit)=i.runOnMainSync(action)
  fun await(label:String,condition:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+12000
   while(android.os.SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(30)};assertTrue(label,condition())}
  fun qa(name:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+name,JSONObject())}
  c.deleteDatabase("codex-session-messages-v1.sqlite")
  val prefs=c.getSharedPreferences("pocket",0)
  prefs.edit().clear().putString("server","http://127.0.0.1:18903").putString("token","synthetic-system76")
   .putString("deviceId","synthetic-device-a").putString("draft:same-thread","Keep my search-page draft").commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}
  await("Initial current history missing"){PocketTranscript.currentHistoryVerified&&PocketTranscript.rows.any{it.s("text")=="Fresh response 1"}}
  try{
   main{Pocket.closeTask();PocketLive.stop()}
   val destination=requireNotNull(Pocket.captureEnvironment())
   runBlocking{CodexCatalog.source(destination).head()}
   qa("search-only-update")
   runBlocking{CodexCatalog.source(destination).readPage("fresh")}
   assertEquals("Search did not advance its card","Fresh response 2",CodexCatalog.source(destination).tasks.first{it.id=="same-thread"}.preview)
   await("Card advanced but the shared message snapshot remained stale"){
    CodexSessionRepository.peek("same-thread")?.getJSONObject("timeline")?.getJSONArray("rows")?.objects()?.any{it.s("text")=="Fresh response 2"}==true&&
     !CodexSessionRepository.isDirty("same-thread")
   }
   qa("hold-history")
   var first=listOf<String>()
   main{Pocket.open("same-thread");first=CodexConversations.snapshot.rows.map{it.s("text")}}
   assertTrue("Reopening painted an older answer despite the newer card",first.contains("Fresh response 2"))
   assertFalse("Older answer flashed on reopen",first.contains("Fresh response 1"))
   assertTrue(device.wait(Until.hasObject(By.text("Fresh response 2")),3000))
   assertEquals("Draft changed","Keep my search-page draft",prefs.getString("draft:same-thread",null))
   assertEquals("Read-only repair sent work",0,qa("state").getJSONArray("replies").length())
  }finally{qa("release-history");main{Pocket.closeTask();PocketLive.stop()}}
 }
}
