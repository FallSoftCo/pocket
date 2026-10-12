package co.fallsoft.pocket

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImmersionReadingWaveNativeTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Before fun isolatedPhoneState() {
        val i=InstrumentationRegistry.getInstrumentation()
        val device=UiDevice.getInstance(i)
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
        device.executeShellCommand("pm grant "+i.targetContext.packageName+" android.permission.POST_NOTIFICATIONS")
        i.targetContext.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
            .putString("server","http://127.0.0.1:18880").commit()
        i.runOnMainSync{Pocket.init(i.targetContext)}
    }

    @Test fun controlsAndBodyCycleOnOneNativeClockAndPauseForSelectionAndReducedMotion() {
        compose.mainClock.autoAdvance=false
        val animation=mutableStateOf(false)
        var clock:ImmersionReadingWaveClock?=null
        var taps=0
        val rendererStates=mutableListOf<ImmersionRendererStatus>()
        val observer:(ImmersionRendererStatus)->Unit={rendererStates+=it}
        val target="Permessi completi per i nuovi incarichi"
        val support="Permissions full per i new tasks"
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{activity->
                check(Pocket.base=="http://127.0.0.1:18880"&&Pocket.token.isBlank())
                PocketImmersion.setEnabled(true)
                PocketImmersion.setMotionEnabled(true)
                PocketImmersion.setSupportEnabled(true)
                activity.setContent {
                    CompositionLocalProvider(LocalImmersionMotionState provides rememberImmersionMotionEnvironment(),
                        LocalImmersionTransitionAnimated provides animation.value,LocalImmersionRendererObserver provides observer) {
                        MaterialTheme{ImmersionReadingWaveHost {
                            clock=LocalImmersionReadingWave.current
                            Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
                                TextButton(onClick={taps++},modifier=Modifier.testTag("wave-control")) {
                                    BilingualLabel("Full permissions for new tasks",maxLines=3,centered=false)
                                }
                                Spacer(Modifier.height(70.dp))
                                ImmersionText("wave-body","Full permissions for new tasks",
                                    Modifier.width(230.dp).testTag("wave-body"),phraseRescue=false,maxLines=5)
                            }
                        }}
                    }
                }
            }
            fun text(tag:String):String {
                val node=compose.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode()
                fun visible(n:androidx.compose.ui.semantics.SemanticsNode):String {
                    val own=n.config.getOrNull(SemanticsProperties.Text)?.joinToString(""){it.text}
                    return own?:n.children.joinToString(""){visible(it)}
                }
                return visible(node)
            }
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            compose.waitUntil(5000){clock!=null}
            val originalControl=compose.onNodeWithTag("wave-control").fetchSemanticsNode().boundsInRoot
            val originalBody=compose.onNodeWithTag("wave-body").fetchSemanticsNode().boundsInRoot
            val i=InstrumentationRegistry.getInstrumentation()
            val device=UiDevice.getInstance(i)
            i.runOnMainSync{animation.value=true}
            val nativeControl=device.findObjects(androidx.test.uiautomator.By.pkg(i.targetContext.packageName).textContains("per i"))
                .sortedBy{it.visibleBounds.top}.first()
            val down=android.os.SystemClock.uptimeMillis()
            fun inject(action:Int) {
                val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,
                    nativeControl.visibleBounds.exactCenterX(),nativeControl.visibleBounds.exactCenterY(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try{assertTrue(i.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}
            }
            inject(android.view.MotionEvent.ACTION_DOWN)
            val pressed=clock!!.elapsedMs
            compose.mainClock.advanceTimeBy(96)
            i.runOnMainSync{assertTrue("An ordinary native tap must keep the shared clock advancing",clock!!.elapsedMs!=pressed)}
            inject(android.view.MotionEvent.ACTION_UP)
            val released=clock!!.elapsedMs
            compose.mainClock.advanceTimeBy(96)
            i.runOnMainSync{
                assertTrue("Tap release must not add a post-tap quiet interval",clock!!.elapsedMs!=released)
                assertEquals(1,taps)
            }
            val controlForms=mutableSetOf<String>()
            val bodyForms=mutableSetOf<String>()
            val traces=org.json.JSONArray()
            repeat(115) {
                compose.mainClock.advanceTimeBy(200)
                // Drive the Compose test display clock explicitly; this is not
                // a physical frame-pacing or real-time perception measurement.
                // A continuously drawing screen need not satisfy Espresso's
                // global-idle contract. Inspect actual native accessibility output.
                val nodes=device.findObjects(androidx.test.uiautomator.By.pkg(i.targetContext.packageName).textContains("per i"))
                    .sortedBy{it.visibleBounds.top}
                if(nodes.size>=2){
                    val c=nodes[0].text
                    val b=nodes[1].text
                    controlForms+=c;bodyForms+=b
                    traces.put(org.json.JSONObject().put("clockMs",clock!!.elapsedMs).put("hasVisible",clock!!.hasVisible).put("selection",clock!!.hasSelection).put("animation",animation.value).put("motionEnabled",PocketImmersion.motionEnabled).put("renderers",rendererStates.toString()).put("control",c).put("body",b)
                        .put("controlBounds",nodes[0].visibleBounds.toShortString()).put("bodyBounds",nodes[1].visibleBounds.toShortString()))
                }
                Thread.sleep(50)
            }
            java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"reading-wave-trace.json").writeText(traces.toString())
            assertTrue("Control never reached both readable forms: "+controlForms,controlForms.containsAll(listOf(target,support)))
            assertTrue("Body never reached both readable forms: "+bodyForms,bodyForms.containsAll(listOf(target,support)))
            i.runOnMainSync{animation.value=false}
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            assertEquals(originalControl,compose.onNodeWithTag("wave-control").fetchSemanticsNode().boundsInRoot)
            assertEquals(originalBody,compose.onNodeWithTag("wave-body").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("wave-control").performClick()
            compose.runOnIdle{assertEquals(2,taps)}
            val owner=Any()
            i.runOnMainSync{animation.value=true;clock!!.selecting(owner,true)}
            val paused=clock!!.elapsedMs
            compose.mainClock.advanceTimeBy(750)
            Thread.sleep(100)
            i.runOnMainSync{assertEquals("Selection advanced the display clock",paused,clock!!.elapsedMs);clock!!.selecting(owner,false)}
            compose.mainClock.advanceTimeBy(100)
            compose.waitUntil(3000){clock!!.elapsedMs!=paused}
            i.runOnMainSync{animation.value=false}
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            val reduced=clock!!.elapsedMs
            compose.mainClock.advanceTimeBy(750)
            Thread.sleep(100)
            compose.runOnIdle{assertEquals("Reduced motion advanced the display clock",reduced,clock!!.elapsedMs)}
            assertEquals(target,text("wave-control"))
            assertEquals(target,text("wave-body"))
        }
    }
}
