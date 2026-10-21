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
    private val requestMutex=Mutex()
    private val requests=mutableMapOf<Pair<String,String>,Deferred<JSONObject>>()
    private val inFlight=hashSetOf<Pair<String,String>>()
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val pending=linkedMapOf<Pair<String,String>,Intent>()
    private val latest=ConcurrentHashMap<Pair<String,String>,JSONObject>()
    // Whole canonical windows are the eviction unit. Cutting rows while keeping
    // the server's original cursor silently skips history on the next page.
    private val memoryWeights=linkedMapOf<Pair<String,String>,Long>()
    private fun putLatest(key:Pair<String,String>,value:JSONObject):Boolean{
        val weight=value.toString().length.toLong()*2
        if(weight>4L*1024*1024){latest.remove(key);memoryWeights.remove(key);dirty.add(key);return false}
        if(value.optInt("windowSchema")!=2||value.optJSONObject("timeline")?.optJSONArray("canonicalIds")==null)dirty.add(key)
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
    private val db by lazy {object:SQLiteOpenHelper(Pocket.context,"codex-session-messages-v1.sqlite",null,1){
        override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE recent(scope TEXT NOT NULL,thread TEXT NOT NULL,body TEXT,notification INTEGER NOT NULL DEFAULT 0,dirty INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,thread))")}
        override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
    }.apply{setWriteAheadLoggingEnabled(true)}.writableDatabase}
    private fun source(destination:EnvironmentRequest)=Source(destination,transcriptCacheOwner(destination.environment.id,destination.endpoint,destination.token))
    /** Shared current read, independent of viewport lifetime. Consumers own private JSON copies. */
    suspend fun readCurrent(destination:EnvironmentRequest,id:String,background:Boolean=false):JSONObject {
        val source=source(destination);val key=source.scope to id
        val request=requestMutex.withLock{
            requests[key]?.takeIf{it.isActive}?:network.async{
                if(!current(source))throw CancellationException("Pairing changed")
                val baseline=latest[key]?.optJSONObject("historyHead")?.s("epoch")
                val notificationAtStart=known[key]?:0
                val invalidationAtStart=invalidations[key]?:0
                val liveAtStart=liveGenerations[key]?:0
                val path="/api/threads/"+android.net.Uri.encode(id)+(if(background)"/head" else "?view=timeline")
                val response=CodexReadTransport.read(destination,path,client=if(background)backgroundClient else CodexReadTransport.historyClient)
                val currentEpoch=latest[key]?.optJSONObject("historyHead")?.s("epoch")
                if(!baseline.isNullOrBlank()&&currentEpoch!=baseline&&response.optJSONObject("historyHead")?.s("epoch")==baseline)
                    throw java.io.IOException("Message runtime changed during the read")
                response.put("_notificationObserved",notificationAtStart)
                response.put("_catalogObserved",invalidationAtStart)
                response.put("_liveGenerationAtReadStart",liveAtStart)
            }.also{created->
                requests[key]=created
                created.invokeOnCompletion{network.launch{requestMutex.withLock{if(requests[key]===created)requests.remove(key)}}}
            }
        }
        val response=request.await()
        return withContext(Dispatchers.Default){JSONObject(response.toString())}
    }
    internal fun currentSnapshot(destination:EnvironmentRequest,id:String,value:JSONObject):Boolean=
        value.optLong("_catalogObserved")==(invalidations[source(destination).scope to id]?:0)
    private fun current(source:Source)=Pocket.captureEnvironment(source.destination.environment.id)?.matches(source.destination)==true
    fun peek(id:String):JSONObject? {
        val destination=Pocket.captureEnvironment()?:return null
        return latest[source(destination).scope to id]
    }
    fun canPresent(id:String,value:JSONObject):Boolean=value.optInt("windowSchema")==2&&value.optJSONObject("timeline")?.optJSONArray("canonicalIds")!=null&&notificationWatermark(id)<=value.optLong("throughNotification")&&!isDirty(id)
    fun isDirty(id:String):Boolean {
        val destination=Pocket.captureEnvironment()?:return true
        return (source(destination).scope to id) in dirty
    }
    fun preview(id:String,destination:EnvironmentRequest?=Pocket.captureEnvironment()){
        if(destination==null)return
        val key=source(destination).scope to id
        if(android.os.SystemClock.elapsedRealtime()-(lastLive[key]?:0)<2000)return
        invalidate(id,destination)
    }
    fun notificationWatermark(id:String):Long {
        val destination=Pocket.captureEnvironment()?:return 0
        return known[source(destination).scope to id]?:0
    }
    /** Only server-advertised read-only snapshots admit cold background reads.
     * Legacy sources keep live/cache evidence and explicit foreground control.
     */
    fun observeCapabilities(destination:EnvironmentRequest,capabilities:JSONObject?){
        val source=source(destination)
        val allowed=capabilities?.optBoolean("readOnlyTimeline")==true
        val previous=readOnlyCapabilities.put(source.scope,allowed)
        if(previous==allowed||!allowed)return
        io.launch{
            if(!current(source))return@launch
            ensurePump()
            dirty.filter{it.first==source.scope}.forEach{key->
                if(key !in pending)pending[key]=Intent(source,key.second,known[key]?:0,
                    workTimeMillis(latest[key]?.optJSONObject("thread")?.optLong("activityAt")?:0))
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
            db.rawQuery("SELECT thread,body,notification,dirty FROM recent WHERE scope=? ORDER BY updated DESC LIMIT 256",arrayOf(source.scope)).use{cursor->
                while(cursor.moveToNext()){
                    val thread=cursor.getString(0);val key=source.scope to thread
                    val alreadyLoaded=latest.containsKey(key)
                    known[key]=maxOf(known[key]?:0,cursor.getLong(2))
                    if(!cursor.isNull(1))runCatching{JSONObject(cursor.getString(1))}.getOrNull()?.let{if(!latest.containsKey(key))putLatest(key,it)}
                    if(cursor.getInt(3)!=0&&(!alreadyLoaded||key in dirty)){dirty.add(key);pending[key]=Intent(source,thread,cursor.getLong(2))}
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
    fun invalidate(id:String,destination:EnvironmentRequest?=Pocket.captureEnvironment(),notification:Long=0,priorityAt:Long=0){
        if(id.isBlank()||destination==null)return
        val source=source(destination);val key=source.scope to id
        if(notification>0){
            if(notification<=(known[key]?:0))return
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
        validateTranscriptSnapshot(value,id)
        val safe=presentation(value)
        val source=source(destination)
        io.launch{
            if(!current(source))return@launch
            retainNewerLiveHead(source.scope to id,safe,value.optLong("_liveGenerationAtReadStart"))
            enforcePositions(source.scope,id,safe)
            val stored=putLatest(source.scope to id,safe)
            val high=safe.optLong("throughNotification")
            known[source.scope to id]=maxOf(known[source.scope to id]?:0,high)
            val key=source.scope to id
            if(stored&&key !in pending&&value.optLong("_catalogObserved")==(invalidations[key]?:0))dirty.remove(key)
            save(source,id,if(stored)safe else null,key in dirty)
        }
    }
    private fun retainNewerLiveHead(key:Pair<String,String>,safe:JSONObject,readBaseline:Long){
        // A persisted higher server counter is not a live event. Only output
        // received after this read started may outrank its authoritative page.
        if((liveGenerations[key]?:0)<=readBaseline)return
        val previous=latest[key]?:return
        val oldHead=previous.optJSONObject("historyHead")?:return
        val newHead=safe.optJSONObject("historyHead")?:return
        if(oldHead.s("epoch")!=newHead.s("epoch")||oldHead.optLong("version")<=newHead.optLong("version"))return
        val rows=newerTranscriptHead(safe.getJSONObject("timeline").getJSONArray("rows").objects(),previous.getJSONObject("timeline").getJSONArray("rows").objects())?:return
        safe.getJSONObject("timeline").put("rows",org.json.JSONArray(rows))
        safe.put("historyHead",oldHead)
    }
    private fun presentation(value:JSONObject):JSONObject {
        val sourcePage=value.getJSONObject("timeline")
        val rows=normalizeTranscriptMessages(sourcePage.getJSONArray("rows").objects().filter{it.s("kind")!="request"})
        val page=JSONObject(sourcePage,sourcePage.keys().asSequence().toList().toTypedArray())
            .put("rows",org.json.JSONArray(rows))
            .put("canonicalIds",org.json.JSONArray(rows.filter{transcriptContentAnchor(it.s("kind"))}.map{it.s("id")}))
        return JSONObject().put("windowSchema",2).put("thread",transcriptPresentationDetail(value.getJSONObject("thread").s("id"),value).getJSONObject("thread"))
            .put("timeline",page).put("revision",value.optLong("revision")).put("historyHead",value.optJSONObject("historyHead"))
            .put("throughNotification",maxOf(value.optJSONArray("notifications")?.objects()?.maxOfOrNull{it.optLong("id")}?:0,value.optLong("throughNotification"),value.optLong("_notificationObserved")))
    }
    private fun load(source:Source,id:String):JSONObject? {
        val key=source.scope to id
        latest[key]?.let{return it}
        return db.rawQuery("SELECT body,notification FROM recent WHERE scope=? AND thread=?",arrayOf(source.scope,id)).use{cursor->
            if(!cursor.moveToFirst())return@use null
            known[key]=maxOf(known[key]?:0,cursor.getLong(1))
            if(cursor.isNull(0))null else runCatching{JSONObject(cursor.getString(0))}.getOrNull()?.also{putLatest(key,it)}
        }
    }
    private fun save(source:Source,id:String,value:JSONObject?,dirty:Boolean){
        val key=source.scope to id
        val previous=writes[key]
        writes[key]=Triple(source,value?:previous?.second,dirty)
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
            arrayOf<Any?>(source.scope,id,value?.toString(),known[source.scope to id]?:0,if(dirty)1 else 0,System.currentTimeMillis()))
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
        known[key]=maxOf(known[key]?:0,intent.notification)
        val old=pending[key]
        pending[key]=intent.copy(priority=maxOf(intent.priority,old?.priority?:0))
        trimPending()
        dirty.add(key)
        save(intent.source,intent.thread,null,true)
        wake.trySend(Unit)
    }
    private fun ensurePump(){
        if(started)return
        started=true
        repeat(2){io.launch{
            for(signal in wake){
                delay(120) // One burst, one canonical repair; token deltas do not trigger reads.
                while(pending.isNotEmpty()){
                    val key=pending.entries.filter{it.key !in inFlight&&readOnlyCapabilities[it.value.source.scope]==true}
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
                        retainNewerLiveHead(key,safe,response.optLong("_liveGenerationAtReadStart"))
                        enforcePositions(intent.source.scope,intent.thread,safe)
                        val stored=putLatest(key,safe)
                        if(stored&&!pending.containsKey(key)&&response.optLong("_catalogObserved")==(invalidations[key]?:0))dirty.remove(key)
                        save(intent.source,intent.thread,if(stored)safe else null,key in dirty)
                        withContext(Dispatchers.Main.immediate){
                            if(Pocket.captureEnvironment()?.matches(intent.source.destination)==true)
                                CodexConversations.repositoryUpdated(intent.thread,safe)
                        }
                    }catch(error:CancellationException){if(current(intent.source))throw error}
                    catch(_:Exception){
                        // Durable dirty state survives transport loss. A reconnect/event retries;
                        // no model wake, unbounded retry loop or command replay.
                        save(intent.source,intent.thread,null,true)
                    }finally{
                        inFlight.remove(key)
                        if(pending.isNotEmpty())wake.trySend(Unit)
                    }
                }
            }
        }}
    }
}
