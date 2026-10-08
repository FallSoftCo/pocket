package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

@Composable fun TeamAgentControls(client:LosangelexClient,team:String,task:String,overview:JSONObject,jobs:CoroutineScope,onReceipt:(String)->Unit,onError:(String)->Unit){
    var selected by remember(team,task){mutableStateOf("")}
    var running by remember{mutableStateOf(false)}
    val agents=overview.optJSONArray("agents")?.objects().orEmpty().filter{it.s("task")==task}
    if(agents.isEmpty())return
    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
        TeamChoice("Agent",agents.map{it.s("agent") to "${it.s("agent")} · ${it.s("status")}"},selected,Modifier.weight(1f)){selected=it}
        listOf("interrupt" to "Stop","pause" to "Pause","resume" to "Resume").forEach{(action,label)->
            TextButton({
                val destination=TeamScope(team,task,selected)
                val key="team-control:${destination.draftKey(client.owner)}:$action"
                val command=Pocket.prefs.getString(key,null)?.let{JSONObject(it)}?:JSONObject().put("commandId",UUID.randomUUID().toString()).put("task",task).put("agent",selected).put("action",action)
                Pocket.prefs.edit().putString(key,command.toString()).apply();running=true
                jobs.launch{try{client.call("teams/$team/control",command);Pocket.prefs.edit().remove(key).apply();onReceipt("$label confirmed for ${destination.recipient} · ${destination.task}")}catch(e:Exception){if(e is CancellationException)throw e;onError("$label unconfirmed; retry the same action. ${e.message.orEmpty()}")}finally{running=false}}
            },enabled=selected.isNotBlank()&&!running){BilingualLabel(label)}
        }
    }
}

@Composable fun TeamNewTask(client:LosangelexClient,team:String,dismiss:()->Unit,created:(String)->Unit){
    val key="team-new:${client.owner}:$team"
    val saved=remember{Pocket.prefs.getString(key,null)?.let{runCatching{JSONObject(it)}.getOrNull()}}
    var command by remember{mutableStateOf(saved)}
    var title by remember{mutableStateOf(saved?.s("title")?:Pocket.prefs.getString("$key:title","").orEmpty())}
    var body by remember{mutableStateOf(saved?.s("body")?:Pocket.prefs.getString("$key:body","").orEmpty())}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val jobs=rememberCoroutineScope()
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={BilingualLabel("New team task")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Losangelex · $team · Coordinator")
        OutlinedTextField(title,{title=it;Pocket.prefs.edit().putString("$key:title",it).apply()},enabled=command==null&&!busy,label={BilingualLabel("Task name",centered=false)})
        OutlinedTextField(body,{body=it;Pocket.prefs.edit().putString("$key:body",it).apply()},enabled=command==null&&!busy,label={BilingualLabel("What should the team do?",centered=false)},maxLines=6)
        if(error.isNotBlank())Text(error,color=Coral)
    }},confirmButton={TextButton({
        val submission=command?:JSONObject().put("commandId",UUID.randomUUID().toString()).put("id","nc-"+UUID.randomUUID().toString()).put("title",title.trim()).put("body",body.trim())
        command=submission;Pocket.prefs.edit().putString(key,submission.toString()).apply();busy=true
        jobs.launch{try{client.call("teams/$team/tasks",submission);Pocket.prefs.edit().remove(key).remove("$key:title").remove("$key:body").apply();created(submission.getString("id"))}catch(e:Exception){if(e is CancellationException)throw e;if(e is PocketApiException&&e.status in listOf(400,404,422)){command=null;Pocket.prefs.edit().remove(key).apply();error=e.message.orEmpty()}else error="Unconfirmed. Retry this saved task. ${e.message.orEmpty()}"}finally{busy=false}}
    },enabled=!busy&&(command!=null||title.isNotBlank()&&title.length<=100&&body.isNotBlank()&&body.length<=3500)){BilingualLabel(if(command==null)"Start task" else "Retry saved task")}},dismissButton={TextButton(dismiss,enabled=!busy){BilingualLabel("Close",centered=false)}})
}

