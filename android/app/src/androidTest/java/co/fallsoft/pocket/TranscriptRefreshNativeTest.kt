package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class TranscriptRefreshNativeTest {
 private val i=InstrumentationRegistry.getInstrumentation()
 private val context get()=i.targetContext
 private val device get()=UiDevice.getInstance(i)
 private fun main(b:()->Unit)=i.runOnMainSync(b)
 private fun await(b:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(b())return;Thread.sleep(50)};assertTrue("Freshness condition timed out",b())}
 private fun qa(path:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+path,JSONObject())}
 private fun has(n:Int)=PocketTranscript.rows.any{it.s("text")=="Fresh response "+n}
 @Test fun cachedPreviewRefreshIsVisibleAndLatestWinsOverOlderHistory(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Unsaved draft preserved").commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.loading}
  qa("advance");await{Pocket.tasks.first().preview=="Fresh response 2"&&PocketTranscript.updating}
  assertTrue("Cached content disappeared during refresh",has(1))
  assertTrue("Refresh indicator not visible",device.wait(Until.hasObject(By.text("Updating messages")),2000))
  await{has(2)&&!PocketTranscript.updating}
  val reads=qa("state").getJSONArray("reads").length()
  qa("repeat");Thread.sleep(800)
  assertEquals("Repeated previews triggered polling",reads,qa("state").getJSONArray("reads").length())
  main{Pocket.scope.launchEnvironment{PocketTranscript.load(true)}};await{PocketTranscript.loading}
  qa("advance");await{PocketTranscript.updating}
  await{has(3)&&!PocketTranscript.updating}
  assertTrue("Older loaded rows lost",PocketTranscript.rows.any{it.s("text")=="Older synthetic history"})
  qa("silent");main{Pocket.refresh()};await{has(4)&&!PocketTranscript.updating}
  main{Pocket.closeTask();Pocket.open("same-thread")}
  await{PocketTranscript.updating};assertTrue("Warm history hidden",has(4))
  await{!PocketTranscript.loading&&!PocketTranscript.updating}
  assertEquals("Unsaved draft preserved",prefs.getString("draft:same-thread",null))
 }
}
