package co.fallsoft.pocket

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.work.WorkManager
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native Firebase callback/WorkManager routing against disposable authenticated hosts.
 * This does not attest actual FCM radio delivery or Android installer behavior. */
@RunWith(AndroidJUnit4::class)
class UpdateOriginNativeTest {
 @Test fun inactiveProfilePushChecksCapturedPairingWithoutChangingSessionOrUnknownReceipts(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  WorkManager.getInstance(c).cancelAllWork().result.get()
  c.getSharedPreferences("app-updates",Context.MODE_PRIVATE).edit().clear().commit()
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  val unknown=JSONObject().put("id","retained-unknown").put("state","unknown").put("thread","same-thread").toString()
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76")
   .putString("deviceId","synthetic-device-a").putString("activeEnvironment","workstation")
   .putString("environment:remote_qa:server","http://127.0.0.1:18882").putString("environment:remote_qa:token","synthetic-ozzz")
   .putString("environment:remote_qa:deviceId","synthetic-device-b")
   .putString("draft:same-thread","Keep composing on System76").putString("environment:remote_qa:draft:same-thread","Retained OZZZ draft")
   .putString("outbox:retained-unknown",unknown)
   .putString("environments:v1",JSONArray().put(JSONObject().put("id","workstation").put("name","System76")).put(JSONObject().put("id","remote_qa").put("name","OZZZ")).toString()).commit()
  i.runOnMainSync{Pocket.init(c);Pocket.selected="same-thread"}
  val service=PocketFirebaseService()
  service.onMessageReceived(RemoteMessage.Builder("synthetic-update-only").addData("kind","app_update").addData("device_id","synthetic-device-b").build())
  val end=android.os.SystemClock.elapsedRealtime()+20000
  var checks=emptyList<JSONObject>()
  while(android.os.SystemClock.elapsedRealtime()<end){
   checks=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment("workstation")),"/_qa/state")}.getJSONArray("updateChecks").objects()
   if(checks.isNotEmpty())break
   Thread.sleep(100)
  }
  assertEquals("Inactive OZZZ update must use OZZZ, with no selected-host fallback",listOf("OZZZ"),checks.map{it.s("host")})
  assertEquals("workstation",Pocket.environmentId);assertEquals("same-thread",Pocket.selected)
  assertEquals("Keep composing on System76",prefs.getString("draft:same-thread",null))
  assertEquals("Retained OZZZ draft",prefs.getString("environment:remote_qa:draft:same-thread",null))
  assertEquals(unknown,prefs.getString("outbox:retained-unknown",null))
  service.onMessageReceived(RemoteMessage.Builder("unknown-device-only").addData("kind","app_update").addData("device_id","unknown-device").build())
  Thread.sleep(500)
  val after=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment("workstation")),"/_qa/state")}.getJSONArray("updateChecks").objects()
  assertEquals(1,after.size)
 }
}
