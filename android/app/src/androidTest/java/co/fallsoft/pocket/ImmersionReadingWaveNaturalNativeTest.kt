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
        var clock:ImmersionReadingWaveClock?=null
        var taps=0
        val target="Permessi completi per i nuovi incarichi"
        val support="Permissions full per i new tasks"
        val controls=mutableSetOf<String>()
        val bodies=mutableSetOf<String>()
        val traces=org.json.JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{activity->
                check(Pocket.base=="http://127.0.0.1:18880"&&Pocket.token.isBlank())
                PocketSpeech.clearAll()
                PocketImmersion.setEnabled(true)
                PocketImmersion.setMotionEnabled(true)
                PocketImmersion.setSupportEnabled(true)
                activity.setContent {
                    CompositionLocalProvider(LocalImmersionMotionState provides rememberImmersionMotionEnvironment(),
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
            val button=device.findObjects(By.pkg(c.packageName).textContains("per i")).minBy{it.visibleBounds.top}
            device.click(button.visibleBounds.centerX(),button.visibleBounds.centerY())
            i.runOnMainSync{assertEquals("Visible button lost its action",1,taps)}
        }
    }
}
