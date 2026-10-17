package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodexHistoryConcurrencyNativeTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device get()=UiDevice.getInstance(instrumentation)
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(message:String,timeout:Long=12000,condition:()->Boolean){
        val end=android.os.SystemClock.elapsedRealtime()+timeout
        while(android.os.SystemClock.elapsedRealtime()<end&&!condition())Thread.sleep(30)
        assertTrue(message,condition())
    }
    @Test fun newestMessagePaintsWhileOlderReadIsHeld(){
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
        val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
        val original=prefs.all.toMap()
        val release=CompletableDeferred<Unit>()
        try{
            prefs.edit().clear().putString("server","http://127.0.0.1:18880")
                .putString("token","synthetic-system76").putString("deviceId","synthetic-device-a")
                .putString("draft:same-thread","Preserve this unsent draft").commit()
            main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}
            await("Initial canonical snapshot missing"){PocketTranscript.rows.any{it.s("text")=="Fresh response 1"}&&!PocketTranscript.loading}
            main{PocketLive.stop()}
            await("Initial history did not settle"){!PocketTranscript.loading&&!PocketTranscript.updating&&PocketTranscript.earlier}
            Thread.sleep(250)
            val entered=CompletableDeferred<Unit>()
            main{Pocket.scope.launch{PocketTranscript.load(older=true,beforeApply={entered.complete(Unit);release.await()})}}
            await("Older read never reached held publication"){entered.isCompleted}
            runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/silent",JSONObject())}
            val began=android.os.SystemClock.elapsedRealtime()
            main{Pocket.refreshDetail()}
            await("Newest messages are blocked behind an unrelated older-page read",6000){PocketTranscript.rows.any{it.s("text")=="Fresh response 2"}}
            assertFalse("The test did not retain its older-read boundary",release.isCompleted)
            assertTrue("Newest committed answer is not visible",device.wait(Until.hasObject(By.text("Fresh response 2")),3000))
            val newestVisibleMs=android.os.SystemClock.elapsedRealtime()-began
            release.complete(Unit)
            await("Older read did not finish"){!PocketTranscript.loading}
            assertEquals(1,PocketTranscript.rows.count{it.s("text")=="Fresh response 2"})
            assertTrue(PocketTranscript.rows.any{it.s("text")=="Older synthetic history"})
            assertEquals("Preserve this unsent draft",prefs.getString("draft:same-thread",null))
            main{Pocket.closeTask();Pocket.open("same-thread")}
            await("Reopening lost the newest answer"){PocketTranscript.currentHistoryVerified&&!PocketTranscript.loading&&!PocketTranscript.updating&&PocketTranscript.rows.any{it.s("text")=="Fresh response 2"}}
            assertEquals("Reopening duplicated the newest answer",1,PocketTranscript.rows.count{it.s("text")=="Fresh response 2"})
            assertEquals("Preserve this unsent draft",prefs.getString("draft:same-thread",null))
            val state=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/state",JSONObject())}
            assertEquals("Reading history dispatched work",0,state.getJSONArray("replies").length())
            java.io.File(context.cacheDir,"codex-history-concurrency.json").writeText(JSONObject()
                .put("newestVisibleBeforeOlderRelease",true).put("newestVisibleMs",newestVisibleMs)
                .put("reopenedAuthoritativeHistoryVerified",PocketTranscript.currentHistoryVerified)
                .put("duplicateNewest",false).put("draftPreserved",true).put("workDispatched",0).toString())
        }finally{
            release.complete(Unit)
            main{Pocket.closeTask();PocketLive.stop()}
            val edit=prefs.edit().clear()
            for((key,value)in original)when(value){
                is String->edit.putString(key,value)
                is Boolean->edit.putBoolean(key,value)
                is Int->edit.putInt(key,value)
                is Long->edit.putLong(key,value)
                is Float->edit.putFloat(key,value)
                is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
            }
            check(edit.commit())
        }
    }
    @Test fun openConversationRepairsMissedEventWithoutCatalogOrModelPolling(){
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
        val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
        val original=prefs.all.toMap()
        try{
            prefs.edit().clear().putString("server","http://127.0.0.1:18880")
                .putString("token","synthetic-system76").putString("deviceId","synthetic-device-a")
                .putString("draft:same-thread","Unsent during silent head recovery").commit()
            main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}
            await("Initial head was not verified"){Pocket.connected&&PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating}
            val destination=requireNotNull(Pocket.captureEnvironment())
            fun state()=runBlocking{Pocket.apiEnvironment(destination,"/_qa/state",JSONObject())}
            val baseline=state();val catalog=baseline.getInt("catalogReads")
            runBlocking{Pocket.apiEnvironment(destination,"/_qa/head-only-update",JSONObject())}
            val began=android.os.SystemClock.elapsedRealtime()
            await("An open conversation did not recover the silently advanced canonical head",12000){PocketTranscript.rows.any{it.s("text")=="Fresh response 2"}&&PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating}
            assertTrue(device.wait(Until.hasObject(By.text("Fresh response 2")),3000))
            val visibleMs=android.os.SystemClock.elapsedRealtime()-began
            val caughtUp=state();val reads=caughtUp.getJSONArray("reads").length()
            val probes=caughtUp.getJSONArray("statusReads").length()
            // A subsequent equal head must not refetch transcript or catalog.
            await("The foreground status owner stopped unexpectedly",8000){state().getJSONArray("statusReads").length()>probes}
            val after=state()
            assertEquals("Status checks polled the whole catalog",catalog,after.getInt("catalogReads"))
            assertEquals("An unchanged head refetched history",reads,after.getJSONArray("reads").length())
            assertEquals(0,after.getJSONArray("replies").length())
            assertEquals("Unsent during silent head recovery",prefs.getString("draft:same-thread",null))
            java.io.File(context.cacheDir,"codex-silent-head-recovery.json").writeText(JSONObject()
                .put("visibleMs",visibleMs).put("catalogReadsDuringRecovery",0).put("unchangedHeadHistoryReads",0)
                .put("draftPreserved",true).put("workDispatched",0).toString())
        }finally{
            main{Pocket.closeTask();PocketLive.stop()}
            val edit=prefs.edit().clear()
            for((key,value)in original)when(value){
                is String->edit.putString(key,value)
                is Boolean->edit.putBoolean(key,value)
                is Int->edit.putInt(key,value)
                is Long->edit.putLong(key,value)
                is Float->edit.putFloat(key,value)
                is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
            }
            check(edit.commit())
        }
    }
}
