package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android UI against the private real gateway/PG/native LAX qualification. */
@RunWith(AndroidJUnit4::class)
class HollywoodRoomNativeTest {
    @Test fun canonicalRoomsDraftsQuestionAndCondensedNativeExecution(){
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val device=UiDevice.getInstance(i)
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="harness_native_room")
        val config=JSONObject(context.filesDir.resolve("hollywood-qa.json").readText())
        device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
        val oldIdle=Configurator.getInstance().waitForIdleTimeout
        fun await(timeout:Long=30000,check:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){i.uiAutomation.clearCache();if(check())return;Thread.sleep(150)};assertTrue("Native Hollywood UI did not settle",check())}
        fun click(selector:BySelector){await{device.findObject(selector)?.isEnabled==true};device.findObject(selector).click()}
        fun text(value:String)=By.text(value)
        try{
            context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
                .putString("server",config.s("base")).putString("token",config.s("token")).commit()
            i.runOnMainSync{Pocket.init(context)}
            Configurator.getInstance().waitForIdleTimeout=0
            i.runOnMainSync{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            await{device.hasObject(text("Native UI A"))&&device.hasObject(By.res("native-room-history"))}
            assertTrue(BackendNavigation.nativeSelected())
            await{device.hasObject(text("LATEST CANONICAL HISTORY"))}
            val input=By.clazz("android.widget.EditText")
            device.findObject(input).text="Retained native room draft"
            click(By.text("Room"));click(text("Rooms"));await{device.hasObject(By.res("native-room:"+config.s("roomB")))}
            click(By.res("native-room:"+config.s("roomB")))
            await{device.hasObject(text("Native UI B"))};device.findObject(input).text="Independent room draft"
            click(By.text("Room"));click(text("Rooms"));click(By.res("native-room:"+config.s("roomA")))
            await{device.findObject(input)?.text=="Retained native room draft"}
            device.findObject(input).text=config.s("request")
            click(By.desc("Send message"))
            await(180000){device.hasObject(text("Needs you"))}
            click(text("Needs you"));await{device.hasObject(text("What value should the Android proof contain?"))}
            device.findObject(input).text="ANDROID_ROOM_VERIFIED"
            click(text("Send answer"))
            await(180000){device.hasObject(By.desc("Expand command runs"))}
            click(By.desc("Expand command runs"))
            await{device.hasObject(By.textContains("ANDROID_ROOM_VERIFIED"))}
            val endpoint=Pocket.base;val credential=Pocket.token
            i.runOnMainSync{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            await{Pocket.base==endpoint&&Pocket.token==credential&&BackendNavigation.nativeSelected()}
            context.filesDir.resolve("hollywood-native-ui-result.json").writeText(JSONObject().put("state","passed")
                .put("realGateway",true).put("realNativeQuestion",true).put("independentRoomDrafts",true)
                .put("condensedNativeCommands",true).put("physicalPhone",false).toString())
        }finally{Configurator.getInstance().waitForIdleTimeout=oldIdle}
    }
}
