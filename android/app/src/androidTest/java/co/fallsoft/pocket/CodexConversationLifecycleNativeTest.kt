package co.fallsoft.pocket

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** One integrated owner journey, with actual cancellable HTTP; no worker admission. */
@RunWith(AndroidJUnit4::class)
class CodexConversationLifecycleNativeTest {
 @Test fun navigationBackgroundAndReconnectShareOneRetirableReadOwner(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  fun main(action:()->Unit)=i.runOnMainSync(action)
  fun await(label:String,condition:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+15000
   while(android.os.SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(30)};assertTrue(label,condition())}
  fun qa(name:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+name,JSONObject())}
  val prefs=c.getSharedPreferences("pocket",0)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76")
   .putString("deviceId","synthetic-device-a").putString("draft:same-thread","Lifecycle draft stays").commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await("Catalog missing"){Pocket.tasks.isNotEmpty()}
  try {
   main{Pocket.open("same-thread",keyboard=true)}
   await("Original current head missing"){PocketTranscript.currentHistoryVerified&&PocketTranscript.rows.any{it.s("text")=="Fresh response 1"}}
   val first=CodexConversations.incarnation;val firstKey=CodexConversations.currentKey
   val readsBefore=qa("state").getJSONArray("reads").length()
   val abortedBefore=qa("state").getJSONArray("abortedReads").length()
   qa("hold-history");main{Pocket.refreshDetail()}
   await("Held read never reached server"){qa("state").getJSONArray("reads").length()>readsBefore}
   main{Pocket.open("second-thread")}
   await("Navigation failed to paint B independently"){CodexConversations.snapshot.key?.thread=="second-thread"&&PocketTranscript.currentHistoryVerified&&PocketTranscript.rows.any{it.s("text")=="Other response 1"}}
   await("Navigation ignored rather than cancelled actual A HTTP call"){qa("state").getJSONArray("abortedReads").length()>abortedBefore}
   assertTrue(CodexConversations.incarnation>first)
   val beforeStale=qa("state").getJSONArray("reads").length()
   runBlocking{CodexConversations.readFrom(firstKey,first)}
   var staleAdmitted=true
   main{staleAdmitted=CodexConversations.submit(firstKey,first,"Must never dispatch","steer","draft:same-thread"){}}
   assertFalse("A stale A composer admitted input to B",staleAdmitted)
   assertEquals("A stale viewport redirected a read to B",beforeStale,qa("state").getJSONArray("reads").length())
   assertEquals("A stale viewport dispatched input",0,qa("state").getJSONArray("replies").length())
   val beforeReturn=qa("state").getJSONArray("reads").length()
   main{Pocket.open("same-thread",keyboard=true)}
   await("A return did not start its own read"){qa("state").getJSONArray("reads").length()>beforeReturn}
   assertTrue("A→B→A reused old incarnation",CodexConversations.incarnation>first)
   val abortedAtPause=qa("state").getJSONArray("abortedReads").length()
   main{PocketVoice.foreground=false;CodexConversations.onForeground(false);PocketLive.stop()}
   await("Background failed to cancel actual HTTP read"){qa("state").getJSONArray("abortedReads").length()>abortedAtPause}
   assertEquals(CodexReadPhase.PAUSED,CodexConversations.snapshot.freshness.phase)
   qa("release-history");qa("silent")
   val pausedReads=qa("state").getJSONArray("reads").length()
   main{repeat(20){Pocket.scheduleRefresh()}}
   Thread.sleep(700)
   assertEquals("Hidden viewport invalidations started history work",pausedReads,qa("state").getJSONArray("reads").length())
   main{PocketVoice.foreground=true;CodexConversations.onForeground(true)}
   await("Foreground did not recover unseen original source head"){PocketTranscript.currentHistoryVerified&&PocketTranscript.rows.any{it.s("text")=="Fresh response 2"}}
   assertEquals("Lifecycle draft stays",prefs.getString("draft:same-thread",null))
   val beforeBurst=qa("state").getJSONArray("reads").length()
   main{repeat(20){CodexConversations.reconnected()}}
   await("Reconnect invalidation did not read"){qa("state").getJSONArray("reads").length()>beforeBurst}
   await("Reconnect did not settle"){PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating}
   Thread.sleep(500)
   assertEquals("A reconnect burst created multiple duplicate reads",beforeBurst+1,qa("state").getJSONArray("reads").length())
   assertTrue("Canonical message identities duplicated",PocketTranscript.rows.groupBy{it.s("id")}.values.all{it.size==1})
   assertEquals("A read lifecycle admitted worker input",0,qa("state").getJSONArray("replies").length())
   c.cacheDir.resolve("codex-conversation-lifecycle.json").writeText(JSONObject().put("passed",true).put("actualHttpCancellations",qa("state").getJSONArray("abortedReads").length()-abortedBefore)
    .put("abaIncarnationsDistinct",true).put("foregroundHeadRecovered",true).put("reconnectBurstRequests",1).put("workerSubmissions",0).put("draftPreserved",true).toString())
   main{Pocket.closeTask()}
   assertNull(CodexConversations.snapshot.key);assertTrue(PocketTranscript.rows.isEmpty())
  }finally{qa("release-history");main{Pocket.closeTask();PocketVoice.foreground=false;CodexConversations.onForeground(false)}}
 }
}
