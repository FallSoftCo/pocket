package co.fallsoft.pocket

import android.content.Context
import android.os.SystemClock
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** Production screen and native input; no global idleness on a continuously animated UI. */
@RunWith(AndroidJUnit4::class)
class TeamControlWorkflowNativeTest {
 @Test fun cyclingSelectorsPreserveScopedDraftsReplyContextAndExactDirectTarget(){
  val i=InstrumentationRegistry.getInstrumentation()
  val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val context=i.targetContext
  context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
   .putString("server","http://127.0.0.1:18880").putString("token","synthetic-alpha24-only")
   .putString("deviceId","alpha24-fixture").commit()
  i.runOnMainSync{
   Pocket.init(context)
   Pocket.prefs.edit().putString("backend:"+backendOwner(Pocket.base,Pocket.token),"losangelex").commit()
   PocketImmersion.setEnabled(true)
  }
  check(Pocket.base=="http://127.0.0.1:18880")
  val config=Configurator.getInstance();val oldIdle=config.waitForIdleTimeout
  config.waitForIdleTimeout=0
  fun fresh(){if(android.os.Build.VERSION.SDK_INT>=34)assertTrue(i.uiAutomation.clearCache())}
  fun await(limit:Long=20000,condition:()->Boolean){
   val end=SystemClock.elapsedRealtime()+limit
   while(SystemClock.elapsedRealtime()<end){fresh();if(condition())return;Thread.sleep(100)}
   fresh();assertTrue("Native Team condition did not settle",condition())
  }
  fun tag(name:String):UiObject2?{fresh();return device.findObject(By.res(name))}
  fun click(name:String){await{tag(name)!=null};requireNotNull(tag(name)).click()}
  fun input()=requireNotNull(tag("team-input"))
  fun type(text:String){await{tag("team-input")!=null};input().click();input().text=text}
  fun choose(name:String){click("team-choice:TEAM_CHOICE_TO");await{device.hasObject(By.text(name))};requireNotNull(device.findObject(By.text(name))).click()}
  fun assertDraft(text:String)=await{tag("team-input")?.text==text}
  i.runOnMainSync{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
  try{
   await{tag("team-choice:TEAM_CHOICE_TEAM")!=null}
   val seen=mutableSetOf<String>()
   await(35000){
    val node=requireNotNull(tag("team-choice:TEAM_CHOICE_TEAM"))
    seen+=node.text.orEmpty()
    node.findObjects(By.text(Pattern.compile(".+"))).forEach{seen+=it.text.orEmpty()}
    seen.any{it.contains("Squadra:")}&&seen.any{it.contains("Team:")}
   }
   choose("Violet")
   type("Room root draft")
   click("team-reply:202")
   type("Retained scoped reply draft")
   click("team-reply-clear")
   assertDraft("Room root draft")
   val owner=backendOwner(Pocket.base,Pocket.token)+"synthetic-alpha24"
   assertEquals("Retained scoped reply draft",Pocket.prefs.getString(TeamScope("studio","lobby","Violet",false,202).draftKey(owner),null))
   click("team-reply:202")
   assertDraft("Retained scoped reply draft")
   click("team-reply-clear")
   click("team-direct")
   type("Unique direct Violet native turn")
   choose("Amber")
   type("Retained Amber native draft")
   choose("Violet")
   assertDraft("Unique direct Violet native turn")
   click("team-send")
   var sends=emptyList<JSONObject>()
   await{
    Pocket.http.newCall(Request.Builder().url(Pocket.base+"/fixture/team").build()).execute().use{response->
     assertTrue(response.isSuccessful)
     sends=JSONObject(requireNotNull(response.body).string()).getJSONArray("requests").objects().filter{it.s("method")=="send"}
    }
    sends.isNotEmpty()
   }
   assertEquals("A single tap must create one synthetic submission",1,sends.size)
   val body=sends.single().getJSONObject("body")
   assertEquals("Unique direct Violet native turn",body.getString("body"))
   assertEquals("direct",body.getString("visibility"))
   assertEquals("Violet",body.getJSONArray("targets").getString(0))
   assertEquals(1,body.getJSONArray("targets").length())
   assertTrue("Cleared reply leaked into direct send",body.isNull("replyTo")||!body.has("replyTo"))
   assertTrue(body.getString("commandId").isNotBlank())
   assertEquals("Retained Amber native draft",Pocket.prefs.getString(TeamScope("studio","lobby","Amber",true).draftKey(owner),null))
  }finally{config.waitForIdleTimeout=oldIdle}
 }
}