@Composable fun TeamAttentionDialog(client:LosangelexClient,selectedTeam:String,source:JSONObject,dismiss:()->Unit,changed:()->Unit){
    // Capture origin when opening; the surrounding chooser cannot retarget this answer.
    val team=remember(source.optLong("id")){source.s("team_id",selectedTeam)}
    val id=source.optLong("id")
    val key="team-answer:${client.owner}:$team:$id"
    val saved=remember{Pocket.prefs.getString(key,null)?.let{runCatching{JSONObject(it)}.getOrNull()}}
    var pending by remember{mutableStateOf(saved)}
    var text by remember{mutableStateOf(saved?.s("body")?:Pocket.prefs.getString("$key:draft","").orEmpty())}
    var event by remember{mutableStateOf(source)}
    var state by remember{mutableStateOf("Checking request…")}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var verified by remember{mutableStateOf(false)}
    var uncertainApproval by remember{mutableStateOf(false)}
    val jobs=rememberCoroutineScope()
    LaunchedEffect(id){uncertainApproval=Pocket.prefs.getBoolean("approval-unconfirmed:${client.owner}:$team:$id",false)}
    suspend fun check(){
        verified=false
        val value=client.call("teams/$team/messages/$id");event=value.getJSONObject("event")
        state=value.s("attentionState",event.s("attentionState"));verified=true
    }
    LaunchedEffect(id){try{check()}catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}}
    val approval=event.s("kind")=="approval"
    val open=verified&&state=="open"
    fun answer(decision:String?=null){
        busy=true
        val command=if(approval)JSONObject().put("decision",decision)else pending?:JSONObject().put("commandId",UUID.randomUUID().toString()).put("body",text.trim())
        if(!approval){pending=command;Pocket.prefs.edit().putString(key,command.toString()).apply()}
        jobs.launch{try{
            check();if(state!="open")throw Exception("This request is already $state")
            client.call("teams/$team/${if(approval)"approval" else "attention"}/$id/answer",command)
            Pocket.prefs.edit().remove(key).remove("$key:draft").remove("approval-unconfirmed:${client.owner}:$team:$id").apply();changed();dismiss()
        }catch(e:Exception){if(e is CancellationException)throw e;if(approval){uncertainApproval=true;verified=false;Pocket.prefs.edit().putBoolean("approval-unconfirmed:${client.owner}:$team:$id",true).apply()}else if(e is PocketApiException&&e.status in listOf(400,404,422)){pending=null;Pocket.prefs.edit().remove(key).apply()};error=e.message.orEmpty()}finally{busy=false}}
    }
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text(if(approval)"Review request" else "Answer ${event.s("author")}")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("Losangelex · $team · ${event.s("task")}",style=MaterialTheme.typography.labelSmall)
        Text(event.s("body"));Text(state,color=Muted)
        if(!approval)OutlinedTextField(text,{text=it;Pocket.prefs.edit().putString("$key:draft",it).apply()},enabled=open&&pending==null&&!busy,label={BilingualLabel("Your answer",centered=false)},maxLines=5)
        if(error.isNotBlank())Text(error,color=Coral)
        if(uncertainApproval)Text("Approval outcome is unconfirmed. Refresh its state before any further action.")
    }},confirmButton={if(approval)Row{TextButton({answer("decline")},enabled=open&&!busy&&!uncertainApproval){BilingualLabel("Decline",centered=false)};TextButton({answer("accept")},enabled=open&&!busy&&!uncertainApproval&&event.optJSONObject("data")?.optBoolean("canAccept")==true){BilingualLabel("Approve once",centered=false)}}
        else TextButton({answer()},enabled=open&&!busy&&(pending!=null||text.isNotBlank()&&text.length<=3500)){BilingualLabel(if(pending==null)"Send answer" else "Retry saved answer")}},dismissButton={Row{TextButton({jobs.launch{try{check();if(state!="open"){uncertainApproval=false;Pocket.prefs.edit().remove("approval-unconfirmed:${client.owner}:$team:$id").apply()}}catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}}},enabled=!busy){BilingualLabel("Refresh",centered=false)};TextButton(dismiss,enabled=!busy){BilingualLabel("Close",centered=false)}}})
}

@Composable fun TeamInbox(client:LosangelexClient,selectedTeam:String,dismiss:()->Unit,inspect:(JSONObject)->Unit){
    val team=remember{selectedTeam}
    var events by remember{mutableStateOf(emptyList<JSONObject>())}
    var cursor by remember{mutableStateOf("")}
    var loaded by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val jobs=rememberCoroutineScope()
    suspend fun load(){
        busy=true
        try{val page=client.call("teams/$team/attention?limit=100"+if(cursor.isNotBlank())"&cursor=$cursor" else "");events=(events+page.optJSONArray("data")?.objects().orEmpty()).distinctBy{it.optLong("id")};cursor=page.s("next_cursor");loaded=true}
        catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}finally{busy=false}
    }
    LaunchedEffect(team){load()}
    AlertDialog(onDismissRequest=dismiss,title={Text("Needs you · $team")},text={Column{
        LazyColumn(Modifier.heightIn(max=350.dp)){items(events,key={it.optLong("id")}){event->TextButton({inspect(JSONObject(event.toString()).put("team_id",team))}){Text("${event.s("author")} · ${event.s("task")}\n${event.s("body")}")}}}
        if(loaded&&events.isEmpty())BilingualLabel("No pending requests",centered=false)
        if(error.isNotBlank())Text(error,color=Coral)
        if(cursor.isNotBlank()||!loaded)TextButton({jobs.launch{load()}},enabled=!busy){BilingualLabel(if(loaded)"Load more" else "Retry")}
    }},confirmButton={TextButton(dismiss){BilingualLabel("Close",centered=false)}})
}
