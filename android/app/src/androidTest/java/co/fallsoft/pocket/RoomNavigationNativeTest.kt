package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomNavigationNativeTest {
 @Test fun roomIdentityDraftsAndMembershipStayScoped()=exercise(false)
 @Test fun unknownMembershipReconcilesWithoutAnotherSubmission()=exercise(true)
 private fun exercise(loseReply:Boolean){
  val i=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val context=i.targetContext
  context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
   .putString("server","http://127.0.0.1:18880").putString("token","synthetic-alpha24-only")
   .putString("deviceId","rooms-fixture").commit()
  i.runOnMainSync{Pocket.init(context);Pocket.prefs.edit().putString("backend:"+backendOwner(Pocket.base,Pocket.token),"losangelex").commit();PocketImmersion.setEnabled(false)}
  val config=Configurator.getInstance();val oldIdle=config.waitForIdleTimeout;config.waitForIdleTimeout=0
  fun await(check:()->Boolean){val end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end){if(android.os.Build.VERSION.SDK_INT>=34)i.uiAutomation.clearCache();if(check())return;Thread.sleep(100)};assertTrue("Room UI did not settle",check())}
  fun click(id:String){await{device.findObject(By.res(id))!=null};requireNotNull(device.findObject(By.res(id))).click()}
  val lobby="hollywood-room:11111111-1111-4111-8111-111111111111"
  val leaf="hollywood-room:22222222-2222-4222-8222-222222222222"
  fun type(value:String){await{device.findObject(By.res("team-input"))!=null};requireNotNull(device.findObject(By.res("team-input"))).text=value}
  i.runOnMainSync{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
  try{
   await{device.findObject(By.res(lobby))!=null&&device.findObject(By.res(leaf))!=null}
   click(lobby);type("Retained lobby draft")
   click("hollywood-rooms");click(leaf);type("Retained leaf draft")
   click("hollywood-rooms");click(lobby)
   await{device.findObject(By.res("team-input"))?.text=="Retained lobby draft"}
   click("hollywood-members")
   if(loseReply)Pocket.http.newCall(Request.Builder().url(Pocket.base+"/fixture/team/fail-next-membership").post("{}".toRequestBody()).build()).execute().use{assertTrue(it.isSuccessful)}
   click("room-join:44444444-4444-4444-8444-444444444444")
   await{device.findObject(By.text("Amber"))!=null}
   val state=Pocket.http.newCall(Request.Builder().url(Pocket.base+"/fixture/team").build()).execute().use{JSONObject(requireNotNull(it.body).string())}
   val requests=state.getJSONArray("requests").objects()
   assertEquals(0,requests.count{it.s("method")=="send"})
   val joins=requests.filter{it.s("method")=="membership"}
   assertEquals(1,joins.size);assertEquals("lobby",joins.single().s("task"))
   val owner=backendOwner(Pocket.base,Pocket.token)+"synthetic-alpha24"
   assertEquals("Retained leaf draft",Pocket.prefs.getString(TeamScope("studio","texture","",false).draftKey(owner),null))
   assertFalse(Pocket.prefs.contains("room-membership:$owner:11111111-1111-4111-8111-111111111111:44444444-4444-4444-8444-444444444444"))
  }finally{config.waitForIdleTimeout=oldIdle}
 }
}
