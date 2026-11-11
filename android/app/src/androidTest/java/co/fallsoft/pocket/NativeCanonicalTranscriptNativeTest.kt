package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
    private fun feedbackEvidence()=org.json.JSONArray(WorkSourceActions.feedback.values.map{action->
        JSONObject().put("key",action.key).put("source",action.source).put("title",action.title)
            .put("operation",action.operation).put("pending",action.pending).put("error",action.error?:JSONObject.NULL)
    })
    /** A live-region update can leave an old text node in UiAutomation's cache
     * even while a new sibling and the rendered pixels are current. Read the
     * actual accessibility tree before judging progress/refusal visibility.
     * This is observation only: it never repeats the archive or dismiss action.
     */
    private fun freshArchiveNode(selector:BySelector):UiObject2?{
        if(android.os.Build.VERSION.SDK_INT>=34)
            assertTrue("Accessibility observation cache did not clear",instrumentation.uiAutomation.clearCache())
        return device.findObject(selector)
    }
    private fun await(message:String,timeout:Long=15000,condition:()->Boolean){
        val end=android.os.SystemClock.elapsedRealtime()+timeout
        var passed=condition()
        while(!passed&&android.os.SystemClock.elapsedRealtime()<end){Thread.sleep(30);passed=condition()}
        val feedback=if(passed)null else feedbackEvidence()
        if(!passed){
            // Capture the failed observation, not the earlier model transition.
            // Keep the original polling/cache behavior while diagnosing the cause.
            val folder=context.getExternalFilesDir(null)?:context.cacheDir
            runCatching{device.dumpWindowHierarchy(java.io.File(folder,"archive-feedback-native-failure.xml"))}
            runCatching{device.takeScreenshot(java.io.File(folder,"archive-feedback-native-failure.png"))}
            runCatching{java.io.File(folder,"archive-feedback-native-failure.json").writeText(JSONObject()
                .put("assertion",message).put("feedback",feedback).put("selected",Pocket.selected?:JSONObject.NULL)
                .put("environment",Pocket.environmentId).put("appError",Pocket.error).toString(2))}
        }
        val diagnostics=if(passed)"" else " [selected="+Pocket.selected+", native="+PocketTranscript.canonicalMode+", verified="+PocketTranscript.currentHistoryVerified+", updating="+PocketTranscript.updating+", loading="+PocketTranscript.loading+", rows="+PocketTranscript.rows.size+", error="+PocketTranscript.readError+", appError="+Pocket.error+", feedback="+feedback+", reads="+runCatching{qa("state").optJSONArray("reads")?.length()}.getOrNull()+"]"
        assertTrue(message+diagnostics,passed)
    }
    /** Observe a stable enabled action target, then tap once. No action retry. */
    private fun skipBounds():android.graphics.Rect{
        val end=android.os.SystemClock.elapsedRealtime()+8000
        var last:android.graphics.Rect?=null;var stable=0
        while(android.os.SystemClock.elapsedRealtime()<end){
            val bounds=try{
                var node=device.findObject(By.text("Skip"))?:device.findObject(By.text("Salta"))
                while(node!=null&&!node.isClickable)node=node.parent
                node?.takeIf{it.isEnabled}?.visibleBounds?.takeIf{it.width()>0&&it.height()>0}?.let{android.graphics.Rect(it)}
            }catch(_:StaleObjectException){null}
            if(bounds!=null&&bounds==last)stable++ else stable=0
            if(stable>=2)return requireNotNull(bounds)
            last=bounds;Thread.sleep(50)
        }
        throw AssertionError("Question Skip did not become a stable enabled action")
    }
    private fun prepare(){
        requireOwnedNativeTarget(device)
        context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
            .putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76-"+java.util.UUID.randomUUID())
            .putString("deviceId","synthetic-device-a").putString("draft:same-thread","Native unsent draft").commit()
        main{Pocket.init(context)}
    }
    private fun qa(action:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+action,JSONObject())}
    private fun open(){main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}}
    @Test fun archiveFromListKeepsDraftAndRestoresWithoutOpeningOrSending(){
        prepare()
        main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.closeTask();Pocket.showArchived=false}
        await("Visible current card"){device.hasObject(By.desc("Open Chat for Native canonical work"))}
        assertFalse("Archive must be hidden before swiping",device.hasObject(By.text("Archive")))
        val bounds=device.findObject(By.desc("Open Chat for Native canonical work")).visibleBounds
        device.swipe(bounds.right-8,bounds.centerY(),bounds.left+8,bounds.centerY(),20)
        await("Swipe reveals archive"){device.hasObject(By.text("Archive"))}
        assertEquals("Swipe alone cannot archive",0,qa("state").getInt("archiveCalls"))
        assertNull("Swipe cannot open chat",Pocket.selected)
        device.findObject(By.text("Archive")).click()
        await("Card leaves current catalog"){Pocket.tasks.none{it.id=="same-thread"}}
        assertEquals(1,qa("state").getInt("archiveCalls"));assertNull(Pocket.selected)
        assertEquals("Native unsent draft",Pocket.prefs.getString("draft:same-thread",null))
        main{Pocket.showArchived=true;UnifiedWorkCatalog.refresh()}
        await("Archived card restores"){device.hasObject(By.desc("Restore conversation"))}
        device.findObject(By.desc("Restore conversation")).click()
        await("Restored card leaves archive"){!device.hasObject(By.desc("Restore conversation"))}
        main{Pocket.showArchived=false;UnifiedWorkCatalog.refresh()}
        await("Restored current card"){device.hasObject(By.desc("Open Chat for Native canonical work"))}
        assertFalse("Restore closes swipe action",device.hasObject(By.text("Archive")))
        val evidence=qa("state");assertEquals(1,evidence.getInt("archiveCalls"));assertEquals(1,evidence.getInt("restoreCalls"));assertEquals(0,evidence.getInt("workSubmissions"))
        assertEquals("Native unsent draft",Pocket.prefs.getString("draft:same-thread",null));assertNull(Pocket.selected)
    }
    @Test fun foreignArchiveProgressAndRefusalStayVisibleWithoutRetargeting(){
        prepare()
        val profile=EnvironmentIdentity("qa_mac_archive","MacBook QA")
        val destination=EnvironmentRequest(profile,"http://localhost:18880","synthetic-system76-mac-archive")
        main{
            Pocket.prefs.edit().putString("environments:v1",org.json.JSONArray().put(JSONObject().put("id",profile.id).put("name",profile.name)).toString())
                .putString(profile.key("server"),destination.endpoint).putString(profile.key("token"),destination.token).commit()
            WorkSourceActions.feedback.clear()
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.closeTask()
        }
        qa("archive-hold")
        val source=WorkSourceKey(profile.id,backendOwner(destination.endpoint,destination.token))
        val entry=WorkEntry(WorkItemKey(source,WorkKind.SESSION,"","same-thread"),1L,
            WorkCatalogPayload(destination,JSONObject().put("id","same-thread").put("name","Mac archive test").toString()))
        main{WorkSourceActions.actions.archive(entry);WorkSourceActions.actions.archive(entry)}
        await("Visible archive progress"){freshArchiveNode(By.text("Archiving…"))!=null}
        assertEquals("Repeated activation must not dispatch twice",1,qa("state").getInt("archiveCalls"))
        assertEquals("workstation",Pocket.environmentId)
        qa("archive-refuse")
        await("Archive refusal recorded"){WorkSourceActions.feedback.values.any{it.error!=null}}
        device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null),"archive-feedback-native.xml"))
        android.util.Log.i("ArchiveQA",feedbackEvidence().toString())
        await("Refusal visible above footer"){freshArchiveNode(By.textContains("Close this session in its Mac terminal"))!=null}
        assertEquals("Close this session in its Mac terminal before archiving.",WorkSourceActions.feedback.values.single().error)
        val bounds=requireNotNull(freshArchiveNode(By.textContains("Close this session in its Mac terminal"))).visibleBounds
        assertTrue("Action feedback must remain at bottom of viewport",bounds.centerY()>device.displayHeight/2)
        device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null),"archive-feedback-native-fresh.xml"))
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null),"archive-feedback-native-fresh.png"))
        assertEquals("synthetic-system76-mac-archive",qa("state").getJSONArray("archiveOrigins").getString(0).removePrefix("Bearer "))
        assertFalse(qa("state").getBoolean("archived"));assertEquals(0,qa("state").getInt("workSubmissions"))
        requireNotNull(freshArchiveNode(By.text("Dismiss"))).click()
        await("Dismiss clears feedback"){WorkSourceActions.feedback.isEmpty()}
        assertEquals(1,qa("state").getInt("archiveCalls"));assertEquals("workstation",Pocket.environmentId)
    }
    @Test fun retiredRoomSelectionOpensStockWorkWithoutReplayingOrLosingDrafts(){
        prepare()
        val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
        val owner=backendOwner(Pocket.base,Pocket.token)
        prefs.edit().putString("backend:$owner","lax")
            .putString("retained-room-draft","Original room words")
            .putString("retained-room-command","unknown-original-id").commit()
        main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        await("Upgrade did not select Codex"){prefs.getString("backend:$owner",null)=="codex"}
        assertTrue("Stock session was not discoverable",device.wait(Until.hasObject(By.text("Native canonical work")),12000))
        assertFalse(device.hasObject(By.text("Connecting to Hollywood…")))
        main{Pocket.open("same-thread")}
        await("Stock native history did not load"){PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating}
        assertTrue(device.wait(Until.hasObject(By.text("Native answer one")),5000))
        assertEquals("Original room words",prefs.getString("retained-room-draft",null))
        assertEquals("unknown-original-id",prefs.getString("retained-room-command",null))
        assertEquals(0,qa("state").getInt("workSubmissions"))
        assertFalse(BackendNavigation.nativeSelected());assertFalse(BackendNavigation.teamSelected())
    }

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
            val stamp=messageTime(1794017100000L+5*60000)!!
            assertTrue("Agent timestamp not rendered with local time",device.wait(Until.hasObject(By.desc(stamp.full)),4000))
            assertEquals(1794017100000L+5*60000,PocketTranscript.rows.first{it.s("text")=="Native answer two"}.getLong("startedAtMs"))
            assertTrue("Native answer painted above its input",user!!.visibleBounds.bottom<=answer!!.visibleBounds.top)
            qa("question")
            assertTrue("Native pending question not actionable",device.wait(Until.hasObject(By.text("Native pending question")),8000))
            val skip=skipBounds()
            device.click(skip.centerX(),skip.centerY())
            await("Question answer was not routed"){qa("state").optInt("answers")==1}
            val completedBefore=((UxMeasurements.snapshot()["counts"] as Map<*,*>)["open_complete"] as? Number)?.toLong()?:0L
            qa("disconnect")
            main{Pocket.closeTask()}
            qa("recover")
            open()
            await("Reopen did not restore canonical native history"){PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating&&PocketTranscript.rows.any{it.s("text")=="Native answer two"}}
            assertTrue("Fresh canonical content was not visible",device.wait(Until.hasObject(By.text("Native answer two")),4000))
            await("Visible canonical reopen did not emit first/fresh UX milestones"){
                val report=UxMeasurements.snapshot();val metrics=report["metrics"] as Map<*,*>;val counts=report["counts"] as Map<*,*>
                (counts["open_complete"] as? Number)?.toLong()?.let{it>completedBefore}==true&&
                    metrics.containsKey("open_first_content_layout")&&metrics.containsKey("open_fresh_content_layout")
            }
            assertFalse("Fresh open left its measurement observer active",CodexConversations.hasOpenMeasurement(CodexConversations.currentKey,CodexConversations.incarnation))
            context.filesDir.resolve("ux-native-history.json").writeText(JSONObject(UxMeasurements.snapshot()).toString(2))
            assertEquals(1,PocketTranscript.rows.count{it.s("text")=="Native request two"})
            // The fixture captures the baseline atomically with the stimulus.
            // A reconnect/reopen read before this boundary is not a token read.
            val beforeStream=qa("stream-unpositioned").getInt("readBaseline")
            await("Synthetic native stream did not finish"){qa("state").optInt("streamsSent")>=20}
            Thread.sleep(300)
            assertTrue("Unpositioned token deltas caused repeated native reads",qa("state").getJSONArray("reads").length()-beforeStream<=2)
            assertFalse("Unpositioned stream was painted as canonical history",device.hasObject(By.text("Native stream answer")))
            qa("stream-finish")
            await("Completed native stream did not receive canonical position"){PocketTranscript.rows.any{it.s("text")=="Native stream answer"}&&!PocketTranscript.updating}
            val streamRows=PocketTranscript.rows
            assertTrue(streamRows.indexOfFirst{it.s("text")=="Native stream request"}<streamRows.indexOfFirst{it.s("text")=="Native stream answer"})
            device.pressHome()
            await("Backgrounded conversation retained a viewport"){CodexConversations.currentKey==null}
            val retiredIncarnation=CodexConversations.incarnation
            main{CodexConversations.reconnected()}
            assertNull("Reconnect recreated an invisible conversation viewport",CodexConversations.currentKey)
            assertEquals(retiredIncarnation,CodexConversations.incarnation)
            open()
            await("Foreground did not restore the canonical conversation"){PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating&&PocketTranscript.rows.any{it.s("text")=="Native stream answer"}}
            val state=qa("state")
            assertEquals(0,state.getInt("legacyReads"));assertEquals(0,state.getInt("positionReads"));assertEquals(0,state.getInt("workSubmissions"))
            assertEquals("Native unsent draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
        }catch(error:Throwable){
            runCatching{device.takeScreenshot(java.io.File(context.cacheDir,"native-question-failure.png"))}
            runCatching{device.dumpWindowHierarchy(java.io.File(context.cacheDir,"native-question-failure.xml"))}
            throw error
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
    @Test fun backgroundHistoryEligibilityPreservesExplicitReadsAndRecovery(){
        requireOwnedNativeTarget(device)
        val token="synthetic-system76-"+java.util.UUID.randomUUID()+"-alpha"
        val endpoint="http://127.0.0.1:18880"
        val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
        prefs.edit().clear().putString("server",endpoint).putString("token",token).putString("deviceId","synthetic-device-a")
            .putString("environment:history_beta:server",endpoint).putString("environment:history_beta:token",token.removeSuffix("alpha")+"beta")
            .putString("environment:history_beta:deviceId","synthetic-device-b")
            .putString("environments:v1",org.json.JSONArray().put(JSONObject().put("id","workstation").put("name","Alpha")).put(JSONObject().put("id","history_beta").put("name","Beta")).toString())
            .putString("draft:archive-history","Retained archive draft").commit()
        val owner=transcriptCacheOwner("workstation",endpoint,token)
        val retained="""{"windowSchema":2,"thread":{"id":"dormant-history"},"timeline":{"rows":[{"id":"retained","kind":"message","text":"Retained old content"}],"canonicalIds":["retained"],"hasEarlier":false}}"""
        // Seed an old dirty cache row in this disposable pairing before activation.
        // This exercises SQLite restoration, not a claimed OS process restart.
        context.openOrCreateDatabase("codex-session-messages-v1.sqlite",Context.MODE_PRIVATE,null).use{db->
            db.execSQL("CREATE TABLE IF NOT EXISTS recent(scope TEXT NOT NULL,thread TEXT NOT NULL,body TEXT,notification INTEGER NOT NULL DEFAULT 0,dirty INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,thread))")
            if(db.version==0)db.version=1
            db.execSQL("INSERT OR REPLACE INTO recent(scope,thread,body,dirty,updated) VALUES(?,?,?,1,?)",arrayOf<Any>(owner,"dormant-history",retained,System.currentTimeMillis()))
        }
        main{Pocket.init(context)}
        val destination=requireNotNull(Pocket.captureEnvironment())
        fun control(id:String?=null,code:String?=null,extra:JSONObject=JSONObject()):JSONObject=runBlocking{
            if(id!=null)extra.put("id",id).put("code",code?:JSONObject.NULL)
            Pocket.apiEnvironment(destination,"/_qa/history-lifecycle",extra)
        }
        fun reads(id:String,origin:String="A")=control().getJSONArray("reads").objects().filter{it.s("id")==id&&it.s("origin")==origin}
        fun refusal(id:String):JSONObject?=context.openOrCreateDatabase("codex-session-messages-v1.sqlite",Context.MODE_PRIVATE,null).use{db->
            if(db.version<2)return@use null
            db.rawQuery("SELECT code,retry_at FROM background_refusal WHERE scope=? AND thread=?",arrayOf(owner,id)).use{cursor->
                if(cursor.moveToFirst())JSONObject().put("code",cursor.getString(0)).put("retryAt",cursor.getLong(1))else null
            }
        }
        fun catalog()=runBlocking{CodexCatalog.source(destination).readPage("history-lifecycle")}
        fun foreground(id:String)=runBlocking{CodexSessionRepository.readCurrent(destination,id)}
        try{
        control("durable-history","THREAD_HISTORY_UNAVAILABLE",JSONObject().put("status","notLoaded"))
        control("ephemeral-history","THREAD_EPHEMERAL_UNAVAILABLE",JSONObject().put("ephemeral",true).put("status","notLoaded"))
        control("healthy-history")
        val capabilities=runBlocking{Pocket.apiEnvironment(destination,"/api/status")}.getJSONObject("capabilities")
        main{CodexSessionRepository.observeCapabilities(destination,capabilities);CodexSessionRepository.activate()}
        catalog()
        await("Typed refusals and positive control did not settle"){
            refusal("durable-history")!=null&&refusal("ephemeral-history")!=null&&reads("healthy-history").any{it.optBoolean("settled")}
        }
        assertTrue("Restoration admitted historical dirty cache",reads("dormant-history").isEmpty())
        val durableBefore=reads("durable-history").size;val ephemeralBefore=reads("ephemeral-history").size
        main{CodexConversations.reconnected();CodexSessionRepository.observeCapabilities(destination,null);CodexSessionRepository.observeCapabilities(destination,capabilities)}
        catalog();control("healthy-history",extra=JSONObject().put("revision",2));catalog()
        await("Eligible current conversation stopped refreshing"){reads("healthy-history").count{it.optBoolean("settled")}>1}
        assertEquals("Reconnect retried unchanged durable failure before backoff",durableBefore,reads("durable-history").size)
        assertEquals("Reconnect retried unavailable ephemeral history",ephemeralBefore,reads("ephemeral-history").size)
        assertTrue(reads("dormant-history").isEmpty())
        val other=requireNotNull(Pocket.captureEnvironment("history_beta"))
        assertEquals("Source B could not read its same-ID history",1,runBlocking{CodexSessionRepository.readCurrent(other,"durable-history")}.getJSONObject("timeline").getInt("native"))
        assertNotNull("Source B cleared source A's refusal",refusal("durable-history"))
        // Availability recovers without changing status/path/timestamps. Only an
        // existing catalog observation after the persisted backoff may re-admit it.
        control("durable-history")
        val retryAt=requireNotNull(refusal("durable-history")).getLong("retryAt")
        while(System.currentTimeMillis()<retryAt)Thread.sleep(minOf(250,retryAt-System.currentTimeMillis()).coerceAtLeast(1))
        catalog()
        await("Unchanged durable metadata never recovered after bounded re-admission"){refusal("durable-history")==null&&reads("durable-history").size>durableBefore}
        assertEquals("Durable retry also woke ephemeral history",ephemeralBefore,reads("ephemeral-history").size)
        control("ephemeral-history")
        assertEquals(1,foreground("ephemeral-history").getJSONObject("timeline").getInt("native"))
        assertNull("Successful explicit read did not release ephemeral suspension",refusal("ephemeral-history"))
        control("wrong-envelope","THREAD_HISTORY_UNAVAILABLE",JSONObject().put("wrongIdentity",true).put("catalog",false))
        val wrong=runCatching{foreground("wrong-envelope")}.exceptionOrNull()
        assertTrue("Wrong-thread envelope retained typed authority",wrong is PocketApiException&&wrong.code==null)
        assertNull(refusal("wrong-envelope"))
        // Only a complete current catalog can withdraw absent membership; the
        // repeated partial search pages above never erased its other entries.
        control("healthy-history",extra=JSONObject().put("catalog",false))
        runBlocking{CodexCatalog.source(destination).head()}
        val removedCount=reads("healthy-history").size
        main{CodexSessionRepository.invalidate("healthy-history",destination);CodexConversations.reconnected()}
        control("durable-history",extra=JSONObject().put("revision",2));catalog()
        await("Full-catalog withdrawal stopped other current work"){reads("durable-history").size>durableBefore+1}
        assertEquals("Removed entry re-entered background queue",removedCount,reads("healthy-history").size)
        assertEquals(1,foreground("healthy-history").getJSONObject("timeline").getInt("native"))
        control("healthy-history",extra=JSONObject().put("catalog",true).put("revision",3));catalog()

        // A deliberate reinspection joins an old held read. Its late refusal
        // must not consume the newer observation or poison the follow-up read.
        val inspectionScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
        try{
            control("empty-reopen","THREAD_NOT_MATERIALIZED",JSONObject().put("catalog",false).put("holdNative",true))
            val old=inspectionScope.async{runCatching{foreground("empty-reopen")}}
            await("Initial empty-session read was not held"){reads("empty-reopen").size==1}
            val joinedOldRead=kotlinx.coroutines.CompletableDeferred<CodexSessionRepository.CurrentReadJoin>()
            CodexSessionRepository.currentReadJoinObserverForTest={join->
                if(join.scope==owner&&join.threadId=="empty-reopen"&&join.requestedInspection>join.requestInspection)joinedOldRead.complete(join)
            }
            main{CodexSessionRepository.inspectEmptyRead("empty-reopen",destination)}
            val reopened=inspectionScope.async{CodexSessionRepository.readCurrent(destination,"empty-reopen")}
            // This barrier is emitted only by the actual requestMutex reuse
            // branch, after the new inspection has selected the old deferred.
            val join=runBlocking{kotlinx.coroutines.withTimeout(5000){joinedOldRead.await()}}
            assertEquals(owner,join.scope);assertEquals("empty-reopen",join.threadId)
            assertTrue(join.requestedInspection>join.requestInspection)
            assertFalse("Held original finished before join barrier",old.isCompleted)
            assertEquals("Reopen bypassed the shared pending request",1,reads("empty-reopen").size)
            control("empty-reopen",extra=JSONObject().put("holdNative",false))
            val original=runBlocking{kotlinx.coroutines.withTimeout(5000){old.await()}}.exceptionOrNull()
            assertTrue("Original held read did not return typed initial-empty refusal",original is PocketApiException&&original.status==409&&original.code=="THREAD_NOT_MATERIALIZED")
            val recovered=runBlocking{kotlinx.coroutines.withTimeout(5000){reopened.await()}}
            assertEquals("empty-reopen",recovered.getJSONObject("thread").getString("id"))
            assertEquals(1,recovered.getJSONObject("timeline").getInt("native"))
            assertEquals("Reopen did not make exactly one new inspection",2,reads("empty-reopen").size)
            assertNull("Late empty-session refusal survived reinspection",refusal("empty-reopen"))

            // Native progress alone, with unchanged catalog and notification,
            // must clear initial-empty availability before queue admission.
            control("empty-progress","THREAD_NOT_MATERIALIZED")
            catalog();await("Initial empty refusal was not persisted"){refusal("empty-progress")!=null}
            val emptyReads=reads("empty-progress").size
            control("empty-progress",extra=JSONObject().put("holdNative",true))
            val beforeCatalog=control(extra=JSONObject().put("holdCatalog",true)).getInt("catalogReads")
            main{CodexSessionRepository.timeline(destination,JSONObject().put("threadId","empty-progress")
                .put("nativeMethod","item/completed").put("turnId","new-turn").put("nativeItemId","new-item").put("version",1))}
            await("Native progress did not admit a repair"){reads("empty-progress").size==emptyReads+1}
            // Hold the catalog across native preview publication: a merged
            // local preview must not feed back as fresh server work evidence.
            val overlappingCatalog=inspectionScope.async{catalog()}
            await("Overlapping catalog did not reach the source"){control().getInt("catalogReads")>beforeCatalog}
            control("empty-progress",extra=JSONObject().put("holdNative",false))
            await("Native repair did not publish its preview"){
                refusal("empty-progress")==null&&CodexCatalog.source(destination).tasks.any{it.id=="empty-progress"&&it.preview=="Native answer one"}
            }
            control(extra=JSONObject().put("holdCatalog",false))
            runBlocking{kotlinx.coroutines.withTimeout(5000){overlappingCatalog.await()}}
            catalog()
            Thread.sleep(500) // Allow the existing120ms background admission lane to run.
            assertEquals("Unchanged state repeated initial-empty repair",emptyReads+1,reads("empty-progress").size)
            assertEquals("Retained archive draft",prefs.getString("draft:archive-history",null))
            context.openOrCreateDatabase("codex-session-messages-v1.sqlite",Context.MODE_PRIVATE,null).use{db->
                db.rawQuery("SELECT body FROM recent WHERE scope=? AND thread=?",arrayOf(owner,"dormant-history")).use{cursor->assertTrue(cursor.moveToFirst());assertEquals(retained,cursor.getString(0))}
            }
            assertEquals(0,control().getInt("workSubmissions"))
        }finally{CodexSessionRepository.currentReadJoinObserverForTest=null;inspectionScope.cancel()}

        // Hold one background archive refusal; an explicit reader joins that
        // exact single flight, then alone receives the archived /head fallback.
        control("archive-history","THREAD_ARCHIVED",JSONObject().put("holdNative",true))
        catalog();await("Archive background request did not enter"){reads("archive-history").size==1}
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
        try{
            val joined=scope.async(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED){CodexSessionRepository.readCurrent(destination,"archive-history")}
            control("archive-history","THREAD_ARCHIVED",JSONObject().put("holdNative",false))
            val archive=runBlocking{joined.await()}
            assertEquals("Archived retained response",archive.getJSONObject("timeline").getJSONArray("rows").getJSONObject(0).s("text"))
            assertEquals("Shared reader submitted duplicate native read",1,reads("archive-history").count{it.s("path").endsWith("/native-timeline")})
            assertEquals("Explicit read did not use one archive fallback",1,reads("archive-history").count{it.s("path").endsWith("/head")})
            val beforeCursor=reads("archive-history").size
            assertTrue(runCatching{runBlocking{CodexSessionRepository.readOlder(destination,"archive-history","native-local:obsolete")}}.isFailure)
            assertEquals("Native cursor leaked into archived history",beforeCursor,reads("archive-history").size)
            assertEquals("Archived earlier response",runBlocking{CodexSessionRepository.readOlder(destination,"archive-history","archive-earlier")}.getJSONObject("timeline").getJSONArray("rows").getJSONObject(0).s("text"))

            // Subsequent archived heads have the same ownership requirement as
            // the initial fallback, even when a new native head becomes eligible.
            control("archive-history","THREAD_ARCHIVED",JSONObject().put("holdHead",true))
            val heldHead=scope.async{runCatching{CodexSessionRepository.readCurrent(destination,"archive-history")}}
            await("Subsequent archived head was not held"){reads("archive-history").any{it.s("path").endsWith("/head")&&!it.optBoolean("settled")}}
            control("archive-history",extra=JSONObject().put("archived",false))
            main{CodexCatalog.archiveChanged(destination,"archive-history",false)}
            await("Unarchive did not restore native protocol"){CodexSessionRepository.nativeEnabled(destination,"archive-history")}
            control("archive-history",extra=JSONObject().put("holdHead",false))
            assertTrue("Late archived head survived unarchive",runBlocking{heldHead.await()}.isFailure)
            assertEquals(1,foreground("archive-history").getJSONObject("timeline").getInt("native"))
            control("archive-history","THREAD_ARCHIVED",JSONObject().put("archived",true))
            main{CodexCatalog.archiveChanged(destination,"archive-history",true)}
            foreground("archive-history")

            // A late archived older page cannot overwrite an unarchived native head.
            control("archive-history","THREAD_ARCHIVED",JSONObject().put("holdHead",true))
            val heldOlder=scope.async{runCatching{CodexSessionRepository.readOlder(destination,"archive-history","archive-earlier")}}
            await("Older archive read was not held"){reads("archive-history").any{it.s("cursor")=="archive-earlier"&&!it.optBoolean("settled")}}
            control("archive-history",extra=JSONObject().put("archived",false))
            main{CodexCatalog.archiveChanged(destination,"archive-history",false)}
            await("Unarchive did not re-admit native history"){CodexSessionRepository.nativeEnabled(destination,"archive-history")&&reads("archive-history").count{it.s("path").endsWith("/native-timeline")}>1}
            control("archive-history",extra=JSONObject().put("holdHead",false))
            assertTrue("Late legacy page survived unarchive",runBlocking{heldOlder.await()}.isFailure)
            assertTrue(CodexSessionRepository.nativeEnabled(destination,"archive-history"))

            // An archive/unarchive ABA with identical catalog fields must reject
            // the old refusal; source identity alone cannot detect that race.
            control("race-history","THREAD_HISTORY_UNAVAILABLE",JSONObject().put("holdNative",true))
            catalog();await("Old refusal was not held"){reads("race-history").any{!it.optBoolean("settled")}}
            main{CodexCatalog.archiveChanged(destination,"race-history",true);CodexCatalog.archiveChanged(destination,"race-history",false)}
            control("race-history",extra=JSONObject().put("holdNative",false))
            await("New native read after archive cycle did not settle"){reads("race-history").count{it.optBoolean("settled")}>1}
            assertNull("Old refusal survived archive/unarchive cycle",refusal("race-history"))
        }finally{scope.cancel()}
        control("archive-history","THREAD_ARCHIVED",JSONObject().put("archived",true))
        main{CodexCatalog.archiveChanged(destination,"archive-history",true)}
        val archivedCount=reads("archive-history").size
        val healthyCount=reads("healthy-history").size
        runBlocking{CodexCatalog.source(destination,true).readPage("history-lifecycle")}
        main{CodexSessionRepository.activate()}
        control("healthy-history",extra=JSONObject().put("revision",4));catalog()
        await("Archive withdrawal blocked healthy work"){reads("healthy-history").count{it.optBoolean("settled")}>healthyCount}
        assertEquals("Archive catalog/reconnect scheduled history",archivedCount,reads("archive-history").size)
        main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("archive-history")}
            await("Explicit archived open did not render retained history"){PocketTranscript.currentHistoryVerified&&PocketTranscript.rows.any{it.s("text")=="Archived retained response"}}
            assertTrue(device.wait(Until.hasObject(By.text("Archived retained response")),4000))
            assertEquals("Retained archive draft",prefs.getString("draft:archive-history",null))
            context.openOrCreateDatabase("codex-session-messages-v1.sqlite",Context.MODE_PRIVATE,null).use{db->
                db.rawQuery("SELECT body FROM recent WHERE scope=? AND thread=?",arrayOf(owner,"dormant-history")).use{cursor->assertTrue(cursor.moveToFirst());assertEquals("Historical body was deleted",retained,cursor.getString(0))}
            }
            val evidence=control();assertEquals(0,evidence.getInt("workSubmissions"))
            context.filesDir.resolve("background-history-lifecycle.json").writeText(evidence.put("scope","Seeded SQLite restore and actual repository/catalog HTTP lifecycle; not an OS process restart").toString(2))
        }catch(failure:Throwable){
            runCatching{context.filesDir.resolve("background-history-lifecycle-failure.json").writeText(control().toString(2))}
            runCatching{device.takeScreenshot(context.filesDir.resolve("background-history-lifecycle-failure.png"))}
            throw failure
        }finally{
            runCatching{control(extra=JSONObject().put("releaseHolds",true))}
            main{Pocket.closeTask();PocketLive.stop()}
        }
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
