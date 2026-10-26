package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
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
        var passed=condition()
        while(!passed&&android.os.SystemClock.elapsedRealtime()<end){Thread.sleep(30);passed=condition()}
        val diagnostics=if(passed)"" else " [selected="+Pocket.selected+", native="+PocketTranscript.canonicalMode+", verified="+PocketTranscript.currentHistoryVerified+", updating="+PocketTranscript.updating+", loading="+PocketTranscript.loading+", rows="+PocketTranscript.rows.size+", error="+PocketTranscript.readError+", appError="+Pocket.error+", reads="+runCatching{qa("state").optJSONArray("reads")?.length()}.getOrNull()+"]"
        assertTrue(message+diagnostics,passed)
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
            val command=runCatching{runBlocking{CodexReadTransport.read(requireNotNull(Pocket.captureEnvironment()),"/api/threads/same-thread/reply",JSONObject().put("text","must never dispatch"))}}
            assertTrue("Read transport admitted a command",command.exceptionOrNull() is IllegalArgumentException)
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
            val beforeStream=qa("state").getJSONArray("reads").length()
            qa("stream-unpositioned")
            await("Synthetic native stream did not finish"){qa("state").optInt("streamsSent")>=20}
            Thread.sleep(300)
            assertTrue("Unpositioned token deltas caused repeated native reads",qa("state").getJSONArray("reads").length()-beforeStream<=2)
            assertFalse("Unpositioned stream was painted as canonical history",device.hasObject(By.text("Native stream answer")))
            qa("stream-finish")
            await("Completed native stream did not receive canonical position"){PocketTranscript.rows.any{it.s("text")=="Native stream answer"}&&!PocketTranscript.updating}
            val streamRows=PocketTranscript.rows
            assertTrue(streamRows.indexOfFirst{it.s("text")=="Native stream request"}<streamRows.indexOfFirst{it.s("text")=="Native stream answer"})
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
            val openedAt=android.os.SystemClock.elapsedRealtime()
            open()
            await("Recorded actual native page did not become current",20000){PocketTranscript.canonicalMode&&PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating&&PocketTranscript.rows.any{it.has("_nativePosition")}}
            val headVerifiedMs=android.os.SystemClock.elapsedRealtime()-openedAt
            instrumentation.sendStatus(0,android.os.Bundle().apply{putString("stream","\nNative canonical load: "+JSONObject().put("headVerifiedMs",headVerifiedMs).put("capturePages",capture.optInt("pages")).put("measurement","current-state verification; not optical first paint").toString()+"\n")})
            await("Native viewport hydration did not settle",15000){!PocketTranscript.loading}
            val olderCursor=capture.s("firstBefore");assertTrue(olderCursor.isNotBlank())
            val readsBefore=qa("state").getJSONArray("reads").length()
            qa("hold-older")
            var older:kotlinx.coroutines.Job?=null
            try{
                main{older=Pocket.scope.launch{PocketTranscript.load(bridgeCursor=olderCursor)}}
                await("Native older read never entered its independent lane"){qa("state").getJSONArray("reads").length()>readsBefore&&PocketTranscript.loading}
                val headReads=qa("state").getJSONArray("reads").objects().count{it.s("cursor").isBlank()}
                main{CodexConversations.invalidate(immediate=true)}
                await("Current native head waited behind the held older page"){qa("state").getJSONArray("reads").objects().count{it.s("cursor").isBlank()}>headReads&&PocketTranscript.currentHistoryVerified&&!PocketTranscript.currentLoading}
                assertTrue("Older read unexpectedly settled before release",older?.isActive==true)
            }finally{qa("release-older")}
            await("Released native older page did not commit"){older?.isCompleted==true&&!PocketTranscript.loading}
            val keys=PocketTranscript.rows.filter{it.has("_nativePosition")}.map{NativeTranscriptKey(it.getLong("_nativePosition"),it.getInt("_nativeRank"),it.s("_nativeId"))}
            assertTrue(keys.zipWithNext().all{(a,b)->a<b})
            assertTrue(PocketTranscript.rows.none{it.s("text").startsWith("Alpha110 is installed")})
            val state=qa("state");assertEquals(0,state.getInt("legacyReads"));assertEquals(0,state.getInt("positionReads"));assertEquals(0,state.getInt("workSubmissions"))
            assertEquals("Native unsent draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
        }finally{qa("release");main{Pocket.closeTask();PocketLive.stop()}}
    }
    @Test fun mixedHistoryProtocolsStaySessionScopedAndKeepTheirOriginalCursors(){
        prepare()
        try{
            val destination=requireNotNull(Pocket.captureEnvironment())
            runBlocking{
                val modern=CodexSessionRepository.readCurrent(destination,"same-thread")
                assertEquals(1,modern.getJSONObject("timeline").getInt("native"))
                val legacy=CodexSessionRepository.readCurrent(destination,"legacy-thread")
                assertFalse(CodexSessionRepository.nativeEnabled(destination,"legacy-thread"))
                assertTrue("A fresh zero-revision snapshot was incorrectly rejected",CodexSessionRepository.currentSnapshot(destination,"legacy-thread",legacy))
                assertTrue(CodexSessionRepository.nativeEnabled(destination,"same-thread"))
                assertEquals("Legacy request",legacy.getJSONObject("timeline").getJSONArray("rows").getJSONObject(0).getString("text"))
                val cursor=legacy.getJSONObject("timeline").getString("before")
                assertEquals("legacy-opaque-cursor",cursor)
                val earlier=CodexSessionRepository.readOlder(destination,"legacy-thread",cursor)
                assertEquals("Earlier legacy request",earlier.getJSONObject("timeline").getJSONArray("rows").getJSONObject(0).getString("text"))
                val refused=runCatching{CodexSessionRepository.readCurrent(destination,"denied-thread")}.exceptionOrNull()
                assertTrue(refused is PocketApiException&&refused.status==403)
                assertTrue(CodexSessionRepository.nativeEnabled(destination,"denied-thread"))
                assertEquals(1,CodexSessionRepository.readCurrent(destination,"same-thread").getJSONObject("timeline").getInt("native"))
            }
            main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("legacy-thread")}
            await("Legacy conversation did not present verified turn history"){!PocketTranscript.canonicalMode&&PocketTranscript.currentHistoryVerified&&PocketTranscript.rows.any{it.s("text")=="Legacy answer"}}
            assertTrue(device.wait(Until.hasObject(By.text("Legacy answer")),4000))
            val legacyRows=PocketTranscript.rows
            assertTrue(legacyRows.indexOfFirst{it.s("text")=="Legacy request"}<legacyRows.indexOfFirst{it.s("text")=="Legacy answer"})
            main{Pocket.closeTask();Pocket.open("same-thread")}
            await("Modern conversation lost native history after legacy navigation"){PocketTranscript.canonicalMode&&PocketTranscript.currentHistoryVerified&&PocketTranscript.rows.any{it.s("text")=="Native answer one"}}
            assertTrue(device.wait(Until.hasObject(By.text("Native answer one")),4000))
            val state=qa("state");assertEquals(0,state.getInt("workSubmissions"));assertEquals(0,state.getInt("positionReads"))
            assertTrue("Legacy conversation entered an unbounded refresh loop",state.getInt("legacyHistoryReads")<=8)
            assertEquals("Native unsent draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
        }finally{main{Pocket.closeTask();PocketLive.stop()}}
    }

}
