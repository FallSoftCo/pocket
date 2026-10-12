package co.fallsoft.pocket
import android.content.Context
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
class CatalogStartupNativeTest{
 private val i=InstrumentationRegistry.getInstrumentation()
 private val context get()=i.targetContext
 private val device get()=UiDevice.getInstance(i)
 private fun main(action:()->Unit)=i.runOnMainSync(action)
 private fun qa(path:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+path,JSONObject())}
 private fun await(condition:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(50)};assertTrue("Startup condition timed out",condition())}
 @Test fun coldStartShowsLoadingAndRetryThenPaintsCatalogBeforeActivity(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Startup draft preserved").commit()
  main{Pocket.init(context)}
  qa("runtime-offline");qa("hold-catalog")
  main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
  try{
   await{qa("state").optBoolean("catalogBlocked")&&device.hasObject(By.text("Loading conversations"))}
   assertTrue(Pocket.tasks.isEmpty())
   assertTrue(device.takeScreenshot(java.io.File(context.cacheDir,"catalog-startup-loading.png")))
   qa("fail-catalog");qa("release-catalog")
   await{Pocket.catalogLoad is CatalogLoadState.Failed&&device.hasObject(By.text("Conversations unavailable"))}
   qa("hold-activity")
   device.findObject(By.text("Retry")).click()
   await{Pocket.catalogLoad==CatalogLoadState.Ready&&Pocket.tasks.any{it.id=="same-thread"}}
   await{!device.hasObject(By.text("Loading conversations"))&&!device.hasObject(By.text("Conversations unavailable"))}
   await{!Pocket.codexOnline&&device.hasObject(By.text(Pocket.tasks.first{it.id=="same-thread"}.title))}
   qa("release-activity");await{Pocket.refreshError.isBlank()}
   qa("hold-catalog");main{Pocket.refresh()}
   await{qa("state").optBoolean("catalogBlocked")}
   assertTrue("Background refresh hid the existing catalog",Pocket.tasks.isNotEmpty())
   assertFalse("Background refresh replaced readable sessions with startup loading",device.hasObject(By.text("Loading conversations")))
   assertEquals("Startup draft preserved",prefs.getString("draft:same-thread",null))
   assertEquals(0,qa("state").getJSONArray("replies").length())
  }finally{qa("release-catalog");qa("release-activity");qa("runtime-online")}
 }
}
