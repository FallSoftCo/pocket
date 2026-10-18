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
    private data class Intent(val source:Source,val thread:String,val notification:Long=0)
    private val lane=Dispatchers.IO.limitedParallelism(1)
    private val io=CoroutineScope(SupervisorJob()+lane+CoroutineExceptionHandler{_,error->
        // A disposable cache failure must never take down input/voice or expose text.
        android.util.Log.w("NextCompSync","Message cache unavailable: "+error.javaClass.simpleName)
    })
    // Network admission cannot queue behind startup SQLite restoration or parsing.
    private val network=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val requestMutex=Mutex()
    private val requests=mutableMapOf<Pair<String,String>,Deferred<JSONObject>>()
    private val inFlight=hashSetOf<Pair<String,String>>()
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val pending=linkedMapOf<Pair<String,String>,Intent>()
    private val latest=ConcurrentHashMap<Pair<String,String>,JSONObject>()
    private val known=ConcurrentHashMap<Pair<String,String>,Long>()
    private val dirty=ConcurrentHashMap.newKeySet<Pair<String,String>>()
    private val livePending=ConcurrentHashMap<Triple<String,String,String>,Pair<Source,JSONObject>>()
    private val liveScheduled=java.util.concurrent.atomic.AtomicBoolean()
    private val lastLive=ConcurrentHashMap<Pair<String,String>,Long>()
    private val liveSequence=java.util.concurrent.atomic.AtomicLong()
    private val liveGenerations=ConcurrentHashMap<Pair<String,String>,Long>()
    private val writes=linkedMapOf<Pair<String,String>,Triple<Source,JSONObject?,Boolean>>()
    private var writeJob:Job?=null
    private var started=false
    private val db by lazy {object:SQLiteOpenHelper(Pocket.context,"codex-session-messages-v1.sqlite",null,1){
        override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE recent(scope TEXT NOT NULL,thread TEXT NOT NULL,body TEXT,notification INTEGER NOT NULL DEFAULT 0,dirty INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,thread))")}
        override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
    }.apply{setWriteAheadLoggingEnabled(true)}.writableDatabase}
    private fun source(destination:EnvironmentRequest)=Source(destination,transcriptCacheOwner(destination.environment.id,destination.endpoint,destination.token))
    /** Shared current read, independent of viewport lifetime. Consumers own private JSON copies. */
    suspend fun readCurrent(destination:EnvironmentRequest,id:String):JSONObject {
        val source=source(destination);val key=source.scope to id
        val request=requestMutex.withLock{
            requests[key]?.takeIf{it.isActive}?:network.async{
                if(!current(source))throw CancellationException("Pairing changed")
                val baseline=latest[key]?.optJSONObject("historyHead")?.s("epoch")
                val notificationAtStart=known[key]?:0
                val liveAtStart=liveGenerations[key]?:0
                val response=CodexReadTransport.read(destination,"/api/threads/"+android.net.Uri.encode(id)+"?view=timeline")
                val currentEpoch=latest[key]?.optJSONObject("historyHead")?.s("epoch")
                if(!baseline.isNullOrBlank()&&currentEpoch!=baseline&&response.optJSONObject("historyHead")?.s("epoch")==baseline)
                    throw java.io.IOException("Message runtime changed during the read")
                response.put("_notificationObserved",notificationAtStart)
                response.put("_liveGenerationAtReadStart",liveAtStart)
            }.also{created->
                requests[key]=created
                created.invokeOnCompletion{network.launch{requestMutex.withLock{if(requests[key]===created)requests.remove(key)}}}
            }
        }
        val response=request.await()
        return withContext(Dispatchers.Default){JSONObject(response.toString())}
    }
    private fun current(source:Source)=Pocket.captureEnvironment(source.destination.environment.id)?.matches(source.destination)==true
    fun peek(id:String):JSONObject? {
        val destination=Pocket.captureEnvironment()?:return null
        return latest[source(destination).scope to id]
    }
    fun canPresent(id:String,value:JSONObject):Boolean=notificationWatermark(id)<=value.optLong("throughNotification")
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
                    if(!cursor.isNull(1))runCatching{JSONObject(cursor.getString(1))}.getOrNull()?.let{latest.putIfAbsent(key,it)}
                    if(cursor.getInt(3)!=0&&(!alreadyLoaded||key in dirty)){dirty.add(key);pending[key]=Intent(source,thread,cursor.getLong(2))}
                }
            }
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
                        page.put("rows",org.json.JSONArray(rows.takeLast(64)))
                        latest[key]=next;save(source,id,next,key in dirty)
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
    fun invalidate(id:String,destination:EnvironmentRequest?=Pocket.captureEnvironment(),notification:Long=0){
        if(id.isBlank()||destination==null)return
        val source=source(destination);val key=source.scope to id
        if(notification>0){
            if(notification<=(known[key]?:0))return
            known.merge(key,notification){a,b->maxOf(a,b)}
        }
        io.launch{
            ensurePump();if(!current(source))return@launch
            load(source,id)
            queue(Intent(source,id,notification))
        }
    }
    /** Save an authoritative recent page without retaining pending requests/command authority. */
    fun accepted(destination:EnvironmentRequest,id:String,value:JSONObject){
        validateTranscriptSnapshot(value,id)
        val safe=presentation(value)
        val source=source(destination)
        io.launch{
            if(!current(source))return@launch
            val page=JSONObject(safe.getJSONObject("timeline").toString())
            page.put("rows",org.json.JSONArray(page.getJSONArray("rows").objects().takeLast(64)))
            safe.put("timeline",page)
            retainNewerLiveHead(source.scope to id,safe,value.optLong("_liveGenerationAtReadStart"))
            latest[source.scope to id]=safe
            val high=safe.optLong("throughNotification")
            known[source.scope to id]=maxOf(known[source.scope to id]?:0,high)
            val key=source.scope to id
            if(key !in pending)dirty.remove(key)
            save(source,id,safe,key in dirty)
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
        val rows=mergeRestoredTranscriptRows(safe.getJSONObject("timeline").getJSONArray("rows").objects(),previous.getJSONObject("timeline").getJSONArray("rows").objects())
        safe.getJSONObject("timeline").put("rows",org.json.JSONArray(rows.takeLast(64)))
        safe.put("historyHead",oldHead)
    }
    private fun presentation(value:JSONObject):JSONObject =
        JSONObject().put("thread",transcriptPresentationDetail(value.getJSONObject("thread").s("id"),value).getJSONObject("thread"))
            .put("timeline",JSONObject(value.getJSONObject("timeline"),value.getJSONObject("timeline").keys().asSequence().toList().toTypedArray())
                .put("rows",org.json.JSONArray(value.getJSONObject("timeline").getJSONArray("rows").objects().filter{it.s("kind")!="request"})))
            .put("revision",value.optLong("revision"))
            .put("historyHead",value.optJSONObject("historyHead"))
            .put("throughNotification",maxOf(value.optJSONArray("notifications")?.objects()?.maxOfOrNull{it.optLong("id")}?:0,value.optLong("throughNotification"),value.optLong("_notificationObserved")))
    private fun load(source:Source,id:String):JSONObject? {
        val key=source.scope to id
        latest[key]?.let{return it}
        return db.rawQuery("SELECT body,notification FROM recent WHERE scope=? AND thread=?",arrayOf(source.scope,id)).use{cursor->
            if(!cursor.moveToFirst())return@use null
            known[key]=maxOf(known[key]?:0,cursor.getLong(1))
            if(cursor.isNull(0))null else runCatching{JSONObject(cursor.getString(0))}.getOrNull()?.also{latest[key]=it}
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
        while(latest.size>256){val victim=latest.keys.firstOrNull{it!=(source.scope to id)}?:break;latest.remove(victim)}
    }
    private fun queue(intent:Intent){
        val key=intent.source.scope to intent.thread
        known[key]=maxOf(known[key]?:0,intent.notification)
        pending[key]=intent
        dirty.add(key)
        save(intent.source,intent.thread,null,true)
        wake.trySend(Unit)
    }
    private fun ensurePump(){
        if(started)return
        started=true
        repeat(3){io.launch{
            for(signal in wake){
                delay(120) // One burst, one canonical repair; token deltas do not trigger reads.
                while(pending.isNotEmpty()){
                    val key=pending.keys.lastOrNull{it !in inFlight}?:break
                    val intent=pending.remove(key)?:continue
                    if(!current(intent.source))continue
                    inFlight.add(key)
                    if(pending.isNotEmpty())wake.trySend(Unit)
                    try{
                        val response=readCurrent(intent.source.destination,intent.thread)
                        if(!current(intent.source))continue
                        validateTranscriptSnapshot(response,intent.thread)
                        val safe=presentation(response)
                        val page=JSONObject(safe.getJSONObject("timeline").toString())
                        page.put("rows",org.json.JSONArray(page.getJSONArray("rows").objects().takeLast(64)))
                        safe.put("timeline",page)
                        retainNewerLiveHead(key,safe,response.optLong("_liveGenerationAtReadStart"))
                        latest[key]=safe
                        if(!pending.containsKey(key))dirty.remove(key)
                        save(intent.source,intent.thread,safe,pending.containsKey(key))
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
