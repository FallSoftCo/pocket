package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

object PocketTranscript {
    private var operationScope:CoroutineScope?=null
    private val readScope get()=requireNotNull(operationScope){"No selected conversation read owner"}
    var rows by mutableStateOf(listOf<JSONObject>());private set
    private val reads=TranscriptReadState()
    val loading get()=reads.loading
    private var latestReadError by mutableStateOf("")
    private var historicalReadError by mutableStateOf("")
    private var positionReadError by mutableStateOf("")
    val readError get()=latestReadError.ifBlank{historicalReadError}.ifBlank{positionReadError}
    val refreshDelayed get()=latestReadError.isNotBlank()&&automaticRetryAllowed
    private var readFailureSince=0L
    var refreshPending by mutableStateOf(false);private set
    private var positioning by mutableStateOf(false)
    val currentLoading get()=reads.latestLoading
    val currentReadError get()=latestReadError.ifBlank{positionReadError}
    val updating get()=positionRestorePending||(!freshForRead&&cacheRestoring)||refreshPending||reads.latestLoading||positioning
    internal val historyGaps:List<TranscriptHistoryGap> get(){
        revision
        return gapWindows.map{TranscriptHistoryGap(it.id,it.cursor,it.beforeRow,reads.gapLoading&&gapReadId==it.id,gapProblems[it.id].orEmpty().ifBlank{if(it.exhausted)"Saved messages are separated from the available history." else ""})}
    }
    internal val historyGap get()=historyGaps.lastOrNull()
    fun markRefreshPending(){
        if(owner==Pocket.selected&&ownerScope==scopeKey()){
            refreshPending=true
            // The scheduler coalesces requests while its read is active. Retain
            // an update arriving after that read began for one follow-up read.
            if(reads.latestLoading)missedUpdates=true
        }
    }
    var automaticRetryAllowed by mutableStateOf(true);private set
    var earlier by mutableStateOf(false);private set
    var before:String?=null;private set
    var revision by mutableIntStateOf(0);private set
    private val buffered=linkedMapOf<String,JSONObject>()
    private var generation=0
    private var gapWindows by mutableStateOf(emptyList<TranscriptGapWindow>())
    private var gapReadId by mutableStateOf<String?>(null)
    private var gapProblems by mutableStateOf(emptyMap<String,String>())
    private var initialGapProbe:String?=null
    private var initialGapProbeUsed=false
    // Legacy presentation fields remain readable by older cache decoders.
    private val bridgeBefore get()=gapWindows.lastOrNull()?.cursor
    private val bridgeKnown get()=gapWindows.lastOrNull()?.olderAnchor?.let{setOf(it)}.orEmpty()
    private val bridgeBoundary get()=gapWindows.lastOrNull()?.beforeRow
    private fun recordWindow(retained:List<JSONObject>,incoming:List<JSONObject>,page:JSONObject,unknownCoverage:Boolean=false){
        missingTranscriptWindow(retained,incoming,page,if(unknownCoverage)emptyList()else gapWindows,unknownCoverage)?.let{gap->
            val existing=gapWindows.firstOrNull{it.beforeRow==gap.beforeRow||it.cursor==gap.cursor}
            if(existing==null){
                gapWindows=gapWindows+gap
                if(!initialGapProbeUsed)initialGapProbe=gap.id
            }else if(unknownCoverage){
                gapWindows=gapWindows.map{if(it.id==existing.id)it.copy(olderAnchor=gap.olderAnchor,unverified=it.unverified+gap.unverified)else it}
                if(!initialGapProbeUsed)initialGapProbe=existing.id
            }
        }
    }
    private fun followNewWindow(previous:List<JSONObject>,incoming:List<JSONObject>){
        if(!openingAtLatest||readerIntentRevision!=cacheRestoreIntent)return
        val previousTail=previous.lastOrNull{transcriptContentAnchor(it.s("kind"))}?.s("id")
        val incomingTail=incoming.lastOrNull{transcriptContentAnchor(it.s("kind"))}?.s("id")
        if(incomingTail!=null&&incomingTail!=previousTail){
            preferLatestOnOpen=true
            if(readPosition!=null||positionRestorePending){readPosition=null;positionRestorePending=false;cacheRestoreRevision++}
        }
    }
    private fun probeInitialGap(){
        if(!initialGapProbeUsed&&!reads.historyLoading&&!refreshPending&&automaticRetryAllowed)
            gapWindows.firstOrNull{it.id==initialGapProbe}?.let{CodexConversations.continueHistory(it.cursor)}
    }
    private var coverageVerified=false
    private var missedUpdates=false
    private data class InputPosition(val turn:String,val target:String,val item:String,val client:String,val known:List<String>,val evidence:Long)
    private val positionQueue=linkedMapOf<String,InputPosition>()
    private var positionJob:Job?=null
    private var positionHistoryRevision=0L
    private data class InputAttempt(val target:String,val item:String)
    private val attemptedPositions=hashSetOf<InputAttempt>()
    private val awaitingPositionHistory=hashSetOf<InputAttempt>()
    private val positionFailures=linkedMapOf<String,String>()
    var browsingEarlier by mutableStateOf(false);private set
    internal var readPosition by mutableStateOf<TranscriptReadPosition?>(null);private set
    private data class Snapshot(val rows:List<JSONObject>,val detail:JSONObject,val before:String?,val earlier:Boolean,val bridgeBefore:String?,val position:TranscriptReadPosition?,val bridgeKnown:Set<String> = emptySet(),val bridgeBoundary:String?=null,val coverageVerified:Boolean=false,val diskPending:Boolean=false,val gaps:List<TranscriptGapWindow> = emptyList())
    private val rowWeights=java.util.WeakHashMap<JSONObject,Long>()
    private val recent=RecentTranscriptCache<Snapshot>(4,Long.MAX_VALUE,12L*1024*1024){snapshot->
        snapshot.rows.sumOf{row->rowWeights.getOrPut(row){row.toString().length.toLong()*2}}+snapshot.detail.toString().length*2L+snapshot.gaps.sumOf{(it.id.length+it.cursor.length+it.beforeRow.length+it.olderAnchor.length)*2L+it.unverified.sumOf{ref->ref.length*2L}}
    }
    private var ownerScope:String?=null
    internal fun scopeKey():String{
        val identity=Pocket.prefs.getString("transcriptCacheIdentity",null)?:java.util.UUID.randomUUID().toString().also{Pocket.prefs.edit().putString("transcriptCacheIdentity",it).apply()}
        return backendOwner(transcriptCacheOwner(Pocket.environmentId,Pocket.base,Pocket.token),identity)
    }
    internal var cacheRestoreRevision by mutableIntStateOf(0);private set
    private val disk by lazy{TranscriptDiskCache(java.io.File(Pocket.context.noBackupFilesDir,"transcript-cache-v1"))}
    private var cacheRestoreJob:Job?=null
    private var cacheRestoring by mutableStateOf(false)
    private var needsDiskRestore=true
    private var readerIntentRevision=0L
    private var cacheRestoreIntent=0L
    private var positionRestorePending by mutableStateOf(false)
    internal fun readerInteraction(){readerIntentRevision++;positionRestorePending=false}
    internal fun mayRestorePosition(revision:Int)=revision!=cacheRestoreRevision||readerIntentRevision==cacheRestoreIntent
    internal fun positionRestored(id:String?,revision:Int){if(owner==id&&cacheRestoreRevision==revision)positionRestorePending=false}
    private val diskSequence=java.util.concurrent.atomic.AtomicLong()
    private val diskRequests=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private fun persist(scope:String,id:String,snapshot:Snapshot){
        if(snapshot.diskPending)return
        // Recent-cache insertion already measured these rows. Avoid constructing a
        // second giant JSON string for an oversized active history.
        var measured=0L
        for(row in snapshot.rows){measured+=rowWeights[row]?:return;if(measured>32L*1024*1024)return}
        val key="$scope/$id";val sequence=diskSequence.incrementAndGet();diskRequests[key]=sequence
        CodexConversations.storageScope.launch{try{
            if(diskRequests[key]!=sequence)return@launch
            // Save presentation only. Pending commands, questions and read acknowledgements
            // must come from the live backend rather than an old disk snapshot.
            val detail=transcriptPresentationDetail(id,snapshot.detail)
            val position=snapshot.position?.let{JSONObject().put("row",it.rowId).put("offset",it.offset).put("follow",it.follow)}
            val body=JSONObject().put("schema",2).put("scope",scope).put("id",id).put("rows",org.json.JSONArray(snapshot.rows.filter{it.s("kind")!="request"})).put("detail",detail).put("before",snapshot.before).put("earlier",snapshot.earlier).put("bridgeBefore",snapshot.bridgeBefore).put("bridgeKnown",org.json.JSONArray(snapshot.bridgeKnown.toList())).put("bridgeBoundary",snapshot.bridgeBoundary).put("coverageVerified",snapshot.coverageVerified).put("historyGaps",org.json.JSONArray(snapshot.gaps.map{it.json()})).put("position",position).toString()
            disk.write(scope,id,body){diskRequests[key]==sequence}
            if(position!=null)disk.writePosition(scope,id,position.toString(),sequence)
        }catch(_:Exception){/* A cache failure never changes delivery, draft or network state. */}}
    }
    private suspend fun diskSnapshot(scope:String,id:String):Snapshot?=withContext(Dispatchers.IO){try{
        val value=disk.read(scope,id)?.let{JSONObject(it)}?:return@withContext null
        if(value.optInt("schema") !in 1..2||value.s("scope")!=scope||value.s("id")!=id)return@withContext null
        val detail=value.getJSONObject("detail");if(detail.optJSONObject("thread")?.s("id")!=id)return@withContext null
        val originalRows=value.getJSONArray("rows").objects().filter{it.s("kind")!="request"}.map{it.put("version",0L)}
        // Sidecar state can be newer than the full history checkpoint. Accept
        // only a reference actually retained in this body; otherwise use its
        // embedded position until fresh history supplies newer content.
        val sidecar=disk.readPosition(scope,id)?.let{runCatching{JSONObject(it)}.getOrNull()}
        val rawPosition=sidecar?.takeIf{candidate->
            val anchor=candidate.s("row")
            val resolved=migrateTranscriptAnchors(originalRows,setOf(anchor))[anchor]
            originalRows.any{transcriptMessageIdentity(it.s("kind"),it.s("turnId"),it.s("clientId"),it.s("id"))==resolved}
        }?:value.optJSONObject("position")
        val rawBoundary=value.s("bridgeBoundary").takeIf{it.isNotBlank()}
        val rawKnown=value.optJSONArray("bridgeKnown")?.let{array->(0 until minOf(array.length(),1024)).map{array.optString(it)}.filter{it.isNotBlank()}}.orEmpty()
        val requested=rawKnown.toMutableSet().apply{rawPosition?.s("row")?.takeIf{it.isNotBlank()}?.let{add(it)};rawBoundary?.let{add(it)}}
        val migrated=migrateTranscriptAnchors(originalRows,requested)
        val cachedRows=normalizeTranscriptMessages(originalRows)
        if(cachedRows.any{it.s("id").isBlank()}||cachedRows.map{it.s("id")}.distinct().size!=cachedRows.size)return@withContext null
        val position=rawPosition?.let{TranscriptReadPosition(migrated[it.s("row")]?:it.s("row"),it.optInt("offset"),it.optBoolean("follow"))}
        Snapshot(cachedRows,detail,value.s("before").takeIf{it.isNotBlank()},value.optBoolean("earlier"),value.s("bridgeBefore").takeIf{it.isNotBlank()},position,rawKnown.map{migrated[it]?:it}.toSet(),rawBoundary?.let{migrated[it]?:it},verifiedTranscriptCoverage(value),gaps=restoreTranscriptGaps(value,originalRows))
    }catch(_:Exception){null}}
    private fun rememberRecent(persist:Boolean=false){
        val id=owner?:return;val scope=ownerScope?:return
        if(historyLoaded&&scope==scopeKey())Pocket.detail?.let{val snapshot=Snapshot(rows,it,before,earlier,bridgeBefore,readPosition,bridgeKnown,bridgeBoundary,coverageVerified,cacheRestoring,gapWindows);recent.put(scope,id,snapshot,android.os.SystemClock.elapsedRealtime());if(persist)persist(scope,id,snapshot)}
    }
    private fun persistReadingPosition(scope:String,id:String,position:TranscriptReadPosition){
        val sequence=diskSequence.incrementAndGet()
        val payload=JSONObject().put("row",position.rowId).put("offset",position.offset).put("follow",position.follow).toString()
        CodexConversations.storageScope.launch{runCatching{disk.writePosition(scope,id,payload,sequence)}}
    }
    internal fun rememberPosition(scope:String,id:String,position:TranscriptReadPosition){
        val now=android.os.SystemClock.elapsedRealtime()
        if(owner==id&&ownerScope==scope){if(readPosition!=position){
            readPosition=position
            val cached=recent.get(scope,id,now)
            // Preserve notes/detail changes made by UI actions between history reads.
            // Only an identical retained payload may reuse its measured weight.
            if(cached!=null&&(cached.rows!==rows||cached.detail!==Pocket.detail))rememberRecent()
            else recent.updateMetadata(scope,id,now){it.copy(position=position)}
            if(!cacheRestoring)persistReadingPosition(scope,id,position)
        };return}
        recent.get(scope,id,now)?.takeIf{it.position!=position}?.let{val snapshot=it.copy(position=position);recent.updateMetadata(scope,id,now){snapshot};persistReadingPosition(scope,id,position)}
    }
    private var observedHead:TranscriptHead?=null
    private var headSnapshotsSupported:Boolean?=null
    private data class PendingHead(val head:TranscriptHead,val baseline:TranscriptHead?)
    private var pendingHead:PendingHead?=null
    internal fun freshnessBaseline()=observedHead
    private fun parseHead(value:JSONObject?,id:String):TranscriptHead? {
        if(value==null||value.s("threadId")!=id)return null
        val epoch=value.s("epoch");if(epoch.isBlank()||epoch.length>100)return null
        val raw=value.opt("version")
        val version=if(raw==null||raw===JSONObject.NULL)null else {
            if(raw !is Number)return null
            val v=raw.toLong();if(v<0||raw.toDouble()!=v.toDouble())return null;v
        }
        return TranscriptHead(epoch,version)
    }
    internal fun checkFreshness(id:String,value:JSONObject?,baseline:TranscriptHead?){
        if(owner!=id||Pocket.selected!=id||ownerScope!=scopeKey()||!automaticRetryAllowed||headSnapshotsSupported==false)return
        val head=parseHead(value,id)?:return
        if(transcriptHeadAdvanced(observedHead,baseline,head)){
            if(reads.latestLoading||refreshPending){pendingHead=PendingHead(head,baseline);refreshPending=true}
            else Pocket.scheduleRefresh()
        }
    }
    private var owner:String?=null
    private var historyLoaded=false
    private var checkedThisOpen=false
    private var freshForRead=false
    private var cachedNeedsConfirmation by mutableStateOf(true)
    private var openingAtLatest=false
    private var preferLatestOnOpen=false
    internal val presentationReady get()=!cachedNeedsConfirmation||latestReadError.isNotBlank()
    internal fun repositoryUpdated(id:String,value:JSONObject){
        if(owner!=id||Pocket.selected!=id||ownerScope!=scopeKey()||!CodexSessionRepository.canPresent(id,value))return
        val incoming=value.optJSONObject("timeline")?.optJSONArray("rows")?.objects().orEmpty()
        if(incoming.isEmpty())return
        followNewWindow(rows,incoming)
        recordWindow(rows,incoming,value.getJSONObject("timeline"),!coverageVerified)
        rows=mergeRestoredTranscriptRows(rows,incoming)
        if(cachedNeedsConfirmation){readPosition=null;cacheRestoreRevision++}
        cachedNeedsConfirmation=false;revision++
    }
    // Work evidence survives an in-flight history refresh; read acknowledgements do not.
    internal var currentWorkVerified by mutableStateOf(false);private set
    internal val currentHistoryVerified get()=freshForRead&&!positionRestorePending
    private fun trace(){if(BuildConfig.DEBUG)android.util.Log.d("PocketCache","rows=${rows.size} buffered=${buffered.size} earlier=$browsingEarlier")}
    /** Stop hidden-view reads without discarding its cache, selection or draft. */
    internal fun pauseReading()=CodexConversations.suspendReading()
    internal fun pauseState(){
        rememberRecent(persist=true);generation++;cacheRestoreJob?.cancel();cacheRestoreJob=null;cacheRestoring=false;positionRestorePending=false
        positionJob?.cancel();positionJob=null;positionQueue.clear();attemptedPositions.clear();awaitingPositionHistory.clear();positionFailures.clear();positioning=false
        reads.reset();refreshPending=false;missedUpdates=false;pendingHead=null;buffered.clear()
    }
    fun reset(id:String)=CodexConversations.select(id,force=true)
    internal fun resetState(id:String,scope:CoroutineScope,openLatest:Boolean=false){
        operationScope=scope
        latestReadError="";historicalReadError="";positionReadError="";readFailureSince=0L
        cacheRestoreJob?.cancel();cacheRestoreJob=null;cacheRestoring=false;positionRestorePending=false
        positionJob?.cancel();positionJob=null;positionQueue.clear();attemptedPositions.clear();awaitingPositionHistory.clear();positionFailures.clear();positioning=false
        cacheRestoreIntent=readerIntentRevision
        currentWorkVerified=false
        generation++;owner=id;ownerScope=scopeKey();observedHead=null;pendingHead=null;headSnapshotsSupported=null;automaticRetryAllowed=true
        val cached=recent.get(ownerScope!!,id,android.os.SystemClock.elapsedRealtime())
        needsDiskRestore=cached?.diskPending?: (cached==null)
        val shared=CodexSessionRepository.peek(id)
        val sharedRows=shared?.optJSONObject("timeline")?.optJSONArray("rows")?.objects().orEmpty()
        openingAtLatest=openLatest;preferLatestOnOpen=openLatest
        cachedNeedsConfirmation=openLatest&&(shared==null||!CodexSessionRepository.canPresent(id,shared))
        rows=if(sharedRows.isEmpty())cached?.rows.orEmpty()else mergeRestoredTranscriptRows(cached?.rows.orEmpty(),sharedRows);Pocket.detail=cached?.detail;readPosition=cached?.position?.takeUnless{preferLatestOnOpen}
        earlier=cached?.earlier?:false;before=cached?.before;gapWindows=cached?.gaps.orEmpty();gapReadId=null;gapProblems=emptyMap();initialGapProbe=null;initialGapProbeUsed=false;coverageVerified=cached?.coverageVerified?:false;reads.reset();refreshPending=false;historyLoaded=cached!=null;checkedThisOpen=false;freshForRead=false
        if(shared!=null&&sharedRows.isNotEmpty()&&shared.optInt("windowSchema")==2)recordWindow(cached?.rows.orEmpty(),sharedRows,shared.getJSONObject("timeline"),!coverageVerified)
        buffered.clear();browsingEarlier=false;missedUpdates=false;revision++;trace()
    }
    fun clear()=CodexConversations.close()
    internal fun clearState(){operationScope=null;latestReadError="";historicalReadError="";positionReadError="";readFailureSince=0L;cacheRestoreJob?.cancel();cacheRestoreJob=null;cacheRestoring=false;positionRestorePending=false;needsDiskRestore=true;positionJob?.cancel();positionJob=null;positionQueue.clear();attemptedPositions.clear();awaitingPositionHistory.clear();positionFailures.clear();positioning=false;automaticRetryAllowed=true;currentWorkVerified=false;generation++;owner=null;ownerScope=null;observedHead=null;pendingHead=null;headSnapshotsSupported=null;readPosition=null;rows=emptyList();buffered.clear();reads.reset();refreshPending=false;historyLoaded=false;checkedThisOpen=false;freshForRead=false;before=null;gapWindows=emptyList();gapReadId=null;gapProblems=emptyMap();initialGapProbe=null;initialGapProbeUsed=false;coverageVerified=false;earlier=false;browsingEarlier=false;revision++;trace()}
    /** Called only by the resumed, unfiltered viewport after settling at latest. */
    internal fun presentedLatest(){
        val id=Pocket.selected?:return
        if(owner!=id||ownerScope!=scopeKey()||!freshForRead||cacheRestoring||gapWindows.isNotEmpty()||loading||updating||BackendNavigation.teamSelected()||!PocketVoice.foreground||!AppWindowVisibility.focused||BlackoutVisibility.active||PocketVoice.active||Pocket.newTask)return
        val d=Pocket.detail?:return
        PocketNotificationReads.readVisible(id,d.optJSONArray("notifications")?.objects()?:emptyList(),local=Pocket.local,catchup=if(!checkedThisOpen)d.optJSONObject("catchup")else null,environment=Pocket.environmentId)
        checkedThisOpen=true
    }
    fun reconcileInputPosition(raw:JSONObject){
        val row=normalizeTranscriptMessage(raw)
        if(BackendNavigation.teamSelected())return
        val id=Pocket.selected?:return;val turn=row.s("turnId");val target=row.s("id")
        if(owner!=id||ownerScope!=scopeKey()||turn.isBlank()||!target.startsWith("$turn/"))return
        val item=transcriptProviderItem(row)
        val attempt=InputAttempt(target,item)
        if(attempt in attemptedPositions)return
        val ticket=generation;val scope=scopeKey();val destination=Pocket.captureEnvironment()?:return
        fun current()=Pocket.selected==id&&owner==id&&generation==ticket&&ownerScope==scope&&scopeKey()==scope
        val known=transcriptPositionAnchors(rows.filter{it.s("turnId")==turn&&it.s("kind") !in listOf("turn","turnEnd","request","attachments")}).map{it.s("itemId").ifBlank{it.s("id").removePrefix("$turn/")}}
        if(known.none{it!=item})return
        attemptedPositions.add(attempt)
        positionQueue[target]=InputPosition(turn,target,item,row.s("clientId"),known,positionHistoryRevision)
        if(positionJob?.isActive==true)return
        positioning=true
        positionJob=readScope.launch{
            try{
                while(current()&&positionQueue.isNotEmpty()){
                    val request=positionQueue.values.first()
                    try{
                        var cursor:String?=null;val visited=hashSetOf<String>()
                        // One serial read lane, bounded per target and driven only by
                        // new input events. No model polling or command resubmission.
                        for(page in 0 until 64){
                            if(!current())return@launch
                            val body=JSONObject().put("turnId",request.turn).put("itemId",request.item).put("knownItemIds",org.json.JSONArray(request.known))
                            if(request.client.isNotBlank())body.put("clientId",request.client)
                            cursor?.let{body.put("cursor",it)}
                            val result=CodexReadTransport.read(destination,"/api/threads/$id/timeline/position",body)
                            if(!current())return@launch
                            if(result.optBoolean("awaitingHistory")){
                                val deferred=InputAttempt(request.target,request.item)
                                awaitingPositionHistory.add(deferred)
                                // The independent head lane can finish before this metadata
                                // reply. Consume that newer evidence exactly once; waiting
                                // for another fetch would strand the input indefinitely.
                                if(positionHistoryRevision>request.evidence){
                                    awaitingPositionHistory.remove(deferred);attemptedPositions.remove(deferred)
                                    rows.firstOrNull{it.s("id")==request.target||
                                        (request.client.isNotBlank()&&it.s("clientId")==request.client&&it.s("turnId")==request.turn)}
                                        ?.let{reconcileInputPosition(it)}
                                }
                            }
                            val successor=transcriptDisplayAnchor(rows,result.s("insertBefore")).orEmpty()
                            if(successor.isNotBlank()){
                                if(successor.startsWith("${request.turn}/")){
                                    val repaired=placeTranscriptRowBefore(rows,request.target,successor){it.s("id")}
                                    if(repaired!==rows){rows=repaired;rememberRecent(persist=true);revision++}
                                    CodexSessionRepository.positioned(destination,id,request.target,successor)
                                }
                                break
                            }
                            cursor=result.s("cursor").takeIf{it.isNotBlank()}
                            val next=cursor?:break
                            if(!visited.add(next))break
                            delay(16)
                        }
                    }catch(e:CancellationException){throw e}catch(e:Exception){
                        // A missing endpoint is honest compatibility, not a broken
                        // conversation. Other failures remain actionable without
                        // repeatedly retrying an unchanged dependency.
                        if(current()&&!(e is PocketApiException&&e.status==404)){
                            positionFailures[request.target]="Could not confirm message order. "+PocketNetwork.error(e)
                            positionReadError=positionFailures.values.first()
                        }
                    }finally{if(current()&&positionQueue[request.target]===request)positionQueue.remove(request.target)}
                }
            }finally{if(current()){positioning=false;positionJob=null}}

        }
    }
    fun latest()=CodexConversations.retry()
    internal fun prepareRetry(){
        attemptedPositions.removeAll{it.target in positionFailures.keys};positionFailures.clear()
        gapWindows.firstOrNull{it.exhausted}?.let{initialGapProbe=it.id;initialGapProbeUsed=false}
        gapWindows=gapWindows.map{it.copy(exhausted=false)};gapProblems=emptyMap()
    }
    // Android memory pressure evicts inactive snapshots, never the conversation being read.
    fun release(){recent.clear();trace()}
    suspend fun load(older:Boolean=false,bridgeCursor:String?=null,retryAttempt:Int=0,beforeApply:suspend ()->Unit={},beforeCacheRead:suspend ()->Unit={})=
        CodexConversations.read(older,bridgeCursor,retryAttempt,beforeApply,beforeCacheRead)
    internal suspend fun readPage(older:Boolean=false,bridgeCursor:String?=null,retryAttempt:Int=0,beforeApply:suspend ()->Unit={},beforeCacheRead:suspend ()->Unit={}){
        if(BackendNavigation.teamSelected())return
        val id=Pocket.selected?:return
        if(owner!=id||(older&&(!earlier||before==null)))return
        if(bridgeCursor!=null&&gapWindows.none{it.cursor==bridgeCursor&&(!it.exhausted||it.id !in gapProblems)})return
        val kind=when{older->TranscriptReadState.Kind.OLDER;bridgeCursor!=null->TranscriptReadState.Kind.GAP;else->TranscriptReadState.Kind.LATEST}
        val latest=kind==TranscriptReadState.Kind.LATEST
        if(latest&&reads.latestLoading){missedUpdates=true;refreshPending=true;return}
        if(!latest&&(reads.historyLoading||refreshPending))return
        val destination=Pocket.captureEnvironment()?:return
        val gapTicket=bridgeCursor?.let{cursor->gapWindows.firstOrNull{it.cursor==cursor}}
        val read=reads.begin(kind)?:return
        if(gapTicket!=null){gapReadId=gapTicket.id;initialGapProbeUsed=true;initialGapProbe=null}
        val headBaseline=observedHead
        val ticket=generation;val scope=scopeKey();val profileLocal=Pocket.local
        fun current()=Pocket.selected==id&&owner==id&&generation==ticket&&ownerScope==scope&&scopeKey()==scope
        val cursor=bridgeCursor?:if(older)before else null
        if(latest){refreshPending=false;freshForRead=false;automaticRetryAllowed=true;buffered.clear()}
        var baselineRows=rows.associateBy{it.s("id")};var succeeded=false
        try{
            // Cache IO and the authoritative read have independent lifetimes.
            // A slow disk cannot delay starting or painting current history.
            var latestPage:List<JSONObject>?=null
            var latestBefore:String?=null
            var latestHasEarlier=false
            if(needsDiskRestore&&!older&&bridgeCursor==null&&cacheRestoreJob?.isActive!=true){
                cacheRestoring=true
                val intentAtStart=readerIntentRevision
                val memoryPosition=readPosition
                cacheRestoreJob=readScope.launch{
                    try{
                        beforeCacheRead()
                        val cached=diskSnapshot(scope,id)
                        if(!current())return@launch
                        if(cached!=null){
                            val previous=rows
                            rows=mergeRestoredTranscriptRows(cached.rows,previous)
                            val aliases=migrateTranscriptAnchors(rows,setOfNotNull(readPosition?.rowId))
                            readPosition=readPosition?.let{it.copy(rowId=aliases[it.rowId]?:it.rowId)}
                            if(Pocket.detail==null)Pocket.detail=cached.detail
                            val oldest=rows.firstOrNull{transcriptContentAnchor(it.s("kind"))}?.s("id")
                            val cachedIds=promoteTranscriptIdentityEvidence(cached.rows,rows).mapTo(hashSetOf()){it.s("id")}
                            if(oldest in cachedIds){before=cached.before;earlier=cached.earlier}
                            if(latestPage==null){
                                gapWindows=(cached.gaps+gapWindows).distinctBy{it.id};coverageVerified=cached.coverageVerified
                                val shared=CodexSessionRepository.peek(id)
                                if(shared!=null&&shared.optInt("windowSchema")==2){
                                    val headRows=shared.optJSONObject("timeline")?.optJSONArray("rows")?.objects().orEmpty()
                                    recordWindow(cached.rows,headRows,shared.getJSONObject("timeline"),!cached.coverageVerified)
                                }
                                baselineRows=normalizeTranscriptMessages(baselineRows.values+cached.rows).associateBy{it.s("id")}
                            }else{
                                // Retain every previously known interval when the current
                                // head wins the race against disk restoration.
                                val restored=(cached.gaps+gapWindows).associateBy{it.id}.values.toList()
                                val aliases=migrateTranscriptAnchors(rows,restored.flatMap{listOf(it.beforeRow,it.olderAnchor)+it.unverified}.toSet())
                                gapWindows=restored.map{it.copy(beforeRow=aliases[it.beforeRow]?:it.beforeRow,olderAnchor=aliases[it.olderAnchor]?:it.olderAnchor,unverified=it.unverified.map{ref->aliases[ref]?:ref}.toSet())}
                                recordWindow(cached.rows,latestPage!!,JSONObject().put("hasEarlier",latestHasEarlier).put("before",latestBefore),!cached.coverageVerified)
                            }
                            historyLoaded=true
                            if(readerIntentRevision==intentAtStart&&!preferLatestOnOpen){
                                val position=memoryPosition?:cached.position
                                if(position!=null)readPosition=resolveTranscriptReadPosition(rows,position)
                                cacheRestoreIntent=intentAtStart
                                positionRestorePending=readPosition?.let{!it.follow&&rows.any{row->row.s("id")==it.rowId}}==true
                                cacheRestoreRevision++
                            }
                            revision++;trace()
                        }
                    }finally{
                        if(current()){
                            needsDiskRestore=false;cacheRestoring=false;cacheRestoreJob=null
                            if(freshForRead)rememberRecent(persist=true)
                            probeInitialGap()
                        }
                    }
                }
            }
            val d=if(latest)CodexSessionRepository.readCurrent(destination,id)else CodexReadTransport.read(destination,"/api/threads/$id?view=timeline"+if(cursor!=null)"&before=${android.net.Uri.encode(cursor)}" else "")
            if(latest&&!CodexSessionRepository.currentSnapshot(destination,id,d)){
                // New catalog evidence superseded this read. Keep the viewport
                // and retry through its owner; this is not a network failure.
                CodexConversations.continueLatest()
                return
            }
            validateTranscriptSnapshot(d,id)
            if(older)beforeApply()
            if(!current()||!reads.active(read))return
            // A current-head read may have replaced the gap while this page was in flight.
            if(gapTicket!=null&&gapWindows.none{it.id==gapTicket.id&&it.cursor==bridgeCursor})return
            val page=d.optJSONObject("timeline")?:JSONObject();var incoming=normalizeTranscriptMessages(page.optJSONArray("rows")?.objects()?:emptyList())
            incoming.forEach{it.put("version",maxOf(it.optLong("version"),d.optLong("revision")))}
            // Promote incomplete live/cache identities before the snapshot diff.
            // Otherwise a metadata-enriched history row is treated as a new send.
            val reconciled=normalizeTranscriptMessages(rows+incoming)
            val requested=gapWindows.flatMap{listOf(it.beforeRow,it.olderAnchor)+it.unverified}.toMutableSet().apply{readPosition?.let{add(it.rowId)}}
            val moved=migrateTranscriptAnchors(reconciled,requested)
            readPosition=readPosition?.let{it.copy(rowId=moved[it.rowId]?:it.rowId)}
            gapWindows=gapWindows.map{it.copy(beforeRow=moved[it.beforeRow]?:it.beforeRow,olderAnchor=moved[it.olderAnchor]?:it.olderAnchor,unverified=it.unverified.map{ref->moved[ref]?:ref}.toSet())}
            rows=promoteTranscriptIdentityEvidence(rows,reconciled)
            baselineRows=promoteTranscriptIdentityEvidence(baselineRows.values.toList(),reconciled).associateBy{it.s("id")}
            incoming=promoteTranscriptIdentityEvidence(incoming,reconciled)
            if(!older&&bridgeCursor==null)rows=authoritativeSnapshotBase(rows,baselineRows,incoming.mapTo(hashSetOf()){it.s("id")},{it.s("id")}){JSONObject(it.toString()).put("version",0)}
            // A stored reading position belongs to the cached head, not to
            // unseen current work. Resolve that policy before cache restoration
            // can move this opening back to an obsolete day. A gesture during
            // the read remains authoritative.
            if(latest)followNewWindow(rows,incoming)
            val previousEvidence=rows.associateBy{it.s("id")}
            incoming=incoming.map{mergeTranscriptMessageEvidence(previousEvidence[it.s("id")],it)}
            if(!older&&bridgeCursor==null){latestPage=incoming;latestBefore=page.s("before").takeIf{it.isNotBlank()};latestHasEarlier=page.optBoolean("hasEarlier")}
            val wasLoaded=historyLoaded&&rows.isNotEmpty()
            val loadedIds=rows.filter{transcriptContentAnchor(it.s("kind"))}.mapTo(hashSetOf()){it.s("id")}
            val incomingContent=incoming.filter{transcriptContentAnchor(it.s("kind"))}
            val incomingIds=incomingContent.mapTo(hashSetOf()){it.s("id")}
            val overlapTargets=gapTicket?.let{ticket->gapWindows.firstOrNull{it.id==ticket.id}?.olderAnchor?.let{setOf(it)}}?:loadedIds
            val overlaps=incomingContent.any{it.s("id") in overlapTargets}
            val nextBefore=page.s("before").takeIf{it.isNotBlank()}
            if(gapTicket!=null){
                val window=gapWindows.first{it.id==gapTicket.id}
                if(!overlaps&&page.optBoolean("hasEarlier")&&(nextBefore==null||nextBefore==cursor))throw IllegalStateException("History page did not advance; cached messages are retained.")
                rows=mergeTranscriptPage(rows,incoming,false,{it.s("id")},{it.optLong("version")},insertBefore=transcriptDisplayAnchor(rows,page.s("insertBefore").takeIf{it.isNotBlank()})?:window.beforeRow,keepPosition={it.s("kind")=="turn"})
                gapWindows=gapWindows.mapNotNull{gap->
                    when{
                        gap.id==window.id->advanceTranscriptGap(gap,incoming,page)?.also{advanced->
                            if(advanced.exhausted)gapProblems=gapProblems+(gap.id to "Saved messages are separated from the available history.")
                        }
                        gap.beforeRow in incomingIds&&gap.olderAnchor in incomingIds&&gap.unverified.all{it in incomingIds}->null
                        else->gap
                    }
                }
            }else{
                if(!older){
                    // A contiguous head can prove one or more intervals complete.
                    // It never rewinds the independent cursor of another interval.
                    gapWindows=gapWindows.filterNot{it.beforeRow in incomingIds&&it.olderAnchor in incomingIds&&it.unverified.all{ref->ref in incomingIds}}
                    if(wasLoaded)recordWindow(rows,incoming,page,!coverageVerified&&gapWindows.isEmpty())
                    coverageVerified=true
                }
                val mergeRows=if(!older&&wasLoaded&&!overlaps)incoming.filterNot{it.s("kind")=="turn"&&rows.any{old->old.s("id")==it.s("id")}}else incoming
                rows=mergeTranscriptPage(rows,mergeRows,older,{it.s("id")},{it.optLong("version")},insertBefore=if(older)transcriptDisplayAnchor(rows,page.s("insertBefore").takeIf{it.isNotBlank()})else null,keepPosition={it.s("kind")=="turn"})
            }
            rows=groupTranscriptTurns(rows)
            // A recent refresh must not move the oldest loaded cursor forward.
            retainedTranscriptCursor(TranscriptCursor(before,earlier),wasLoaded,older,bridgeCursor!=null,TranscriptCursor(nextBefore,page.optBoolean("hasEarlier"))).let{
                before=it.before;earlier=it.hasEarlier
            }
            browsingEarlier=browsingEarlier||older
            historyLoaded=true
            if(!older&&bridgeCursor==null)CodexSessionRepository.accepted(destination,id,d)
            d.remove("timeline");if((!older&&bridgeCursor==null)||Pocket.detail==null){
                Pocket.detail=d
                val pendingIds=d.optJSONArray("pending")?.objects()?.map{it.s("id")}.orEmpty().toSet()
                rows=rows.filter{it.s("kind")!="request"||it.optJSONObject("request")?.s("id") in pendingIds}
            }
            if(!older&&bridgeCursor==null){cachedNeedsConfirmation=false;currentWorkVerified=true;freshForRead=true;val head=parseHead(d.optJSONObject("historyHead"),id);headSnapshotsSupported=head!=null;head?.let{observedHead=snapshotTranscriptHead(observedHead,headBaseline,it)}}
            val updates=if(latest)buffered.values.toList().also{buffered.clear()}else emptyList()
            updates.filter{older||bridgeCursor!=null||it.optLong("version")>d.optLong("revision")}.forEach{apply(it,false)}
            rememberRecent(persist=true);revision++;positionReadError=positionFailures.values.firstOrNull().orEmpty()
            if(latest){latestReadError="";readFailureSince=0L}else if(gapTicket!=null)gapProblems=gapProblems-gapTicket.id else historicalReadError=""
            succeeded=true;trace()
            if(!older&&bridgeCursor==null){
                positionHistoryRevision++
                // Retry only after a successful independent snapshot supplied new ordering evidence.
                val deferredTargets=awaitingPositionHistory.mapTo(hashSetOf()){it.target}
                attemptedPositions.removeAll(awaitingPositionHistory);awaitingPositionHistory.clear()
                rows.filter{it.s("id") in deferredTargets}.forEach{reconcileInputPosition(it)}
                // A warm reopening must also repair delayed inputs that were outside
                // its bounded recent page when the previous read was cancelled.
                val incomingIds=incoming.mapTo(hashSetOf()){it.s("id")}
                val firstReplies=incoming.filter{it.s("kind")=="message"}.groupBy{it.s("turnId")}.mapValues{(_,items)->rows.indexOfFirst{it.s("id")==items.first().s("id")}}
                rows.filterIndexed{index,row->row.s("kind")=="user"&&row.s("id") !in incomingIds&&firstReplies[row.s("turnId")]?.let{it>=0&&index>it}==true}.forEach{reconcileInputPosition(it)}
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){if(current()){
            if(latest){
                automaticRetryAllowed=retryableTranscriptRead(e)
                val now=android.os.SystemClock.elapsedRealtime()
                if(readFailureSince==0L)readFailureSince=now
                // Current-read failures and historical-page failures are independent.
                if(!automaticRetryAllowed||now-readFailureSince>=5000L)latestReadError=if(automaticRetryAllowed)"Message refresh delayed. Retrying automatically." else PocketNetwork.error(e)
            }else if(gapTicket!=null)gapProblems=gapProblems+(gapTicket.id to PocketNetwork.error(e))else historicalReadError=PocketNetwork.error(e)
        }}
        finally {
            if(current()&&reads.active(read)) {
                reads.finish(read)
                if(!latest){
                    // One viewport demand owns one page; no eager historical chain.
                    if(gapTicket!=null&&gapReadId==gapTicket.id)gapReadId=null
                }else{
                    if(!succeeded&&buffered.isNotEmpty()){buffered.clear();missedUpdates=true}
                    pendingHead?.let{pending->if(automaticRetryAllowed&&headSnapshotsSupported!=false&&transcriptHeadAdvanced(observedHead,pending.baseline,pending.head))missedUpdates=true}
                    pendingHead=null
                    if(succeeded&&!missedUpdates)refreshPending=false
                    if(!succeeded&&!automaticRetryAllowed)refreshPending=false
                    if(!succeeded&&automaticRetryAllowed&&!older){
                        missedUpdates=false;refreshPending=true
                        CodexConversations.continueLatest(
                            delayMs=(1000L shl retryAttempt.coerceAtMost(5)).coerceAtMost(30_000L),
                            retryAttempt=(retryAttempt+1).coerceAtMost(5))
                    }else if(missedUpdates) {
                        missedUpdates=false
                        if(succeeded||automaticRetryAllowed)CodexConversations.continueLatest(if(succeeded)0 else 450)
                    } else if(succeeded) {
                        probeInitialGap()
                    }
                }
            }
        }
    }
    fun apply(update:JSONObject,buffer:Boolean=true){
        if(update.s("threadId")!=Pocket.selected||owner!=Pocket.selected||ownerScope!=scopeKey())return
        parseHead(update.optJSONObject("historyHead"),owner!!)?.let{head->
            val changedRuntime=observedHead?.epoch?.let{it!=head.epoch}?:historyLoaded
            observedHead=observeTranscriptHead(observedHead,head)
            if(changedRuntime)Pocket.scheduleRefresh()
        }
        if(update.optBoolean("reload")){if(reads.latestLoading)missedUpdates=true else Pocket.scheduleRefresh();return}
        if(reads.latestLoading&&buffer){
            val key=update.optJSONObject("row")?.s("id")?:update.s("turnId")
            buffered[key]=update
            // Live progress is readable immediately; replay still protects snapshot reconciliation.
            apply(update,false)
            if(buffered.size>100||buffered.values.sumOf{it.toString().length}>512000){buffered.clear();missedUpdates=true}
            return
        }
        rows=reduceTranscriptUpdate(rows,update)
        update.optJSONObject("turn")?.let{turn->
            Pocket.detail?.optJSONObject("thread")?.put("status",JSONObject().put("type",if(turn.s("status")=="inProgress")"active" else "idle"))
        }
        rememberRecent();revision++
        if(missedUpdates&&!loading){missedUpdates=false;Pocket.scheduleRefresh()}
    }
}

internal fun validateTranscriptSnapshot(snapshot:JSONObject,id:String){
    if(snapshot.optJSONObject("thread")?.s("id")!=id||snapshot.optJSONObject("timeline")?.optJSONArray("rows")==null)
        throw java.io.IOException("Messages were not returned for this conversation. Retrying the history read.")
}

/** Inactive conversations use whole-snapshot LRU eviction, never hidden local pages. */
internal class RecentTranscriptCache<T>(private val capacity:Int,private val lifetimeMs:Long,private val maxWeight:Long=Long.MAX_VALUE,private val weight:(T)->Long={1}){
    private data class Entry<T>(val value:T,val savedAt:Long,val weight:Long)
    private val entries=linkedMapOf<Pair<String,String>,Entry<T>>()
    fun put(scope:String,id:String,value:T,now:Long){
        val key=scope to id;entries.remove(key)
        val size=weight(value).coerceAtLeast(0)
        if(size>maxWeight)return // Oversized inactive history is honestly evicted as a whole.
        entries[key]=Entry(value,now,size)
        while(entries.size>capacity||entries.values.sumOf{it.weight}>maxWeight)entries.remove(entries.keys.first())
    }
    /** Update unweighed presentation metadata only; the retained payload must
     * remain unchanged. Reading-position copies preserve rows and detail. */
    fun updateMetadata(scope:String,id:String,now:Long,transform:(T)->T):T?{
        val key=scope to id;val entry=entries[key]?:return null
        if(now-entry.savedAt>lifetimeMs){entries.remove(key);return null}
        val value=transform(entry.value)
        entries.remove(key);entries[key]=Entry(value,now,entry.weight)
        return value
    }
    fun get(scope:String,id:String,now:Long):T?{val key=scope to id;val entry=entries.remove(key)?:return null;if(now-entry.savedAt>lifetimeMs)return null;entries[key]=entry;return entry.value}
    fun remove(scope:String,id:String){entries.remove(scope to id)}
    fun clear(){entries.clear()}
}

/** Reads may recover automatically; rejected credentials/cursors need changed state. */
internal fun retryableTranscriptRead(error:Throwable)=error is java.io.IOException||error is PocketApiException&&(error.status in listOf(408,429)||error.status in 500..599)

/** The same canonical event reducer serves selected and inactive session caches. */
internal fun reduceTranscriptUpdate(rows:List<JSONObject>,update:JSONObject):List<JSONObject>{
        val turnId=update.s("turnId")
        if(turnId.isBlank()){return rows}
        val next=rows.toMutableList()
        fun put(row:JSONObject){
            val at=next.indexOfFirst{it.s("id")==row.s("id")}
            if(at>=0){if(row.optLong("version")>=next[at].optLong("version"))next[at]=mergeTranscriptMessageEvidence(next[at],row)}
            else{
                val end=next.indexOfFirst{it.s("turnId")==turnId&&it.s("kind")=="turnEnd"}
                val last=next.indexOfLast{it.s("turnId")==turnId}
                next.add(if(end>=0)end else if(last>=0)last+1 else next.size,row)
            }
        }
        if(next.none{it.s("turnId")==turnId})next.add(JSONObject().put("id","$turnId/header").put("turnId",turnId).put("kind","turn").put("status","inProgress"))
        update.optJSONObject("row")?.let{put(normalizeTranscriptMessage(it))}
        update.optJSONObject("turn")?.let{turn->
            put(JSONObject(turn.toString()).put("id","$turnId/header").put("turnId",turnId).put("kind","turn").put("version",update.optLong("version")))
            val active=turn.s("status")=="inProgress"
            if(!active)put(JSONObject().put("id","$turnId/end").put("turnId",turnId).put("kind","turnEnd").put("status",turn.s("status")).put("durationMs",turn.optLong("durationMs")).put("error",turn.optJSONObject("error")).put("text",turn.optJSONObject("error")?.s("message")?:""))
        }
        return if(update.optJSONObject("row")?.s("kind")=="user")normalizeTranscriptMessages(next)else next
}
