package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
/** Deliberately uses no application classes: the target is the actual R8 APK. */
@RunWith(AndroidJUnit4::class)
class OptimizedPresentedReadNativeTest {
 @Test fun optimizedReplacementRecoversOnlyPresentedViews(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  assertNotEquals(prefs.getInt("qualification:read-recovery-pid",-1),android.os.Process.myPid())
  c.startActivity(Intent().setClassName(c.packageName,"co.fallsoft.pocket.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  fun state():JSONObject {
   val connection=java.net.URL("http://127.0.0.1:18880/_qa/state").openConnection() as java.net.HttpURLConnection
   connection.requestMethod="POST";connection.setRequestProperty("Authorization","Bearer synthetic-system76");connection.doOutput=true;connection.connectTimeout=3000;connection.readTimeout=3000
   try{connection.outputStream.use{it.write("{}".toByteArray())};return JSONObject(connection.inputStream.bufferedReader().use{it.readText()})}finally{connection.disconnect()}
  }
  val end=System.currentTimeMillis()+90000;var result=state()
  while(result.getJSONArray("acknowledgements").length()<4&&System.currentTimeMillis()<end){Thread.sleep(100);result=state()}
  val array=result.getJSONArray("acknowledgements");val rows=(0 until array.length()).map{array.getJSONObject(it)}
  assertEquals(listOf("capture-1","capture-2"),rows.filter{it.has("token")}.map{it.getString("token")})
  assertEquals(listOf(1L,2L),rows.filter{it.has("throughId")}.map{it.getLong("throughId")})
  assertFalse(rows.any{it.optString("token")=="capture-3"||it.optLong("throughId")==3L})
  assertEquals(0,result.getJSONArray("replies").length())
  assertEquals("Keep read recovery draft",prefs.getString("draft:same-thread",null))
  val pendingEnd=System.currentTimeMillis()+5000
  while(prefs.all.keys.any{it.startsWith("presented-read:")}&&System.currentTimeMillis()<pendingEnd)Thread.sleep(50)
  assertFalse(prefs.all.keys.any{it.startsWith("presented-read:")})
  assertEquals(2L,prefs.all.filterKeys{it.startsWith("notification-read:")&&it.endsWith(":same-thread")}.values.single())
 }
}
