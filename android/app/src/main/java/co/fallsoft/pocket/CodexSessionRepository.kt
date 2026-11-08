package co.fallsoft.pocket

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Environment-owned message synchronization. No turn admission or read acknowledgements. */
internal object CodexSessionRepository {
    private data class Source(val destination:EnvironmentRequest,val scope:String)
    private data class Intent(val source:Source,val thread:String,val notification:Long=0,val priority:Long=0)
    private val lane=Dispatchers.IO.limitedParallelism(1)
    private val io=CoroutineScope(SupervisorJob()+lane+CoroutineExceptionHandler{_,error->
        // A disposable cache failure must never take down input/voice or expose text.
        android.util.Log.w("NextCompSync","Message cache unavailable: "+error.javaClass.simpleName)
    })
    // Network admission cannot queue behind startup SQLite restoration or parsing.
    private val network=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    // Background reads have their own two-call/five-second budget. They share
    // cached session identity and single-flight results, never UI admission capacity.
    private val backgroundClient by lazy{CodexReadTransport.historyClient.newBuilder()
        .dispatcher(okhttp3.Dispatcher().apply{maxRequests=2;maxRequestsPerHost=2})
        .callTimeout(5,java.util.concurrent.TimeUnit.SECONDS).build()}
    private val readOnlyCapabilities=ConcurrentHashMap<String,Boolean>()
    private val capabilityClient by lazy{Pocket.http.newBuilder()
        .dispatcher(okhttp3.Dispatcher().apply{maxRequests=4;maxRequestsPerHost=2})
        .callTimeout(5,java.util.concurrent.TimeUnit.SECONDS).build()}
    private val nativeCapabilities=ConcurrentHashMap<String,Boolean>()
    private val turnHistorySessions=ConcurrentHashMap.newKeySet<Pair<String,String>>()
    // Archive fallback is temporary membership, not a native-protocol downgrade.
    private val archivedHistorySessions=ConcurrentHashMap.newKeySet<Pair<String,String>>()
    private val archivedHistoryCursors=ConcurrentHashMap<Pair<String,String>,Set<String>>()
    private val capabilityMutex=Mutex()
    private val capabilityRequests=mutableMapOf<String,Deferred<Unit>>()
    /** Negotiate once per paired source before choosing a history protocol.
     * Older gateways are explicit compatibility, never an optimistic native
     * request whose error disables further refresh. Auth/network errors stay errors. */
    private suspend fun ensureCapabilities(source:Source){
        if(nativeCapabilities.containsKey(source.scope))return
        val request=capabilityMutex.withLock{
            capabilityRequests[source.scope]?.takeIf{it.isActive}?:network.async{
                if(!current(source))throw CancellationException("Pairing changed")
                val status=CodexReadTransport.read(source.destination,"/api/status",client=capabilityClient)
                if(!current(source))throw CancellationException("Pairing changed")
                observeCapabilities(source.destination,status.optJSONObject("capabilities")?:JSONObject())
            }.also{created->
                capabilityRequests[source.scope]=created
                created.invokeOnCompletion{network.launch{capabilityMutex.withLock{if(capabilityRequests[source.scope]===created)capabilityRequests.remove(source.scope)}}}
            }
        }
        request.await()
    }
    private val nativeFollowing=ConcurrentHashMap<Pair<String,String>,Boolean>()
    // Native positions are normally durable at completion. One first observation
    // requests position proof; repeated deltas cannot create a metadata read loop.
    private val nativeUnpositioned=linkedMapOf<Pair<String,String>,MutableSet<String>>()
    private val nativeStore by lazy{NativeTranscriptStore(Pocket.context)}
    fun nativeEnabled(destination:EnvironmentRequest?=Pocket.captureEnvironment(),id:String?=Pocket.selected):Boolean =
        destination!=null&&nativeCapabilities[source(destination).scope]!=false&&(id==null||(source(destination).scope to id).let{it !in turnHistorySessions&&it !in archivedHistorySessions})
    private fun native(value:JSONObject)=value.optInt("windowSchema")==3||value.optJSONObject("timeline")?.optInt("native")==1
    private suspend fun nativeCommitted(source:Source,id:String,response:JSONObject,cursor:String?):JSONObject=withContext(lane){
        if(!current(source))throw CancellationException("Pairing changed")
        if(retiredHistoryEpochs[source.scope to id]?.contains(response.optJSONObject("historyHead")?.s("epoch"))==true)throw java.io.IOException("Conversation history was replaced during this read")
        nativeStore.commit(source.scope,id,response,cursor)
        val protected=inFlight.toMutableSet().apply{add(source.scope to id);Pocket.captureEnvironment()?.let{current->Pocket.selected?.let{add(CodexSessionRepository.source(current).scope to it)}}}
        nativeStore.trim(protected)
        requireNotNull(nativeStore.snapshot(source.scope,id)).also{value->
            for(field in listOf("_notificationObserved","_catalogObserved","_liveGenerationAtReadStart"))value.put(field,response.optLong(field))
            value.put("_nativeReadVerified",true)
            val key=source.scope to id
            val stored=putLatest(key,value)
            // A committed current native read settles the same invalidation it
            // observed. Older-page reads never claim current-head freshness.
            if(cursor==null&&stored&&key !in pending&&
                response.optLong("_catalogObserved")== (invalidations[key]?:0L)&&
                response.optLong("_notificationObserved")>= (known[key]?:0L))dirty.remove(key)
            save(source,id,null,key in dirty)
            withContext(Dispatchers.Main.immediate){if(current(source))publishPreview(id,value,source.destination)}
        }
    }
    private fun publishPreview(id:String,value:JSONObject,destination:EnvironmentRequest){
        val row=value.optJSONObject("nativePreview")?:return
        if(Pocket.captureEnvironment(destination.environment.id)?.matches(destination)!=true)return
        val catalog=CodexCatalog.source(destination)
        catalog.tasks=catalog.tasks.map{if(it.id==id)nativeCatalogProjection(destination,it)else it}
    }
    fun nativeCatalogProjection(destination:EnvironmentRequest,task:Task):Task{
        if(!nativeEnabled(destination,task.id))return task
        val row=latest[source(destination).scope to task.id]?.optJSONObject("nativePreview")
            ?:return task.copy(preview="",previewRole="activity",previewKind="pending")
        return task.copy(preview=row.s("text").take(600),previewRole=when(row.s("kind")){"user"->"user";"message"->"codex";else->"activity"},
            previewKind=if(row.s("kind")=="activity")"activity"else"message")
    }
    /** Visible cards request the same committed native head as chat. Cold older
     * cards are not permanently blank after bounded catalog admission evicts them. */
    fun previewVisible(environmentId:String,id:String){
        val destination=Pocket.captureEnvironment(environmentId)?:return
        if(!nativeEnabled(destination,id))return
        val source=source(destination);val key=source.scope to id
        if(latest[key]?.optJSONObject("nativePreview")!=null)return
        io.launch{
            if(!current(source)||key in pending||key in inFlight||latest[key]?.optJSONObject("nativePreview")!=null)return@launch
            ensurePump()
            queue(Intent(source,id,known[key]?:0L,System.currentTimeMillis()))
        }
    }
    fun focus(id:String,position:TranscriptReadPosition?){
        val destination=Pocket.captureEnvironment()?:return
        if(!nativeEnabled(destination,id))return
        val source=source(destination);val key=source.scope to id
        io.launch{
            if(!current(source))return@launch
            val changed=nativeFollowing.put(key,position?.follow?:true)!=(position?.follow?:true)
            nativeStore.focus(source.scope,id,position)
            if(changed)nativeStore.snapshot(source.scope,id)?.let{value->
                putLatest(key,value)
                withContext(Dispatchers.Main.immediate){if(current(source))CodexConversations.repositoryUpdated(id,value)}
            }
        }
    }
    fun releaseNativeMemory(){io.launch{nativeStore.releaseMemory()}}
    suspend fun cachedNative(destination:EnvironmentRequest,id:String):JSONObject?=withContext(lane){
        val source=source(destination)
        if(!current(source))return@withContext null
        nativeStore.snapshot(source.scope,id)?.also{putLatest(source.scope to id,it)}
    }
    suspend fun readOlder(destination:EnvironmentRequest,id:String,cursor:String):JSONObject{
        val source=source(destination)
        ensureCapabilities(source)
        val key=source.scope to id
        if(key in archivedHistorySessions){
            val membershipRevision=withContext(lane){backgroundRevisions[key]?:0L}
            if(cursor !in archivedHistoryCursors[key].orEmpty())throw java.io.IOException("Conversation history changed. Reload before reading earlier messages.")
            val value=CodexReadTransport.read(destination,"/api/threads/"+android.net.Uri.encode(id)+"?view=timeline&before="+android.net.Uri.encode(cursor))
            validateTranscriptSnapshot(value,id)
            withContext(lane){
                if(!current(source))throw CancellationException("Pairing changed")
                if(key !in archivedHistorySessions||(backgroundRevisions[key]?:0L)!=membershipRevision)throw java.io.IOException("Conversation archive state changed during this read. Reload the conversation.")
                value.optJSONObject("timeline")?.s("before")?.takeIf{it.isNotBlank()}?.let{next->archivedHistoryCursors.compute(key){_,old->(old.orEmpty()+next).toList().takeLast(128).toSet()}}
            }
            return value
        }
        if(!nativeEnabled(destination,id))return CodexReadTransport.read(destination,"/api/threads/"+android.net.Uri.encode(id)+"?view=timeline&before="+android.net.Uri.encode(cursor))
        if(cursor.startsWith("native-local:"))return withContext(lane){
            if(!current(source))throw CancellationException("Pairing changed")
            nativeStore.navigate(source.scope,id,cursor)
            requireNotNull(nativeStore.snapshot(source.scope,id)).also{putLatest(source.scope to id,it)}
        }
        val response=CodexReadTransport.read(destination,"/api/threads/"+android.net.Uri.encode(id)+"/native-timeline?before="+android.net.Uri.encode(cursor))
        validateTranscriptSnapshot(response,id)
        return nativeCommitted(source,id,response,cursor)
    }

