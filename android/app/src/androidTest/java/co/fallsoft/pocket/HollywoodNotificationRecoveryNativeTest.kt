package co.fallsoft.pocket

import android.app.NotificationManager
import android.content.*
import android.os.*
import android.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real native question -> Hollywood -> device-specific websocket -> Android PendingIntent -> original LAX turn. FCM is separate. */
@RunWith(AndroidJUnit4::class)
class HollywoodNotificationRecoveryNativeTest {
 @Test fun nativeQuestionNotificationOpensAndAnswersFromAnotherSelectedProfile()=exercise(false)
 @Test fun resumeOriginalQuestionWithoutSubmittingAgain()=exercise(true)
 private fun exercise(resume:Boolean)=runBlocking{
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="harness_native_room")
  val settings=JSONObject(c.filesDir.resolve("hollywood-native-push-r02-settings.json").readText())
  val intentFile=c.filesDir.resolve("hollywood-native-push-r02-intent.json");check(intentFile.exists()==resume){ "Use the original captured request without replay" }
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  val oldIdle=Configurator.getInstance().waitForIdleTimeout;Configurator.getInstance().waitForIdleTimeout=0
  suspend fun await(timeout:Long=30000,check:()->Boolean){withTimeout(timeout){while(!check()){i.uiAutomation.clearCache();delay(150)}}}
  try{
   check(c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().putString("server",settings.s("base")).putString("token",settings.s("token")).putString("deviceId",settings.s("ownerDevice")).putString("activeEnvironment","workstation").putBoolean("activeLocal",false).commit())
   i.runOnMainSync{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
   await{BackendNavigation.nativeSelected()&&Pocket.connected}
   val origin=Pocket.captureEnvironment("workstation")!!
   val binding=Pocket.apiEnvironment(origin,"/api/hollywood/v3/identity");val owner=backendOwner(origin.endpoint,origin.token)
   val client=HollywoodClient(owner,binding,origin.endpoint,origin.token)
   val request=HollywoodScope(settings.s("room"),settings.s("agent")).message(settings.s("operation"),settings.s("request"))
   if(!resume)intentFile.writeText(JSONObject().put("binding",binding).put("body",request).toString())
   else{val original=JSONObject(intentFile.readText());check(hollywoodBindingMatches(original.getJSONObject("binding"),binding)&&hollywoodJsonEqual(original.getJSONObject("body"),request))}
   val receipt=if(resume)client.call("commands/"+settings.s("operation"))else client.call("rooms/${settings.s("room")}/messages",request)
   val delivery=receipt.getJSONArray("deliveries").getJSONObject(0).s("id")
   val manager=c.getSystemService(NotificationManager::class.java)
   var key=""
   await(180000){manager.activeNotifications.any{n->
    if(n.tag?.startsWith("hollywood:")==true){val candidate=n.tag.removePrefix("hollywood:");val record=HollywoodNotifications.record(candidate)
     if(record?.s("roomId")==settings.s("room")&&record.s("state")=="verified"&&record.optJSONObject("attention")!=null&&n.notification.actions?.any{!it.remoteInputs.isNullOrEmpty()}==true){key=candidate;true}else false
    }else false}}
   val record=HollywoodNotifications.record(key)!!;val attention=record.getJSONObject("attention");val originalScope=attention.getJSONObject("scope")
   val notification=manager.activeNotifications.first{it.tag=="hollywood:$key"}.notification
   val reply=notification.actions.first{!it.remoteInputs.isNullOrEmpty()}
   val profile=EnvironmentIdentity("notification_viewer","Read-only verification viewer")
   val profiles=Pocket.environmentProfiles.all().filter{it.id!=profile.id}+profile
   check(Pocket.prefs.edit().putString("environments:v1",JSONArray(profiles.map{JSONObject().put("id",it.id).put("name",it.name).put("local",it.local)}).toString())
     .putString(profile.key("server"),settings.s("base")).putString(profile.key("token"),settings.s("viewerToken")).commit())
   val viewer=Pocket.captureEnvironment(profile.id)!!;val viewerBinding=Pocket.apiEnvironment(viewer,"/api/hollywood/v3/identity")
   val viewerClient=HollywoodClient(backendOwner(viewer.endpoint,viewer.token),viewerBinding,viewer.endpoint,viewer.token)
   val viewerScope=HollywoodScope(settings.s("room"),settings.s("agent"));viewerClient.saveDraft(viewerScope,"Viewer draft stays local")
   i.runOnMainSync{Pocket.activateEnvironment(profile.id)}
   await{Pocket.environmentId==profile.id}
   notification.contentIntent.send()
   await{Pocket.environmentId=="workstation"&&device.hasObject(By.text("Which marker should the notification proof contain?"))}
   device.findObject(By.text("Close")).click()
   i.runOnMainSync{Pocket.activateEnvironment(profile.id)};await{Pocket.environmentId==profile.id}
   val answer=Intent();RemoteInput.addResultsToIntent(reply.remoteInputs,answer,Bundle().apply{putCharSequence("reply",settings.s("marker"))})
   reply.actionIntent.send(c,0,answer)
   var final=client.call("deliveries/$delivery")
   withTimeout(180000){while(final.getJSONObject("delivery").s("state")!="completed"){delay(1000);final=client.call("deliveries/$delivery")}}
   val answered=client.call("attention/"+attention.getJSONObject("attention").s("id"))
   assertEquals(binding.s("principalId"),answered.getJSONObject("attention").s("response_author"))
   assertTrue(hollywoodJsonEqual(originalScope,answered.getJSONObject("scope")))
   assertEquals(profile.id,Pocket.environmentId);assertEquals("Viewer draft stays local",viewerClient.draft(viewerScope))
   await{manager.activeNotifications.none{it.tag=="hollywood:$key"}}
   c.filesDir.resolve("hollywood-native-push-r02-result.json").writeText(JSONObject().put("state","passed-native-notification-and-cross-profile-inline-answer")
    .put("deliveryId",delivery).put("scope",originalScope).put("questionNotification",true).put("nativePendingIntentOpen",true).put("nativeRemoteInputReply",true)
    .put("inactiveOriginProfileReply",true).put("viewerDraftPreserved",true).put("transport","authenticated-device-websocket").put("fcmVerified",false).put("physicalPhone",false).put("originalRequestResubmitted",false).put("metadataResume",resume).toString())
  }finally{Configurator.getInstance().waitForIdleTimeout=oldIdle}
 }
}
