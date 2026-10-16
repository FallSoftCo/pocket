package co.fallsoft.pocket

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real bound QA room through a read-only fault proxy; no agent/model or command writes. */
@RunWith(AndroidJUnit4::class)
class HollywoodReconnectNativeTest {
 @Test fun capabilityOutageRecoversWithoutLosingOpenRoomOrDraft()=runBlocking {
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val config=JSONObject(c.filesDir.resolve("live-room-qa-config.json").readText())
  val id="live_room_qa_20261015";val base=config.s("base");val token=config.s("token")
  val destination=EnvironmentRequest(EnvironmentIdentity(id,"Real room QA"),base,token)
  suspend fun control(action:String)=Pocket.apiEnvironment(destination,"/_qa/reconnect",JSONObject().put("controlToken",config.s("controlToken")).put("action",action))
  fun await(label:String,test:()->Boolean){val end=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<end){i.uiAutomation.clearCache();if(test())return;Thread.sleep(100)};assertTrue(label,test())}
  val client=HollywoodClient(backendOwner(base,token),config.getJSONObject("identity"),base,token)
  val latest=client.call("rooms/"+config.s("roomId")+"/history?before=9007199254740991&limit=100").getJSONArray("events").objects()
   .last{it.s("kind") in setOf("message","request","result")&&it.s("body").isNotBlank()}.s("body").take(70)
  control("fail")
  val p=c.getSharedPreferences("pocket",0)
  val profiles=JSONArray(p.getString("environments:v1","[]")).objects().filter{it.s("id")!=id}
  p.edit().putString("environments:v1",JSONArray(profiles+JSONObject().put("id",id).put("name","Real room QA").put("local",false)).toString())
   .putString("activeEnvironment",id).putString("environment:$id:server",base).putString("environment:$id:token",token)
   .putString("backend:"+backendOwner(base,token),"lax").commit()
  try {
   i.runOnMainSync{Pocket.init(c);Pocket.activateEnvironment(id,force=true);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
   await("Capability failure not shown"){d.hasObject(By.text("Hollywood connection unavailable"))}
   await("Actual live socket did not open"){Pocket.connected}
   val before=control("state")
   control("recover")
   await("Room did not recover automatically on an unchanged live socket"){d.hasObject(By.textContains(latest))}
   val after=control("state")
   assertEquals("Metadata recovery unexpectedly needed another live connection",before.getInt("connections"),after.getInt("connections"))
   val draft="Keep this unsent native room draft"
   requireNotNull(d.findObject(By.clazz("android.widget.EditText"))).text=draft
   await("Draft was not saved"){p.all.any{(key,value)->key.startsWith("draft:hollywood:")&&value==draft}}
   control("fail");control("disconnect")
   await("Transport did not reconnect"){runBlocking{control("state")}.getInt("connections")>after.getInt("connections")}
   await("Failed revalidation was not visible"){d.hasObject(By.textContains("QA capability discovery temporarily unavailable"))}
   assertTrue("Revalidation hid the mounted room",d.hasObject(By.res("native-room-address")))
   assertTrue("Revalidation hid unsent input",d.hasObject(By.text(draft)))
   control("recover")
   await("Recovery warning did not clear"){!d.hasObject(By.textContains("QA capability discovery temporarily unavailable"))}
   assertTrue("Recovery lost draft",d.hasObject(By.text(draft)))
   val events=client.call("rooms/"+config.s("roomId")+"/history?before=9007199254740991&limit=100").getJSONArray("events").objects()
   assertEquals("Original accepted message was duplicated",1,events.count{it.s("id")=="4564d27f-0fd2-4365-8d8b-69022e098fb7"})
   val report=control("state").put("originalMessageCount",1).put("draftPreserved",true).put("actualCanonicalRoom",true).put("physicalPhone",false)
   c.filesDir.resolve("native-backend-reconnect-receipt.json").writeText(report.toString())
  }finally{control("recover")}
 }
}
