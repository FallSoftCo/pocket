package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCanonicalTranscriptNativeTest{
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device get()=UiDevice.getInstance(instrumentation)
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(message:String,timeout:Long=15000,condition:()->Boolean){
        val end=android.os.SystemClock.elapsedRealtime()+timeout
        while(android.os.SystemClock.elapsedRealtime()<end&&!condition())Thread.sleep(30)
        assertTrue(message,condition())
    }
    private fun prepare(){
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
        context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
            .putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76-"+java.util.UUID.randomUUID())
            .putString("deviceId","synthetic-device-a").putString("draft:same-thread","Native unsent draft").commit()
        main{Pocket.init(context)}
    }
    private fun qa(action:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+action,JSONObject())}
    private fun open(){main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}}
    @Test fun nativeOrderSurvivesDelayedArrivalQuestionsAndReconnectWithoutLegacyRepair(){
        prepare()
        try{
            open()
            await("Native current head did not settle"){PocketTranscript.canonicalMode&&PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating}
            assertTrue(device.wait(Until.hasObject(By.text("Native answer one")),4000))
            qa("advance-held")
            await("Unknown native entries did not trigger a canonical read"){qa("state").getJSONArray("reads").length()>1}
            repeat(4){
                assertFalse("Unpositioned answer painted before canonical commit",device.hasObject(By.text("Native answer two")))
                assertFalse("Unpositioned input painted before canonical commit",device.hasObject(By.text("Native request two")))
                Thread.sleep(100)
            }
            qa("release")
            await("Native pair did not arrive atomically"){PocketTranscript.rows.any{it.s("text")=="Native answer two"}&&!PocketTranscript.updating}
            val rows=PocketTranscript.rows
            assertEquals(1,rows.count{it.s("text")=="Native request two"})
            assertTrue(rows.indexOfFirst{it.s("text")=="Native request two"}<rows.indexOfFirst{it.s("text")=="Native answer two"})
            val user=device.wait(Until.findObject(By.text("Native request two")),4000)
            val answer=device.wait(Until.findObject(By.text("Native answer two")),4000)
            assertNotNull(user);assertNotNull(answer)
            assertTrue("Native answer painted above its input",user!!.visibleBounds.bottom<=answer!!.visibleBounds.top)
            qa("question")
            assertTrue("Native pending question not actionable",device.wait(Until.hasObject(By.text("Native pending question")),8000))
            val skip=device.wait(Until.findObject(By.text("Skip")),2000)?:device.wait(Until.findObject(By.text("Salta")),2000)
            assertNotNull("Question skip control missing",skip);skip!!.click()
            await("Question answer was not routed"){qa("state").optInt("answers")==1}
            qa("disconnect")
            main{Pocket.closeTask()}
            qa("recover")
            open()
            await("Reopen did not restore canonical native history"){PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating&&PocketTranscript.rows.any{it.s("text")=="Native answer two"}}
            assertEquals(1,PocketTranscript.rows.count{it.s("text")=="Native request two"})
            val state=qa("state")
            assertEquals(0,state.getInt("legacyReads"));assertEquals(0,state.getInt("positionReads"));assertEquals(0,state.getInt("workSubmissions"))
            assertEquals("Native unsent draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
        }finally{qa("release");main{Pocket.closeTask();PocketLive.stop()}}
    }
    @Test fun recordedNativeMacPagesOpenCurrentAndRetainNativeKeys(){
        prepare()
        try{
            val capture=qa("enable-capture")
            assertEquals("recorded-native-timeline",capture.s("source"))
            assertTrue(capture.optInt("pages")>=4)
            open()
            await("Recorded actual native page did not become current",20000){PocketTranscript.canonicalMode&&PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating&&PocketTranscript.rows.any{it.has("_nativePosition")}}
            val keys=PocketTranscript.rows.filter{it.has("_nativePosition")}.map{NativeTranscriptKey(it.getLong("_nativePosition"),it.getInt("_nativeRank"),it.s("_nativeId"))}
            assertTrue(keys.zipWithNext().all{(a,b)->a<b})
            assertTrue(PocketTranscript.rows.none{it.s("text").startsWith("Alpha110 is installed")})
            val state=qa("state");assertEquals(0,state.getInt("legacyReads"));assertEquals(0,state.getInt("positionReads"));assertEquals(0,state.getInt("workSubmissions"))
            assertEquals("Native unsent draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
        }finally{main{Pocket.closeTask();PocketLive.stop()}}
    }
}
