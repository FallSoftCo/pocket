package co.fallsoft.pocket

import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android notification tray, native navigation and independently failing HTTP reads. */
@RunWith(AndroidJUnit4::class)
class ForegroundNotificationNativeTest {
 @Test fun activeAppCoalescesAlertsPreservesQuestionsAndKeepsDelayedRefreshInline(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  fun main(action:()->Unit)=i.runOnMainSync(action)
  fun await(label:String,predicate:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+25000
   while(android.os.SystemClock.elapsedRealtime()<end){if(predicate())return;Thread.sleep(40)};assertTrue(label,predicate())}
  val prefs=c.getSharedPreferences("pocket",0)
  prefs.edit().clear().putString("server","http://127.0.0.1:18907").putString("token","synthetic-system76").putString("deviceId","synthetic-notification-device")
   .putString("draft:same-thread","Keep the foreground draft").commit()
  val activityMonitor=i.addMonitor(MainActivity::class.java.name,null,false)
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread",keyboard=true)}
  val firstActivity=i.waitForMonitorWithTimeout(activityMonitor,15000)?:error("Owned activity unavailable")
  await("Initial source did not load"){PocketTranscript.currentHistoryVerified&&PocketVoice.foreground}
  val manager=c.getSystemService(NotificationManager::class.java);val tag=Pocket.notificationTag(Pocket.environmentId)
  fun inTray(id:Long)=manager.activeNotifications.any{it.tag==tag&&it.id==(id+1000).toInt()}
  fun notice(id:Long,thread:String,body:String,kind:String="complete")=JSONObject().put("id",id).put("thread_id",thread).put("title","Synthetic "+thread).put("body",body)
   .put("kind",kind).put("created_at",System.currentTimeMillis()).put("_environment",Pocket.environmentId)
  fun qa(action:String,body:JSONObject=JSONObject())=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+action,body)}
  val speechBefore=PocketSpeech.count
  try{
   val current=notice(73001,"same-thread","Current result")
   main{Pocket.acceptNotification(current,"socket");Pocket.acceptNotification(JSONObject(current.toString()),"fcm")}
   Thread.sleep(250)
   assertFalse("Open conversation created a system notification",inTray(73001))
   assertNull("Open conversation duplicated its own content in a foreground banner",ForegroundNotifications.current())
   main{Pocket.acceptNotification(notice(73002,"second-thread","Other result 1"),"socket");Pocket.acceptNotification(notice(73003,"second-thread","Other result 2"),"fcm")}
   assertEquals("Other source was not retained by notification policy",73003L,ForegroundNotifications.current()?.optLong("id"))
   await("Other work was not surfaced in place"){d.hasObject(By.res("foreground-work-notice"))}
   assertEquals("Same-source results did not coalesce",73003L,ForegroundNotifications.current()?.optLong("id"))
   assertFalse(inTray(73002));assertFalse(inTray(73003));assertEquals("Foreground updates unexpectedly queued speech",speechBefore,PocketSpeech.count)
   main{firstActivity.recreate()}
   val recreated=i.waitForMonitorWithTimeout(activityMonitor,15000)
   assertNotNull("Configuration recreation did not finish",recreated)
   await("Foreground alert lost during recreation"){PocketVoice.foreground&&d.hasObject(By.res("foreground-work-notice"))}
   assertFalse("Configuration change flushed foreground alerts into Android tray",inTray(73003))
   d.findObject(By.res("foreground-work-notice")).click()
   await("One tap did not open original source"){Pocket.selected=="second-thread"&&PocketTranscript.currentHistoryVerified}
   assertEquals("Foreground navigation erased the first draft","Keep the foreground draft",prefs.getString("draft:same-thread",null))
   main{Pocket.open("same-thread")}
   await("Original source did not reopen"){PocketTranscript.currentHistoryVerified&&PocketTranscript.rows.any{it.s("text").startsWith("Fresh response")}}
   qa("fail-reads",JSONObject().put("count",20));main{Pocket.refreshDetail()}
   await("Actual repeated HTTP failures did not expose quiet refresh state"){PocketTranscript.refreshDelayed&&d.hasObject(By.res("conversation-refresh-delayed"))}
   assertFalse("Transient history error still blames the workstation",d.hasObject(By.textContains("workstation is taking too long")))
   assertTrue("Current messages vanished on a history error",PocketTranscript.rows.any{it.s("text").startsWith("Fresh response")})
   c.cacheDir.resolve("foreground-refresh-delayed.png").let{d.takeScreenshot(it)}
   qa("fail-reads",JSONObject().put("count",0));main{Pocket.refreshDetail()}
   await("Healthy read did not clear delayed state"){!PocketTranscript.refreshDelayed&&PocketTranscript.currentHistoryVerified}
   val question=notice(73004,"second-thread","Please answer this question","question")
   qa("set-attention",JSONObject().put("notifications",org.json.JSONArray().put(question)))
   main{Pocket.acceptNotification(question,"socket")}
   await("Question was not actionable in app"){ForegroundNotifications.current()?.optLong("id")==73004L}
   assertFalse(inTray(73004))
   d.pressHome()
   await("Unseen question was lost when leaving app"){!PocketVoice.foreground&&inTray(73004)}
   assertTrue("Background question lost its response actions",manager.activeNotifications.single{it.tag==tag&&it.id==74004}.notification.actions.any{it.title.toString().contains("Answer")})
   assertTrue("Latest unseen result was lost",inTray(73003));assertFalse("Coalesced stale result resurfaced",inTray(73002))
   qa("set-attention",JSONObject().put("notifications",org.json.JSONArray()))
   main{c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
   await("Resolved canonical question remained in foreground/tray"){PocketAttention.dismissed(73004)&&!inTray(73004)&&ForegroundNotifications.current()?.optLong("id")!=73004L}
   assertEquals("Notification/refresh handling admitted model work",0,qa("state").getJSONArray("replies").length())
   c.cacheDir.resolve("foreground-notification-result.json").writeText(JSONObject().put("passed",true).put("foregroundSystemAlerts",0).put("socketFcmDeduplicated",true).put("configurationChangeKeptInApp",true)
    .put("sameConversationDuplicated",false).put("otherSourceCoalesced",true).put("oneTapOriginalSource",true).put("draftPreserved",true)
    .put("actualHttpFailureInline",true).put("backgroundQuestionRetained",true).put("canonicalResolutionClearsStaleAttention",true).put("modelSubmissions",0).put("physicalPhone",false).toString())
  }finally{
   runCatching{qa("fail-reads",JSONObject().put("count",0));qa("set-attention",JSONObject().put("notifications",org.json.JSONArray()))}
   main{Pocket.closeTask();PocketVoice.foreground=false;ForegroundNotifications.background()}
   (73001L..73004L).forEach{manager.cancel(tag,(it+1000).toInt())}
   i.removeMonitor(activityMonitor)
  }
 }
}