    private val requestMutex=Mutex()
    private data class CurrentRead(val value:Deferred<JSONObject>,val inspection:Long)
    private val requests=mutableMapOf<Pair<String,String>,CurrentRead>()
    private val inFlight=hashSetOf<Pair<String,String>>()
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val pending=linkedMapOf<Pair<String,String>,Intent>()
    private val latest=ConcurrentHashMap<Pair<String,String>,JSONObject>()
    private val retiredHistoryEpochs=ConcurrentHashMap<Pair<String,String>,MutableSet<String>>()
    // Whole canonical windows are the eviction unit. Cutting rows while keeping
    // the server's original cursor silently skips history on the next page.
    private val memoryWeights=linkedMapOf<Pair<String,String>,Long>()
    private fun putLatest(key:Pair<String,String>,value:JSONObject):Boolean{
        val weight=if(native(value))value.optLong("_nativeWindowBytes")+16384 else value.toString().length.toLong()*2
        if(weight>4L*1024*1024){latest.remove(key);memoryWeights.remove(key);dirty.add(key);return false}
        if(value.optInt("windowSchema") !in listOf(2,3)||value.optJSONObject("timeline")?.optJSONArray("canonicalIds")==null)dirty.add(key)
        latest[key]=value
        memoryWeights.remove(key);memoryWeights[key]=weight
        var total=memoryWeights.values.sum()
        while(total>16L*1024*1024&&memoryWeights.size>1){
            val victim=memoryWeights.entries.first{it.key!=key}
            total-=victim.value;latest.remove(victim.key);memoryWeights.remove(victim.key)
        }
        return true
    }
    private val known=ConcurrentHashMap<Pair<String,String>,Long>()
    private val dirty=ConcurrentHashMap.newKeySet<Pair<String,String>>()
    private data class BackgroundMembership(val evidence:String,val admitted:Boolean)
    private data class BackgroundRefusal(val evidence:String,val code:String,val notification:Long,val attempts:Int=0,val retryAt:Long=0,val inspection:Long=0)
    // Dirty means retained content needs confirmation, never permission to read
    // every historical cache entry. Membership is established anew this process.
    private val backgroundMembership=mutableMapOf<Pair<String,String>,BackgroundMembership>()
    private val backgroundRefusals=mutableMapOf<Pair<String,String>,BackgroundRefusal>()
    private val backgroundRevisions=mutableMapOf<Pair<String,String>,Long>()
    private var backgroundSerial=0L
    private val restoredBackgroundScopes=mutableSetOf<String>()
    private val invalidations=ConcurrentHashMap<Pair<String,String>,Long>()
    private val invalidationSerial=java.util.concurrent.atomic.AtomicLong()
    private val livePending=ConcurrentHashMap<Triple<String,String,String>,Pair<Source,JSONObject>>()
    private val liveScheduled=java.util.concurrent.atomic.AtomicBoolean()
    private val lastLive=ConcurrentHashMap<Pair<String,String>,Long>()
    private val liveSequence=java.util.concurrent.atomic.AtomicLong()
    private val liveGenerations=ConcurrentHashMap<Pair<String,String>,Long>()
    private val positionEvidence=linkedMapOf<Triple<String,String,String>,String>()
    private val writes=linkedMapOf<Pair<String,String>,Triple<Source,JSONObject?,Boolean>>()
    private var writeJob:Job?=null
    private var started=false
    private val db by lazy {object:SQLiteOpenHelper(Pocket.context,"codex-session-messages-v1.sqlite",null,2){
        private fun backgroundTable(db:SQLiteDatabase){db.execSQL("CREATE TABLE IF NOT EXISTS background_refusal(scope TEXT NOT NULL,thread TEXT NOT NULL,evidence TEXT NOT NULL,code TEXT NOT NULL,notification INTEGER NOT NULL,attempts INTEGER NOT NULL,retry_at INTEGER NOT NULL,PRIMARY KEY(scope,thread))")}
        override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE recent(scope TEXT NOT NULL,thread TEXT NOT NULL,body TEXT,notification INTEGER NOT NULL DEFAULT 0,dirty INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,thread))");backgroundTable(db)}
        override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){if(oldVersion<2)backgroundTable(db)}
    }.apply{setWriteAheadLoggingEnabled(true)}.writableDatabase}
    private fun source(destination:EnvironmentRequest)=Source(destination,transcriptCacheOwner(destination.environment.id,destination.endpoint,destination.token))
    private fun restoreBackground(source:Source){
        if(!restoredBackgroundScopes.add(source.scope))return
        db.rawQuery("SELECT thread,evidence,code,notification,attempts,retry_at FROM background_refusal WHERE scope=?",arrayOf(source.scope)).use{cursor->
            while(cursor.moveToNext())backgroundRefusals[source.scope to cursor.getString(0)]=BackgroundRefusal(cursor.getString(1),cursor.getString(2),cursor.getLong(3),cursor.getInt(4),cursor.getLong(5))
        }
    }
    private val emptyReadInspections=EmptyHistoryInspections<Pair<String,String>>()
    fun inspectEmptyRead(id:String,destination:EnvironmentRequest?=Pocket.captureEnvironment()){
        if(destination!=null)emptyReadInspections.observe(source(destination).scope to id)
    }
    // Run on the repository lane before admission as well as direct reads.
    private fun applyEmptyInspection(key:Pair<String,String>){
        val refusal=backgroundRefusals[key]?:return
        if(emptyReadInspections.clears(key,refusal.code,refusal.inspection))clearBackgroundRefusal(key)
    }
    private fun recoveryDue(key:Pair<String,String>)=backgroundRefusals[key]?.let{it.code=="THREAD_HISTORY_UNAVAILABLE"&&System.currentTimeMillis()>=it.retryAt}==true
    private fun backgroundAllowed(key:Pair<String,String>)=backgroundMembership[key]?.admitted==true&&(key !in backgroundRefusals||recoveryDue(key))
    private fun clearBackgroundRefusal(key:Pair<String,String>){
        backgroundRefusals.remove(key)
        db.delete("background_refusal","scope=? AND thread=?",arrayOf(key.first,key.second))
    }
    private fun retainBackgroundRefusal(key:Pair<String,String>,refusal:BackgroundRefusal){
        backgroundRefusals[key]=refusal;pending.remove(key)
        db.execSQL("INSERT OR REPLACE INTO background_refusal(scope,thread,evidence,code,notification,attempts,retry_at) VALUES(?,?,?,?,?,?,?)",arrayOf<Any>(key.first,key.second,refusal.evidence,refusal.code,refusal.notification,refusal.attempts,refusal.retryAt))
    }
    /** Accepted catalog membership, not absence from a partial head/search page. */
    fun catalogObserved(destination:EnvironmentRequest,threads:List<JSONObject>,complete:Boolean,archived:Boolean){
        val source=source(destination)
        io.launch{
            if(!current(source))return@launch
            restoreBackground(source)
            val present=threads.map{it.s("id")}.toSet()
            if(complete&&!archived)backgroundMembership.keys.filter{it.first==source.scope&&it.second !in present}.toList().forEach{key->
                if(backgroundMembership[key]?.admitted==true)backgroundRevisions[key]=++backgroundSerial
                backgroundMembership[key]=requireNotNull(backgroundMembership[key]).copy(admitted=false);pending.remove(key)
            }
            threads.forEach{thread->
                val id=thread.s("id");if(id.isBlank())return@forEach
                val key=source.scope to id
                val isArchived=archived||thread.optBoolean("archived")
                val previousEvidence=backgroundMembership[key]?.evidence?:backgroundRefusals[key]?.evidence
                val previous=runCatching{JSONObject(previousEvidence.orEmpty())}.getOrNull()
                val evidence=JSONObject().put("archived",isArchived).put("status",thread.optJSONObject("status")?.s("type"))
                    .put("updated",thread.optLong("updatedAt")).put("activity",thread.optLong("activityAt"))
                    .put("ephemeral",if(thread.has("ephemeral"))thread.opt("ephemeral")else previous?.opt("ephemeral"))
                    .put("hasRollout",if(thread.has("hasRollout"))thread.opt("hasRollout")else previous?.opt("hasRollout")).toString()
                val old=backgroundMembership.put(key,BackgroundMembership(evidence,!isArchived))
                if(old!=backgroundMembership[key])backgroundRevisions[key]=++backgroundSerial
                if(!isArchived){
                    // A repeated stale current-catalog row cannot override a
                    // newer typed archive refusal or its explicit-read protocol.
                    if(backgroundRefusals[key]?.code!="THREAD_ARCHIVED"||backgroundRefusals[key]?.evidence!=evidence||old?.admitted==false){
                        archivedHistorySessions.remove(key);archivedHistoryCursors.remove(key)
                    }
                    backgroundRefusals[key]?.takeIf{it.evidence!=evidence||old?.admitted==false}?.let{clearBackgroundRefusal(key)}
                }else retainBackgroundRefusal(key,BackgroundRefusal(evidence,"THREAD_ARCHIVED",known[key]?:0L))
                if(!backgroundAllowed(key))pending.remove(key)
                else if((old!=backgroundMembership[key]||recoveryDue(key))&&key in dirty&&key !in pending&&key !in inFlight){ensurePump();queue(Intent(source,id,known[key]?:0L,workTimeMillis(thread.optLong("activityAt"))))}
            }
        }
    }
    fun archiveChanged(destination:EnvironmentRequest,id:String,archived:Boolean){
        val source=source(destination)
        io.launch{
            if(!current(source))return@launch
            restoreBackground(source)
            val key=source.scope to id
            backgroundRevisions[key]=++backgroundSerial
            backgroundMembership[key]=BackgroundMembership("archive:$archived",!archived)
            if(archived)retainBackgroundRefusal(key,BackgroundRefusal("archive:true","THREAD_ARCHIVED",known[key]?:0L))else{
                archivedHistorySessions.remove(key);archivedHistoryCursors.remove(key);clearBackgroundRefusal(key)
                ensurePump();queue(Intent(source,id,known[key]?:0L))
            }
        }
    }
    private suspend fun refuseBackground(source:Source,id:String,error:PocketApiException,evidence:String,generation:Long,notification:Long,inspection:Long){
        if(error.status!=409||error.code !in setOf("THREAD_EPHEMERAL_UNAVAILABLE","THREAD_HISTORY_UNAVAILABLE","THREAD_ARCHIVED","THREAD_NOT_MATERIALIZED"))return
        withContext(lane){
            if(!current(source))return@withContext
            restoreBackground(source)
            val key=source.scope to id
            if((backgroundRevisions[key]?:0L)!=generation||(known[key]?:0L)>notification)return@withContext
            if(error.code=="THREAD_NOT_MATERIALIZED"&&!emptyReadInspections.current(key,inspection))return@withContext
            val attempts=(backgroundRefusals[key]?.attempts?:0)+1
            // Existing catalog/reconnect observations may re-admit a repaired
            // durable source. No timer is installed for this backoff.
            val retryAt=if(error.code=="THREAD_HISTORY_UNAVAILABLE")System.currentTimeMillis()+(30_000L shl (attempts-1).coerceIn(0,4)).coerceAtMost(300_000L)else 0
            retainBackgroundRefusal(key,BackgroundRefusal(evidence,requireNotNull(error.code),notification,attempts,retryAt,inspection))
        }
    }
    /** Shared current read, independent of viewport lifetime. Consumers own private JSON copies. */
    suspend fun readCurrent(destination:EnvironmentRequest,id:String,background:Boolean=false):JSONObject =
        readCurrentBounded(destination,id,background,true)
    private suspend fun readCurrentBounded(destination:EnvironmentRequest,id:String,background:Boolean,followupAllowed:Boolean):JSONObject {
        val source=source(destination);val key=source.scope to id
        // An explicit native empty-session refusal is read availability, never a
        // successful empty snapshot. Re-admit on new catalog/notification evidence.
        withContext(lane){
            restoreBackground(source)
            applyEmptyInspection(key)
            if(backgroundRefusals[key]?.code=="THREAD_NOT_MATERIALIZED")throw PocketApiException(409,
                "Codex has not stored a first message for this session yet. Previously saved messages are retained.","THREAD_NOT_MATERIALIZED")
        }
        val requestedInspection=emptyReadInspections.generation(key)
        val request=requestMutex.withLock{
            requests[key]?.takeIf{it.value.isActive}?:run{
            val inspection=emptyReadInspections.generation(key)
            val value=network.async{
                if(!current(source))throw CancellationException("Pairing changed")
                val baseline=latest[key]?.optJSONObject("historyHead")?.s("epoch")
                val notificationAtStart=known[key]?:0L
                val invalidationAtStart=invalidations[key]?:0L
                val liveAtStart=liveGenerations[key]?:0L
                val backgroundEvidence=withContext(lane){(backgroundMembership[key]?.evidence?:"") to (backgroundRevisions[key]?:0L)}
                val base="/api/threads/"+android.net.Uri.encode(id)
                ensureCapabilities(source)
                val protocol=withContext(lane){nativeEnabled(destination,id) to if(key in archivedHistorySessions)backgroundRevisions[key]?:0L else null}
                val archiveAtStart=protocol.second
                var useNative=protocol.first
                val response=try{
                    CodexReadTransport.read(destination,base+if(useNative)"/native-timeline"else if(background||archiveAtStart!=null)"/head"else"?view=timeline",client=if(background)backgroundClient else CodexReadTransport.historyClient)
                }catch(error:PocketApiException){
                    refuseBackground(source,id,error,backgroundEvidence.first,backgroundEvidence.second,notificationAtStart,inspection)
                    // Only a typed per-session capability refusal changes its
                    // history protocol. Network/auth failures never downgrade,
                    // and another session on this host keeps native chronology.
                    val sessionUnsupported=error.status==409&&error.code=="NATIVE_TIMELINE_UNSUPPORTED"
                    val routeMissing=error.status==404&&nativeCapabilities[source.scope]!=true
                    if(!useNative||!sessionUnsupported&&!routeMissing)throw error
                    if(sessionUnsupported)turnHistorySessions.add(key)else nativeCapabilities[source.scope]=false
                    useNative=false
                    try{CodexReadTransport.read(destination,base+if(background)"/head"else"?view=timeline",client=if(background)backgroundClient else CodexReadTransport.historyClient)}
                    catch(fallback:PocketApiException){
                        refuseBackground(source,id,fallback,backgroundEvidence.first,backgroundEvidence.second,notificationAtStart,inspection)
                        throw fallback
                    }
                }
                if(archiveAtStart!=null)withContext(lane){
                    if(!current(source))throw CancellationException("Pairing changed")
                    if(key !in archivedHistorySessions||(backgroundRevisions[key]?:0L)!=archiveAtStart)throw java.io.IOException("Conversation archive state changed during this read. Reload the conversation.")
                }
                val currentEpoch=latest[key]?.optJSONObject("historyHead")?.s("epoch")
                if(!baseline.isNullOrBlank()&&currentEpoch!=baseline&&response.optJSONObject("historyHead")?.s("epoch")==baseline)
                    throw java.io.IOException("Message runtime changed during the read")
                response.put("_notificationObserved",notificationAtStart)
                response.put("_catalogObserved",invalidationAtStart)
                response.put("_liveGenerationAtReadStart",liveAtStart)
                if(useNative){
                    validateTranscriptSnapshot(response,id)
                    if(response.optJSONObject("timeline")?.optInt("native")!=1)throw java.io.IOException("Native timeline was not returned")
                    nativeCommitted(source,id,response,null)
                }else response
            }
            CurrentRead(value,inspection).also{created->
                requests[key]=created
                value.invokeOnCompletion{network.launch{requestMutex.withLock{if(requests[key]===created)requests.remove(key)}}}
            }
            }
        }
        val response=try{emptyReadInspections.await(key,request.inspection,requestedInspection,followupAllowed,
            isEmpty={it is PocketApiException&&it.status==409&&it.code=="THREAD_NOT_MATERIALIZED"},
            read={request.value.await()},reinspect={readCurrentBounded(destination,id,background,false)})}catch(error:PocketApiException){
            if(background||key in archivedHistorySessions||error.status!=409||error.code!="THREAD_ARCHIVED")throw error
            // Explicit inspection may read archived rollout history. Background
            // repairs never take this fallback, resume or unarchive the session.
            val notification=known[key]?:0L;val invalidation=invalidations[key]?:0L
            val live=liveGenerations[key]?:0L
            val membershipRevision=withContext(lane){
                if(backgroundRefusals[key]?.code!="THREAD_ARCHIVED")throw error // Refusal was superseded while this shared read was pending.
                backgroundRevisions[key]?:0L
            }
            val value=CodexReadTransport.read(destination,"/api/threads/"+android.net.Uri.encode(id)+"/head")
            validateTranscriptSnapshot(value,id)
            withContext(lane){
                if(!current(source))throw CancellationException("Pairing changed")
                if((backgroundRevisions[key]?:0L)!=membershipRevision)throw java.io.IOException("Conversation archive state changed during this read. Reload the conversation.")
                archivedHistorySessions.add(key)
                archivedHistoryCursors[key]=value.optJSONObject("timeline")?.s("before")?.takeIf{it.isNotBlank()}?.let{setOf(it)}?:emptySet()
            }
            value.put("_notificationObserved",notification).put("_catalogObserved",invalidation).put("_liveGenerationAtReadStart",live)
        }
        withContext(lane){
            if(current(source)){
                restoreBackground(source)
                if(backgroundRefusals[key]?.code!="THREAD_ARCHIVED")clearBackgroundRefusal(key)
                if(key in archivedHistorySessions)response.optJSONObject("timeline")?.s("before")?.takeIf{it.isNotBlank()}?.let{cursor->
                    archivedHistoryCursors.compute(key){_,old->(old.orEmpty()+cursor).toList().takeLast(128).toSet()}
                }
            }
        }
        return if(native(response))response else withContext(Dispatchers.Default){JSONObject(response.toString())}
    }
    internal fun currentSnapshot(destination:EnvironmentRequest,id:String,value:JSONObject):Boolean=
        native(value)||value.optLong("_catalogObserved")==(invalidations[source(destination).scope to id]?:0L)
    private fun current(source:Source)=Pocket.captureEnvironment(source.destination.environment.id)?.matches(source.destination)==true
    fun peek(id:String):JSONObject? {
        val destination=Pocket.captureEnvironment()?:return null
        return latest[source(destination).scope to id]?.takeIf{!nativeEnabled(destination,id)||native(it)}
    }
    fun canPresent(id:String,value:JSONObject):Boolean=(value.optInt("windowSchema")==3||!nativeEnabled(id=id)&&value.optInt("windowSchema")==2)&&value.optJSONObject("timeline")?.optJSONArray("canonicalIds")!=null&&notificationWatermark(id)<=value.optLong("throughNotification")&&!isDirty(id)
    fun isDirty(id:String):Boolean {
        val destination=Pocket.captureEnvironment()?:return true
        return (source(destination).scope to id) in dirty
    }
    fun preview(id:String,destination:EnvironmentRequest?=Pocket.captureEnvironment()){
        if(destination==null)return
        val key=source(destination).scope to id
        if(android.os.SystemClock.elapsedRealtime()-(lastLive[key]?:0L)<2000)return
        invalidate(id,destination)
    }
    fun notificationWatermark(id:String):Long {
        val destination=Pocket.captureEnvironment()?:return 0
        return known[source(destination).scope to id]?:0L
    }
    /** Only server-advertised read-only snapshots admit cold background reads.
     * Legacy sources keep live/cache evidence and explicit foreground control.
     */
    fun observeCapabilities(destination:EnvironmentRequest,capabilities:JSONObject?){
        val source=source(destination)
        if(capabilities!=null){
            val enabled=capabilities.optInt("canonicalTimeline")==1
            val was=nativeCapabilities.put(source.scope,enabled)
            if(was!=enabled)Pocket.scope.launch(Dispatchers.Main.immediate){
                if(current(source)&&Pocket.captureEnvironment()?.matches(source.destination)==true&&PocketTranscript.canonicalMode!=nativeEnabled(destination))
                    Pocket.selected?.let{CodexConversations.select(it,force=true,openLatest=true);CodexConversations.invalidate(immediate=true)}
            }
        }
        val allowed=capabilities?.optBoolean("readOnlyTimeline")==true
        val previous=readOnlyCapabilities.put(source.scope,allowed)
        if(previous==allowed||!allowed)return
        io.launch{
            if(!current(source))return@launch
            ensurePump()
            restoreBackground(source)
            dirty.filter{it.first==source.scope&&backgroundAllowed(it)}.forEach{key->
                if(key !in pending)pending[key]=Intent(source,key.second,known[key]?:0L,
                    workTimeMillis(latest[key]?.optJSONObject("thread")?.optLong("activityAt")?:0L))
            }
            trimPending();wake.trySend(Unit)
        }
    }
    private fun trimPending(){
        if(pending.size<=32)return
        val keep=pending.entries.sortedWith(
            compareByDescending<Map.Entry<Pair<String,String>,Intent>>{readOnlyCapabilities[it.value.source.scope]==true}
                .thenByDescending{it.value.priority}
        ).take(32).map{it.key}.toSet()
        pending.keys.filter{it !in keep}.forEach{pending.remove(it)}
    }
    fun activate(){
        val destination=Pocket.captureEnvironment()?:return
        val source=source(destination)
        io.launch{
            ensurePump()
            if(!current(source))return@launch
            restoreBackground(source)
            db.rawQuery("SELECT thread,body,notification,dirty FROM recent WHERE scope=? ORDER BY updated DESC LIMIT 256",arrayOf(source.scope)).use{cursor->
                while(cursor.moveToNext()){
                    val thread=cursor.getString(0);val key=source.scope to thread
                    val alreadyLoaded=latest.containsKey(key)
                    known[key]=maxOf(known[key]?:0L,cursor.getLong(2))
                    if(!nativeEnabled(source.destination,thread)&&!cursor.isNull(1))runCatching{JSONObject(cursor.getString(1))}.getOrNull()?.let{if(!latest.containsKey(key))putLatest(key,it)}
                    if(cursor.getInt(3)!=0&&(!alreadyLoaded||key in dirty)){
                        dirty.add(key)
                        if(backgroundAllowed(key))pending[key]=Intent(source,thread,cursor.getLong(2))
                    }
                }
            }
            trimPending()
            val selected=Pocket.selected
            if(selected!=null)latest[source.scope to selected]?.takeIf{canPresent(selected,it)}?.let{value->
                withContext(Dispatchers.Main.immediate){
                    if(Pocket.captureEnvironment()?.matches(source.destination)==true)CodexConversations.repositoryUpdated(selected,value)
                }
            }
            wake.trySend(Unit)
        }
    }
    /** Public live events enter this store even if their chat has never opened.
     * Complete row updates coalesce before parsing/copying/persisting each tail. */
    fun timeline(destination:EnvironmentRequest,update:JSONObject){
        val id=update.s("threadId");if(id.isBlank())return
        val source=source(destination)
        inspectEmptyRead(id,destination)
        liveGenerations[source.scope to id]=liveSequence.incrementAndGet()
        val item=update.optJSONObject("row")?.s("id")?:update.s("turnId","head")
        livePending[Triple(source.scope,id,item)]=source to update
        flushLiveSoon()
    }
    private fun flushLiveSoon(){
        if(!liveScheduled.compareAndSet(false,true))return
        io.launch{
            try{
                delay(80)
                val batch=livePending.entries.mapNotNull{entry->if(livePending.remove(entry.key,entry.value))entry.key to entry.value else null}
                ensurePump()
                for(group in batch.groupBy{it.first.first to it.first.second}.values){
                    val source=group.first().second.first;val id=group.first().first.second
                    if(!current(source))continue
                    val key=source.scope to id;lastLive[key]=android.os.SystemClock.elapsedRealtime()
                    val previous=load(source,id)
                    val updates=group.map{it.second.second}.sortedBy{it.optLong("version")}
                    if(nativeEnabled(source.destination,id)){
                        var repair=previous==null
                        val unknown=nativeUnpositioned.getOrPut(key){linkedSetOf()}
                        for(update in updates){
                            val rowId=update.optJSONObject("row")?.s("id")?.takeIf{it.isNotBlank()}
                                ?:update.s("nativeItemId").takeIf{it.isNotBlank()}?.let{update.s("turnId")+"/"+it}
                            if(nativeStore.live(source.scope,id,update)){if(rowId!=null)unknown.remove(rowId);continue}
                            val method=update.s("nativeMethod")
                            val terminal=method=="item/completed"||method=="turn/completed"
                            if(rowId==null||terminal||unknown.add(rowId))repair=true
                            while(unknown.size>128)unknown.remove(unknown.first())
                        }
                        while(nativeUnpositioned.size>256)nativeUnpositioned.remove(nativeUnpositioned.keys.first())
                        val next=nativeStore.snapshot(source.scope,id)
                        if(next!=null){
                            putLatest(key,next)
                            withContext(Dispatchers.Main.immediate){if(current(source)){
                                publishPreview(id,next,source.destination)
                                CodexConversations.repositoryUpdated(id,next)
                            }}
                        }
                        if(repair){
                            // The repository owns a successful native repair. Scheduling
                            // the viewport pump as well issues a second sequential read
                            // after the shared in-flight read has already completed.
                            withContext(Dispatchers.Main.immediate){if(current(source)&&Pocket.captureEnvironment()?.matches(source.destination)==true&&Pocket.selected==id)PocketTranscript.repositoryRefreshPending()}
                            queue(Intent(source,id))
                        }
                        continue
                    }
                    if(previous!=null){
                        val next=JSONObject(previous.toString());val page=next.getJSONObject("timeline")
                        var rows=page.getJSONArray("rows").objects()
                        for(update in updates){
                            rows=reduceTranscriptUpdate(rows,update)
                            update.optJSONObject("historyHead")?.let{next.put("historyHead",it)}
                        }
                        page.put("rows",org.json.JSONArray(ordered(source.scope,id,rows)))
                        if(putLatest(key,next))save(source,id,next,key in dirty)else save(source,id,null,true)
                    }
                    val epoch=previous?.optJSONObject("historyHead")?.s("epoch")
                    if(previous==null||updates.any{it.optBoolean("reload")||it.has("turn")||(!epoch.isNullOrBlank()&&it.optJSONObject("historyHead")?.s("epoch")?.let{next->next!=epoch}==true)})queue(Intent(source,id))
                }
            }finally{
                liveScheduled.set(false)
                if(livePending.isNotEmpty())flushLiveSoon()
            }
        }
    }
    fun historyReplaced(id:String,head:JSONObject,destination:EnvironmentRequest?=Pocket.captureEnvironment()){
        if(destination==null||id.isBlank())return
        val source=source(destination);val key=source.scope to id
        val epoch=latest[key]?.optJSONObject("historyHead")?.s("epoch")
        if(!epoch.isNullOrBlank()&&epoch!=head.s("epoch"))retiredHistoryEpochs.computeIfAbsent(key){ConcurrentHashMap.newKeySet<String>()}.add(epoch)
        dirty.add(key);invalidations[key]=invalidationSerial.incrementAndGet()
        io.launch{
            if(!current(source))return@launch
            val stored=nativeStore.snapshot(source.scope,id)?.optJSONObject("historyHead")?.s("epoch")
            if(!stored.isNullOrBlank()&&stored!=head.s("epoch"))retiredHistoryEpochs.computeIfAbsent(key){ConcurrentHashMap.newKeySet<String>()}.add(stored)
            latest.remove(key);nativeStore.replaceHistory(source.scope,id)
            withContext(Dispatchers.Main){if(Pocket.captureEnvironment()?.matches(destination)==true&&Pocket.selected==id){CodexConversations.select(id,force=true,openLatest=true);CodexConversations.invalidate(immediate=true)}}
            queue(Intent(source,id))
        }
    }
    fun invalidate(id:String,destination:EnvironmentRequest?=Pocket.captureEnvironment(),notification:Long=0,priorityAt:Long=0){
        if(id.isBlank()||destination==null)return
        val source=source(destination);val key=source.scope to id
        if(notification>0){
            if(notification<=(known[key]?:0L))return
            known.merge(key,notification){a,b->maxOf(a,b)}
        }
        if(!current(source))return
        // Publish missing-content evidence before the card can recompose or be
        // tapped. Disk restoration/network admission must never delay this flag.
        invalidations[key]=invalidationSerial.incrementAndGet()
        dirty.add(key)
        io.launch{
            ensurePump();if(!current(source))return@launch
            load(source,id)
            queue(Intent(source,id,notification,maxOf(workTimeMillis(priorityAt),if(notification>0)System.currentTimeMillis() else 0)))
        }
    }
    /** Persist server-proven ordering in the shared tail as well as the open
     * viewport. Reopening cannot replay the arrival order from another cache.
     * This is a read-result application; it never submits or acknowledges work. */
    fun positioned(destination:EnvironmentRequest,id:String,target:String,successor:String){
        val source=source(destination)
        io.launch{
            if(!current(source))return@launch
            val evidenceKey=Triple(source.scope,id,target)
            positionEvidence.remove(evidenceKey);positionEvidence[evidenceKey]=successor
            while(positionEvidence.size>512)positionEvidence.remove(positionEvidence.keys.first())
            val previous=load(source,id)?:return@launch
            val old=previous.optJSONObject("timeline")?.optJSONArray("rows")?.objects()?:return@launch
            val repaired=ordered(source.scope,id,old)
            if(repaired===old)return@launch
            val next=JSONObject(previous.toString())
            next.getJSONObject("timeline").put("rows",org.json.JSONArray(repaired))
            val key=source.scope to id
            if(putLatest(key,next))save(source,id,next,key in dirty)else save(source,id,null,true)
        }
    }
    // Proof may precede the coalesced live projection. Retain it independently
    // of row presence; arrival timing cannot erase a confirmed same-thread edge.
    private fun ordered(scope:String,id:String,rows:List<JSONObject>):List<JSONObject>{
        var result=groupTranscriptTurns(rows)
        for((key,successor) in positionEvidence)if(key.first==scope&&key.second==id){
            val target=transcriptDisplayAnchor(result,key.third)?:continue
            result=result.map{if(it.s("id")==target)positionedTranscriptInput(it,successor)else it}
            if(successor.isBlank())continue
            val before=transcriptDisplayAnchor(result,successor)?:continue
            result=placeTranscriptRowBefore(result,target,before){it.s("id")}
        }
        return result
    }
    private fun enforcePositions(scope:String,id:String,value:JSONObject){
        val page=value.optJSONObject("timeline")?:return
        val rows=page.optJSONArray("rows")?.objects()?:return
        page.put("rows",org.json.JSONArray(ordered(scope,id,rows)))
    }
    /** Save an authoritative recent page without retaining pending requests/command authority. */
    fun accepted(destination:EnvironmentRequest,id:String,value:JSONObject){
        if(native(value))return
        validateTranscriptSnapshot(value,id)
        val safe=presentation(value)
        val source=source(destination)
        io.launch{
            if(!current(source))return@launch
            retainNewerLiveHead(source.scope to id,safe,value.optLong("_liveGenerationAtReadStart"))
            enforcePositions(source.scope,id,safe)
            val stored=putLatest(source.scope to id,safe)
            val high=safe.optLong("throughNotification")
            known[source.scope to id]=maxOf(known[source.scope to id]?:0L,high)
            val key=source.scope to id
            if(stored&&key !in pending&&value.optLong("_catalogObserved")==(invalidations[key]?:0L))dirty.remove(key)
            save(source,id,if(stored)safe else null,key in dirty)
        }
    }
    private fun retainNewerLiveHead(key:Pair<String,String>,safe:JSONObject,readBaseline:Long){
        // A persisted higher server counter is not a live event. Only output
        // received after this read started may outrank its authoritative page.
        if((liveGenerations[key]?:0L)<=readBaseline)return
        val previous=latest[key]?:return
        val oldHead=previous.optJSONObject("historyHead")?:return
        val newHead=safe.optJSONObject("historyHead")?:return
        if(oldHead.s("epoch")!=newHead.s("epoch")||oldHead.optLong("version")<=newHead.optLong("version"))return
        val rows=newerTranscriptHead(safe.getJSONObject("timeline").getJSONArray("rows").objects(),previous.getJSONObject("timeline").getJSONArray("rows").objects())?:return
        safe.getJSONObject("timeline").put("rows",org.json.JSONArray(rows))
        safe.put("historyHead",oldHead)
    }
    private fun presentation(value:JSONObject):JSONObject {
        if(native(value))return value
        val sourcePage=value.getJSONObject("timeline")
        val rows=normalizeTranscriptMessages(sourcePage.getJSONArray("rows").objects().filter{it.s("kind")!="request"}.map(::canonicalTranscriptInput))
        val page=JSONObject(sourcePage,sourcePage.keys().asSequence().toList().toTypedArray())
            .put("rows",org.json.JSONArray(rows))
            .put("canonicalIds",org.json.JSONArray(rows.filter{transcriptContentAnchor(it.s("kind"))}.map{it.s("id")}))
        return JSONObject().put("windowSchema",2).put("thread",transcriptPresentationDetail(value.getJSONObject("thread").s("id"),value).getJSONObject("thread"))
            .put("timeline",page).put("revision",value.optLong("revision")).put("historyHead",value.optJSONObject("historyHead"))
            .put("throughNotification",maxOf(value.optJSONArray("notifications")?.objects()?.maxOfOrNull{it.optLong("id")}?:0L,value.optLong("throughNotification"),value.optLong("_notificationObserved")))
    }
    private fun load(source:Source,id:String):JSONObject? {
        val key=source.scope to id
        latest[key]?.takeIf{!nativeEnabled(source.destination,id)||native(it)}?.let{return it}
        if(nativeEnabled(source.destination,id))return nativeStore.snapshot(source.scope,id)?.also{putLatest(key,it)}
        return db.rawQuery("SELECT body,notification FROM recent WHERE scope=? AND thread=?",arrayOf(source.scope,id)).use{cursor->
            if(!cursor.moveToFirst())return@use null
            known[key]=maxOf(known[key]?:0L,cursor.getLong(1))
            if(cursor.isNull(0))null else runCatching{JSONObject(cursor.getString(0))}.getOrNull()?.also{putLatest(key,it)}
        }
    }
    private fun save(source:Source,id:String,value:JSONObject?,dirty:Boolean){
        val key=source.scope to id
        val previous=writes[key]
        writes[key]=Triple(source,if(value!=null&&native(value))null else value?:previous?.second,dirty)
        if(writeJob?.isActive==true)return
        writeJob=io.launch{
            delay(200)
            val batch=writes.toMap();writes.clear();writeJob=null
            db.beginTransaction()
            try{batch.forEach{(entry,write)->persist(write.first,entry.second,write.second,write.third)};db.setTransactionSuccessful()}
            finally{db.endTransaction()}
        }
    }
    private fun persist(source:Source,id:String,value:JSONObject?,dirty:Boolean){
        db.execSQL("INSERT INTO recent(scope,thread,body,notification,dirty,updated) VALUES(?,?,?,?,?,?) ON CONFLICT(scope,thread) DO UPDATE SET body=COALESCE(excluded.body,recent.body),notification=MAX(recent.notification,excluded.notification),dirty=excluded.dirty,updated=excluded.updated",
            arrayOf<Any?>(source.scope,id,value?.toString(),known[source.scope to id]?:0L,if(dirty)1 else 0,System.currentTimeMillis()))
        // Cache eviction affects presentation only; never deletes commands, drafts or pairing.
        db.execSQL("DELETE FROM recent WHERE rowid IN (SELECT rowid FROM recent WHERE dirty=0 ORDER BY updated DESC LIMIT -1 OFFSET 512)")
        // Basic SQLite operations retain support for the declared Android28 minimum.
        val oversized=mutableListOf<Long>();var retainedBytes=0L
        db.rawQuery("SELECT rowid,length(CAST(body AS BLOB)) FROM recent WHERE body IS NOT NULL ORDER BY updated DESC,rowid DESC",null).use{cursor->
            while(cursor.moveToNext()){
                retainedBytes+=cursor.getLong(1)
                if(retainedBytes>64L*1024*1024)oversized.add(cursor.getLong(0))
            }
        }
        if(oversized.isNotEmpty())db.execSQL("UPDATE recent SET body=NULL WHERE rowid IN ("+oversized.joinToString(","){"?"}+")",oversized.toTypedArray())
    }
    private fun queue(intent:Intent){
        val key=intent.source.scope to intent.thread
        restoreBackground(intent.source)
        applyEmptyInspection(key)
        known[key]=maxOf(known[key]?:0L,intent.notification)
        // Fresh notifications may warrant one new inspection, but an archive or
        // authoritative catalog removal is not reversed by a content hint.
        backgroundMembership.putIfAbsent(key,BackgroundMembership("",true))
        backgroundRefusals[key]?.takeIf{it.code!="THREAD_ARCHIVED"&&intent.notification>it.notification}?.let{clearBackgroundRefusal(key)}
        dirty.add(key)
        save(intent.source,intent.thread,null,true)
        if(!backgroundAllowed(key))return
        val old=pending[key]
        pending[key]=intent.copy(priority=maxOf(intent.priority,old?.priority?:0L))
        trimPending()
        wake.trySend(Unit)
    }
    private fun ensurePump(){
        if(started)return
        started=true
        repeat(2){io.launch{
            for(signal in wake){
                delay(120) // One burst, one canonical repair; token deltas do not trigger reads.
                while(pending.isNotEmpty()){
                    val key=pending.entries.filter{it.key !in inFlight&&backgroundAllowed(it.key)&&readOnlyCapabilities[it.value.source.scope]==true}
                        .maxByOrNull{it.value.priority}?.key?:break
                    val intent=pending.remove(key)?:continue
                    if(!current(intent.source))continue
                    inFlight.add(key)
                    if(pending.isNotEmpty())wake.trySend(Unit)
                    try{
                        val response=readCurrent(intent.source.destination,intent.thread,background=true)
                        if(!current(intent.source))continue
                        validateTranscriptSnapshot(response,intent.thread)
                        val safe=presentation(response)
                        if(!native(safe)){
                            retainNewerLiveHead(key,safe,response.optLong("_liveGenerationAtReadStart"))
                            enforcePositions(intent.source.scope,intent.thread,safe)
                        }
                        val stored=putLatest(key,safe)
                        if(stored&&!pending.containsKey(key)&&response.optLong("_catalogObserved")==(invalidations[key]?:0L))dirty.remove(key)
                        save(intent.source,intent.thread,if(stored)safe else null,key in dirty)
                        withContext(Dispatchers.Main.immediate){
                            if(Pocket.captureEnvironment()?.matches(intent.source.destination)==true)
                                CodexConversations.repositoryUpdated(intent.thread,safe)
                        }
                    }catch(error:CancellationException){if(current(intent.source))throw error}
                    catch(_:Exception){
                        // Durable dirty state survives transport loss. Only a failed
                        // background read hands an active viewport to its existing
                        // bounded recovery pump; success must never schedule both.
                        save(intent.source,intent.thread,null,true)
                        if(nativeEnabled(intent.source.destination,intent.thread))withContext(Dispatchers.Main.immediate){
                            if(current(intent.source)&&Pocket.captureEnvironment()?.matches(intent.source.destination)==true&&Pocket.selected==intent.thread)
                                CodexConversations.invalidate()
                        }
                    }finally{
                        inFlight.remove(key)
                        if(pending.isNotEmpty())wake.trySend(Unit)
                    }
                }
            }
        }}
    }
}
