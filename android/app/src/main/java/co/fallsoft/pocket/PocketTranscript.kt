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
    private val buffered=mutableListOf<JSONObject>()
    private var owner:String?=null
    fun reset(id:String){owner=id;rows=emptyList();earlier=false;before=null;loading=false;buffered.clear();revision++}
    suspend fun load(older:Boolean=false){
        val id=Pocket.selected?:return;if(loading||owner!=id)return
        loading=true;buffered.clear()
        try{
            val d=Pocket.api("/api/threads/$id?view=timeline"+if(older&&before!=null)"&before=$before" else "")
            if(Pocket.selected!=id||owner!=id)return
            val page=d.optJSONObject("timeline")?:JSONObject();val incoming=page.optJSONArray("rows")?.objects()?:emptyList()
            if(older){rows=(incoming+rows).distinctBy{it.s("id")}}
            else{
                val boundary=incoming.firstOrNull()?.s("turnId")
                val at=rows.indexOfFirst{it.s("turnId")==boundary}
                val retained=if(at>0)rows.take(at)else emptyList()
                rows=retained+incoming
            }
            if(older||before==null){before=page.s("before").takeIf{it.isNotBlank()};earlier=page.optBoolean("hasEarlier")}
            Pocket.detail=d
            val updates=buffered.toList();buffered.clear()
            updates.filter{it.optLong("version")>d.optLong("revision")}.forEach{apply(it,false)}
            revision++;Pocket.error=""
        }catch(e:Exception){if(Pocket.selected==id)Pocket.error=e.message?:"Could not load conversation"}
        finally{if(owner==id)loading=false}
    }
    fun apply(update:JSONObject,buffer:Boolean=true){
        if(update.s("threadId")!=Pocket.selected)return
        if(loading&&buffer){buffered.add(update);return}
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
        rows=next;revision++
    }
}
