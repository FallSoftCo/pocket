package co.fallsoft.pocket

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/** Native Hollywood conversation; existing session surfaces, canonical room identities. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun HollywoodScreen(owner:String,binding:JSONObject,onSettings:()->Unit){
    val client=remember(owner,binding.s("bindingId")){HollywoodClient(owner,binding)}
    val jobs=rememberCoroutineScope()
    var room by remember(client.bindingId){mutableStateOf(Pocket.prefs.getString("hollywood-room:$owner:${client.bindingId}",client.defaultRoomId)?:client.defaultRoomId)}
    var browsing by remember(client.bindingId){mutableStateOf(false)}
    var rooms by remember(client.bindingId){mutableStateOf(emptyList<JSONObject>())}
    var detail by remember(client.bindingId,room){mutableStateOf(JSONObject())}
    var events by remember(client.bindingId,room){mutableStateOf(emptyList<JSONObject>())}
    var attention by remember(client.bindingId,room){mutableStateOf(emptyList<JSONObject>())}
    var cursor by remember(client.bindingId,room){mutableStateOf<Long?>(null)}
    var older by remember(client.bindingId,room){mutableStateOf<Long?>(null)}
    var recipient by remember(client.bindingId,room){mutableStateOf("")}
    var direct by remember(client.bindingId,room){mutableStateOf(false)}
    var source by remember(client.bindingId,room){mutableStateOf<String?>(null)}
    var postOnly by remember(client.bindingId,room){mutableStateOf(false)}
    var error by remember(client.bindingId,room){mutableStateOf("")}
    var notice by remember(client.bindingId,room){mutableStateOf("")}
    var busy by remember(client.bindingId,room){mutableStateOf(false)}
    var expanded by remember(client.bindingId,room,direct,recipient){mutableStateOf(emptySet<String>())}
    var menu by remember{mutableStateOf(false)}
    var inspecting by remember{mutableStateOf<JSONObject?>(null)}
    val destination=recipient.ifBlank{detail.optJSONObject("room")?.s("owner").orEmpty()}
    val scope=if(hollywoodUuid(destination))HollywoodScope(room,destination,if(direct)"direct" else "room",source,if(postOnly)"message" else "request")else null
    val currentScope by rememberUpdatedState(scope)
    var input by remember(scope){mutableStateOf(TextFieldValue(scope?.let(client::draft).orEmpty()))}
    var pending by remember(scope){mutableStateOf(scope?.let(client::pending))}
    val list=rememberLazyListState()
    var following by remember(client.bindingId,room,direct,recipient){mutableStateOf(true)}
    val actors=detail.optJSONArray("actors")?.objects().orEmpty()
    val agentNames=actors.associate{it.s("id") to it.s("name")}
    val visible=events.filter{!direct||it.s("audience")=="direct"&&setOf(it.s("author"),it.s("recipient"))==setOf(client.principalId,destination)}
        .map{JSONObject(it.toString()).put("isSelf",it.s("author")==client.principalId).put("authorName",if(it.s("author")==client.principalId)"You" else agentNames[it.s("author")]?:it.s("author"))}
    val transcript=remember(visible,expanded){commandRunProjection(roomTranscriptRows(visible,"hollywood:$owner:${client.bindingId}:$room"),expanded)}
    LaunchedEffect(list){snapshotFlow{list.isScrollInProgress to list.canScrollForward}.collect{(scrolling,forward)->if(scrolling)following=!forward}}
    LaunchedEffect(transcript.rows.size,room){if(following&&transcript.rows.isNotEmpty())list.scrollToItem(transcript.rows.lastIndex+if(older!=null)1 else 0)}
    suspend fun refresh(selected:String){
        val catalog=client.call("rooms").getJSONArray("rooms").objects()
        val metadata=client.call("rooms/$selected")
        val previous=cursor
        var page=client.call("rooms/$selected/history?"+(previous?.let{"after=$it"}?:"before=9007199254740991")+"&limit=100")
        val batch=page.getJSONArray("events").objects().toMutableList()
        var next=page.getLong("nextCursor")
        if(previous!=null){
            repeat(4){if(page.getJSONArray("events").length()==100){page=client.call("rooms/$selected/history?after=$next&limit=100");batch+=page.getJSONArray("events").objects();next=page.getLong("nextCursor")}}
        }
        val requests=client.call("rooms/$selected/attention?after=0&limit=100").getJSONArray("attention").objects()
        if(room!=selected)return
        rooms=catalog;detail=metadata;events=(events+batch).associateBy{it.s("id")}.values.sortedBy{it.optLong("seq")};cursor=next
        if(previous==null)older=page.takeUnless{it.isNull("olderCursor")}?.optLong("olderCursor")
        attention=requests;error=""
    }
    LaunchedEffect(client.bindingId,room){
        while(isActive){try{refresh(room)}catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()};delay(3000)}
    }
    fun choose(value:String){room=value;recipient="";direct=false;source=null;browsing=false;Pocket.prefs.edit().putString("hollywood-room:$owner:${client.bindingId}",value).apply()}
    fun send(inspect:Boolean=false){
        val captured=scope?:return;if(busy)return
        val intent=pending?:runCatching{client.capture(captured,input.text)}.getOrElse{error=it.message.orEmpty();return}
        pending=intent;busy=true
        jobs.launch{try{
            if(inspect)client.inspect(captured,intent)else client.submit(captured,intent)
            if(currentScope==captured){pending=null;if(input.text==intent.getJSONObject("body").s("body")){input=TextFieldValue();client.saveDraft(captured,"")};notice="Accepted by Hollywood";refresh(captured.room)}
        }catch(e:Exception){if(e is CancellationException)throw e;if(currentScope==captured)error="Delivery remains saved. ${e.message.orEmpty()}"}finally{if(currentScope==captured)busy=false}}
    }
    BackHandler(enabled=browsing){browsing=false}
    if(browsing){
        Column(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}.imePadding()){
            WorkCatalogList(Modifier.weight(1f)){
                items(rooms.sortedBy{it.s("name")},key={it.s("id")}){item->
                    val task=Task("hollywood-room:"+item.s("id"),item.s("name"),"","idle",0,false,preview=item.s("purpose"))
                    TaskCard(task,modifier=Modifier.testTag("native-room:"+item.s("id")),onOpen={choose(item.s("id"))},
                        voice={modifier->CardInteractionRegion("Chat",modifier,item.s("name")){choose(item.s("id"))}},onRename=null,onWatch=null)
                }
            }
            Row(Modifier.padding(16.dp)){TextButton({browsing=false}){BilingualLabel("Back")};ProfileSwitcher{};TextButton(onSettings){BilingualLabel("Settings")}}
        };return
    }
    Column(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}.imePadding().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text(detail.optJSONObject("room")?.s("name").orEmpty(),style=MaterialTheme.typography.titleMedium)
        if(error.isNotBlank())Text(error,color=Coral)
        if(notice.isNotBlank())Text(notice,color=Muted)
        LazyColumn(Modifier.weight(1f).testTag("native-room-history"),state=list,verticalArrangement=Arrangement.spacedBy(10.dp)){
            older?.let{before->item{TextButton({following=false;val selected=room;jobs.launch{try{val page=client.call("rooms/$selected/history?before=$before&limit=100");if(room==selected){events=(page.getJSONArray("events").objects()+events).associateBy{it.s("id")}.values.sortedBy{it.optLong("seq")};older=page.takeUnless{it.isNull("olderCursor")}?.optLong("olderCursor")}}catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}}}){BilingualLabel("Earlier messages")}}}
            itemsIndexed(transcript.rows,key={_,row->row.s("id")}){index,row->
                Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
                    if(roomAuthorStartsBlock(transcript.rows,index))Text(row.s("roomAuthor"),color=Mint,style=MaterialTheme.typography.labelMedium)
                    val group=transcript.groups[row.s("id")]
                    if(group!=null){val opened=group.any{it.s("id") in expanded};CommandRunsHeader(group,opened){val keys=group.map{it.s("id")}.toSet();expanded=if(opened)expanded-keys else expanded+keys}}
                    else if(isCommandRun(row))ActivityRow(row,fullyExpanded=true)
                    else {
                        TranscriptRow(row,showSpeaker=false)
                        val event=visible.firstOrNull{it.s("id")==row.s("roomEventId")}
                        val request=attention.firstOrNull{it.getJSONObject("attention").s("event")==event?.s("id")}
                        if(request!=null)TextButton({inspecting=request}){BilingualLabel(if(request.getJSONObject("attention").s("state")=="pending")"Needs you" else "Review response")}
                        if(event!=null&&event.s("kind") in listOf("message","result","request")&&
                            (event.s("audience")!="direct"||client.principalId in setOf(event.s("author"),event.s("recipient")))){
                            TextButton({source=event.s("id");direct=event.s("audience")=="direct";recipient=if(event.s("author")==client.principalId)event.s("recipient")else event.s("author");postOnly=actors.firstOrNull{it.s("id")==recipient}?.s("kind")=="human"}){BilingualLabel("Reply")}
                        }
                    }
                }
            }
        }
        if(pending!=null)TextButton({send(true)},enabled=!busy){BilingualLabel("Check original delivery")}
        ConversationComposerRow{
            ConversationDraftField(input,{input=it;scope?.let{target->client.saveDraft(target,it.text)}},Modifier.weight(1f),enabled=!busy,
                onSend={if(pending==null&&input.text.isNotBlank())send()},placeholder={BilingualLabel("Message…",centered=false)})
            ConversationSendButton(input.text.isNotBlank(),scope!=null&&!busy&&pending==null&&input.text.isNotBlank()&&
                detail.optJSONObject("permissions")?.optBoolean(if(postOnly)"can_post" else "can_request")==true,busy,"Send message"){send()}
        }
        ConversationActionsMenu(menu,{menu=it},statusLabel="Room"){
            TextButton({menu=false;browsing=true}){BilingualLabel("Rooms")}
            TextButton({menu=false;onSettings()}){BilingualLabel("Settings")}
            ProfileSwitcher{menu=false}
            Row{TextButton({recipient="";source=null;direct=false}){BilingualLabel("Room owner")};Switch(direct,{direct=it;source=null});BilingualLabel("Private")}
            actors.filter{it.s("id")!=client.principalId}.forEach{actor->TextButton({recipient=actor.s("id");source=null;postOnly=actor.s("kind")=="human";menu=false}){Text(actor.s("name"))}}
            Row{Checkbox(postOnly,{postOnly=it});BilingualLabel("Post without requesting work")}
            if(source!=null)TextButton({source=null}){BilingualLabel("Clear reply target")}
            scope?.let{current->client.savedScopes(room).filter{it!=current}.forEach{saved->TextButton({recipient=saved.recipient;direct=saved.audience=="direct";source=saved.sourceEvent;postOnly=saved.kind=="message";menu=false}){BilingualLabel("Saved message",centered=false);Text(" · "+(agentNames[saved.recipient]?:saved.recipient))}}}
            detail.optJSONArray("deliveries")?.objects().orEmpty().filter{it.optBoolean("can_cancel")}.forEach{delivery->
                TextButton({val selected=room;jobs.launch{try{client.call("deliveries/${delivery.s("id")}/cancel",JSONObject());if(room==selected)notice="Stop requested";refresh(selected)}catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}}}){Text("Stop · "+(agentNames[delivery.s("agent")]?:delivery.s("agent"))+" · "+delivery.s("state"))}
            }
        }
    }
    inspecting?.let{value->HollywoodAttentionDialog(client,value,{inspecting=null}){jobs.launch{refresh(room)}}}
}

@Composable private fun HollywoodAttentionDialog(client:HollywoodClient,source:JSONObject,dismiss:()->Unit,changed:()->Unit){
    val id=source.getJSONObject("attention").s("id")
    var current by remember(id){mutableStateOf(source)}
    var pending by remember(id){mutableStateOf(client.attentionPending(id))}
    var answers by remember(id){mutableStateOf(emptyMap<String,String>())}
    var busy by remember(id){mutableStateOf(false)}
    var error by remember(id){mutableStateOf("")}
    val jobs=rememberCoroutineScope()
    val attention=current.getJSONObject("attention")
    val request=attention.getJSONObject("request")
    val questions=request.optJSONArray("questions")?.objects().orEmpty()
    val approval=attention.s("method") !in listOf("hollywood/question","item/tool/requestUserInput")
    val open=attention.s("state")=="pending"&&pending==null
    fun answer(decision:String?=null,inspect:Boolean=false){
        busy=true;jobs.launch{try{
            val fresh=client.call("attention/$id")
            require(hollywoodJsonEqual(source.getJSONObject("scope"),fresh.getJSONObject("scope"))){"Original attention scope changed"}
            current=fresh
            if(!inspect)require(fresh.getJSONObject("attention").s("state")=="pending"){"This request is already resolved"}
            val response=if(approval)JSONObject().put("decision",decision)else JSONObject().put("answers",JSONObject().apply{questions.forEach{q->put(q.s("id"),JSONObject().put("answers",org.json.JSONArray(listOf(answers[q.s("id")].orEmpty()))))}})
            val intent=pending?:client.captureAttention(fresh,response).also{pending=it}
            client.respond(id,intent,inspect);pending=null;changed();dismiss()
        }catch(e:Exception){if(e is CancellationException)throw e;error="Response remains saved. ${e.message.orEmpty()}"}finally{busy=false}}
    }
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text(if(approval)"Review approval" else "Answer question")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(attention.s("state"),color=Muted)
            if(approval){Text(request.s("command",request.optJSONArray("changes")?.toString().orEmpty()));if(request.s("cwd").isNotBlank())Text(request.s("cwd"));Text(request.optJSONObject("nativeContext")?.toString().orEmpty())}
            else questions.forEach{question->
                Text(question.s("question"))
                question.optJSONArray("options")?.objects().orEmpty().forEach{option->TextButton({answers=answers+(question.s("id") to option.s("label"))},enabled=open&&!busy){Text(option.s("label"))}}
                OutlinedTextField(answers[question.s("id")].orEmpty(),{answers=answers+(question.s("id") to it)},enabled=open&&!busy,label={BilingualLabel("Your answer",centered=false)})
            }
            if(error.isNotBlank())Text(error,color=Coral)
        }
    },confirmButton={
        if(pending!=null)TextButton({answer(inspect=true)},enabled=!busy){BilingualLabel("Check original response")}
        else if(approval)Row{TextButton({answer("decline")},enabled=open&&!busy){BilingualLabel("Decline")};TextButton({answer("accept")},enabled=open&&!busy){BilingualLabel("Approve once")}}
        else TextButton({answer()},enabled=open&&!busy&&questions.all{answers[it.s("id")].orEmpty().isNotBlank()}){BilingualLabel("Send answer")}
    },dismissButton={TextButton(dismiss,enabled=!busy){BilingualLabel("Close")}})
}
