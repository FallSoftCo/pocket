package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native session entry/navigation and actual adapter requests; synthetic destinations only. */
internal class SessionRoomJourney {
 val i=InstrumentationRegistry.getInstrumentation()
 val device=UiDevice.getInstance(i)
 val context=i.targetContext
 val lobby="hollywood-room:11111111-1111-4111-8111-111111111111"
 val leaf="hollywood-room:22222222-2222-4222-8222-222222222222"
 val oldIdle=Configurator.getInstance().waitForIdleTimeout
 val owner get()=backendOwner(Pocket.base,Pocket.token)+"synthetic-alpha24"
 fun fixture(path:String="",post:Boolean=false):JSONObject=Pocket.http.newCall(Request.Builder().url(Pocket.base+"/fixture/team"+path).apply{if(post)post("{}".toRequestBody())}.build()).execute().use{assertTrue(it.isSuccessful);JSONObject(requireNotNull(it.body).string())}
 fun start(seedLegacy:Boolean=false,historyMode:String?=null){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.RECORD_AUDIO")
  context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-alpha24-only").putString("deviceId","sessions-fixture").commit()
  i.runOnMainSync{Pocket.init(context);Pocket.prefs.edit().putString("backend:"+backendOwner(Pocket.base,Pocket.token),"losangelex").commit();PocketImmersion.setEnabled(false)}
  if(seedLegacy){val legacy=TeamScope("studio","lobby","Violet");LosangelexClient(owner).savePending(legacy,JSONObject().put("path","teams/studio/messages").put("body",legacy.message("legacy-pending","Legacy pending body")))}
  fixture("/setup-sessions",true)
  historyMode?.let{fixture("/$it",true)}
  Pocket.prefs.edit().putString("room-membership:$owner:11111111-1111-4111-8111-111111111111:44444444-4444-4444-8444-444444444444","legacy-observed")
   .putString("room-membership:$owner:11111111-1111-4111-8111-111111111111:unobserved","legacy-unknown").commit()
  Configurator.getInstance().waitForIdleTimeout=0
  i.runOnMainSync{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
  await{tag(lobby)!=null&&tag(leaf)!=null}
 }
 fun close(){Configurator.getInstance().waitForIdleTimeout=oldIdle}
 fun tag(id:String)=device.findObject(By.res(id))
 fun await(check:()->Boolean){val end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end){if(android.os.Build.VERSION.SDK_INT>=34)i.uiAutomation.clearCache();if(check())return;Thread.sleep(100)};val passed=check();if(!passed){device.takeScreenshot(java.io.File(context.cacheDir,"session-ui-failure.png"));device.dumpWindowHierarchy(java.io.File(context.cacheDir,"session-ui-failure.xml"))};assertTrue("Session UI did not settle",passed)}
 fun click(id:String)=click(By.res(id))
 fun click(selector:BySelector){var bounds:android.graphics.Rect?=null;var stable=0;await{val node=device.findObject(selector);val next=node?.visibleBounds;stable=if(next!=null&&next==bounds&&node.isEnabled)stable+1 else 0;bounds=next;stable>=2};requireNotNull(device.findObject(selector)).click()}
 fun keyboard(){await{device.findObject(By.text("Keyboard"))!=null};requireNotNull(device.findObject(By.text("Keyboard"))).click();await{tag("team-input")!=null}}
 fun type(text:String){await{tag("team-input")!=null};requireNotNull(tag("team-input")).text=text}
 fun back(){click("hollywood-room-actions");click("hollywood-rooms");await{tag(lobby)!=null}}
 fun sends()=fixture().getJSONArray("requests").objects().filter{it.s("method")=="send"}.map{it.getJSONObject("body")}
 fun assertNoImplementationControls(){listOf("hollywood-members","team-choice:TEAM_CHOICE_TO","team-room","team-direct","team-choice:TEAM_CHOICE_AGENT").forEach{assertNull(tag(it))}}
}

