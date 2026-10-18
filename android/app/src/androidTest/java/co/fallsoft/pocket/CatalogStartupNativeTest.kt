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
 private fun await(condition:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(android.os.Build.VERSION.SDK_INT>=34)i.uiAutomation.clearCache();if(condition())return;Thread.sleep(50)};if(!condition()){device.dumpWindowHierarchy(java.io.File(context.cacheDir,"catalog-paging-failure.xml"));device.takeScreenshot(java.io.File(context.cacheDir,"catalog-paging-failure.png"))};assertTrue("Startup condition timed out",condition())}
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
   val readsWhileActivityHeld=qa("state").optInt("catalogReads")
   qa("silent");main{Pocket.refresh()}
   await{qa("state").optInt("catalogReads")>readsWhileActivityHeld&&Pocket.tasks.any{it.id=="same-thread"&&it.preview=="Fresh response 2"}}
   assertTrue("Activity hold hid the independently refreshed catalog",Pocket.catalogLoad==CatalogLoadState.Ready)
   qa("release-activity");await{Pocket.refreshError.isBlank()}
   qa("hold-catalog");main{Pocket.refresh()}
   await{qa("state").optBoolean("catalogBlocked")}
   assertTrue("Background refresh hid the existing catalog",Pocket.tasks.isNotEmpty())
   assertFalse("Background refresh replaced readable sessions with startup loading",device.hasObject(By.text("Loading conversations")))
   assertEquals("Startup draft preserved",prefs.getString("draft:same-thread",null))
   assertEquals(0,qa("state").getJSONArray("replies").length())
   qa("release-catalog");qa("catalog-pages");main{Pocket.refresh()}
   await{Pocket.tasks.count{it.id.startsWith("catalog-page-")}==40}
   fun titles():Set<String>{
    if(android.os.Build.VERSION.SDK_INT>=34)i.uiAutomation.clearCache()
    return device.findObjects(By.textStartsWith("Catalog passage ")).filter{it.visibleBounds.top>50&&it.visibleBounds.bottom<device.displayHeight*3/4}.map{it.text}.toSet()
   }
   // Background membership refresh retains the existing reader anchor.
   if(titles().isEmpty()){requireNotNull(device.findObject(By.res("reader-page-down"))).click();Thread.sleep(600)}
   await{titles().isNotEmpty()};device.waitForIdle(2000)
   val initial=titles();assertTrue(initial.isNotEmpty())
   requireNotNull(device.findObject(By.res("reader-page-up"))).click();Thread.sleep(600)
   val older=titles();assertTrue("Page-up didn't reach older cards",older.isNotEmpty()&&older!=initial)
   fun newest(names:Set<String>)=names.minOf{it.substringAfter("Catalog passage ").toInt()}
   assertTrue("Reverse list paged in the wrong direction",newest(older)>newest(initial))
   requireNotNull(device.findObject(By.res("reader-page-down"))).click();Thread.sleep(600)
   assertEquals("Paging back lost recent cards",initial,titles())
   assertEquals("Paging lost draft","Startup draft preserved",prefs.getString("draft:same-thread",null))
   assertEquals("Paging started worker commands",0,qa("state").getJSONArray("replies").length())

  }finally{qa("release-catalog");qa("release-activity");qa("runtime-online")}
 }
 @Test fun delayedSearchCannotRollBackLiveCatalogEvidence(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").commit()
  main{Pocket.init(context);PocketLive.stop();Pocket.refresh()}
  await{Pocket.tasks.any{it.id=="same-thread"}&&!Pocket.loadingSessions}
  qa("hold-catalog")
  try{
   main{Pocket.moreSessions("Synthetic")}
   await{qa("state").optBoolean("catalogBlocked")}
   main{Pocket.event(JSONObject().put("type","sessionPreview").put("threadId","same-thread").put("preview","Newest live command").put("previewKind","command").put("previewRole","activity").put("activityAt",1900000000000L))}
   await{Pocket.tasks.first{it.id=="same-thread"}.preview=="Newest live command"}
   qa("release-catalog")
   await{!Pocket.loadingSessions}
   val task=Pocket.tasks.first{it.id=="same-thread"}
   assertEquals("Delayed search overwrote newer live preview","Newest live command",task.preview)
   assertEquals("command",task.previewKind)
   assertEquals(1900000000000L,task.activityAt)
   assertEquals("Catalog read started work",0,qa("state").getJSONArray("replies").length())
  }finally{qa("release-catalog")}
 }

}
