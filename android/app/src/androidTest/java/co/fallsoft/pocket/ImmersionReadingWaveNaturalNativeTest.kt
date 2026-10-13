package co.fallsoft.pocket

import android.content.Context
import android.app.Activity
import android.app.Application
import android.os.Bundle
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
        val allowMotion=mutableStateOf(false)
        var nativeActivity:MainActivity?=null
        var clock:ImmersionReadingWaveClock?=null
        var taps=0
        val target="Permessi completi per i nuovi incarichi"
        val support="Permissions full per i new tasks"
        val controls=mutableSetOf<String>()
        val bodies=mutableSetOf<String>()
        java.io.File(c.cacheDir,"reading-wave-menu-trace.json").delete()
        val traces=org.json.JSONArray()
        fun capture(stage:String){
            device.takeScreenshot(java.io.File(c.cacheDir,"wave-"+stage+".png"))
            device.dumpWindowHierarchy(java.io.File(c.cacheDir,"wave-"+stage+".xml"))
        }
        check(android.os.Build.VERSION.SDK_INT>=29)
        val application=c.applicationContext as Application
        val lifecycle=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity:Activity,savedInstanceState:Bundle?) {}
            override fun onActivityPostCreated(activity:Activity,savedInstanceState:Bundle?) {
                if(activity !is MainActivity)return
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
                                                Modifier.width(230.dp),phraseRescue=true,maxLines=5)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            override fun onActivityStarted(activity:Activity){}
            override fun onActivityResumed(activity:Activity){}
            override fun onActivityPaused(activity:Activity){}
            override fun onActivityStopped(activity:Activity){}
            override fun onActivitySaveInstanceState(activity:Activity,outState:Bundle){}
            override fun onActivityDestroyed(activity:Activity){}
        }
        // Install the fixture before resumed UI can own a continuous display loop.
        // Launch/idle completes with its own clock disabled; then use the genuine
        // Android frame clock without changing system animation preferences.
        application.registerActivityLifecycleCallbacks(lifecycle)
        try {
        ActivityScenario.launch(MainActivity::class.java).use{
            var stopPerformance:(()->Unit)?=null
            try {
            val samples=java.util.Collections.synchronizedList(mutableListOf<LongArray>())
            val frameThread=android.os.HandlerThread("OwnedWaveFrameObservation").apply{start()}
            val frameListener=android.view.Window.OnFrameMetricsAvailableListener { _,metrics,_ ->
                samples.add(longArrayOf(metrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION),
                    metrics.getMetric(android.view.FrameMetrics.DRAW_DURATION),
                    metrics.getMetric(android.view.FrameMetrics.DEADLINE)))
            }
            val allocationStart=android.os.Debug.getRuntimeStats()
            val cpuStart=android.os.Process.getElapsedCpuTime()
            val observationStart=android.os.SystemClock.elapsedRealtime()
            i.runOnMainSync{
                nativeActivity!!.window.addOnFrameMetricsAvailableListener(frameListener,android.os.Handler(frameThread.looper))
                allowMotion.value=true
            }
            stopPerformance={
                i.runOnMainSync{nativeActivity!!.window.removeOnFrameMetricsAvailableListener(frameListener)}
                frameThread.quitSafely();frameThread.join(2000)
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
            stopPerformance.invoke();stopPerformance=null
            val allocationEnd=android.os.Debug.getRuntimeStats()
            val frameSnapshot=synchronized(samples){samples.toList()}
            val frameValues=org.json.JSONArray()
            frameSnapshot.forEach{frameValues.put(org.json.JSONArray().put(it[0]).put(it[1]).put(it[2]))}
            val allocationDelta=org.json.JSONObject()
            listOf("art.gc.bytes-allocated","art.gc.gc-count","art.gc.gc-time","art.gc.blocking-gc-count").forEach{key->
                val a=allocationStart[key]?.toLongOrNull();val b=allocationEnd[key]?.toLongOrNull()
                if(a!=null&&b!=null)allocationDelta.put(key,b-a)
            }
            java.io.File(c.cacheDir,"reading-wave-performance.json").writeText(org.json.JSONObject()
                .put("scope","Owned software emulator; 23-second natural reading-wave journey with native accessibility observations; physical frame pacing unqualified")
                .put("wallMs",android.os.SystemClock.elapsedRealtime()-observationStart)
                .put("processCpuMs",android.os.Process.getElapsedCpuTime()-cpuStart)
                .put("runtimeDeltas",allocationDelta).put("frames",frameValues).toString())
            java.io.File(c.cacheDir,"reading-wave-natural-trace.json").writeText(traces.toString())
            assertTrue("Control did not cycle naturally: "+controls,controls.containsAll(listOf(target,support)))
            assertTrue("Body did not cycle naturally: "+bodies,bodies.containsAll(listOf(target,support)))
            // Actual native selection menu, not direct clock-owner injection.
            val body=device.findObjects(By.pkg(c.packageName).textContains("per i")).maxBy{it.visibleBounds.top}
            val x=body.visibleBounds.left+30
            val y=body.visibleBounds.top+25
            // Inject an actual stationary hold; a zero-distance swipe is not
            // evidence that the platform received a long press of known duration.
            val downAt=android.os.SystemClock.uptimeMillis()
            fun inject(action:Int):Boolean {
                val event=android.view.MotionEvent.obtain(downAt,android.os.SystemClock.uptimeMillis(),
                    action,x.toFloat(),y.toFloat(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                return try{i.uiAutomation.injectInputEvent(event,true)}finally{event.recycle()}
            }
            try {
                assertTrue("Native touch-down delivery failed",inject(android.view.MotionEvent.ACTION_DOWN))
                Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong()+350)
            } finally {
                assertTrue("Native touch-up delivery failed",inject(android.view.MotionEvent.ACTION_UP))
            }
            val select=device.wait(androidx.test.uiautomator.Until.findObject(By.text("Select all")),5000)
            if(select==null)capture("selection-open-unavailable")
            assertNotNull("Native body selection did not open its menu",select)
            val menuTrace=org.json.JSONArray()
            fun recordMenu(stage:String){
                val plan=PocketImmersion.presentation("natural-wave-body","Full permissions for new tasks")
                val selected=PocketImmersion.readingSelection(PocketImmersion.readingKey("natural-wave-body",plan),plan)
                menuTrace.put(org.json.JSONObject().put("stage",stage).put("wallMs",android.os.SystemClock.elapsedRealtime())
                    .put("clockMs",clock?.elapsedMs).put("nativeMenu",nativeActivity?.immersionSelectionActive)
                    .put("waveSelection",clock?.hasSelection).put("readingHelp",selected?.let{immersionPhraseIdentity(it)}))
            }
            recordMenu("before-select-all")
            select!!.click()
            repeat(20){recordMenu("after-select-all");Thread.sleep(25)}
            java.io.File(c.cacheDir,"reading-wave-menu-trace.json").writeText(menuTrace.toString())
            val copy=device.wait(androidx.test.uiautomator.Until.findObject(By.text("Copy")),5000)
            if(copy==null)capture("copy-unavailable")
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
            // Copy preserves native selection; explicitly tap the selected phrase
            // to leave selection before testing an unrelated control.
            device.click(x,y)
            capture("initial-selection-dismiss")
            val button=device.findObjects(By.pkg(c.packageName).textContains("per i")).minBy{it.visibleBounds.top}
            device.click(button.visibleBounds.centerX(),button.visibleBounds.centerY())
            i.runOnMainSync{assertEquals("Visible button lost its action",1,taps)}
            val resumeStart=android.os.SystemClock.elapsedRealtime()
            while(clock!!.elapsedMs==paused&&android.os.SystemClock.elapsedRealtime()-resumeStart<5000)Thread.sleep(25)
            assertNotEquals("Wave stayed frozen after leaving selection: nativeMenu="+nativeActivity?.immersionSelectionActive+
                ", selection="+clock!!.hasSelection,paused,clock!!.elapsedMs)
            i.runOnMainSync{nativeActivity!!.enterBlackout()}
            Thread.sleep(100)
            val black=clock!!.elapsedMs
            assertTrue("Blackout failed to enter",BlackoutVisibility.active)
            Thread.sleep(750)
            assertEquals("Hidden text kept animating in blackout",black,clock!!.elapsedMs)
            device.click(device.displayWidth/2,device.displayHeight/2)
            val exitStart=android.os.SystemClock.elapsedRealtime()
            while(BlackoutVisibility.active&&android.os.SystemClock.elapsedRealtime()-exitStart<3000)Thread.sleep(25)
            assertFalse("Blackout failed to exit",BlackoutVisibility.active)
            capture("blackout-return")
            // Blackout restores prior native input focus. Android can therefore
            // reopen the existing selection menu; its text must remain frozen.
            if(nativeActivity?.immersionSelectionActive==true) {
                assertTrue("Restored selection lost its wave hold",clock!!.hasSelection)
                Thread.sleep(250)
                assertEquals("Restored selected text changed language",black,clock!!.elapsedMs)
                // Tap the linked selected word: it must dismiss selection first,
                // not toggle language help beneath a still-active native menu.
                device.click(x,y)
                capture("restored-text-tap")
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
                stopPerformance?.invoke()
                i.runOnMainSync { allowMotion.value=false }
            }
        }
        } finally {application.unregisterActivityLifecycleCallbacks(lifecycle)}
    }
}
