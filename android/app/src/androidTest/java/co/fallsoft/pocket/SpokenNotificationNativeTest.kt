package co.fallsoft.pocket

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpokenNotificationNativeTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Test fun retainedSpokenNotificationOpensItsSourceWithoutSending(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Keep caption navigation draft").commit()
  fun main(b:()->Unit)=i.runOnMainSync(b)
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  compose.waitUntil(15000){Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{
   Pocket.closeTask()
   PocketNotificationTitles.remember(JSONObject().put("id",901).put("thread_id","same-thread").put("title","Spoken source task").put("title_revision",1))
   PocketSpeechCaptions.update(901,"Spoken source task","The actual spoken passage",0,1)
   PocketSpeechCaptions.complete()
  }
  val manager=c.getSystemService(NotificationManager::class.java)
  val notice=manager.activeNotifications.single{it.id==998}
  assertEquals("The actual spoken passage",notice.notification.extras.getCharSequence("android.bigText").toString())
  assertNull(Pocket.selected)
  notice.notification.contentIntent.send()
  compose.waitUntil(15000){Pocket.selected=="same-thread"&&PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  assertEquals("Keep caption navigation draft",prefs.getString("draft:same-thread",null))
  val state=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/state",JSONObject())}
  assertEquals("Opening spoken source submitted work",0,state.getJSONArray("replies").length())
  main{PocketSpeechCaptions.dismiss()}
 }
}
