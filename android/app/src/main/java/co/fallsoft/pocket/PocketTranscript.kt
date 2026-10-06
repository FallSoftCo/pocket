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
    private var missedUpdates=false
    private var pageCursor:String?=null
    var browsingEarlier by mutableStateOf(false);private set
    private data class Snapshot(val rows:List<JSONObject>,val detail:JSONObject,val before:String?,val earlier:Boolean)
    private val recent=RecentTranscriptCache<Snapshot>(4,120000)
    private var ownerScope:String?=null
    private fun scopeKey()=backendOwner(Pocket.base,Pocket.token)
    private fun rememberRecent(){
        val id=owner?:return;val scope=ownerScope?:return
        if(historyLoaded&&!browsingEarlier&&scope==scopeKey())Pocket.detail?.let{recent.put(scope,id,Snapshot(rows,it,before,earlier),android.os.SystemClock.elapsedRealtime())}
    }
    private var owner:String?=null
    private var historyLoaded=false
    private fun trace(){if(BuildConfig.DEBUG)android.util.Log.d("PocketCache","rows=${rows.size} buffered=${buffered.size} earlier=$browsingEarlier")}
    fun reset(id:String){
        generation++;owner=id;ownerScope=scopeKey()
        val cached=recent.get(ownerScope!!,id,android.os.SystemClock.elapsedRealtime())
        rows=cached?.rows?:emptyList();Pocket.detail=cached?.detail
        earlier=cached?.earlier?:false;before=cached?.before;loading=false;historyLoaded=cached!=null
        buffered.clear();pageCursor=null;browsingEarlier=false;missedUpdates=false;revision++;trace()
    }
    fun clear(){generation++;owner=null;ownerScope=null;rows=emptyList();buffered.clear();loading=false;historyLoaded=false;before=null;earlier=false;pageCursor=null;browsingEarlier=false;revision++;trace()}
    fun latest(){owner?.let{recent.remove(ownerScope.orEmpty(),it)};historyLoaded=false;Pocket.selected?.let{reset(it)};Pocket.detail=null;Pocket.refreshDetail()}
    fun release(){generation++;recent.clear();rows=emptyList();buffered.clear();loading=false;historyLoaded=false;Pocket.detail=null;revision++;trace()}
    suspend fun load(older:Boolean=false){
        val id=Pocket.selected?:return;if(loading||owner!=id||(!older&&browsingEarlier&&rows.isNotEmpty()))return
        val ticket=generation;val scope=scopeKey();val profileLocal=Pocket.local
        fun current()=Pocket.selected==id&&owner==id&&generation==ticket&&ownerScope==scope&&scopeKey()==scope
        val cursor=if(older)before else pageCursor
        loading=true;buffered.clear()
        try{
            val d=Pocket.apiFor(profileLocal,"/api/threads/$id?view=timeline"+if(cursor!=null)"&before=${android.net.Uri.encode(cursor)}" else "")
            if(!current())return
            val page=d.optJSONObject("timeline")?:JSONObject();val incoming=page.optJSONArray("rows")?.objects()?:emptyList()
            rows=incoming
            pageCursor=cursor;browsingEarlier=cursor!=null
            before=page.s("before").takeIf{it.isNotBlank()};earlier=page.optBoolean("hasEarlier")
            historyLoaded=true
            d.remove("timeline");Pocket.detail=d
            if(PocketVoice.foreground&&!PocketVoice.active&&!Pocket.newTask&&!older)PocketNotificationReads.readVisible(id,d.optJSONArray("notifications")?.objects()?:emptyList())
            val updates=buffered.values.toList();buffered.clear()
            updates.filter{it.optLong("version")>d.optLong("revision")}.forEach{apply(it,false)}
            rememberRecent();revision++;Pocket.error="";trace()
        }catch(e:Exception){if(current())Pocket.error=PocketNetwork.error(e)}
        finally{if(current()){loading=false;if(missedUpdates){missedUpdates=false;Pocket.scope.launch{delay(450);Pocket.scheduleRefresh()}}}}
    }
    fun apply(update:JSONObject,buffer:Boolean=true){
        if(update.s("threadId")!=Pocket.selected||owner!=Pocket.selected||ownerScope!=scopeKey()||browsingEarlier)return
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
            if(!active)put(JSONObject().put("id","$turnId/end").put("turnId",turnId).put("kind","turnEnd").put("status",turn.s("status")).put("durationMs",turn.optLong("durationMs")).put("text",turn.optJSONObject("error")?.s("message")?:""))
        }
        var size=next.sumOf{it.toString().length}
        while(next.size>500||size>1024*1024){size-=next.removeAt(0).toString().length;missedUpdates=true}
        rows=next;rememberRecent();revision++
        if(missedUpdates&&!loading){missedUpdates=false;Pocket.scheduleRefresh()}
    }
}

/** A short-lived bounded cache; backend identity and thread identity are both mandatory. */
internal class RecentTranscriptCache<T>(private val capacity:Int,private val lifetimeMs:Long){
    private data class Entry<T>(val value:T,val savedAt:Long)
    private val entries=linkedMapOf<Pair<String,String>,Entry<T>>()
    fun put(scope:String,id:String,value:T,now:Long){val key=scope to id;entries.remove(key);entries[key]=Entry(value,now);while(entries.size>capacity)entries.remove(entries.keys.first())}
    fun get(scope:String,id:String,now:Long):T?{val key=scope to id;val entry=entries.remove(key)?:return null;if(now-entry.savedAt>lifetimeMs)return null;entries[key]=entry;return entry.value}
    fun remove(scope:String,id:String){entries.remove(scope to id)}
    fun clear(){entries.clear()}
}
