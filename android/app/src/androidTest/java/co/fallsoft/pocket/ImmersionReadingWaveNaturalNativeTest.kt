package co.fallsoft.pocket

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android display clock: deliberately no Compose test-clock/idling rule. */
@RunWith(AndroidJUnit4::class)
class ImmersionReadingWaveNaturalNativeTest {
    @Test fun naturalDisplayClockCyclesReadableBodyAndControlWithoutMovingTheirBounds() {
        val i=InstrumentationRegistry.getInstrumentation()
        val c=i.targetContext
        val device=UiDevice.getInstance(i)
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
        device.executeShellCommand("pm grant "+c.packageName+" android.permission.POST_NOTIFICATIONS")
        c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
            .putString("server","http://127.0.0.1:18880").commit()
        i.runOnMainSync{Pocket.init(c)}
        val allowMotion=mutableStateOf(true)
        var nativeActivity:MainActivity?=null
        var clock:ImmersionReadingWaveClock?=null
        var taps=0
        val target="Permessi completi per i nuovi incarichi"
        val support="Permissions full per i new tasks"
        val controls=mutableSetOf<String>()
        val bodies=mutableSetOf<String>()
        val traces=org.json.JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            try {
            scenario.onActivity{activity->
                nativeActivity=activity
                check(Pocket.base=="http://127.0.0.1:18880"&&Pocket.token.isBlank())
                PocketSpeech.clearAll()
                PocketImmersion.setEnabled(true)
                PocketImmersion.setMotionEnabled(true)
                PocketImmersion.setSupportEnabled(true)
                activity.setContent {
                    val environmentReady=rememberImmersionMotionEnvironment()
                    CompositionLocalProvider(LocalImmersionMotionState provides (environmentReady && allowMotion.value),
                        LocalImmersionTransitionAnimated provides true) {
                        MaterialTheme(colorScheme=darkColorScheme(primary=Mint,background=Ink,surface=Panel,onSurface=Paper),
                            typography=Typography(bodyLarge=TextStyle(fontFamily=AppFont,fontSize=17.sp))) {
                            ImmersionReadingWaveHost {
                                clock=LocalImmersionReadingWave.current
                                Surface(Modifier.fillMaxSize(),color=Ink) {
                                    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
                                        TextButton(onClick={taps++},colors=ButtonDefaults.textButtonColors(contentColor=Paper)) {
                                            BilingualLabel("Full permissions for new tasks",fontSize=17.sp,maxLines=3,centered=false)
                                        }
                                        Spacer(Modifier.height(70.dp))
                                        ImmersionSelection("Full permissions for new tasks") {
                                            ImmersionText("natural-wave-body","Full permissions for new tasks",
                                                Modifier.width(230.dp),phraseRescue=false,maxLines=5)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            var controlBounds:String?=null
            var bodyBounds:String?=null
            val started=android.os.SystemClock.elapsedRealtime()
            while(android.os.SystemClock.elapsedRealtime()-started<23000) {
                val nodes=device.findObjects(By.pkg(c.packageName).textContains("per i")).sortedBy{it.visibleBounds.top}
                if(nodes.size>=2) {
                    val control=nodes[0]
                    val body=nodes[1]
                    val cb=control.visibleBounds.toShortString()
                    val bb=body.visibleBounds.toShortString()
                    if(controlBounds==null){controlBounds=cb;bodyBounds=bb}
                    assertEquals("Natural wave changed control bounds",controlBounds,cb)
                    assertEquals("Natural wave changed body bounds",bodyBounds,bb)
                    controls+=control.text;bodies+=body.text
                    traces.put(org.json.JSONObject().put("wallMs",android.os.SystemClock.elapsedRealtime()-started)
                        .put("clockMs",clock?.elapsedMs).put("control",control.text).put("body",body.text)
                        .put("controlBounds",cb).put("bodyBounds",bb))
                }
                Thread.sleep(200)
            }
            java.io.File(c.cacheDir,"reading-wave-natural-trace.json").writeText(traces.toString())
            assertTrue("Control did not cycle naturally: "+controls,controls.containsAll(listOf(target,support)))
            assertTrue("Body did not cycle naturally: "+bodies,bodies.containsAll(listOf(target,support)))
            // Actual native selection menu, not direct clock-owner injection.
            val body=device.findObjects(By.pkg(c.packageName).textContains("per i")).maxBy{it.visibleBounds.top}
            val x=body.visibleBounds.left+30
            val y=body.visibleBounds.top+25
            device.swipe(x,y,x,y,120)
            val select=device.wait(androidx.test.uiautomator.Until.findObject(By.text("Select all")),5000)
            assertNotNull("Native body selection did not open its menu",select)
            select!!.click()
            val copy=device.wait(androidx.test.uiautomator.Until.findObject(By.text("Copy")),5000)
            assertNotNull("Native body selection did not offer Copy",copy)
            assertTrue("Selection menu did not freeze the shared wave",clock!!.hasSelection)
            val paused=clock!!.elapsedMs
            val frozen=device.findObjects(By.pkg(c.packageName).textContains("per i")).maxBy{it.visibleBounds.top}.text
            Thread.sleep(750)
            assertEquals("Wave changed while native selection menu was open",paused,clock!!.elapsedMs)
            copy!!.click()
            var copied:String?=null
            i.runOnMainSync{copied=(c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .primaryClip?.getItemAt(0)?.text?.toString()}
            assertEquals("Selection copied a different or duplicated language flow",frozen,copied)
            val button=device.findObjects(By.pkg(c.packageName).textContains("per i")).minBy{it.visibleBounds.top}
            device.click(button.visibleBounds.centerX(),button.visibleBounds.centerY())
            i.runOnMainSync{assertEquals("Visible button lost its action",1,taps)}
            val resumeStart=android.os.SystemClock.elapsedRealtime()
            while(clock!!.elapsedMs==paused&&android.os.SystemClock.elapsedRealtime()-resumeStart<5000)Thread.sleep(25)
            assertNotEquals("Wave stayed frozen after leaving selection",paused,clock!!.elapsedMs)
            scenario.onActivity{it.enterBlackout()}
            Thread.sleep(100)
            val black=clock!!.elapsedMs
            assertTrue("Blackout failed to enter",BlackoutVisibility.active)
            Thread.sleep(750)
            assertEquals("Hidden text kept animating in blackout",black,clock!!.elapsedMs)
            device.click(device.displayWidth/2,device.displayHeight/2)
            val exitStart=android.os.SystemClock.elapsedRealtime()
            while(BlackoutVisibility.active&&android.os.SystemClock.elapsedRealtime()-exitStart<3000)Thread.sleep(25)
            assertFalse("Blackout failed to exit",BlackoutVisibility.active)
            // Blackout restores prior native input focus. Android can therefore
            // reopen the existing selection menu; its text must remain frozen.
            if(nativeActivity?.immersionSelectionActive==true) {
                assertTrue("Restored selection lost its wave hold",clock!!.hasSelection)
                Thread.sleep(250)
                assertEquals("Restored selected text changed language",black,clock!!.elapsedMs)
                // Complete the restored native menu normally, without invoking
                // the activity's navigation/back action or clearing focus by fiat.
                val restoredCopy=device.wait(androidx.test.uiautomator.Until.findObject(By.text("Copy")),3000)
                assertNotNull("Restored selection lost its native Copy action",restoredCopy)
                restoredCopy!!.click()
                val restoredButton=device.findObjects(By.pkg(c.packageName).textContains("per i")).minBy{it.visibleBounds.top}
                device.click(restoredButton.visibleBounds.centerX(),restoredButton.visibleBounds.centerY())
                i.runOnMainSync{assertEquals("Action failed after blackout return",2,taps)}
            }
            val afterExit=android.os.SystemClock.elapsedRealtime()
            while(clock!!.elapsedMs==black&&android.os.SystemClock.elapsedRealtime()-afterExit<5000)Thread.sleep(25)
            val exitState="visible="+clock!!.hasVisible+", selection="+clock!!.hasSelection+
                ", touching="+ImmersionInteraction.touching+", nativeMenu="+nativeActivity?.immersionSelectionActive
            java.io.File(c.cacheDir,"reading-wave-blackout-state.txt").writeText(exitState)
            assertNotEquals("Wave did not resume after leaving blackout/selection: "+exitState,black,clock!!.elapsedMs)
            } finally {
                // Stop the fixture display clock before ActivityScenario waits for idle.
                // Failed interaction assertions must produce a receipt rather than hang teardown.
                i.runOnMainSync { allowMotion.value=false }
            }
        }
    }
}