@RunWith(AndroidJUnit4::class)
class RoomNavigationNativeTest {
 @Test fun sessionEntryAggregatesIndependentResultsAndKeepsDraftsScoped(){
  val j=SessionRoomJourney();try{j.start();j.click(j.lobby)
   j.assertNoImplementationControls();assertNull(j.tag("team-input"))
   j.await{j.device.findObject(By.textContains("Shared artifact"))!=null}
   j.keyboard();j.type("Broadcast to this scope");j.click("team-send")
   j.await{j.sends().size==1}
   val sent=j.sends().single();assertEquals("lobby",sent.s("task"));assertEquals("room",sent.s("visibility"));assertEquals(listOf("Violet"),(0 until sent.getJSONArray("targets").length()).map{sent.getJSONArray("targets").getString(it)})
   j.await{j.tag("team-input")?.text==""};j.type("Retained aggregate draft")
   j.click("team-reply:202");j.type("Addressed reply draft");j.click("team-reply-clear")
   j.await{j.tag("team-input")?.text=="Retained aggregate draft"};assertNotNull(j.device.findObject(By.textContains("Shared artifact")))
   j.click("team-reply:202");j.await{j.tag("team-input")?.text=="Addressed reply draft"};j.click("team-send")
   j.await{j.sends().size==2&&j.tag("team-input")?.text=="Retained aggregate draft"}
   assertEquals("Violet",j.sends()[1].getJSONArray("targets").getString(0));assertEquals(1,j.sends()[1].getJSONArray("targets").length())
   j.type("Collective after reply");j.click("team-send");j.await{j.sends().size==3&&j.tag("team-input")?.text==""}
   assertEquals(1,j.sends()[2].getJSONArray("targets").length());j.type("Retained aggregate draft");j.back();j.click(j.leaf);j.keyboard();j.type("Retained independent scope draft");j.back();j.click(j.lobby);j.keyboard()
   j.await{j.tag("team-input")?.text=="Retained aggregate draft"}
   assertEquals("Retained independent scope draft",Pocket.prefs.getString(TeamScope("studio","texture").draftKey(j.owner),null))
   j.click("hollywood-room-actions");j.assertNoImplementationControls()
   assertEquals(0,j.fixture().getJSONArray("requests").objects().count{it.s("method")=="membership"})
   assertFalse(Pocket.prefs.contains("room-membership:${j.owner}:11111111-1111-4111-8111-111111111111:44444444-4444-4444-8444-444444444444"))
   assertTrue(Pocket.prefs.contains("room-membership:${j.owner}:11111111-1111-4111-8111-111111111111:unobserved"))
  }finally{j.close()}
 }
 @Test fun unknownBroadcastKeepsCapturedTargetsAcrossScopeChanges(){
  val j=SessionRoomJourney();try{j.start(seedLegacy=true);j.click(j.lobby);j.keyboard();j.type("Frozen broadcast")
   j.fixture("/fail-next-message",true);j.click("team-send")
   val scope=TeamScope("studio","lobby");val client=LosangelexClient(j.owner)
   j.await{client.pending(scope)!=null&&j.sends().size==1}
   val original=requireNotNull(client.pending(scope)).getJSONObject("body").toString()
   j.fixture("/remove-destinations",true);j.back();j.click(j.leaf);j.keyboard();j.type("Other scope draft");j.back();j.click(j.lobby);j.keyboard()
   assertEquals(original,requireNotNull(client.pending(scope)).getJSONObject("body").toString())
   j.click("team-send");j.await{j.sends().size==2&&client.pending(scope)==null}
   assertEquals(j.sends()[0].toString(),j.sends()[1].toString())
   j.click("hollywood-room-actions");j.click("team-saved:Violet:false:root")
   j.await{j.tag("team-input")?.text=="Legacy pending body"};assertEquals(2,j.sends().size)
   j.click("team-send");j.await{j.sends().size==3}
   val recovered=j.sends()[2];assertEquals("legacy-pending",recovered.s("commandId"));assertEquals("Violet",recovered.getJSONArray("targets").getString(0));assertEquals("room",recovered.s("visibility"));assertFalse(recovered.has("replyTo"))
   assertEquals("Other scope draft",Pocket.prefs.getString(TeamScope("studio","texture").draftKey(j.owner),null))
  }finally{j.close()}
 }
}
