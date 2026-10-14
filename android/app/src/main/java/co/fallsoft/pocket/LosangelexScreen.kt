package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.UUID
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable fun LosangelexScreen(owner:String,onSettings:()->Unit={}){
    val client=remember(owner){LosangelexClient(owner)}
    val jobs=rememberCoroutineScope()
    var roomBrowsing by remember(owner){mutableStateOf(true)}
    var actionsOpen by remember(owner){mutableStateOf(false)}
    var keyboardInput by remember(owner){mutableStateOf(false)}
    val inputFocus=remember{androidx.compose.ui.focus.FocusRequester()}
    val keyboard=LocalSoftwareKeyboardController.current
    val context=LocalContext.current
    var roomAuthority by remember(owner){mutableStateOf("")}
    var rooms by remember(owner){mutableStateOf(emptyList<JSONObject>())}
    var teams by remember{mutableStateOf(emptyList<JSONObject>())}
    var team by remember{mutableStateOf(Pocket.prefs.getString("team:$owner","")?:"")}
    var task by remember{mutableStateOf("")}
    var recipient by remember{mutableStateOf("")}
    var direct by remember{mutableStateOf(false)}
    var replyTo by remember{mutableStateOf<Long?>(null)}
    var overview by remember{mutableStateOf(JSONObject())}
    var historyScope by remember(owner){mutableStateOf(TeamScope("",""))}
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
    val taskTitle=roomForTask(rooms,task)?.s("title")?:overview.optJSONArray("tasks")?.objects()?.firstOrNull{it.s("id")==task}?.s("title")?:"Selected task"
    var input by remember(target.draftKey(owner)){mutableStateOf(client.draft(target).let{TextFieldValue(it,TextRange(it.length))})}
    val text=input.text
    var pending by remember(target.draftKey(owner)){mutableStateOf(client.pending(target))}
    val conversationList=rememberLazyListState()
    var expandedRuns by remember(owner,team,task,direct,if(direct)recipient else ""){mutableStateOf(emptySet<String>())}
    val visibleEvents=if(sameTeamHistoryScope(target,historyScope))messages else emptyList()
    val transcript=remember(visibleEvents,expandedRuns,owner,team,task,direct,if(direct)recipient else ""){
        commandRunProjection(roomTranscriptRows(visibleEvents,"lx:$owner:$team:$task:${if(direct)recipient else "aggregate"}"),expandedRuns)
    }
    val currentTranscript by rememberUpdatedState(transcript)
    val currentVisibleEvents by rememberUpdatedState(visibleEvents)

    suspend fun refresh(destination:TeamScope,history:Boolean=true){
        if(destination.team.isBlank())return
        val roomRows=mutableListOf<JSONObject>();var roomCursor="";val roomSeen=mutableSetOf<String>()
        do{
            val page=client.call("teams/${destination.team}/rooms?limit=100"+if(roomCursor.isNotBlank())"&cursor=$roomCursor" else "")
            roomAuthority=page.s("authorityId");roomRows+=page.optJSONArray("rooms")?.objects().orEmpty();roomCursor=page.s("next_cursor")
            if(roomCursor.isNotBlank()&&!roomSeen.add(roomCursor))throw Exception("Repeated room cursor")
        }while(roomCursor.isNotBlank())
        if(currentTarget.team!=destination.team)return
        // Reconcile legacy desired-state receipts only from this captured backend's
        // observed membership. Hidden controls never replay a membership command.
        roomRows.forEach{room->room.optJSONArray("members")?.objects().orEmpty().forEach{member->
            val key="room-membership:${client.owner}:${room.s("id")}:${member.s("id")}"
            if(Pocket.prefs.contains(key))Pocket.prefs.edit().remove(key).commit()
        }}
        rooms=roomRows
        if(task.isBlank()){task=teams.firstOrNull{it.s("id")==destination.team}?.s("lobby").orEmpty().ifBlank{"lobby"};return}
        if(history){
            val snapshot=client.call("teams/${destination.team}/overview")
            val inbox=client.call("teams/${destination.team}/attention?limit=100")
            if(currentTarget.team!=destination.team)return
            overview=snapshot;attention=inbox.optJSONArray("data")?.objects().orEmpty()
            val visibility=if(destination.direct)"direct:${destination.recipient}" else "room"
            val page=client.call("teams/${destination.team}/history?task=${destination.task}&visibility=$visibility&limit=50")
            if(sameTeamHistoryScope(currentTarget,destination)){
                val batch=page.optJSONArray("data")?.objects().orEmpty()
                val loadedEarlier=messages.minOfOrNull{it.optLong("id")}?.let{old->batch.minOfOrNull{it.optLong("id")}?.let{old<it}}==true
                historyScope=destination
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
                val event=origin.getJSONObject("event")
                val destination=notificationTeamScope(origin.s("team"),origin.s("task"),origin.s("agent"),event.s("visibility"))
                roomBrowsing=false;team=destination.team;task=destination.task;recipient=destination.recipient;direct=destination.direct;replyTo=null
                inspected=JSONObject(event.toString()).put("team_id",team)
                BackendNavigation.consumed()
            }catch(e:Exception){if(e is CancellationException)throw e;error=e.message?:"Could not open the original request"}
        }
    }
    LaunchedEffect(roomBrowsing,owner,team,task,direct,if(direct)recipient else ""){
        if(roomBrowsing||team.isBlank()||task.isBlank())return@LaunchedEffect
        try{refresh(currentTarget)}catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}
        val latest=messages.lastOrNull()?.optLong("id")?:return@LaunchedEffect
        val last=snapshotFlow{
            val count=currentTranscript.rows.size+(if(older!=null)1 else 0)
            if(sameTeamHistoryScope(currentTarget,historyScope)&&currentTranscript.rows.isNotEmpty()&&
                (currentVisibleEvents.lastOrNull()?.optLong("id")?:0)>=latest&&conversationList.layoutInfo.totalItemsCount>=count)
                count-1 else -1
        }.first{it>=0}
        conversationList.scrollToItem(last)
        conversationList.scrollBy(Float.MAX_VALUE)
    }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle,roomBrowsing,team,task,direct,if(direct)recipient else ""){
        messages=emptyList();older=null;error=""
        if(team.isNotBlank())Pocket.prefs.edit().putString("team:$owner",team).apply()
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
            while(isActive){try{refresh(currentTarget,history=!roomBrowsing);error=""}catch(e:Exception){if(e is CancellationException)throw e;error=e.message?:"Losangelex unavailable"};delay(3000)}
        }
    }
    fun submit(command:JSONObject){
        val destination=target
        try{client.savePending(destination,command)}catch(e:Exception){error=e.message.orEmpty();receipt="Message saved as draft · delivery not started";return}
        pending=command;busy=true;receipt="Submitting…"
        jobs.launch{
            try{
                client.call(command.getString("path"),command.getJSONObject("body"))
                client.savePending(destination,null);client.saveDraft(destination,"")
                if(currentTarget==destination){pending=null;input=TextFieldValue();replyTo=null;if(!destination.direct)recipient="";receipt="Accepted · work may still be queued"}
                refresh(destination)
            }catch(e:Exception){
                if(e is CancellationException)throw e
                val rejected=e is PocketApiException&&e.status in listOf(400,404,422)
                if(rejected){
                    val original=command.optJSONObject("body")?.s("body").orEmpty()
                    if(client.draft(destination).isBlank())client.saveDraft(destination,original)
                    client.savePending(destination,null)
                    if(currentTarget==destination&&input.text.isBlank())input=TextFieldValue(original,TextRange(original.length))
                }
                if(currentTarget==destination){if(rejected)pending=null;receipt=if(rejected)"Rejected · edit your message" else "Unconfirmed · retry the saved command";error=e.message?:"Submission unconfirmed"}
            }
            finally{busy=false}
        }
    }
    val selectedRoom=roomForTask(rooms,task)
    val destinations=selectedRoom?.optJSONArray("members")?.objects().orEmpty()
    val selectedTeam=teams.firstOrNull{it.s("id")==team}
    val lobby=selectedTeam?.s("lobby").orEmpty().ifBlank{overview.s("lobby")}
    val coordinator=selectedTeam?.s("coordinator").orEmpty()
    val sessionActor=if(direct)destinations.firstOrNull{it.s("agent")==recipient}else destinations.singleOrNull()
    val activeSession=sessionActor?.s("status")=="working"
    fun interruptSession(){
        val actor=sessionActor?.s("agent")?.takeIf{it.isNotBlank()}?:return
        if(busy)return
        val shown=target
        val destination=TeamScope(team,task,actor)
        val key="team-control:${destination.draftKey(client.owner)}:interrupt"
        val command=Pocket.prefs.getString(key,null)?.let{JSONObject(it)}?:JSONObject().put("commandId",UUID.randomUUID().toString()).put("task",task).put("agent",actor).put("action","interrupt")
        Pocket.prefs.edit().putString(key,command.toString()).apply();busy=true
        jobs.launch{try{
            client.call("teams/${destination.team}/control",command)
            Pocket.prefs.edit().remove(key).apply()
            if(sameTeamHistoryScope(currentTarget,shown))receipt="Stop confirmed"
        }catch(e:Exception){if(e is CancellationException)throw e;if(sameTeamHistoryScope(currentTarget,shown))error="Stop unconfirmed; retry the same action. ${e.message.orEmpty()}"}finally{busy=false}}
    }
    val canSend=!busy&&team.isNotBlank()&&task.isNotBlank()&&(pending!=null||text.isNotBlank()&&text.length<=3500)&&
        (pending!=null||if(direct||recipient.isNotBlank())recipient.isNotBlank() else if(task==lobby)coordinator.isNotBlank() else destinations.any{it.s("agent").isNotBlank()})
    fun send(){if(canSend&&!busy)submit(pending?:JSONObject().put("path","teams/$team/messages").put("body",scopedRoomMessage(target,UUID.randomUUID().toString(),text,selectedRoom,lobby,coordinator)))}
    val roomActions:@Composable ColumnScope.()->Unit={
        TextButton({roomBrowsing=true;actionsOpen=false},modifier=Modifier.testTag("hollywood-rooms")){BilingualLabel("Back")}
        TextButton({actionsOpen=false;onSettings()}){BilingualLabel("Settings")}
        if(attention.isNotEmpty())TextButton({inbox=true;actionsOpen=false}){BilingualLabel("Needs you");Text(" · ${attention.size}")}
        // The team service has no per-session permission preference; expose its
        // actual execution contract in the existing actions menu, not a header.
        BilingualLabel("Full access",fontSize=12.sp,color=Muted)
        client.savedScopes(team,task).filter{it!=target}.forEach{saved->
            TextButton({
                actionsOpen=false;recipient=saved.recipient;direct=saved.direct;replyTo=saved.replyTo;keyboardInput=true;receipt="";error=""
            },modifier=Modifier.testTag("team-saved:${saved.recipient}:${saved.direct}:${saved.replyTo?:"root"}")){
                BilingualLabel(if(saved.recipient.isBlank())"Saved message" else (if(saved.direct)"Saved private message to " else "Saved message to ")+saved.recipient,centered=false,maxLines=2)
            }
        }
    }
    fun voiceDestination(room:JSONObject?,agent:String=recipient):JSONObject? {
        if(room==null||roomAuthority.isBlank())return null
        val name=agent.ifBlank{teams.firstOrNull{it.s("id")==team}?.s("coordinator").orEmpty()}
        val actor=room.optJSONArray("members")?.objects()?.firstOrNull{it.s("agent")==name}?:return null
        return JSONObject().put("team",team).put("roomId",room.s("id")).put("authorityId",roomAuthority).put("actorId",actor.s("id")).put("direct",direct&&agent.isNotBlank())
    }
    BackHandler(enabled=!roomBrowsing){keyboard?.hide();roomBrowsing=true}
    LaunchedEffect(keyboardInput,task,direct,recipient){if(keyboardInput&&!roomBrowsing){inputFocus.requestFocus();keyboard?.show()}}
    if(roomBrowsing){
        RoomBrowser(teams,team,rooms,error,
            {team=it;task="";rooms=emptyList();recipient="";direct=false;replyTo=null},
            {room,typing->task=room.s("task");recipient="";direct=false;replyTo=null;keyboardInput=typing;roomBrowsing=false},onSettings,
            {room,agent->task=room.s("task");recipient=agent;direct=true;replyTo=null;keyboardInput=false;roomBrowsing=false},
            {room,modifier->voiceDestination(room,"")?.let{destination->VoiceLaunchButton(modifier=modifier,cardRegion=true,roomDestination=destination)}?:CardInteractionRegion("Chat",modifier,room.s("title")){task=room.s("task");recipient="";direct=false;replyTo=null;keyboardInput=false;roomBrowsing=false}},
            {newTask=true})
        if(newTask)RoomCreateDialog(client,team,{newTask=false}){id->task=id;recipient="";direct=false;replyTo=null;newTask=false;roomBrowsing=false}
        return
    }
    Column(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}.imePadding().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        if(error.isNotBlank())ImmersionText("team-error:$owner:$team",error,color=Coral,fontSize=13.sp)
        LazyColumn(Modifier.weight(1f).testTag("room-history"),state=conversationList,verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(older!=null)item{TextButton({val destination=target;val cursor=older;jobs.launch{try{val visibility=if(destination.direct)"direct:${destination.recipient}" else "room";val page=client.call("teams/${destination.team}/history?task=${destination.task}&visibility=$visibility&limit=50&before=$cursor");if(sameTeamHistoryScope(currentTarget,destination)){messages=(page.optJSONArray("data")?.objects().orEmpty()+messages).distinctBy{it.optLong("id")}.sortedBy{it.optLong("id")};older=page.takeUnless{it.isNull("olderCursor")}?.optLong("olderCursor")}}catch(e:Exception){if(e is CancellationException)throw e;if(sameTeamHistoryScope(currentTarget,destination))error=e.message.orEmpty()}}}){BilingualLabel("Earlier messages")}}
            itemsIndexed(transcript.rows,key={_,row->row.s("id")}){index,row->
                val event=visibleEvents.firstOrNull{it.optLong("id")==row.optLong("roomEventId")}?:return@itemsIndexed
                Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
                    if(roomAuthorStartsBlock(transcript.rows,index))Text(row.s("roomAuthor"),color=Mint,style=MaterialTheme.typography.labelMedium)
                    event.optJSONObject("reply")?.let{Text("Reply to ${it.s("author")}: ${it.s("body")}",style=MaterialTheme.typography.bodySmall,color=Muted)}
                    // Adapt scoped events into the actual session renderer. Contexts
                    // remain independent; only their authorized public output is aggregated.
                    val runs=transcript.groups[row.s("id")]
                    if(runs!=null){
                        val expanded=runs.any{it.s("id") in expandedRuns}
                        CommandRunsHeader(runs,expanded){val ids=runs.map{it.s("id")}.toSet();expandedRuns=if(expanded)expandedRuns-ids else expandedRuns+ids}
                    }else if(isCommandRun(row))ActivityRow(row,fullyExpanded=true)
                    else TranscriptRow(row,showSpeaker=false)
                        if(event.s("kind") in listOf("attention","approval")&&event.s("attentionState")=="open")TextButton({inspected=JSONObject(event.toString()).put("team_id",team)}){BilingualLabel("Open request")}
                        else if(event.s("kind")=="message")TextButton({replyTo=event.optLong("id");recipient=teamReplyRecipient(target,event.s("author"))},modifier=Modifier.testTag("team-reply:"+event.optLong("id"))){BilingualLabel("Reply")}
                }
            }
        }
        if(replyTo!=null||!direct&&recipient.isNotBlank())TextButton({replyTo=null;if(!direct)recipient="";receipt="";error=""},modifier=Modifier.testTag("team-reply-clear")){BilingualLabel(if(replyTo!=null)"Cancel reply" else "Close saved message")}
        if(receipt.isNotBlank())ImmersionText("team-receipt:$owner:$team",receipt,fontSize=12.sp,color=Muted)
        if(keyboardInput)ConversationComposerRow{
            voiceDestination(selectedRoom)?.let{VoiceLaunchButton(modifier=Modifier.size(64.dp),compact=true,roomDestination=it)}
            if(activeSession)IconButton({interruptSession()},enabled=!busy,modifier=Modifier.size(48.dp)){SymbolIcon("Stop","Stop agent",Modifier.size(32.dp),tint=Coral)}
            val displayedInput=if(pending!=null){val captured=pending?.optJSONObject("body")?.s("body").orEmpty();TextFieldValue(captured,TextRange(captured.length))}else input
            ConversationDraftField(displayedInput,{input=it;client.saveDraft(target,it.text)},enabled=pending==null&&!busy,
                modifier=Modifier.weight(1f).focusRequester(inputFocus).testTag("team-input").onPreviewKeyEvent{event->
                    val enter=event.key==Key.Enter||event.key==Key.NumPadEnter
                    if(enter){
                        if(event.type==KeyEventType.KeyDown){
                            if(event.isShiftPressed){if(pending==null&&!busy){input=teamInsertNewline(input);client.saveDraft(target,input.text)}}
                            else send()
                        };true
                    }else false
                },onSend={send()},placeholder={
                    if(direct)InterfaceTemplateText(InterfaceTemplate.TEAM_MESSAGE_RECIPIENT,recipient)
                    else BilingualLabel("Message…",centered=false,color=Muted,fontSize=15.sp)
                })
            ConversationSendButton(text.isNotBlank()||pending!=null,canSend,busy,
                if(pending!=null)"Retry message" else "Send message",modifier=Modifier.testTag("team-send")){send()}
        }
        if(!keyboardInput)Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            voiceDestination(selectedRoom)?.let{VoiceLaunchButton(modifier=Modifier.weight(1f),bar=true,roomDestination=it)}
            if(activeSession)IconButton({interruptSession()},enabled=!busy,modifier=Modifier.size(48.dp)){SymbolIcon("Stop","Stop agent",Modifier.size(32.dp),tint=Coral)}
            ChatActionButton("Keyboard",Icons.Rounded.Keyboard,Modifier.weight(1f),{keyboardInput=true})
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
            ConversationActionsMenu(actionsOpen,{actionsOpen=it},modifier=Modifier.testTag("hollywood-room-actions"),statusLabel=if(if(direct)activeSession else destinations.any{it.s("status")=="working"})"Working" else "Idle",content=roomActions)
        }
    }
    if(newTask)RoomCreateDialog(client,team,{newTask=false}){id->task=id;recipient="";direct=false;replyTo=null;newTask=false}
    if(inbox)TeamInbox(client,team,{inbox=false}){event->inspected=event;inbox=false}
    inspected?.let{event->TeamAttentionDialog(client,team,event,{inspected=null}){jobs.launch{try{refresh(currentTarget)}catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}}}}
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun TeamChoice(label:InterfaceTemplate,choices:List<Pair<String,String>>,selected:String,modifier:Modifier=Modifier,onSelect:(String)->Unit){
    var open by remember{mutableStateOf(false)}
    val name=choices.firstOrNull{it.first==selected}?.second?:selected
    val caption=when(label){
        InterfaceTemplate.TEAM_CHOICE_TEAM->InterfaceTemplate.TEAM_CAPTION_TEAM
        InterfaceTemplate.TEAM_CHOICE_TASK->InterfaceTemplate.TEAM_CAPTION_TASK
        InterfaceTemplate.TEAM_CHOICE_TO->InterfaceTemplate.TEAM_CAPTION_TO
        InterfaceTemplate.TEAM_CHOICE_AGENT->InterfaceTemplate.TEAM_CAPTION_AGENT
        else->error("Unsupported Team selector caption")
    }
    Box(modifier){
        TextButton({open=true},modifier=Modifier.fillMaxWidth().testTag("team-choice:"+label.name),contentPadding=PaddingValues(horizontal=4.dp,vertical=4.dp)){
            // Keep identity readable independently of the longer cycling caption.
            // Both stay inside one existing thumb target, without another toolbar row.
            Column(horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally){
                InterfaceTemplateText(caption,style=MaterialTheme.typography.labelSmall.copy(fontSize=14.sp,lineHeight=16.sp),maxLines=1,overflow=TextOverflow.Ellipsis)
                if(label==InterfaceTemplate.TEAM_CHOICE_TO&&selected.isBlank())BilingualLabel("Coordinator",fontSize=14.sp,maxLines=1)
                else Text(name,fontSize=14.sp,lineHeight=18.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(open,{open=false}){
            choices.forEach{(id,value)->DropdownMenuItem(
                text={if(label==InterfaceTemplate.TEAM_CHOICE_TO&&id.isBlank())BilingualLabel("Coordinator",centered=false)else Text(value)},
                modifier=Modifier.testTag("team-choice-option:"+label.name+":"+id).semantics{testTagsAsResourceId=true},
                onClick={open=false;onSelect(id)})}
        }
    }
}
