package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import androidx.work.WorkInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable emulator only: loopback synthetic hosts, no Codex/provider or live pairing. */
@RunWith(AndroidJUnit4::class)
class EnvironmentIsolationNativeTest {
 private val instrumentation=InstrumentationRegistry.getInstrumentation()
 private val context get()=instrumentation.targetContext
 private val device get()=UiDevice.getInstance(instrumentation)
 private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
 private fun await(condition:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(100)};assertTrue("Timed out waiting for isolated environment state",condition())}
 @Test fun sameThreadHistoryDraftsAndPendingDeliveryStayWithOrigin(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa"){"Wrong emulator ownership"}
  check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.FINGERPRINT.contains("emu")){"Synthetic test must never run on an owner's phone"}
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a")
   .putString("local:server","http://127.0.0.1:18882").putString("local:token","synthetic-phone").putString("local:deviceId","synthetic-device-phone")
   .putString("draft:same-thread","System76 draft retained").putString("environment:remote_qa:server","http://127.0.0.1:18881").putString("environment:remote_qa:token","synthetic-ozzz").putString("environment:remote_qa:deviceId","synthetic-device-b")
   .putString("environment:remote_qa:draft:same-thread","OZZZ draft retained")
   .putString("outbox:unknown-retained",JSONObject().put("thread","same-thread").put("text","uncertain prior delivery").put("local",false).put("environment","workstation").put("state","unknown").toString())
   .putString("environments:v1",JSONArray().put(JSONObject().put("id","workstation").put("name","System76")).put(JSONObject().put("id","remote_qa").put("name","OZZZ")).toString()).commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  assertTrue(device.wait(Until.hasObject(By.text("System76 synthetic work")),15000))
  main{Pocket.open("same-thread")};await{PocketTranscript.rows.any{it.s("text").contains("System76 answer only")}}
  main{Pocket.closeTask()}
  assertTrue(device.wait(Until.hasObject(By.desc("Settings, app updates, usage and activity")),5000));device.findObject(By.desc("Settings, app updates, usage and activity")).click()
  assertTrue(device.wait(Until.hasObject(By.desc("Development environment: System76")),5000));device.findObject(By.desc("Development environment: System76")).click()
  assertTrue(device.wait(Until.hasObject(By.text("OZZZ")),5000));device.findObject(By.text("OZZZ")).click()
  await{Pocket.environmentId=="remote_qa"&&Pocket.tasks.any{it.title=="OZZZ synthetic work"}}
  main{Pocket.open("same-thread")};await{PocketTranscript.rows.any{it.s("text").contains("OZZZ answer only")}}
  assertFalse(PocketTranscript.rows.any{it.s("text").contains("System76 answer only")})
  await{PhoneEventMonitor.connected}
  runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment("phone")),"/_qa/notify",JSONObject())}
  await{context.getSystemService(NotificationManager::class.java).activeNotifications.any{it.notification.extras.getCharSequence("android.title")?.toString()=="Phone result only"}}
  assertEquals("remote_qa",Pocket.environmentId);assertTrue(PocketTranscript.rows.any{it.s("text").contains("OZZZ answer only")})
  assertEquals("System76 draft retained",prefs.getString("draft:same-thread",null));assertEquals("OZZZ draft retained",prefs.getString("environment:remote_qa:draft:same-thread",null))
  // Origin captured before scheduling: worker must submit A while B remains selected.
  val id="native-origin-a"
  prefs.edit().putString("outbox:$id",JSONObject().put("thread","same-thread").put("text","synthetic origin A reply").put("local",false).put("environment","workstation").put("endpoint","http://127.0.0.1:18880").put("token","synthetic-system76").put("notificationId",987).toString()).commit()
  ReplyDeliveryWorker.enqueue(context,id);await{!prefs.contains("outbox:$id")}
  assertEquals("remote_qa",Pocket.environmentId)
  ReplyDeliveryWorker.enqueue(context,"unknown-retained")
  await{WorkManager.getInstance(context).getWorkInfosForUniqueWork("pocket-reply-unknown-retained").get().any{it.state==WorkInfo.State.FAILED}}
  assertTrue(prefs.contains("outbox:unknown-retained"));assertEquals("OZZZ draft retained",prefs.getString("environment:remote_qa:draft:same-thread",null))
  main{Pocket.activateEnvironment("workstation");Pocket.open("same-thread")};await{PocketTranscript.rows.any{it.s("text").contains("System76 answer only")}}
  assertFalse(PocketTranscript.rows.any{it.s("text").contains("OZZZ answer only")})
  assertEquals("workstation",EnvironmentProfiles(prefs).selected().id)
 }
}
