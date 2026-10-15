package co.fallsoft.pocket

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh native Global/worker -> Android notification -> original answer receipt.
 * The owner deliberately drops transport; the test never resubmits a saved effect.
 */
@RunWith(AndroidJUnit4::class)
class HollywoodNativeAttentionJourneyTest {
    @Test fun notificationAnswerRecoversOriginalReceiptAcrossDisconnect() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="harness_native_room")
        val settings=JSONObject(context.filesDir.resolve("native-attention-settings.json").readText())
        val prefix=settings.s("case")
        fun file(name:String)=context.filesDir.resolve("$prefix-$name.json")
        check(!file("intent").exists()){"Inspect the original admitted native request; do not replay this journey"}
        val oldIdle=Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout=0
        suspend fun await(timeout:Long=30000,check:()->Boolean) {
            withTimeout(timeout){while(!check()){delay(200)}}
        }
        fun phase(name:String,extra:JSONObject=JSONObject()){
            extra.put("phase",name)
            file("phase").writeText(extra.toString())
        }
        try {
            device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
            instrumentation.runOnMainSync {Pocket.init(context)}
            val ownerProfile=EnvironmentIdentity(settings.s("ownerProfile"),"Native attention owner")
            val viewerProfile=EnvironmentIdentity(settings.s("viewerProfile"),"Native attention viewer")
            val before=Pocket.environmentProfiles.all()
            check(before.none{it.id in listOf(ownerProfile.id,viewerProfile.id)})
            val profiles=before+ownerProfile+viewerProfile
            val edit=Pocket.prefs.edit().putString("environments:v1",JSONArray(profiles.map{JSONObject().put("id",it.id).put("name",it.name).put("local",it.local)}).toString())
            for((profile,key) in listOf(ownerProfile to "ownerToken",viewerProfile to "viewerToken")){
                edit.putString(profile.key("server"),settings.s("base")).putString(profile.key("token"),settings.s(key))
                    .putString(profile.key("deviceId"),settings.s(if(key=="ownerToken")"ownerDevice" else "viewerDevice"))
            }
            check(edit.commit())
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Pocket.activateEnvironment(ownerProfile.id)
            }
            await{Pocket.environmentId==ownerProfile.id&&BackendNavigation.nativeSelected()&&Pocket.connected}
            val origin=Pocket.captureEnvironment(ownerProfile.id)!!
            val binding=Pocket.apiEnvironment(origin,"/api/hollywood/v3/identity")
            val client=HollywoodClient(backendOwner(origin.endpoint,origin.token),binding,origin.endpoint,origin.token)
            HollywoodNotifications.rememberBinding(origin,binding)
            val globalScope=HollywoodScope(settings.s("globalRoom"),settings.s("globalAgent"))
            val intent=client.capture(globalScope,settings.s("request"))
            file("intent").writeText(intent.toString())
            val receipt=client.submit(globalScope,intent)
            file("admission").writeText(receipt.toString())
            phase("admitted",JSONObject().put("requestId",receipt.getJSONObject("event").s("id")))
            val manager=context.getSystemService(NotificationManager::class.java)
            var key=""
            var nativeQuestion=""
            await(420000){
                manager.activeNotifications.any{notification->
                    val candidate=notification.tag?.takeIf{it.startsWith("hollywood:")}?.removePrefix("hollywood:")?:return@any false
                    val record=HollywoodNotifications.record(candidate)?:return@any false
                    val value=record.optJSONObject("attention")?:return@any false
                    val attention=value.optJSONObject("attention")?:return@any false
                    val questions=attention.optJSONObject("request")?.optJSONArray("questions")?:return@any false
                    val exact=record.s("bindingId")==binding.s("bindingId")&&record.s("authorityId")==binding.s("authorityId")&&record.s("state")=="verified"&&
                        attention.s("human")==binding.s("principalId")&&attention.s("state")=="pending"&&value.s("deliveryState") in listOf("claimed","running")&&
                        questions.length()==1&&questions.getJSONObject(0).s("id")=="attention_marker"&&questions.getJSONObject(0).optBoolean("isOther",true)&&
                        value.getJSONObject("scope").s("deliveryId")==record.getJSONObject("event").getJSONObject("data").s("deliveryId")
                    if(exact&&notification.notification.actions?.any{!it.remoteInputs.isNullOrEmpty()}==true){
                        // Scope and callback identity select the native question;
                        // model punctuation is not a routing or authority boundary.
                        key=candidate;nativeQuestion=questions.getJSONObject(0).s("question");true
                    }else false
                }
            }
            val notification=manager.activeNotifications.single{it.tag=="hollywood:$key"}.notification
            val record=HollywoodNotifications.record(key)!!
            val attention=record.getJSONObject("attention")
            val capturedScope=attention.getJSONObject("scope")
            val fresh=client.call("attention/"+attention.getJSONObject("attention").s("id"))
            assertTrue(hollywoodJsonEqual(capturedScope,fresh.getJSONObject("scope")))
            val reply=notification.actions.single{!it.remoteInputs.isNullOrEmpty()}
            val viewer=Pocket.captureEnvironment(viewerProfile.id)!!
            val viewerBinding=Pocket.apiEnvironment(viewer,"/api/hollywood/v3/identity")
            val viewerClient=HollywoodClient(backendOwner(viewer.endpoint,viewer.token),viewerBinding,viewer.endpoint,viewer.token)
            val viewerScope=HollywoodScope(settings.s("globalRoom"),settings.s("globalAgent"))
            viewerClient.saveDraft(viewerScope,"VIEWER_DRAFT_STAYS_LOCAL")
            instrumentation.runOnMainSync{Pocket.activateEnvironment(viewerProfile.id)}
            await{Pocket.environmentId==viewerProfile.id}
            notification.contentIntent.send()
            await{Pocket.environmentId==ownerProfile.id&&device.hasObject(By.text(nativeQuestion))}
            device.findObject(By.text("Close")).click()
            instrumentation.runOnMainSync{Pocket.activateEnvironment(viewerProfile.id)}
            await{Pocket.environmentId==viewerProfile.id}
            phase("ready-for-offline",JSONObject().put("scope",capturedScope).put("notificationKey",key))
            await(60000){file("gateway-offline").exists()}
            // Exercise the actual refresh/reply race while the origin is offline.
            HollywoodNotifications.receive(origin,mapOf(
                "binding_id" to record.s("bindingId"),"authority_id" to record.s("authorityId"),
                "principal_id" to record.s("principalId"),"event_id" to record.s("eventId"),
                "room_id" to record.s("roomId"),"revision" to record.getLong("revision").toString(),
                "action" to "notify"),forceRefresh=true)
            assertEquals("verified",HollywoodNotifications.record(key)!!.s("state"))
            assertTrue(hollywoodJsonEqual(capturedScope,HollywoodNotifications.replySnapshot(key,record.getLong("revision"))!!.getJSONObject("attention").getJSONObject("scope")))
            val answer=Intent()
            RemoteInput.addResultsToIntent(reply.remoteInputs,answer,Bundle().apply{putCharSequence("reply",settings.s("marker"))})
            reply.actionIntent.send(context,0,answer)
            fun outbox():JSONObject?=Pocket.prefs.all.entries.asSequence().filter{it.key.startsWith("hollywood-reply:")}.mapNotNull{entry->runCatching{JSONObject(entry.value as String)}.getOrNull()}
                .firstOrNull{it.s("eventId")==record.s("eventId")&&it.s("owner")==record.s("owner")}
            await(60000){outbox()?.let{it.s("state")=="saved"&&it.s("error").isNotBlank()}==true}
            val offline=outbox()!!
            file("offline-intent").writeText(offline.toString())
            phase("offline-intent-saved")
            await(180000){outbox()?.s("state")=="accepted"}
            val accepted=outbox()!!
            assertTrue(hollywoodJsonEqual(offline.getJSONObject("intent").getJSONObject("body"),accepted.getJSONObject("intent").getJSONObject("body")))
            file("reply-receipt").writeText(accepted.toString())
            val resolved=client.call("attention/"+attention.getJSONObject("attention").s("id"))
            assertEquals(binding.s("principalId"),resolved.getJSONObject("attention").s("response_author"))
            assertEquals(settings.s("marker"),resolved.getJSONObject("attention").getJSONObject("response").getJSONObject("answers").getJSONObject("attention_marker").getJSONArray("answers").getString(0))
            assertTrue(hollywoodJsonEqual(capturedScope,resolved.getJSONObject("scope")))
            assertEquals(viewerProfile.id,Pocket.environmentId)
            assertEquals("VIEWER_DRAFT_STAYS_LOCAL",viewerClient.draft(viewerScope))
            var goals=client.call("rooms/"+settings.s("globalRoom")+"/goals").getJSONArray("goals")
            withTimeout(240000){
                while(goals.length()!=1||goals.getJSONObject(0).s("state")!="accepted"){
                    delay(1000)
                    goals=client.call("rooms/"+settings.s("globalRoom")+"/goals").getJSONArray("goals")
                }
            }
            await{manager.activeNotifications.none{it.tag=="hollywood:$key"}}
            val result=JSONObject().put("state","passed-real-native-global-worker-android-attention")
                .put("scope",capturedScope).put("goalId",goals.getJSONObject(0).s("id")).put("ownerProfile",ownerProfile.id).put("viewerProfile",viewerProfile.id)
                .put("nativePendingIntentOpen",true).put("nativeRemoteInputReply",true).put("offlineIntentDurable",true).put("originalOperationRetained",true)
                .put("inactiveOriginProfileReply",true).put("viewerDraftPreserved",true).put("businessGoalAccepted",true)
                .put("transport","authenticated-device-websocket").put("realFcmVerified",false).put("physicalPhone",false).put("originalRequestResubmitted",false)
            file("result").writeText(result.toString())
            phase("passed")
        } finally {
            Configurator.getInstance().waitForIdleTimeout=oldIdle
        }
    }
}
