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
    @Test fun actualHistoryReplayNeverPaintsAlpha110IslandAndFillsRecentViewport(){
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
        val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
        prefs.edit().clear().putString("server","http://127.0.0.1:18880")
            .putString("token","synthetic-system76-"+java.util.UUID.randomUUID().toString())
            .putString("deviceId","synthetic-device-a").putString("draft:same-thread","Unsent actual-history audit draft").commit()
        main{Pocket.init(context)}
        fun qa(name:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+name,JSONObject())}
        val evidence=qa("enable-history-replay")
        assertEquals("recorded-native-read-only",evidence.s("source"));assertTrue(evidence.optInt("pages")>=16)
        val destination=requireNotNull(Pocket.captureEnvironment())
        val current=runBlocking{CodexSessionRepository.readCurrent(destination,"same-thread")}
        CodexSessionRepository.accepted(destination,"same-thread",current)
        await("Canonical shared head did not settle"){CodexSessionRepository.peek("same-thread")!=null}
        val old=org.json.JSONArray().put(JSONObject().put("id","legacy-alpha110-a").put("kind","message").put("text","Alpha110 is installed on your phone and connected."))
            .put(JSONObject().put("id","legacy-alpha110-b").put("kind","message").put("text","Alpha110 is installed and connected."))
        current.getJSONObject("timeline").getJSONArray("rows").objects().forEach{old.put(JSONObject(it.toString()))}
        val scope=PocketTranscript.scopeKey()
        val cached=JSONObject().put("schema",1).put("scope",scope).put("id","same-thread").put("rows",old)
            .put("detail",JSONObject().put("thread",JSONObject().put("id","same-thread"))).put("earlier",false).put("coverageVerified",true)
            .put("position",JSONObject().put("row","legacy-alpha110-a").put("offset",0).put("follow",false))
        assertTrue(TranscriptDiskCache(java.io.File(context.noBackupFilesDir,"transcript-cache-v1")).write(scope,"same-thread",cached.toString()))
        qa("hold-replay-head")
        try{
            main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}
            await("Legacy islands were not retained for the audit"){PocketTranscript.rows.count{it.s("id").startsWith("legacy-alpha110-")}==2}
            repeat(4){
                assertFalse("Alpha110 cache island painted before fresh history",device.hasObject(By.textStartsWith("Alpha110 is installed")))
                Thread.sleep(100)
            }
            qa("release-replay-head")
            await("Actual canonical head did not settle",20000){PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating}
            await("Tool-heavy raw pages left the viewport unhydrated",12000){qa("replay-state").getJSONArray("reads").objects().any{it.optBoolean("older")}}
            await("Viewport hydration did not settle",12000){!PocketTranscript.loading&&!PocketTranscript.updating}
            assertFalse("Old Alpha110 island is still presented as recent context",device.hasObject(By.textStartsWith("Alpha110 is installed")))
            val projected=connectedTranscriptWindow(PocketTranscript.rows,PocketTranscript.historyGaps,
                connectedTranscriptRange(PocketTranscript.rows,PocketTranscript.historyGaps,PocketTranscript.readPosition?.rowId))
            assertFalse(projected.any{it.s("id").startsWith("legacy-alpha110-")})
            assertEquals("Local retained bodies were erased",2,PocketTranscript.rows.count{it.s("id").startsWith("legacy-alpha110-")})
            val state=qa("replay-state")
            assertTrue("Opening downloaded history without a viewport budget",state.getJSONArray("reads").objects().count{it.optBoolean("older")}<=9)
            assertEquals(0,state.optInt("submissions"))
            assertEquals("Unsent actual-history audit draft",prefs.getString("draft:same-thread",null))
            java.io.File(context.filesDir,"actual-history-window-audit.json").writeText(JSONObject()
                .put("actualReadOnlyCapturePages",evidence.optInt("pages")).put("alpha110NeverVisible",true).put("legacyBodiesRetained",2)
                .put("olderReads",state.getJSONArray("reads").objects().count{it.optBoolean("older")}).put("workSubmitted",0).toString())
        }finally{qa("release-replay-head");main{Pocket.closeTask();PocketLive.stop()}}
    }
    @Test fun followingLatestCrossesDisconnectedWindowWhileHistoryReadIsHeld(){
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
        val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
        prefs.edit().clear().putString("server","http://127.0.0.1:18880")
            .putString("token","synthetic-system76-"+java.util.UUID.randomUUID().toString())
            .putString("deviceId","synthetic-device-a").putString("draft:same-thread","Mac freshness draft").commit()
        main{Pocket.init(context)}
        fun qa(name:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+name,JSONObject())}
        qa("long-retained-gap")
        try{
            main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}
            await("Initial current head did not settle",20000){PocketTranscript.currentHistoryVerified&&!PocketTranscript.loading&&!PocketTranscript.updating}
            assertTrue(device.wait(Until.hasObject(By.text("Fresh response 1")),4000))
            // A reader can return to the tail after browsing; a saved measured
            // anchor must no longer select an old island once following resumes.
            main{
                PocketTranscript.readerInteraction()
                PocketTranscript.rememberPosition(PocketTranscript.scopeKey(),"same-thread",TranscriptReadPosition("long-head",0,true))
            }
            qa("hold-history")
            qa("new-long-window")
            main{Pocket.refreshDetail()}
            await("Newest disjoint canonical page did not arrive",12000){PocketTranscript.rows.any{it.s("id")=="long-head-new"}&&!PocketTranscript.currentLoading}
            assertTrue("Following reader stayed on an old connected component",device.wait(Until.hasObject(By.text("Fresh response 2")),4000))
            assertTrue("Historical raw bodies were discarded",PocketTranscript.rows.any{it.s("id")=="long-head"})
            assertEquals("Mac freshness draft",prefs.getString("draft:same-thread",null))
            assertEquals(0,qa("state").getJSONArray("replies").length())
        }finally{
            qa("release-history")
            main{Pocket.closeTask();PocketLive.stop()}
        }
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

    @Test fun sharedHeadKeepsWholePageAndExposesMissingMiddleBeforeDiskReopen(){
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
        val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE);val original=prefs.all.toMap()
        fun qa(name:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+name,JSONObject())}
        try{
            prefs.edit().clear().putString("server","http://127.0.0.1:18880")
                .putString("token","synthetic-system76-"+java.util.UUID.randomUUID().toString()).putString("deviceId","synthetic-device-a")
                .putString("draft:same-thread","Unsent across old and new windows").commit()
            main{Pocket.init(context)}
            val destination=requireNotNull(Pocket.captureEnvironment())
            val advertised=runBlocking{Pocket.apiEnvironment(destination,"/api/status")}.getJSONObject("capabilities")
            main{CodexSessionRepository.observeCapabilities(destination,advertised)}
            val hundred=org.json.JSONArray((0 until 100).map{n->JSONObject().put("id","complete-window-"+n).put("kind","message").put("text","Window message "+n)})
            val checkpoint=JSONObject().put("thread",JSONObject().put("id","same-thread"))
                .put("timeline",JSONObject().put("rows",hundred).put("hasEarlier",true).put("before","before-entire-window"))
            CodexSessionRepository.accepted(destination,"same-thread",checkpoint)
            await("Shared cache did not publish the complete page"){CodexSessionRepository.peek("same-thread")?.getJSONObject("timeline")?.getJSONArray("rows")?.length()==100}
            assertEquals("before-entire-window",CodexSessionRepository.peek("same-thread")!!.getJSONObject("timeline").s("before"))
            qa("long-retained-gap")
            val firstHead=runBlocking{CodexSessionRepository.readCurrent(destination,"same-thread")}
            val liveRow=JSONObject(firstHead.getJSONObject("timeline").getJSONArray("rows").objects().first{transcriptContentAnchor(it.s("kind"))}.toString()).put("version",1000)
            CodexSessionRepository.timeline(destination,JSONObject().put("threadId","same-thread").put("turnId",liveRow.s("turnId")).put("version",1000).put("historyHead",firstHead.getJSONObject("historyHead")).put("row",liveRow))
            await("Live overlay did not reach the shared window"){CodexSessionRepository.peek("same-thread")?.getJSONObject("timeline")?.getJSONArray("rows")?.objects()?.any{it.s("id")==liveRow.s("id")}==true}
            assertEquals("A live row falsely became canonical history",100,CodexSessionRepository.peek("same-thread")!!.getJSONObject("timeline").getJSONArray("canonicalIds").length())
            val scope=PocketTranscript.scopeKey()
            main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}
            await("A matching live overlay hid the missing canonical interval",20000){PocketTranscript.currentHistoryVerified&&PocketTranscript.historyGaps.isNotEmpty()&&!PocketTranscript.loading}
            val cache=TranscriptDiskCache(java.io.File(context.noBackupFilesDir,"transcript-cache-v1"))
            await("Canonical gap did not retain the old window boundary",12000){cache.read(scope,"same-thread")?.let{JSONObject(it).optJSONArray("historyGaps")?.objects()?.any{it.s("olderAnchor")=="complete-window-99"}}==true}
            main{Pocket.closeTask();PocketTranscript.release()}
            runBlocking{CodexConversations.storageScope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach{it.join()}}
            val head=runBlocking{CodexSessionRepository.readCurrent(destination,"same-thread")}
            CodexSessionRepository.accepted(destination,"same-thread",head)
            await("Current shared head not ready"){CodexSessionRepository.peek("same-thread")?.getJSONObject("timeline")?.getJSONArray("canonicalIds")?.length()==1}
            val historicalBefore=qa("state").getJSONArray("reads").objects().count{it.optBoolean("older")}
            val old=org.json.JSONArray((0 until 1000).map{n->JSONObject().put("id","retained-old-"+n).put("kind","message").put("turnId","old-turn").put("text","Retained old message "+n)})
            old.put(JSONObject(head.getJSONObject("timeline").getJSONArray("rows").objects().first{transcriptContentAnchor(it.s("kind"))}.toString()))
            val snapshot=JSONObject().put("schema",1).put("scope",scope).put("id","same-thread").put("rows",old)
                .put("detail",JSONObject().put("thread",JSONObject().put("id","same-thread"))).put("earlier",false)
                .put("coverageVerified",true).put("position",JSONObject().put("row","retained-old-500").put("offset",17).put("follow",false))
            assertTrue(TranscriptDiskCache(java.io.File(context.noBackupFilesDir,"transcript-cache-v1")).write(scope,"same-thread",snapshot.toString()))
            main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}
            await("Disk restoration joined old and new windows without a missing interval",20000){PocketTranscript.historyGaps.isNotEmpty()&&PocketTranscript.rows.count{it.s("id").startsWith("retained-old-")}==1000}
            assertTrue("Normal opening restored an obsolete passage instead of latest",device.wait(Until.hasObject(By.text("Fresh response 1")),5000))
            assertFalse("Old reading anchor was restored on an explicit latest open",PocketTranscript.readPosition?.rowId=="retained-old-500")
            await("Independent current head did not settle",20000){PocketTranscript.currentHistoryVerified&&!PocketTranscript.loading}
            val state=qa("state")
            assertTrue("Opening exceeded bounded viewport hydration",state.getJSONArray("reads").objects().count{it.optBoolean("older")}-historicalBefore<=9)
            assertEquals("Unsent across old and new windows",prefs.getString("draft:same-thread",null))
            assertEquals(0,state.getJSONArray("replies").length())
            java.io.File(context.cacheDir,"shared-window-reopen-result.json").writeText(JSONObject()
                .put("completePageRetained",100).put("olderRowsRetained",1000).put("missingMiddleExplicit",true)
                .put("openedAtLatest",true).put("originalCursorRetained",true).put("liveOverlayCannotProveCoverage",true).put("workSubmitted",0).toString())
        }finally{
            main{Pocket.closeTask();PocketLive.stop()}
            val edit=prefs.edit().clear()
            for((key,value)in original)when(value){
                is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value)
                is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)
                is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
            }
            check(edit.commit())
        }
    }
    @Test fun longRetainedGapDoesNotDrainOrResetWhileCurrentMessagesAdvance(){
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
        val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE);val original=prefs.all.toMap()
        fun qa(name:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+name,JSONObject())}
        fun olderReads()=qa("state").getJSONArray("reads").objects().filter{it.optBoolean("older")}
        try{
            prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Retained gap draft").commit()
            main{Pocket.init(context)}
            qa("long-retained-gap")
            val scope=PocketTranscript.scopeKey()
            val cached=org.json.JSONArray()
            repeat(1000){n->cached.put(JSONObject().put("id","retained-old-"+n).put("kind","message").put("turnId","old-turn").put("text","Retained old message "+n))}
            val snapshot=JSONObject().put("schema",2).put("scope",scope).put("id","same-thread").put("rows",cached).put("detail",JSONObject().put("thread",JSONObject().put("id","same-thread"))).put("earlier",false).put("coverageVerified",true).put("confirmedIds",org.json.JSONArray(cached.objects().map{it.s("id")}))
            assertTrue(TranscriptDiskCache(java.io.File(context.noBackupFilesDir,"transcript-cache-v1")).write(scope,"same-thread",snapshot.toString()))
            main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}
            await("Current message never became independently fresh",20000){PocketTranscript.currentHistoryVerified&&PocketTranscript.rows.any{it.s("text")=="Fresh response 1"}}
            await("Bounded initial overlap probe did not settle",20000){PocketTranscript.historyGap?.cursor?.startsWith("long-gap-")==true&&!PocketTranscript.loading}
            Thread.sleep(2500)
            await("Initial viewport hydration did not settle",12000){!PocketTranscript.loading&&!PocketTranscript.updating}
            val initialCursor=requireNotNull(PocketTranscript.historyGap).cursor
            assertFalse("A historical gap kept the current head updating",PocketTranscript.updating)
            assertEquals(1000,PocketTranscript.rows.count{it.s("id").startsWith("retained-old-")})
            val count=olderReads().size;assertTrue("Initial viewport hydration exceeded its budget",count in 1..9)
            qa("silent");main{Pocket.refreshDetail()}
            await("Current head was blocked by retained history",12000){PocketTranscript.rows.any{it.s("text")=="Fresh response 2"}&&!PocketTranscript.currentLoading}
            Thread.sleep(3000)
            assertEquals("A head refresh reset the historical cursor",initialCursor,PocketTranscript.historyGap?.cursor)
            assertEquals("Opening or refreshing eagerly drained historical pages",count,olderReads().size)
            qa("hold-history")
            assertTrue("The current answer has not painted",device.wait(Until.hasObject(By.text("Fresh response 2")),10000))
            val viewport=device.wait(Until.findObject(By.res("conversation-history")),5000)
            assertNotNull("Conversation viewport is absent from native semantics",viewport)
            val bounds=viewport!!.visibleBounds
            device.swipe(bounds.centerX(),(bounds.top+bounds.height()*.25).toInt(),bounds.centerX(),(bounds.top+bounds.height()*.78).toInt(),18)
            await("Thumb scrolling did not request the visible gap",12000){olderReads().size==count+1}
            assertEquals(initialCursor,olderReads().last().s("cursor"))
            qa("silent");main{Pocket.refreshDetail()}
            await("A held historical interval blocked the next current head",12000){PocketTranscript.rows.any{it.s("text")=="Fresh response 3"}&&!PocketTranscript.currentLoading}
            assertEquals("Current refresh rewound an in-flight interval",initialCursor,PocketTranscript.historyGap?.cursor)
            qa("release-history")
            val advancedCursor="long-gap-"+(initialCursor.removePrefix("long-gap-").toInt()-8)
            await("Requested interval did not advance",12000){PocketTranscript.historyGap?.cursor==advancedCursor&&!PocketTranscript.loading}
            Thread.sleep(3000)
            assertEquals("One gesture recursively drained more than one interval",count+1,olderReads().size)
            qa("new-long-window");main{Pocket.refreshDetail()}
            await("A second away interval was hidden or replaced the first",12000){PocketTranscript.rows.any{it.s("text")=="Fresh response 4"}&&PocketTranscript.historyGaps.size==2&&!PocketTranscript.currentLoading}
            assertTrue(PocketTranscript.historyGaps.any{it.cursor==advancedCursor})
            assertTrue(PocketTranscript.historyGaps.any{it.cursor.startsWith("next-gap-")})
            Thread.sleep(2500)
            assertEquals("A second head window restarted automatic history draining",count+1,olderReads().size)
            await("Multiple gap windows were not persisted",12000){
                TranscriptDiskCache(java.io.File(context.noBackupFilesDir,"transcript-cache-v1")).read(scope,"same-thread")?.let{JSONObject(it).optJSONArray("historyGaps")?.length()==2}==true
            }
            main{
                PocketTranscript.rememberPosition(scope,"same-thread",TranscriptReadPosition("long-head-new",0,true))
                Pocket.closeTask();PocketTranscript.release();Pocket.open("same-thread")
            }
            await("Disk reopening lost a retained interval",20000){PocketTranscript.currentHistoryVerified&&PocketTranscript.historyGaps.size==2&&!PocketTranscript.loading}
            assertTrue(PocketTranscript.historyGaps.any{it.cursor==advancedCursor})
            assertTrue(PocketTranscript.historyGaps.any{it.cursor.startsWith("next-gap-")})
            assertEquals(1000,PocketTranscript.rows.count{it.s("id").startsWith("retained-old-")})
            assertEquals("Retained gap draft",prefs.getString("draft:same-thread",null))
            assertEquals(0,qa("state").getJSONArray("replies").length())
            java.io.File(context.cacheDir,"long-retained-gap-result.json").writeText(JSONObject().put("retainedRows",1000).put("initialHistoricalReads",1).put("gestureHistoricalReads",1).put("headRefreshDidNotResetGap",true).put("currentAdvancedWhileGapHeld",true).put("twoIndependentGapsRestoredFromDisk",true).put("workSubmitted",0).toString())
        }finally{
            runCatching{qa("release-history")}
            main{Pocket.closeTask();PocketLive.stop()}
            val edit=prefs.edit().clear()
            for((key,value)in original)when(value){
                is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value)
                is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)
                is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
            }
            check(edit.commit())
        }
    }

}
