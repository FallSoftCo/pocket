package co.fallsoft.pocket

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual foreground keys/IME/AudioRecord; synthetic loopback boundary has no provider. */
@RunWith(AndroidJUnit4::class)
class HollywoodVoiceKeysNativeTest{
 @Test fun nativeVolumeAndTouchKeepOriginalRoomAndDraftWhileProviderIsUnavailable():Unit=runBlocking{
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val base="http://127.0.0.1:18893";val token="synthetic-native-voice-only";val id="synthetic_native_voice_20261016"
  val roomA="cccccccc-cccc-4ccc-8ccc-ccccccccccc1";val roomB="cccccccc-cccc-4ccc-8ccc-ccccccccccc2"
  val agentA="dddddddd-dddd-4ddd-8ddd-ddddddddddd1";val agentB="dddddddd-dddd-4ddd-8ddd-ddddddddddd2";val bindingId="e".repeat(64)
  val old=Pocket.environmentId;val p=c.getSharedPreferences("pocket",0)
  require(p.getString("voicePendingId",null)==null){"Existing recording must be reconciled first"}
  val profiles=JSONArray(p.getString("environments:v1","[]")).objects().filter{it.s("id")!=id}
  p.edit().putString("environments:v1",JSONArray(profiles+JSONObject().put("id",id).put("name","Owned synthetic voice").put("local",false)).toString())
   .putString("environment:$id:server",base).putString("environment:$id:token",token)
   .putString("backend:"+backendOwner(base,token),"lax")
   .putString("hollywood-room:"+backendOwner(base,token)+":"+bindingId,roomA).commit()
  val oldIdle=Configurator.getInstance().waitForIdleTimeout;Configurator.getInstance().waitForIdleTimeout=0
  fun await(label:String,test:()->Boolean){val end=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<end){i.uiAutomation.clearCache();if(test())return;Thread.sleep(100)};assertTrue(label,test())}
  fun tap(tag:String){await("Missing "+tag){runCatching{d.findObject(By.res(tag))?.let{it.click();true}?:false}.getOrDefault(false)}}
  val source=EnvironmentRequest(EnvironmentIdentity(id,"Owned synthetic voice"),base,token)
  val binding=JSONObject().put("bindingId",bindingId).put("authorityId","aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
   .put("principalId","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").put("defaultRoomId",roomA)
  val client=HollywoodClient(backendOwner(base,token),binding,base,token)
  val draft="Owned draft stays in room A"
  val audio=c.getSystemService(android.media.AudioManager::class.java)
  val initialVolume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
  val originalDraft=client.draft(HollywoodScope(roomA,agentA))
  val originalDraftB=client.draft(HollywoodScope(roomB,agentB))
  try{
   i.runOnMainSync{Pocket.init(c);Pocket.activateEnvironment(id,force=true);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
   await("Floating native mic unavailable"){d.hasObject(By.res("native-room-microphone"))}
   await("Synthetic room history failed"){d.hasObject(By.res("native-room-empty"))&&!d.hasObject(By.res("native-room-read-error"))}
   d.takeScreenshot(c.filesDir.resolve("native-room-mic-ready.png"))
   val priorStarts=Pocket.apiEnvironment(source,"/_qa/status").getJSONArray("starts").length()
   assertFalse("Empty voice-primary editor should be behind Keyboard",d.hasObject(By.clazz("android.widget.EditText")))
   tap("native-room-keyboard")
   await("Native keyboard editor did not focus"){d.hasObject(By.clazz("android.widget.EditText"))&&d.hasObject(By.pkg("com.google.android.inputmethod.latin"))}
   d.findObject(By.clazz("android.widget.EditText")).text=draft
   await("Typed draft not saved"){client.draft(HollywoodScope(roomA,agentA))==draft}
   assertFalse("Keyboard action remains after opening the editor",d.hasObject(By.res("native-room-keyboard")))
   val downAt=SystemClock.uptimeMillis()
   fun key(action:Int,repeat:Int=0){assertTrue(i.uiAutomation.injectInputEvent(KeyEvent(downAt,SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_VOLUME_DOWN,repeat),true))}
   key(KeyEvent.ACTION_DOWN)
   await("Hardware did not start actual microphone"){PocketVoice.state=="Listening"&&PocketVoice.recordingStartedAt>0}
   assertTrue("Starting capture closed the IME",d.hasObject(By.pkg("com.google.android.inputmethod.latin")))
   assertEquals(roomA,PocketVoice.targetRoom?.s("roomId"))
   assertEquals(agentA,PocketVoice.targetRoom?.s("recipientId"))
   assertTrue(PocketVoice.targetSourceLabel.orEmpty().startsWith("Synthetic voice room A"))
   assertTrue(d.hasObject(By.res("native-room-capture-status")))
   repeat(3){key(KeyEvent.ACTION_DOWN,it+1)}
   Thread.sleep(1100);assertEquals("Held key toggled twice","Listening",PocketVoice.state)
   d.takeScreenshot(c.filesDir.resolve("native-room-recording-ime.png"))
   key(KeyEvent.ACTION_UP)
   d.pressBack()
   await("Back with IME did not reach rooms"){d.hasObject(By.res("native-room:"+roomB))}
   assertTrue("Catalog hid active capture feedback",d.hasObject(By.res("native-room-capture-status")))
   assertTrue("Catalog hid the stop control",d.hasObject(By.res("native-catalog-microphone")))
   tap("native-room:"+roomB)
   await("Other room did not open"){d.hasObject(By.res("native-room-controls"))&&d.hasObject(By.text("Synthetic voice room B"))}
   d.pressKeyCode(KeyEvent.KEYCODE_VOLUME_UP)
   await("Hardware stop did not complete"){PocketVoice.state !in listOf("Listening","Starting microphone","Finishing recording")&&PocketVoice.problem.isNotBlank()}
   assertEquals("Navigation retargeted captured recording",roomA,PocketVoice.targetRoom?.s("roomId"))
   assertEquals("Capture keys changed playback volume",initialVolume,audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
   assertEquals(draft,client.draft(HollywoodScope(roomA,agentA)))
   assertTrue("Capture failure is only a sound",d.hasObject(By.res("native-room-capture-status")))
   // A second explicitly requested turn exercises the touch alternative in room B.
   i.runOnMainSync{PocketVoice.stop()}
   tap("native-room-keyboard")
   await("Room B keyboard did not open"){d.hasObject(By.clazz("android.widget.EditText"))&&d.hasObject(By.pkg("com.google.android.inputmethod.latin"))}
   val draftB="Owned draft stays in room B"
   d.findObject(By.clazz("android.widget.EditText")).text=draftB
   await("Room B draft not saved"){client.draft(HollywoodScope(roomB,agentB))==draftB}
   tap("native-room-microphone")
   await("Touch did not start actual microphone"){PocketVoice.state=="Listening"}
   assertEquals(roomB,PocketVoice.targetRoom?.s("roomId"))
   assertTrue("Touch mic closed the keyboard",d.hasObject(By.pkg("com.google.android.inputmethod.latin")))
   assertEquals(draftB,client.draft(HollywoodScope(roomB,agentB)))
   d.takeScreenshot(c.filesDir.resolve("native-room-touch-recording-ime.png"))
   tap("native-room-microphone")
   await("Touch stop did not surface capture/provider outcome"){PocketVoice.state !in listOf("Listening","Starting microphone","Finishing recording")&&PocketVoice.problem.isNotBlank()}
   i.runOnMainSync{PocketVoice.stop()}
   d.pressBack()
   await("Catalog assistant unavailable"){d.hasObject(By.res("native-catalog-microphone"))}
   tap("native-catalog-microphone")
   await("Catalog mic did not start default room capture"){PocketVoice.state=="Listening"}
   assertEquals("Catalog voice used a stale selected room",roomA,PocketVoice.targetRoom?.s("roomId"))
   d.pressKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN)
   await("Catalog hardware stop failed"){PocketVoice.state !in listOf("Listening","Starting microphone","Finishing recording")&&PocketVoice.problem.isNotBlank()}
   i.runOnMainSync{PocketVoice.stop()}
   tap("native-catalog-settings")
   await("Settings hid the work-context mic"){d.hasObject(By.res("native-settings-microphone"))}
   d.pressKeyCode(KeyEvent.KEYCODE_VOLUME_UP)
   await("Settings hardware capture did not start"){PocketVoice.state=="Listening"}
   assertEquals(roomA,PocketVoice.targetRoom?.s("roomId"))
   assertTrue("Settings hid recording state",d.hasObject(By.res("native-settings-capture-status")))
   d.pressKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN)
   await("Settings hardware stop failed"){PocketVoice.state !in listOf("Listening","Starting microphone","Finishing recording")&&PocketVoice.problem.isNotBlank()}
   tap("native-settings-back")
   val status=Pocket.apiEnvironment(source,"/_qa/status")
   val starts=status.getJSONArray("starts").objects().drop(priorStarts)
   assertTrue("Native capture lost its explicit room",starts.any{it.optJSONObject("room")?.s("roomId")==roomA})
   assertTrue(starts.any{it.optJSONObject("room")?.s("roomId")==roomB})
   assertEquals(0,status.getInt("blockedTaskWrites"))
   assertTrue(status.getBoolean("noProvider"))
   c.filesDir.resolve("native-room-volume-ime-receipt.json").writeText(status.put("hardwareStartStop",true).put("touchStartStop",true)
    .put("draftPreserved",true).put("navigationKeptOriginalRoom",true).put("catalogDefaultRoom",true).put("settingsCapture",true).put("providerQualified",false).put("physicalPhone",false).toString())
   d.takeScreenshot(c.filesDir.resolve("native-room-volume-ime.png"))
  }finally{
   i.runOnMainSync{PocketVoice.stop()}
   // Only this disposable fixture's recording can be cleared, and only after
   // its no-provider/task-write boundary answers definitively.
   val pending=p.getString("voicePendingId",null)
   if(pending!=null){
    require(p.getString("voicePendingEnvironment",null)==id)
    val status=Pocket.apiEnvironment(source,"/_qa/status");require(status.getBoolean("noProvider")&&status.getInt("blockedTaskWrites")==0)
    c.filesDir.resolve("qa-synthetic-voice-pending-"+pending+".json").writeText(JSONObject().put("id",pending).put("noProvider",true).toString())
    c.filesDir.resolve("voice-pending.wav").takeIf{it.exists()}?.copyTo(c.filesDir.resolve("qa-synthetic-voice-"+pending+".wav"),overwrite=false)
    p.edit().remove("voicePendingId").remove("voicePendingRoom").remove("voicePendingSourceLabel").remove("voicePendingTarget")
     .remove("voicePendingLocal").remove("voicePendingEnvironment").remove("voicePendingOwner").remove("voicePendingTextRequest").commit()
   }
   client.saveDraft(HollywoodScope(roomA,agentA),originalDraft)
   client.saveDraft(HollywoodScope(roomB,agentB),originalDraftB)
   Configurator.getInstance().waitForIdleTimeout=oldIdle
   i.runOnMainSync{if(Pocket.captureEnvironment(old)!=null)Pocket.activateEnvironment(old,force=true)}
  }
 }
}
