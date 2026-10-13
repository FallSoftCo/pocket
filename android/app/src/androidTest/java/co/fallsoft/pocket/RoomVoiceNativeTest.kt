package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomVoiceNativeTest {
 @Test fun unknownTypedTurnKeepsRoomAndReceiptAcrossServiceRestart(){
  val i=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.RECORD_AUDIO")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-alpha24-only").putString("deviceId","room-voice-fixture").commit()
  i.runOnMainSync{Pocket.init(c);PocketImmersion.setEnabled(false);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
  fun await(check:()->Boolean){val end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end){if(check())return;Thread.sleep(100)};assertTrue("Room voice did not settle",check())}
  val origin=JSONObject().put("team","studio").put("roomId","11111111-1111-4111-8111-111111111111").put("authorityId","33333333-3333-4333-8333-333333333333").put("actorId","44444444-4444-4444-8444-444444444444").put("direct",true)
  Pocket.http.newCall(Request.Builder().url(Pocket.base+"/fixture/room-voice/reset").post("{}".toRequestBody()).build()).execute().close()
  try{
   i.runOnMainSync{PocketVoice.start(c,recordImmediately=false,inPlace=true,roomDestination=origin)}
   await{PocketVoice.active&&PocketVoice.state=="Ready"}
   i.runOnMainSync{assertTrue(PocketVoice.sendText("Keep this with the original room"))}
   await{PocketVoice.state=="Retry needed"&&Pocket.prefs.contains("voicePendingId")}
   val saved=Pocket.prefs.getString("voicePendingId",null)
   assertEquals(origin.toString(),Pocket.prefs.getString("voicePendingRoom",null))
   assertTrue(Pocket.prefs.getString("voicePendingTextRequest",null).orEmpty().contains("Keep this with the original room"))
   i.runOnMainSync{PocketVoice.stop()};await{PocketVoice.service==null}
   val other=JSONObject(origin.toString()).put("roomId","22222222-2222-4222-8222-222222222222")
   i.runOnMainSync{PocketVoice.start(c,recordImmediately=false,inPlace=true,checkSaved=true,roomDestination=other)}
   await{PocketVoice.state=="Retry needed"&&PocketVoice.active}
   assertEquals(saved,Pocket.prefs.getString("voicePendingId",null))
   assertEquals(origin.toString(),PocketVoice.targetRoom?.toString())
   val state=Pocket.http.newCall(Request.Builder().url(Pocket.base+"/fixture/room-voice").build()).execute().use{JSONObject(requireNotNull(it.body).string())}
   assertEquals(1,state.getJSONArray("submissions").length())
   assertTrue(state.getJSONArray("starts").objects().all{it.getJSONObject("room").toString()==origin.toString()})
   assertEquals(saved,state.getJSONArray("submissions").getJSONObject(0).s("turnId"))
  }finally{i.runOnMainSync{PocketVoice.stop()}}
 }
}
