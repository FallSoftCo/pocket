package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

object PocketTranscript {
    var rows by mutableStateOf(listOf<JSONObject>());private set
    var loading by mutableStateOf(false);private set
    var refreshPending by mutableStateOf(false);private set
    private var loadingEarlier by mutableStateOf(false)
    private var positioning by mutableStateOf(false)
    val updating get()=refreshPending||(loading&&!loadingEarlier)||positioning||(bridgeBefore!=null&&automaticRetryAllowed)
    fun markRefreshPending(){
        if(owner==Pocket.selected&&ownerScope==scopeKey()){
            refreshPending=true
            // The scheduler coalesces requests while its read is active. Retain
            // an update arriving after that read began for one follow-up read.
            if(loading)missedUpdates=true
        }
    }
    var automaticRetryAllowed by mutableStateOf(true);private set
    var earlier by mutableStateOf(false);private set
    var before:String?=null;private set
    var revision by mutableIntStateOf(0);private set
    private val buffered=linkedMapOf<String,JSONObject>()
    private var generation=0
    private var bridgeBefore:String?=null
    private var bridgeKnown:Set<String> = emptySet()
    private var bridgeBoundary:String?=null
    private var coverageVerified=false
    private var missedUpdates=false
    private data class InputPosition(val turn:String,val target:String,val item:String,val known:List<String>)
    private val positionQueue=linkedMapOf<String,InputPosition>()
    private var positionJob:Job?=null
    private val attemptedPositions=hashSetOf<String>()
    private val positionFailures=linkedMapOf<String,String>()
    var browsingEarlier by mutableStateOf(false);private set
    internal var readPosition by mutableStateOf<TranscriptReadPosition?>(null);private set
    private data class Snapshot(val rows:List<JSONObject>,val detail:JSONObject,val before:String?,val earlier:Boolean,val bridgeBefore:String?,val position:TranscriptReadPosition?,val bridgeKnown:Set<String> = emptySet(),val bridgeBoundary:String?=null,val coverageVerified:Boolean=false)
    private val rowWeights=java.util.WeakHashMap<JSONObject,Long>()
    private val recent=RecentTranscriptCache<Snapshot>(4,Long.MAX_VALUE,12L*1024*1024){snapshot->
        snapshot.rows.sumOf{row->rowWeights.getOrPut(row){row.toString().length.toLong()*2}}+snapshot.detail.toString().length*2L
    }
    private var ownerScope:String?=null
    internal fun scopeKey():String{
        val identity=Pocket.prefs.getString("transcriptCacheIdentity",null)?:java.util.UUID.randomUUID().toString().also{Pocket.prefs.edit().putString("transcriptCacheIdentity",it).apply()}
        return backendOwner(transcriptCacheOwner(Pocket.environmentId,Pocket.base,Pocket.token),identity)
    }
    internal var cacheRestoreRevision by mutableIntStateOf(0);private set
    private val disk by lazy{TranscriptDiskCache(java.io.File(Pocket.context.noBackupFilesDir,"transcript-cache-v1"))}
    private val diskSequence=java.util.concurrent.atomic.AtomicLong()
    private val diskRequests=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private fun persist(scope:String,id:String,snapshot:Snapshot){
        // Recent-cache insertion already measured these rows. Avoid constructing a
        // second giant JSON string for an oversized active history.
        var measured=0L
        for(row in snapshot.rows){measured+=rowWeights[row]?:return;if(measured>32L*1024*1024)return}
        val key="$scope/$id";val sequence=diskSequence.incrementAndGet();diskRequests[key]=sequence
        Pocket.scope.launch(Dispatchers.IO){try{
            if(diskRequests[key]!=sequence)return@launch
            // Save presentation only. Pending commands, questions and read acknowledgements
            // must come from the live backend rather than an old disk snapshot.
            val detail=transcriptPresentationDetail(id,snapshot.detail)
            val position=snapshot.position?.let{JSONObject().put("row",it.rowId).put("offset",it.offset).put("follow",it.follow)}
            val body=JSONObject().put("schema",1).put("scope",scope).put("id",id).put("rows",org.json.JSONArray(snapshot.rows.filter{it.s("kind")!="request"})).put("detail",detail).put("before",snapshot.before).put("earlier",snapshot.earlier).put("bridgeBefore",snapshot.bridgeBefore).put("bridgeKnown",org.json.JSONArray(snapshot.bridgeKnown.toList())).put("bridgeBoundary",snapshot.bridgeBoundary).put("coverageVerified",snapshot.coverageVerified).put("position",position).toString()
            disk.write(scope,id,body){diskRequests[key]==sequence}
        }catch(_:Exception){/* A cache failure never changes delivery, draft or network state. */}}
    }
    private suspend fun diskSnapshot(scope:String,id:String):Snapshot?=withContext(Dispatchers.IO){try{
        val value=disk.read(scope,id)?.let{JSONObject(it)}?:return@withContext null
        if(value.optInt("schema")!=1||value.s("scope")!=scope||value.s("id")!=id)return@withContext null
        val detail=value.getJSONObject("detail");if(detail.optJSONObject("thread")?.s("id")!=id)return@withContext null
        val cachedRows=normalizeTranscriptMessages(value.getJSONArray("rows").objects().filter{it.s("kind")!="request"}.map{it.put("version",0L)});if(cachedRows.any{it.s("id").isBlank()}||cachedRows.map{it.s("id")}.distinct().size!=cachedRows.size)return@withContext null
        val position=value.optJSONObject("position")?.let{TranscriptReadPosition(transcriptDisplayAnchor(cachedRows,it.s("row"))?:it.s("row"),it.optInt("offset"),it.optBoolean("follow"))}
        Snapshot(cachedRows,detail,value.s("before").takeIf{it.isNotBlank()},value.optBoolean("earlier"),value.s("bridgeBefore").takeIf{it.isNotBlank()},position,value.optJSONArray("bridgeKnown")?.let{array->(0 until array.length()).map{array.optString(it)}.filter{it.isNotBlank()}.map{transcriptDisplayAnchor(cachedRows,it)?:it}.take(1024).toSet()}.orEmpty(),transcriptDisplayAnchor(cachedRows,value.s("bridgeBoundary").takeIf{it.isNotBlank()}),value.optBoolean("coverageVerified"))
    }catch(_:Exception){null}}
    private fun rememberRecent(persist:Boolean=false){
        val id=owner?:return;val scope=ownerScope?:return
        if(historyLoaded&&scope==scopeKey())Pocket.detail?.let{val snapshot=Snapshot(rows,it,before,earlier,bridgeBefore,readPosition,bridgeKnown,bridgeBoundary,coverageVerified);recent.put(scope,id,snapshot,android.os.SystemClock.elapsedRealtime());if(persist)persist(scope,id,snapshot)}
    }
    internal fun rememberPosition(scope:String,id:String,position:TranscriptReadPosition){
        val now=android.os.SystemClock.elapsedRealtime()
        if(owner==id&&ownerScope==scope){if(readPosition!=position){readPosition=position;rememberRecent(persist=true)};return}
        recent.get(scope,id,now)?.takeIf{it.position!=position}?.let{val snapshot=it.copy(position=position);recent.put(scope,id,snapshot,now);persist(scope,id,snapshot)}
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
            if(loading||refreshPending){pendingHead=PendingHead(head,baseline);refreshPending=true}
            else Pocket.scheduleRefresh()
        }
    }
    private var owner:String?=null
    private var historyLoaded=false
    private var checkedThisOpen=false
    private var freshForRead=false
    internal val currentHistoryVerified get()=freshForRead
    private fun trace(){if(BuildConfig.DEBUG)android.util.Log.d("PocketCache","rows=${rows.size} buffered=${buffered.size} earlier=$browsingEarlier")}
    /** Stop hidden-view reads without discarding its cache, selection or draft. */
    internal fun pauseReading(){
        rememberRecent(persist=true);generation++
        positionJob?.cancel();positionJob=null;positionQueue.clear();attemptedPositions.clear();positionFailures.clear();positioning=false
        loading=false;refreshPending=false;loadingEarlier=false;missedUpdates=false;pendingHead=null;buffered.clear()
    }
    fun reset(id:String){
        positionJob?.cancel();positionJob=null;positionQueue.clear();attemptedPositions.clear();positionFailures.clear();positioning=false
        generation++;owner=id;ownerScope=scopeKey();observedHead=null;pendingHead=null;headSnapshotsSupported=null;automaticRetryAllowed=true
        val cached=recent.get(ownerScope!!,id,android.os.SystemClock.elapsedRealtime())
        rows=cached?.rows?:emptyList();Pocket.detail=cached?.detail;readPosition=cached?.position
        earlier=cached?.earlier?:false;before=cached?.before;bridgeBefore=cached?.bridgeBefore;bridgeKnown=cached?.bridgeKnown.orEmpty();bridgeBoundary=cached?.bridgeBoundary;coverageVerified=cached?.coverageVerified?:false;loading=false;refreshPending=false;loadingEarlier=false;historyLoaded=cached!=null;checkedThisOpen=false;freshForRead=false
        buffered.clear();browsingEarlier=false;missedUpdates=false;revision++;trace()
    }
    fun clear(){positionJob?.cancel();positionJob=null;positionQueue.clear();attemptedPositions.clear();positionFailures.clear();positioning=false;automaticRetryAllowed=true;generation++;owner=null;ownerScope=null;observedHead=null;pendingHead=null;headSnapshotsSupported=null;readPosition=null;rows=emptyList();buffered.clear();loading=false;refreshPending=false;loadingEarlier=false;historyLoaded=false;checkedThisOpen=false;freshForRead=false;before=null;bridgeBefore=null;bridgeKnown=emptySet();bridgeBoundary=null;coverageVerified=false;earlier=false;browsingEarlier=false;revision++;trace()}
    /** Called only by the resumed, unfiltered viewport after settling at latest. */
    internal fun presentedLatest(){
        val id=Pocket.selected?:return
        if(owner!=id||ownerScope!=scopeKey()||!freshForRead||bridgeBefore!=null||loading||updating||BackendNavigation.teamSelected()||!PocketVoice.foreground||!AppWindowVisibility.focused||BlackoutVisibility.active||PocketVoice.active||Pocket.newTask)return
        val d=Pocket.detail?:return
        PocketNotificationReads.readVisible(id,d.optJSONArray("notifications")?.objects()?:emptyList(),local=Pocket.local,catchup=if(!checkedThisOpen)d.optJSONObject("catchup")else null,environment=Pocket.environmentId)
        checkedThisOpen=true
    }
    fun reconcileInputPosition(row:JSONObject){
        if(BackendNavigation.teamSelected())return
        val id=Pocket.selected?:return;val turn=row.s("turnId");val target=row.s("id")
        if(owner!=id||ownerScope!=scopeKey()||turn.isBlank()||!target.startsWith("$turn/"))return
        val item=row.s("itemId").ifBlank{target.removePrefix("$turn/")}
        if(target in attemptedPositions)return
        val ticket=generation;val scope=scopeKey();val destination=Pocket.captureEnvironment()?:return
        fun current()=Pocket.selected==id&&owner==id&&generation==ticket&&ownerScope==scope&&scopeKey()==scope
        val known=transcriptPositionAnchors(rows.filter{it.s("turnId")==turn&&it.s("kind") !in listOf("turn","turnEnd","request","attachments")}).map{it.s("itemId").ifBlank{it.s("id").removePrefix("$turn/")}}
        if(known.none{it!=item})return
        attemptedPositions.add(target)
        positionQueue[target]=InputPosition(turn,target,item,known)
        if(positionJob?.isActive==true)return
        positioning=true
        positionJob=Pocket.scope.launch{
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
                            cursor?.let{body.put("cursor",it)}
                            val result=Pocket.apiEnvironment(destination,"/api/threads/$id/timeline/position",body)
                            if(!current())return@launch
                            val successor=transcriptDisplayAnchor(rows,result.s("insertBefore")).orEmpty()
                            if(successor.isNotBlank()){
                                if(successor.startsWith("${request.turn}/")){
                                    val repaired=placeTranscriptRowBefore(rows,request.target,successor){it.s("id")}
                                    if(repaired!==rows){rows=repaired;rememberRecent();revision++}
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
                            if(Pocket.error.isBlank())Pocket.error=positionFailures.values.first()
                        }
                    }finally{if(current())positionQueue.remove(request.target)}
                }
            }finally{if(current()){positioning=false;positionJob=null}}

        }
    }
    fun latest(){positionFailures.keys.forEach{attemptedPositions.remove(it)};positionFailures.clear();Pocket.refreshDetail()}
    // Android memory pressure evicts inactive snapshots, never the conversation being read.
    fun release(){recent.clear();trace()}
    suspend fun load(older:Boolean=false,bridgeCursor:String?=null,retryAttempt:Int=0,beforeApply:suspend ()->Unit={}){
        if(BackendNavigation.teamSelected())return
        val id=Pocket.selected?:return
        if(owner!=id||(older&&(!earlier||before==null)))return
        if(loading){if(!older&&bridgeCursor==null){missedUpdates=true;refreshPending=true};return}
        if((older||bridgeCursor!=null)&&refreshPending)return
        val headBaseline=observedHead
        val ticket=generation;val scope=scopeKey();val profileLocal=Pocket.local;val destination=Pocket.captureEnvironment()?:return
        fun current()=Pocket.selected==id&&owner==id&&generation==ticket&&ownerScope==scope&&scopeKey()==scope
        val cursor=bridgeCursor?:if(older)before else null
        loadingEarlier=older
        if(!loadingEarlier){refreshPending=false;freshForRead=false}
        automaticRetryAllowed=true;loading=true;buffered.clear();var baselineRows=rows.associateBy{it.s("id")};var succeeded=false
        try{
            if(!historyLoaded&&!older&&bridgeCursor==null){
                val cached=diskSnapshot(scope,id)
                if(!current())return
                if(cached!=null){rows=cached.rows;Pocket.detail=cached.detail;before=cached.before;earlier=cached.earlier;bridgeBefore=cached.bridgeBefore;bridgeKnown=cached.bridgeKnown;bridgeBoundary=cached.bridgeBoundary;coverageVerified=cached.coverageVerified;readPosition=cached.position;historyLoaded=true;buffered.values.toList().forEach{apply(it,false)};cacheRestoreRevision++;revision++;trace()}
                baselineRows=rows.associateBy{it.s("id")}
            }
            val d=Pocket.apiEnvironment(destination,"/api/threads/$id?view=timeline"+if(cursor!=null)"&before=${android.net.Uri.encode(cursor)}" else "")
            validateTranscriptSnapshot(d,id)
            if(older)beforeApply()
            if(!current())return
            val page=d.optJSONObject("timeline")?:JSONObject();val incoming=normalizeTranscriptMessages(page.optJSONArray("rows")?.objects()?:emptyList())
            incoming.forEach{it.put("version",maxOf(it.optLong("version"),d.optLong("revision")))}
            if(!older&&bridgeCursor==null)rows=authoritativeSnapshotBase(rows,baselineRows,incoming.mapTo(hashSetOf()){it.s("id")},{it.s("id")}){JSONObject(it.toString()).put("version",0)}
            val wasLoaded=historyLoaded&&rows.isNotEmpty()
            val loadedIds=rows.filter{transcriptContentAnchor(it.s("kind"))}.mapTo(hashSetOf()){it.s("id")}
            val incomingContent=incoming.filter{transcriptContentAnchor(it.s("kind"))}
            val incomingIds=incomingContent.mapTo(hashSetOf()){it.s("id")}
            val overlapTargets=if(bridgeCursor!=null&&bridgeKnown.isNotEmpty())bridgeKnown else loadedIds
            val overlaps=incomingContent.any{it.s("id") in overlapTargets}
            val nextBefore=page.s("before").takeIf{it.isNotBlank()}
            if(bridgeCursor!=null){
                if(!overlaps&&page.optBoolean("hasEarlier")&&(nextBefore==null||nextBefore==cursor))throw IllegalStateException("History page did not advance; cached messages are retained.")
                rows=mergeTranscriptPage(rows,incoming,false,{it.s("id")},{it.optLong("version")},insertBefore=transcriptDisplayAnchor(rows,page.s("insertBefore").takeIf{it.isNotBlank()})?:bridgeBoundary,keepPosition={it.s("kind")=="turn"})
                bridgeBefore=if(!overlaps&&page.optBoolean("hasEarlier")&&nextBefore!=cursor)nextBefore else null
                if(bridgeBefore==null){bridgeKnown=emptySet();bridgeBoundary=null;coverageVerified=true}else incomingContent.firstOrNull()?.let{bridgeBoundary=it.s("id")}
            }else{
                if(!older){
                    val retainedOutside=loadedIds-incomingIds
                    val needsBridge=wasLoaded&&incomingContent.isNotEmpty()&&page.optBoolean("hasEarlier")&&(!overlaps||(!coverageVerified&&retainedOutside.isNotEmpty())||bridgeBefore!=null)
                    if(needsBridge){
                        if(bridgeKnown.isEmpty())bridgeKnown=retainedOutside.toList().takeLast(1024).toSet()
                        bridgeBoundary=incomingContent.first().s("id")
                        bridgeBefore=nextBefore
                    }else if(bridgeBefore==null)coverageVerified=true
                }
                val mergeRows=if(!older&&wasLoaded&&!overlaps)incoming.filterNot{it.s("kind")=="turn"&&rows.any{old->old.s("id")==it.s("id")}}else incoming
                rows=mergeTranscriptPage(rows,mergeRows,older,{it.s("id")},{it.optLong("version")},insertBefore=if(older)transcriptDisplayAnchor(rows,page.s("insertBefore").takeIf{it.isNotBlank()})else null,keepPosition={it.s("kind")=="turn"})
            }
            // A recent refresh must not move the oldest loaded cursor forward.
            retainedTranscriptCursor(TranscriptCursor(before,earlier),wasLoaded,older,bridgeCursor!=null,TranscriptCursor(nextBefore,page.optBoolean("hasEarlier"))).let{
                before=it.before;earlier=it.hasEarlier
            }
            browsingEarlier=browsingEarlier||older
            historyLoaded=true
            d.remove("timeline");if((!older&&bridgeCursor==null)||Pocket.detail==null){
                Pocket.detail=d
                val pendingIds=d.optJSONArray("pending")?.objects()?.map{it.s("id")}.orEmpty().toSet()
                rows=rows.filter{it.s("kind")!="request"||it.optJSONObject("request")?.s("id") in pendingIds}
            }
            if(!older&&bridgeCursor==null){freshForRead=true;val head=parseHead(d.optJSONObject("historyHead"),id);headSnapshotsSupported=head!=null;head?.let{observedHead=snapshotTranscriptHead(observedHead,headBaseline,it)}}
            val updates=buffered.values.toList();buffered.clear()
            updates.filter{older||bridgeCursor!=null||it.optLong("version")>d.optLong("revision")}.forEach{apply(it,false)}
            rememberRecent(persist=true);revision++;Pocket.error=positionFailures.values.firstOrNull().orEmpty();succeeded=true;trace()
            if(!older&&bridgeCursor==null){
                // A warm reopening must also repair delayed inputs that were outside
                // its bounded recent page when the previous read was cancelled.
                val incomingIds=incoming.mapTo(hashSetOf()){it.s("id")}
                val firstReplies=incoming.filter{it.s("kind")=="message"}.groupBy{it.s("turnId")}.mapValues{(_,items)->rows.indexOfFirst{it.s("id")==items.first().s("id")}}
                rows.filterIndexed{index,row->row.s("kind")=="user"&&row.s("id") !in incomingIds&&firstReplies[row.s("turnId")]?.let{it>=0&&index>it}==true}.forEach{reconcileInputPosition(it)}
            }
        }catch(e:CancellationException){throw e}catch(e:Exception){if(current()){automaticRetryAllowed=retryableTranscriptRead(e);Pocket.error=PocketNetwork.error(e)}}
        finally {
            if(current()) {
                if(!succeeded&&buffered.isNotEmpty()){buffered.clear();missedUpdates=true}
                loading=false;loadingEarlier=false
                pendingHead?.let{pending->if(automaticRetryAllowed&&headSnapshotsSupported!=false&&transcriptHeadAdvanced(observedHead,pending.baseline,pending.head))missedUpdates=true}
                pendingHead=null
                if(succeeded&&!missedUpdates)refreshPending=false
                if(!succeeded&&!automaticRetryAllowed)refreshPending=false
                if(!succeeded&&automaticRetryAllowed&&!older){
                    missedUpdates=false;refreshPending=true
                    Pocket.scope.launch{
                        // History reads do not start workers. A prolonged outage
                        // must not leave the visible cached conversation frozen
                        // after the initial quick retry budget.
                        delay((1000L shl retryAttempt.coerceAtMost(5)).coerceAtMost(30_000L))
                        if(current()&&PocketVoice.foreground&&refreshPending&&!loading)
                            PocketTranscript.load(retryAttempt=(retryAttempt+1).coerceAtMost(5))
                    }
                }else if(missedUpdates) {
                    missedUpdates=false
                    if(succeeded||automaticRetryAllowed)Pocket.scope.launch{if(!succeeded)delay(450);if(current())PocketTranscript.load()}
                } else if(succeeded) {
                    bridgeBefore?.let{next->Pocket.scope.launch{load(bridgeCursor=next)}}
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
        if(update.optBoolean("reload")){if(loading)missedUpdates=true else Pocket.scheduleRefresh();return}
        if(loading&&buffer){
            val key=update.optJSONObject("row")?.s("id")?:update.s("turnId")
            buffered[key]=update
            // Live progress is readable immediately; replay still protects snapshot reconciliation.
            apply(update,false)
            if(buffered.size>100||buffered.values.sumOf{it.toString().length}>512000){buffered.clear();missedUpdates=true}
            return
        }
        val turnId=update.s("turnId")
        if(turnId.isBlank()){return}
        val next=rows.toMutableList()
        fun put(row:JSONObject){
            val at=next.indexOfFirst{it.s("id")==row.s("id")}
            if(at>=0){if(row.optLong("version")>=next[at].optLong("version"))next[at]=row}
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
            Pocket.detail?.optJSONObject("thread")?.put("status",JSONObject().put("type",if(active)"active" else "idle"))
            if(!active)put(JSONObject().put("id","$turnId/end").put("turnId",turnId).put("kind","turnEnd").put("status",turn.s("status")).put("durationMs",turn.optLong("durationMs")).put("error",turn.optJSONObject("error")).put("text",turn.optJSONObject("error")?.s("message")?:""))
        }
        rows=next;rememberRecent();revision++
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
    fun get(scope:String,id:String,now:Long):T?{val key=scope to id;val entry=entries.remove(key)?:return null;if(now-entry.savedAt>lifetimeMs)return null;entries[key]=entry;return entry.value}
    fun remove(scope:String,id:String){entries.remove(scope to id)}
    fun clear(){entries.clear()}
}

/** Reads may recover automatically; rejected credentials/cursors need changed state. */
internal fun retryableTranscriptRead(error:Throwable)=error is java.io.IOException||error is PocketApiException&&(error.status in listOf(408,429)||error.status in 500..599)
