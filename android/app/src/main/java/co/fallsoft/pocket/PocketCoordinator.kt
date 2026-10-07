package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Text access to the same per-device controller used by voice, with no audio service or transport. */
object PocketCoordinator {
    var visible by mutableStateOf(false);private set
    var keyboardRequested by mutableStateOf(false);private set
    var messages by mutableStateOf<List<CoordinatorMessage>>(emptyList());private set
    var pendingText by mutableStateOf("");private set
    var state by mutableStateOf("Ready");private set
    var problem by mutableStateOf("");private set
    var historyLoading by mutableStateOf(false);private set
    var historyEarlier by mutableStateOf(false);private set
    var historyProblem by mutableStateOf("");private set
    internal val routes=mutableStateMapOf<String,List<CoordinatorRoute>>()
    internal var correctionOf:CoordinatorRoute?=null
    internal var turnIds by mutableStateOf<List<String>>(emptyList());private set
    private val actionsByTurn=linkedMapOf<String,List<JSONObject>>()
    private val presentationTickets=mutableMapOf<String,Ticket>()
    private val presented=mutableSetOf<String>()
    private val presenting=mutableSetOf<String>()
    private var messageIds by mutableStateOf<Set<String>>(emptySet())
    val pendingInHistory get()=Pocket.prefs.getString("coordinator-pending:$owner",null)?.let{runCatching{JSONObject(it).s("id")}.getOrNull()}?.let{it in messageIds}==true
    val busy get()=state in setOf("Sending","Thinking")
    val preview get()=pendingText.takeIf{it.isNotBlank()}?:messages.lastOrNull()?.second?.ifBlank{messages.lastOrNull()?.first.orEmpty()}.orEmpty()
    private var owner=""
    private var generation=0
    private var before:Long?=null
    private var ready=false
    private var work:Job?=null
    private var historyJob:Job?=null
    private val turns=linkedMapOf<String,Pair<String,String>>()
    private data class Ticket(val local:Boolean,val identity:String,val generation:Int,val base:String,val token:String)
    private fun identity(local:Boolean):String {
        val raw="$local\u0000${Pocket.savedBase(local)}\u0000${Pocket.savedToken(local)}"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun capture():Ticket {
        val local=Pocket.local;val id=identity(local)
        if(owner!=id){
            generation++;work?.cancel();historyJob?.cancel();work=null;historyJob=null
            owner=id;ready=false;before=null;historyEarlier=false;historyLoading=false;historyProblem="";problem="";state="Ready";pendingText="";turns.clear();routes.clear();actionsByTurn.clear();presentationTickets.clear();correctionOf=null
            runCatching{JSONArray(Pocket.prefs.getString("coordinator-history:$id","[]")!!).objects().forEach{row->val turnId=row.s("id");turns[turnId]=Pair(row.s("user"),row.s("response"));val actions=row.optJSONArray("actions")?.objects().orEmpty();actionsByTurn[turnId]=actions;routes[turnId]=routeReceipts(actions,turnId)}}
            messages=turns.map{(id,text)->CoordinatorMessage(id,text.first,text.second)};turnIds=turns.keys.toList();messageIds=turns.keys.toSet()
        }
        return Ticket(local,id,generation,Pocket.savedBase(local).trimEnd('/'),Pocket.savedToken(local))
    }
    private fun current(t:Ticket)=generation==t.generation&&owner==t.identity&&Pocket.local==t.local&&identity(t.local)==t.identity
    private suspend fun api(t:Ticket,path:String,body:JSONObject?=null):JSONObject {
        if(!current(t))throw CancellationException("Coordinator profile changed")
        val result=withContext(Dispatchers.IO){
            val request=Request.Builder().url(t.base+path).header("Authorization","Bearer ${t.token}")
            if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
            Pocket.http.newCall(request.build()).execute().use{response->
                val json=JSONObject(response.body?.string()?:"{}")
                if(!response.isSuccessful)throw PocketApiException(response.code,ConnectionMessages.server(json.s("error","Request failed (${response.code})")))
                json
            }
        }
        if(!current(t))throw CancellationException("Coordinator profile changed")
        return result
    }
    private fun publish(){
        messages=turns.map{(id,text)->CoordinatorMessage(id,text.first,text.second)};turnIds=turns.keys.toList();messageIds=turns.keys.toSet()
        val cached=JSONArray(turns.entries.toList().takeLast(100).map{(id,text)->JSONObject().put("id",id).put("user",text.first).put("response",text.second).put("actions",JSONArray(actionsByTurn[id].orEmpty()))})
        Pocket.prefs.edit().putString("coordinator-history:$owner",cached.toString()).apply()
    }
    fun keyboard(show:Boolean){keyboardRequested=show}
    fun open(keyboard:Boolean=false){capture();if(!visible)ready=false;visible=true;keyboardRequested=keyboard;loadHistory();resumePending()}
    fun close(){visible=false;keyboardRequested=false}
    fun olderHistory(){loadHistory(true)}
    fun loadHistory(older:Boolean=false){
        val t=capture();if(historyJob?.isActive==true||older&&before==null)return
        historyLoading=true;historyProblem=""
        val cursor=if(older)before else null
        val initialTurns=turns.toMap()
        historyJob=Pocket.scope.launch{
            try{
                val result=api(t,"/api/voice/history"+if(cursor!=null)"?before=$cursor" else "")
                val page=linkedMapOf<String,Pair<String,String>>()
                val ownPendingId=savedPending(t)?.s("id")
                result.optJSONArray("turns")?.objects().orEmpty().forEachIndexed{index,row->
                    val user=row.s("transcript");val response=row.s("response").ifBlank{row.s("error")}
                    if(row.s("id")==ownPendingId&&row.s("state") !in setOf("completed","failed","unknown"))return@forEachIndexed
                    if(user.isNotBlank()||response.isNotBlank()){
                        val id=row.s("id").ifBlank{"history:${row.optLong("cursor")}:$index:${user.hashCode()}"}
                        page[id]=Pair(user,response);rememberActions(t,id,row.optJSONArray("actions")?.objects().orEmpty(),row.s("state")=="completed")
                    }
                }
                val merged=mergeCoordinatorHistory(page,initialTurns,turns.toMap(),older)
                turns.clear();turns.putAll(merged)
                before=result.optLong("before").takeIf{it>0};historyEarlier=result.optBoolean("hasEarlier");publish()
            }catch(e:Exception){if(e is CancellationException)throw e;if(current(t))historyProblem=PocketNetwork.error(e)}
            finally{if(current(t)){historyLoading=false;resumePending()}}
        }
    }
    private fun rememberActions(t:Ticket,id:String,actions:List<JSONObject>,completed:Boolean){
        actionsByTurn[id]=actions;routes[id]=routeReceipts(actions,id)
        if(completed&&actions.any{it.s("type")=="catchup"})presentationTickets[id]=t
    }
    fun presented(id:String){
        if(!visible||!PocketVoice.foreground)return
        val ticket=presentationTickets[id]?:return
        val key=ticket.identity+":"+id
        if(!current(ticket)||key in presented||!presenting.add(key))return
        Pocket.scope.launch{try{if(api(ticket,"/api/voice/turns/$id/presented",JSONObject()).optBoolean("ok"))presented.add(key)}catch(e:Exception){if(e is CancellationException)throw e}finally{presenting.remove(key)}}
    }
    private fun savedPending(t:Ticket)=Pocket.prefs.getString("coordinator-pending:${t.identity}",null)?.let{runCatching{JSONObject(it)}.getOrNull()}
    private fun resumePending(){
        val t=capture();if(work?.isActive==true)return
        savedPending(t)?.let{pendingText=it.s("text");runTurn(t,it)}
    }
    fun send(text:String):Boolean {
        val clean=text.trim();val t=capture()
        if(clean.isBlank()||clean.length>24000||work?.isActive==true)return false
        if(savedPending(t)!=null){problem="Check the previous turn before sending another message.";return false}
        val pending=JSONObject().put("id",UUID.randomUUID().toString()).put("text",clean)
        correctionOf?.let{pending.put("correctionOf",JSONObject().put("turnId",it.turnId).put("threadId",it.threadId))}
        Pocket.prefs.edit().putString("coordinator-pending:${t.identity}",pending.toString()).commit()
        pendingText=clean;problem="";runTurn(t,pending);return true
    }
    fun retry(){problem="";resumePending()}
    private fun runTurn(t:Ticket,pending:JSONObject){
        work=Pocket.scope.launch{
            try{
                state="Sending";problem=""
                val id=pending.s("id")
                var result=try{api(t,"/api/voice/turns/$id")}catch(e:PocketApiException){if(e.status!=404)throw e;null}
                if(result==null){
                    if(!ready){api(t,"/api/voice/start",JSONObject().put("fullPermissions",Pocket.fullPermissions));ready=true}
                    val request=JSONObject().put("turnId",id).put("text",pending.s("text"));pending.optJSONObject("correctionOf")?.let{request.put("correctionOf",it)}
                    result=api(t,"/api/voice/text",request)
                }
                if(pending.has("correctionOf"))correctionOf=null
                val deadline=System.nanoTime()+240000000000L
                while(result!!.s("state") !in setOf("completed","failed","unknown")){
                    state="Thinking"
                    if(System.nanoTime()>deadline)throw IllegalStateException("The coordinator is still working. Retry checks the same turn.")
                    delay(1000);result=api(t,"/api/voice/turns/$id")
                }
                val finished=result?:throw IllegalStateException("The coordinator returned no turn")
                val user=finished.s("transcript").ifBlank{pending.s("text")}
                val response=finished.s("response").ifBlank{finished.s("error")}
                turns[id]=Pair(user,response);rememberActions(t,id,finished.optJSONArray("actions")?.objects().orEmpty(),finished.s("state")=="completed");publish()
                Pocket.prefs.edit().remove("coordinator-pending:${t.identity}").commit()
                pendingText="";state="Ready"
                if(finished.s("state")!="completed")problem=finished.s("error","The turn stopped. Check what happened before repeating it.")
                else applyActions(finished.optJSONArray("actions")?.objects().orEmpty())
            }catch(e:Exception){if(e is CancellationException)throw e;if(current(t)){state="Retry needed";problem=PocketNetwork.error(e)}}
        }
    }
    private fun applyActions(actions:List<JSONObject>){for(action in actions)when(action.s("type")){
        "select"->{val id=action.s("threadId").takeIf{it.isNotBlank()};PocketVoice.targetThread=id;if(visible){close();if(id==null)Pocket.closeTask()else Pocket.open(id)}}
        "permissions"->Pocket.updateFullPermissions(action.optBoolean("full"))
        "profile"->{if(!visible)continue;val local=action.optBoolean("local");if(Pocket.savedToken(local).isBlank())problem="That device is not paired. Pair it in NextComp first." else {Pocket.activate(local);capture();loadHistory()}}
        "exit"->close()
    }}
}

/** Merge by server turn ID, preserving responses completed after the request snapshot. */
internal fun mergeCoordinatorHistory(
    page:Map<String,Pair<String,String>>,
    initial:Map<String,Pair<String,String>>,
    current:Map<String,Pair<String,String>>,
    older:Boolean
):LinkedHashMap<String,Pair<String,String>> = linkedMapOf<String,Pair<String,String>>().apply{
    putAll(page)
    putAll(if(older)current else current.filter{(id,text)->initial[id]!=text})
}
