package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

object PocketTranscript {
    var rows by mutableStateOf(listOf<JSONObject>());private set
    var loading by mutableStateOf(false);private set
    var earlier by mutableStateOf(false);private set
    var before:String?=null;private set
    var revision by mutableIntStateOf(0);private set
    private val buffered=linkedMapOf<String,JSONObject>()
    private var generation=0
    private var bridgeBefore:String?=null
    private var missedUpdates=false
    var browsingEarlier by mutableStateOf(false);private set
    internal var readPosition by mutableStateOf<TranscriptReadPosition?>(null);private set
    private data class Snapshot(val rows:List<JSONObject>,val detail:JSONObject,val before:String?,val earlier:Boolean,val bridgeBefore:String?,val position:TranscriptReadPosition?)
    private val rowWeights=java.util.WeakHashMap<JSONObject,Long>()
    private val recent=RecentTranscriptCache<Snapshot>(4,Long.MAX_VALUE,12L*1024*1024){snapshot->
        snapshot.rows.sumOf{row->rowWeights.getOrPut(row){row.toString().length.toLong()*2}}+snapshot.detail.toString().length*2L
    }
    private var ownerScope:String?=null
    private fun scopeKey()=backendOwner(Pocket.base,Pocket.token)
    private fun rememberRecent(){
        val id=owner?:return;val scope=ownerScope?:return
        if(historyLoaded&&scope==scopeKey())Pocket.detail?.let{recent.put(scope,id,Snapshot(rows,it,before,earlier,bridgeBefore,readPosition),android.os.SystemClock.elapsedRealtime())}
    }
    internal fun rememberPosition(scope:String,id:String,position:TranscriptReadPosition){
        val now=android.os.SystemClock.elapsedRealtime()
        if(owner==id&&ownerScope==scope){readPosition=position;rememberRecent()}
        recent.get(scope,id,now)?.let{recent.put(scope,id,it.copy(position=position),now)}
    }
    private var owner:String?=null
    private var historyLoaded=false
    private var checkedThisOpen=false
    private fun trace(){if(BuildConfig.DEBUG)android.util.Log.d("PocketCache","rows=${rows.size} buffered=${buffered.size} earlier=$browsingEarlier")}
    fun reset(id:String){
        generation++;owner=id;ownerScope=scopeKey()
        val cached=recent.get(ownerScope!!,id,android.os.SystemClock.elapsedRealtime())
        rows=cached?.rows?:emptyList();Pocket.detail=cached?.detail;readPosition=cached?.position
        earlier=cached?.earlier?:false;before=cached?.before;bridgeBefore=cached?.bridgeBefore;loading=false;historyLoaded=cached!=null;checkedThisOpen=false
        buffered.clear();browsingEarlier=false;missedUpdates=false;revision++;trace()
    }
    fun clear(){generation++;owner=null;ownerScope=null;readPosition=null;rows=emptyList();buffered.clear();loading=false;historyLoaded=false;checkedThisOpen=false;before=null;bridgeBefore=null;earlier=false;browsingEarlier=false;revision++;trace()}
    fun latest(){Pocket.refreshDetail()}
    // Android memory pressure evicts inactive snapshots, never the conversation being read.
    fun release(){recent.clear();trace()}
    suspend fun load(older:Boolean=false,bridgeCursor:String?=null){
        val id=Pocket.selected?:return;if(loading||owner!=id||(older&&(!earlier||before==null)))return
        val ticket=generation;val scope=scopeKey();val profileLocal=Pocket.local
        fun current()=Pocket.selected==id&&owner==id&&generation==ticket&&ownerScope==scope&&scopeKey()==scope
        val cursor=bridgeCursor?:if(older)before else null
        loading=true;buffered.clear();var succeeded=false
        try{
            val d=Pocket.apiFor(profileLocal,"/api/threads/$id?view=timeline"+if(cursor!=null)"&before=${android.net.Uri.encode(cursor)}" else "")
            if(!current())return
            val page=d.optJSONObject("timeline")?:JSONObject();val incoming=page.optJSONArray("rows")?.objects()?:emptyList()
            incoming.filter{it.s("kind") in listOf("turn","turnEnd","request")}.forEach{it.put("version",d.optLong("revision"))}
            val wasLoaded=historyLoaded&&rows.isNotEmpty()
            val loadedIds=rows.mapTo(hashSetOf()){it.s("id")}
            val overlaps=incoming.any{it.s("id") in loadedIds}
            val nextBefore=page.s("before").takeIf{it.isNotBlank()}
            if(bridgeCursor!=null){
                rows=mergeTranscriptPage(rows,incoming,false,{it.s("id")},{it.optLong("version")},"$bridgeCursor/header")
                bridgeBefore=if(!overlaps&&page.optBoolean("hasEarlier")&&nextBefore!=cursor)nextBefore else null
            }else{
                if(!older&&wasLoaded&&!overlaps&&incoming.isNotEmpty()&&page.optBoolean("hasEarlier"))bridgeBefore=nextBefore
                rows=mergeTranscriptPage(rows,incoming,older,{it.s("id")},{it.optLong("version")})
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
            if(PocketVoice.foreground&&!BlackoutVisibility.active&&!PocketVoice.active&&!Pocket.newTask&&!older&&bridgeCursor==null){
                PocketNotificationReads.readVisible(id,d.optJSONArray("notifications")?.objects()?:emptyList(),local=profileLocal,catchup=if(!checkedThisOpen)d.optJSONObject("catchup") else null)
                checkedThisOpen=true
            }
            val updates=buffered.values.toList();buffered.clear()
            updates.filter{older||bridgeCursor!=null||it.optLong("version")>d.optLong("revision")}.forEach{apply(it,false)}
            rememberRecent();revision++;Pocket.error="";succeeded=true;trace()
        }catch(e:CancellationException){throw e}catch(e:Exception){if(current())Pocket.error=PocketNetwork.error(e)}
        finally{if(current()){if(!succeeded&&buffered.isNotEmpty()){buffered.clear();missedUpdates=true};loading=false;if(succeeded)bridgeBefore?.let{next->Pocket.scope.launch{load(bridgeCursor=next)}};if(missedUpdates){missedUpdates=false;Pocket.scope.launch{delay(450);Pocket.scheduleRefresh()}}}}
    }
    fun apply(update:JSONObject,buffer:Boolean=true){
        if(update.s("threadId")!=Pocket.selected||owner!=Pocket.selected||ownerScope!=scopeKey())return
        if(update.optBoolean("reload")){if(loading)missedUpdates=true else Pocket.scheduleRefresh();return}
        if(loading&&buffer){
            val key=update.optJSONObject("row")?.s("id")?:update.s("turnId")
            buffered[key]=update
            if(buffered.size>100||buffered.values.sumOf{it.toString().length}>512000){buffered.clear();missedUpdates=true}
            return
        }
        val turnId=update.s("turnId");if(turnId.isBlank())return
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
        update.optJSONObject("row")?.let{put(it)}
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
