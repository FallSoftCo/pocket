package co.fallsoft.pocket

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** One-use pairing input is supplied privately on an owned emulator, never in source or test output. */
@RunWith(AndroidJUnit4::class)
class NativePairingTransportTest {
 @Test fun additiveAuthenticatedPairingPreservesOtherEnvironments(){
  val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
  val device=UiDevice.getInstance(instrumentation)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa"){"Wrong emulator ownership"}
  val file=java.io.File(context.filesDir,"native-pairing-input.json")
  val input=JSONObject(file.readText());file.delete()
  val before=Pocket.environmentProfiles.all().mapNotNull{Pocket.captureEnvironment(it.id)}
  instrumentation.runOnMainSync{Pocket.pair(input.getString("url"),input.getString("code"),input.getString("name"))}
  val deadline=System.currentTimeMillis()+30000
  while(System.currentTimeMillis()<deadline&&(Pocket.base!=input.getString("url")||Pocket.busy)&&Pocket.error.isBlank())Thread.sleep(100)
  assertFalse("Pairing did not settle",Pocket.busy);assertEquals("",Pocket.error)
  val current=requireNotNull(Pocket.captureEnvironment());assertEquals(input.getString("url"),current.endpoint)
  assertEquals(input.getString("name"),current.environment.name);assertFalse(current.environment.local)
  before.forEach{assertTrue("Existing pairing changed",Pocket.captureEnvironment(it.environment.id)?.matches(it)==true)}
  val status=runBlocking{Pocket.apiEnvironment(current,"/api/status")};assertTrue("Authenticated runtime is unavailable",status.optBoolean("connected"))
  // This test creates no task. Revoke only its own disposable device after the read settles.
  runBlocking{Pocket.apiEnvironment(current,"/api/device/disconnect",JSONObject())}
  Pocket.prefs.edit().remove(current.environment.key("server")).remove(current.environment.key("token")).remove(current.environment.key("deviceId"))
   .putString("environments:v1",org.json.JSONArray(Pocket.environmentProfiles.all().filter{it.id!=current.environment.id}.map{JSONObject().put("id",it.id).put("name",it.name).put("local",it.local)}).toString()).commit()
  instrumentation.runOnMainSync{Pocket.activateEnvironment(before.first().environment.id)}
  val end=System.currentTimeMillis()+10000
  while(System.currentTimeMillis()<end&&Pocket.captureEnvironment(current.environment.id)!=null)Thread.sleep(100)
  assertNull(Pocket.captureEnvironment(current.environment.id))
 }
}
