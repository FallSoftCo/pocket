package co.fallsoft.pocket

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual native Guide UI and original receipt after a controlled connector stop. */
@RunWith(AndroidJUnit4::class)
class HollywoodGuidanceRecoveryJourneyTest {
    @Test fun guideOriginalWorkAndRecoverAfterConnectorStop() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="harness_native_room")
        val config=JSONObject(context.filesDir.resolve("native-guidance-settings.json").readText())
        val prefix=config.s("case")
        fun file(name:String)=context.filesDir.resolve(prefix+"-"+name+".json")
        check(!file("intent").exists()){"Inspect original admission; do not replay this journey"}
        val oldIdle=Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout=0
        suspend fun await(timeout:Long=30000,check:()->Boolean) {
            withTimeout(timeout){while(!check()){delay(200)}}
        }
        suspend fun click(selector:BySelector){
            await{device.findObject(selector)?.isEnabled==true};device.findObject(selector).click()
        }
        fun editor():UiObject2 {
            val tagged=device.findObject(By.res("native-guidance-text"))?:error("Guidance field missing")
            return if(tagged.className=="android.widget.EditText")tagged else tagged.findObject(By.clazz("android.widget.EditText"))?:error("Guidance editor missing")
        }
        fun phase(name:String){file("phase").writeText(JSONObject().put("phase",name).toString())}
        try {
            instrumentation.runOnMainSync{Pocket.init(context)}
            val owner=EnvironmentIdentity(config.s("ownerProfile"),"Guidance owner")
            val viewer=EnvironmentIdentity(config.s("viewerProfile"),"Guidance viewer")
            val before=Pocket.environmentProfiles.all()
            check(before.none{it.id in listOf(owner.id,viewer.id)})
            val profiles=before+owner+viewer
            val edit=Pocket.prefs.edit().putString("environments:v1",JSONArray(profiles.map{JSONObject().put("id",it.id).put("name",it.name).put("local",it.local)}).toString())
            for((profile,key)in listOf(owner to "ownerToken",viewer to "viewerToken")){
                edit.putString(profile.key("server"),config.s("base")).putString(profile.key("token"),config.s(key))
                    .putString(profile.key("deviceId"),config.s(if(key=="ownerToken")"ownerDevice"else "viewerDevice"))
            }
            check(edit.commit())
            instrumentation.runOnMainSync{
                context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Pocket.activateEnvironment(owner.id)
            }
            await{Pocket.environmentId==owner.id&&BackendNavigation.nativeSelected()&&Pocket.connected}
            val origin=Pocket.captureEnvironment(owner.id)!!
            val binding=Pocket.apiEnvironment(origin,"/api/hollywood/v3/identity")
            val client=HollywoodClient(backendOwner(origin.endpoint,origin.token),binding,origin.endpoint,origin.token)
            val roomScope=HollywoodScope(config.s("globalRoom"),config.s("globalAgent"))
            val request=client.capture(roomScope,config.s("request"))
            file("intent").writeText(request.toString())
            val admission=client.submit(roomScope,request)
            file("admission").writeText(admission.toString())
            val delivery=admission.getJSONArray("deliveries").getJSONObject(0).s("id")
            phase("admitted")
            await(180000){file("native-ready").exists()}
            await{device.hasObject(By.res("native-guide:"+delivery))}
            val scope=client.call("rooms/"+config.s("globalRoom")).getJSONArray("deliveries").objects().single{it.s("id")==delivery}.getJSONObject("control_scope")
            val roomDraft="ROOM_DRAFT_REMAINS_LOCAL"
            device.findObject(By.clazz("android.widget.EditText")).text=roomDraft
            await{client.draft(roomScope)==roomDraft}
            click(By.res("native-guide:"+delivery))
            await{device.hasObject(By.res("native-guidance-text"))}
            editor().text=config.s("guidance")
            await{client.guidanceDraft(scope)==config.s("guidance")}
            click(By.text("Close"))
            click(By.res("native-guide:"+delivery))
            await{device.hasObject(By.res("native-guidance-text"))}
            assertEquals(config.s("guidance"),editor().text)
            click(By.res("native-guidance-send"))
            await{client.guidancePending(scope)?.s("state") in listOf("pending","dispatching")}
            val saved=client.guidancePending(scope)!!
            assertTrue(hollywoodJsonEqual(saved.getJSONObject("body").getJSONObject("scope"),scope))
            assertEquals(config.s("guidance"),saved.getJSONObject("body").s("body"))
            file("guidance-intent").writeText(saved.toString())
            assertEquals(roomDraft,client.draft(roomScope))
            click(By.text("Close"))
            val other=Pocket.captureEnvironment(viewer.id)!!
            val otherBinding=Pocket.apiEnvironment(other,"/api/hollywood/v3/identity")
            val otherClient=HollywoodClient(backendOwner(other.endpoint,other.token),otherBinding,other.endpoint,other.token)
            otherClient.saveDraft(roomScope,"VIEWER_DRAFT_REMAINS_LOCAL")
            var denied=false
            try{otherClient.guide(saved,inspect=true)}catch(_:IllegalArgumentException){denied=true}
            assertTrue("A different profile binding cannot inspect an origin intent",denied)
            instrumentation.runOnMainSync{Pocket.activateEnvironment(viewer.id)}
            await{Pocket.environmentId==viewer.id}
            phase("guidance-sent")
            await(480000){file("reconciled").exists()}
            val recovery=JSONObject(file("reconciled").readText())
            assertEquals(viewer.id,Pocket.environmentId)
            assertEquals("VIEWER_DRAFT_REMAINS_LOCAL",otherClient.draft(roomScope))
            instrumentation.runOnMainSync{Pocket.activateEnvironment(owner.id)}
            await{Pocket.environmentId==owner.id&&Pocket.connected}
            await{!device.hasObject(By.res("native-guide:"+delivery))}
            click(By.text("Room"))
            click(By.res("native-guidance-saved:"+delivery+":"+scope.s("nativeTurn")))
            await{device.hasObject(By.text("Check original delivery"))}
            click(By.text("Check original delivery"))
            await{device.hasObject(By.text("Recorded in the active conversation"))}
            assertFalse(device.findObject(By.res("native-guidance-send")).isEnabled)
            assertEquals("native_recorded",client.guidancePending(scope)!!.s("state"))
            assertEquals(roomDraft,client.draft(roomScope))
            click(By.text("Close"))
            val review=By.res("native-guidance-review:"+recovery.s("unknownControl"))
            repeat(4){
                if(!device.hasObject(review))device.findObject(By.res("native-room-history"))?.scroll(Direction.UP,.7f)
                delay(300)
            }
            click(review)
            await{device.hasObject(By.text("Guidance receipt"))}
            assertTrue(device.hasObject(By.text("unknown · work completed")))
            click(By.text("Close"))
            file("result").writeText(JSONObject().put("passed",true).put("deliveryId",delivery).put("scope",scope)
                .put("actualGuideUi",true).put("guidanceDraftReopened",true).put("roomDraftPreserved",true)
                .put("viewerDraftPreserved",true).put("crossBindingIntentDenied",true)
                .put("originalReceiptReadAfterStop",true).put("endedWorkCannotReceiveNewGuidance",true)
                .put("canonicalUnknownReceiptVisible",true).put("physicalPhone",false).toString())
            phase("passed")
        } finally {Configurator.getInstance().waitForIdleTimeout=oldIdle}
    }
}
