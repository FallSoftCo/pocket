package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

@Composable fun LosangelexScreen(owner:String,onSettings:()->Unit={}){
    val client=remember(owner){LosangelexClient(owner)}
    val jobs=rememberCoroutineScope()
    var teams by remember{mutableStateOf(emptyList<JSONObject>())}
    var team by remember{mutableStateOf(Pocket.prefs.getString("team:$owner","")?:"")}
    var task by remember{mutableStateOf("")}
    var recipient by remember{mutableStateOf("")}
    var direct by remember{mutableStateOf(false)}
    var replyTo by remember{mutableStateOf<Long?>(null)}
    var overview by remember{mutableStateOf(JSONObject())}
    var messages by remember{mutableStateOf(emptyList<JSONObject>())}
    var attention by remember{mutableStateOf(emptyList<JSONObject>())}
    var older by remember{mutableStateOf<Long?>(null)}
    var error by remember{mutableStateOf("")}
    var receipt by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var newTask by remember{mutableStateOf(false)}
    var inspected by remember{mutableStateOf<JSONObject?>(null)}
    var inbox by remember{mutableStateOf(false)}
    val target=TeamScope(team,task,recipient,direct,replyTo)
    val currentTarget by rememberUpdatedState(target)
    val taskTitle=overview.optJSONArray("tasks")?.objects()?.firstOrNull{it.s("id")==task}?.s("title")?:"Selected task"
    var input by remember(target.draftKey(owner)){mutableStateOf(client.draft(target).let{TextFieldValue(it,TextRange(it.length))})}
    val text=input.text
    var pending by remember(target.draftKey(owner)){mutableStateOf(client.pending(target))}

    suspend fun refresh(destination:TeamScope,history:Boolean=true){
        if(destination.team.isBlank())return
        val snapshot=client.call("teams/${destination.team}/overview")
        var rosterCursor=snapshot.s("teamNextCursor")
        val roster=snapshot.optJSONObject("team")?:JSONObject()
        val rosterSeen=mutableSetOf<String>()
        while(rosterCursor.isNotBlank()){
            if(!rosterSeen.add(rosterCursor))throw Exception("Repeated roster cursor")
            val page=client.call("teams/${destination.team}/teammates?cursor=$rosterCursor&limit=100")
            page.optJSONObject("team")?.let{members->members.keys().forEach{roster.put(it,members.get(it))}}
            rosterCursor=page.s("next_cursor")
        }
        snapshot.put("team",roster)
        val inbox=client.call("teams/${destination.team}/attention?limit=100")
        if(currentTarget.team!=destination.team)return
        overview=snapshot;attention=inbox.optJSONArray("data")?.objects().orEmpty()
        if(task.isBlank()){task=snapshot.s("lobby","lobby");return}
        if(history){
            val visibility=if(destination.direct)"direct:${destination.recipient}" else "room"
            val page=client.call("teams/${destination.team}/history?task=${destination.task}&visibility=$visibility&limit=50")
            if(sameTeamHistoryScope(currentTarget,destination)){
                val batch=page.optJSONArray("data")?.objects().orEmpty()
                val loadedEarlier=messages.minOfOrNull{it.optLong("id")}?.let{old->batch.minOfOrNull{it.optLong("id")}?.let{old<it}}==true
                messages=(messages+batch).associateBy{it.optLong("id")}.values.sortedBy{it.optLong("id")}
                if(!loadedEarlier)older=page.takeUnless{it.isNull("olderCursor")}?.optLong("olderCursor")
            }
        }
    }
    LaunchedEffect(owner){
        try{
            val all=mutableListOf<JSONObject>();var cursor="";val seen=mutableSetOf<String>()
            do{val page=client.call("teams?limit=100"+if(cursor.isNotBlank())"&cursor=$cursor" else "");all+=page.optJSONArray("teams")?.objects().orEmpty();cursor=page.s("next_cursor");if(cursor.isNotBlank()&&!seen.add(cursor))throw Exception("Repeated team cursor")}while(cursor.isNotBlank())
            teams=all;if(teams.none{it.s("id")==team})team=teams.firstOrNull()?.s("id").orEmpty()
        }catch(e:Exception){if(e is CancellationException)throw e;error=e.message?:"Could not load teams"}
    }
    LaunchedEffect(BackendNavigation.notification){
        val request=BackendNavigation.notification
        if(request!=null&&request.first==backendOwner(Pocket.base,Pocket.token)){
            try{
                val origin=client.call("notification-target/${request.second}")
                team=origin.s("team");task=origin.s("task");recipient=origin.s("agent")
                val event=origin.getJSONObject("event");direct=event.s("visibility").startsWith("direct:")
                inspected=JSONObject(event.toString()).put("team_id",team)
                BackendNavigation.consumed()
            }catch(e:Exception){if(e is CancellationException)throw e;error=e.message?:"Could not open the original request"}
        }
    }
    LaunchedEffect(team,task,direct,recipient){
        messages=emptyList();older=null;error=""
        if(team.isNotBlank())Pocket.prefs.edit().putString("team:$owner",team).apply()
        while(isActive){try{refresh(currentTarget);error=""}catch(e:Exception){if(e is CancellationException)throw e;error=e.message?:"Losangelex unavailable"};delay(3000)}
    }
    fun submit(command:JSONObject){
        val destination=target;client.savePending(destination,command);pending=command;busy=true;receipt="Submitting…"
        jobs.launch{
            try{
                client.call(command.getString("path"),command.getJSONObject("body"))
                client.savePending(destination,null);client.saveDraft(destination,"")
                if(currentTarget==destination){pending=null;input=TextFieldValue();replyTo=null;receipt="Accepted · work may still be queued"}
                refresh(destination)
            }catch(e:Exception){
                if(e is CancellationException)throw e
                val rejected=e is PocketApiException&&e.status in listOf(400,404,422)
                if(rejected)client.savePending(destination,null)
                if(currentTarget==destination){if(rejected)pending=null;receipt=if(rejected)"Rejected · edit your message" else "Unconfirmed · retry the saved command";error=e.message?:"Submission unconfirmed"}
            }
            finally{busy=false}
        }
    }
    val canSend=!busy&&team.isNotBlank()&&task.isNotBlank()&&(pending!=null||text.isNotBlank()&&text.length<=3500)&&(!direct||recipient.isNotBlank())
    fun send(){if(canSend&&!busy)submit(pending?:JSONObject().put("path","teams/$team/messages").put("body",target.message(UUID.randomUUID().toString(),text)))}
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        WeeklyLimitBar()
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onSettings){BilingualLabel("Settings")};TextButton({newTask=true},enabled=team.isNotBlank()&&!busy){BilingualLabel("New task")}}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            TeamChoice("Team",teams.map{it.s("id") to it.s("name")},team,Modifier.weight(1f)){team=it;task="";recipient="";direct=false;replyTo=null}
            TeamChoice("Task",overview.optJSONArray("tasks")?.objects().orEmpty().map{it.s("id") to it.s("title")},task,Modifier.weight(1f)){task=it;replyTo=null}
            TeamChoice("To",listOf("" to "Coordinator")+(overview.optJSONObject("team")?.keys()?.asSequence()?.map{it to it}?.toList().orEmpty()),recipient,Modifier.weight(1f)){recipient=it;replyTo=null;if(it.isBlank())direct=false}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(!direct,{direct=false;replyTo=null},label={BilingualLabel("Room")})
            FilterChip(direct,{direct=true;replyTo=null},label={BilingualLabel("Direct")},enabled=recipient.isNotBlank())
            TextButton({jobs.launch{try{refresh(target)}catch(e:Exception){error=e.message.orEmpty()}}}){BilingualLabel("Refresh")}
        }
        Text("${if(direct)"Direct · $recipient" else "Room"} · ${if(task.isBlank())"Loading…" else taskTitle}",style=MaterialTheme.typography.labelMedium,color=Mint)
        BilingualLabel("Full access",fontSize=12.sp,color=Muted)
        if(error.isNotBlank())ImmersionText("team-error:$owner:$team",error,color=Coral,fontSize=13.sp)
        if(attention.isNotEmpty())TextButton({inbox=true}){BilingualLabel("Needs you");Text(" · ${attention.size}")}
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(older!=null)item{TextButton({val destination=target;val cursor=older;jobs.launch{try{val visibility=if(destination.direct)"direct:${destination.recipient}" else "room";val page=client.call("teams/${destination.team}/history?task=${destination.task}&visibility=$visibility&limit=50&before=$cursor");if(sameTeamHistoryScope(currentTarget,destination)){messages=(page.optJSONArray("data")?.objects().orEmpty()+messages).distinctBy{it.optLong("id")}.sortedBy{it.optLong("id")};older=page.takeUnless{it.isNull("olderCursor")}?.optLong("olderCursor")}}catch(e:Exception){if(e is CancellationException)throw e;if(sameTeamHistoryScope(currentTarget,destination))error=e.message.orEmpty()}}}){BilingualLabel("Earlier messages")}}
            items(messages,key={it.optLong("id")}){event->
                Surface(color=Panel,modifier=Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text(event.s("author")+" · "+event.s("kind"),style=MaterialTheme.typography.labelMedium,color=Mint)
                        event.optJSONObject("reply")?.let{Text("Reply to ${it.s("author")}: ${it.s("body")}",style=MaterialTheme.typography.bodySmall,color=Muted)}
                        val sourceId="team-message:$owner:$team:${event.optLong("id")}"
                        LaunchedEffect(sourceId,event.s("body"),PocketImmersion.enabled){PocketImmersion.offer(sourceId,event.s("body"),"team conversation")}
                        BilingualMessage(sourceId,event.s("body"))
                        if(event.s("kind") in listOf("attention","approval")&&event.s("attentionState")=="open")TextButton({inspected=JSONObject(event.toString()).put("team_id",team)}){BilingualLabel("Open request")}
                        else if(event.s("kind")=="message")TextButton({replyTo=event.optLong("id");recipient=teamReplyRecipient(target,event.s("author"))}){BilingualLabel("Reply")}
                    }
                }
            }
        }
        TeamAgentControls(client,team,task,overview,jobs,{receipt=it},{error=it})
        if(replyTo!=null)TextButton({replyTo=null}){Text("Reply to #$replyTo · clear")}
        if(receipt.isNotBlank())ImmersionText("team-receipt:$owner:$team",receipt,fontSize=12.sp,color=Muted)
        OutlinedTextField(input,{input=it;client.saveDraft(target,it.text)},enabled=pending==null&&!busy,label={BilingualLabel(if(direct)"Message $recipient" else "Message the team")},modifier=Modifier.fillMaxWidth().onPreviewKeyEvent{event->
            val enter=event.key==Key.Enter||event.key==Key.NumPadEnter
            if(enter){
                if(event.type==KeyEventType.KeyDown){
                    if(event.isShiftPressed){if(pending==null&&!busy){input=teamInsertNewline(input);client.saveDraft(target,input.text)}}
                    else send()
                }
                true
            }else false
        },maxLines=4,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}))
        Button({send()},enabled=canSend,modifier=Modifier.fillMaxWidth()){BilingualLabel(if(busy)"Submitting…" else if(pending!=null)"Retry saved command" else "Send")}
    }
    if(newTask)TeamNewTask(client,team,{newTask=false}){id->task=id;recipient="";direct=false;replyTo=null;newTask=false}
    if(inbox)TeamInbox(client,team,{inbox=false}){event->inspected=event;inbox=false}
    inspected?.let{event->TeamAttentionDialog(client,team,event,{inspected=null}){jobs.launch{try{refresh(currentTarget)}catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}}}}
}

@Composable fun TeamChoice(label:String,choices:List<Pair<String,String>>,selected:String,modifier:Modifier=Modifier,onSelect:(String)->Unit){
    var open by remember{mutableStateOf(false)}
    Box(modifier){TextButton({open=true},modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=4.dp)){Text("$label: "+(choices.firstOrNull{it.first==selected}?.second?:selected),maxLines=1,overflow=TextOverflow.Ellipsis)};DropdownMenu(open,{open=false}){choices.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={open=false;onSelect(id)})}}}
}
