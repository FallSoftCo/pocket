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
    var workflow by remember(client.bindingId,room){mutableStateOf(false)}
    var inspecting by remember{mutableStateOf<JSONObject?>(null)}
    var guidance by remember(client.bindingId,room){mutableStateOf<JSONObject?>(null)}
    var guidanceReceipt by remember(client.bindingId,room){mutableStateOf<JSONObject?>(null)}
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
    val requested=HollywoodNotifications.target
    LaunchedEffect(client.bindingId,requested){
        requested?.takeIf{it.owner==owner&&it.binding==client.bindingId}?.let{target->
            choose(target.room)
            target.reply?.let{reply->recipient=reply.recipient;direct=reply.audience=="direct";source=reply.sourceEvent;postOnly=reply.kind=="message"}
            inspecting=target.attention
            HollywoodNotifications.consumed(target)
        }
    }
    BackHandler(enabled=browsing){browsing=false}
    if(browsing){
        Column(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}.imePadding()){
            WorkCatalogList(Modifier.weight(1f),reverseLayout=false){
                val ordered=rooms.sortedWith(compareBy<JSONObject>{it.s("id")!=client.defaultRoomId}.thenBy{it.s("name").lowercase()})
                items(ordered,key={it.s("id")}){item->
                    val task=Task("hollywood-room:"+item.s("id"),item.s("name"),"","idle",0,false,preview=item.s("purpose"))
                    TaskCard(task,modifier=Modifier.testTag("native-room:"+item.s("id")),onOpen={choose(item.s("id"))},
                        voice={modifier->CardInteractionRegion("Chat",modifier,item.s("name")){choose(item.s("id"))}},onRename=null,onWatch=null)
                }
            }
            Row(Modifier.padding(16.dp)){TextButton({browsing=false}){BilingualLabel("Back")};ProfileSwitcher{};TextButton(onSettings){BilingualLabel("Settings")}}
        };return
    }
    Column(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}.imePadding().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text(detail.optJSONObject("room")?.s("name").orEmpty(),style=MaterialTheme.typography.titleMedium)
            TextButton({workflow=true},modifier=Modifier.testTag("native-room-work")){BilingualLabel("Goals & work")}
        }
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
                        event?.optJSONObject("data")?.s("nativeControlId")?.takeIf(::hollywoodUuid)?.let{id->
                            TextButton({
                                val selected=room;jobs.launch{try{
                                    val receipt=client.call("native-controls/"+id)
                                    require(receipt.getJSONObject("control").s("id")==id&&receipt.getJSONObject("scope").s("roomId")==selected)
                                    if(room==selected)guidanceReceipt=receipt
                                }catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}}
                            },modifier=Modifier.testTag("native-guidance-review:"+id)){BilingualLabel("Review guidance")}
                        }
                        if(event!=null&&event.s("kind") in listOf("message","result","request")&&
                            (event.s("audience")!="direct"||client.principalId in setOf(event.s("author"),event.s("recipient")))){
                            TextButton({source=event.s("id");direct=event.s("audience")=="direct";recipient=if(event.s("author")==client.principalId)event.s("recipient")else event.s("author");postOnly=actors.firstOrNull{it.s("id")==recipient}?.s("kind")=="human"}){BilingualLabel("Reply")}
                        }
                    }
                }
            }
        }
        val active=detail.optJSONArray("deliveries")?.objects().orEmpty()
        if(active.isNotEmpty())Column(Modifier.heightIn(max=120.dp).verticalScroll(rememberScrollState())){
            active.forEach{delivery->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Text((agentNames[delivery.s("agent")]?:"Agent")+" · "+delivery.s("state"),modifier=Modifier.weight(1f),color=Muted)
                    if(delivery.optBoolean("can_steer"))TextButton({guidance=delivery.getJSONObject("control_scope")},modifier=Modifier.testTag("native-guide:"+delivery.s("id"))){BilingualLabel("Guide")}
                    if(delivery.optBoolean("can_cancel"))TextButton({
                        val selected=room;jobs.launch{try{
                            client.call("deliveries/"+delivery.s("id")+"/cancel",JSONObject())
                            if(room==selected)notice="Stop requested"
                            refresh(selected)
                        }catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}}
                    }){BilingualLabel("Stop")}
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
        if(binding.optBoolean("voice")&&scope!=null&&!postOnly&&detail.optJSONObject("permissions")?.optBoolean("can_request")==true){
            val voiceDestination=JSONObject().put("backend","lax").put("bindingId",client.bindingId).put("authorityId",client.authorityId)
                .put("roomId",room).put("recipientId",scope.recipient).put("audience",scope.audience)
                .apply{scope.sourceEvent?.let{put("sourceEventId",it)}}
            VoiceLaunchButton(modifier=Modifier.fillMaxWidth(),bar=true,roomDestination=voiceDestination)
        }
        ConversationActionsMenu(menu,{menu=it},statusLabel="Room"){
            TextButton({menu=false;browsing=true}){BilingualLabel("Rooms")}
            TextButton({menu=false;onSettings()}){BilingualLabel("Settings")}
            ProfileSwitcher{menu=false}
            client.savedGuidanceScopes(room).forEach{saved->
                TextButton({guidance=saved;menu=false},modifier=Modifier.testTag("native-guidance-saved:"+saved.s("deliveryId")+":"+saved.s("nativeTurn"))){Text("Saved guidance · "+(client.guidancePending(saved)?.s("state")?:"draft"))}
            }

            Row{TextButton({recipient="";source=null;direct=false}){BilingualLabel("Room default")};Switch(direct,{direct=it;source=null});BilingualLabel("Private")}
            actors.filter{it.s("id")!=client.principalId}.forEach{actor->TextButton({recipient=actor.s("id");source=null;postOnly=actor.s("kind")=="human";menu=false}){Text(actor.s("name"))}}
            Row{Checkbox(postOnly,{postOnly=it});BilingualLabel("Post without requesting work")}
            if(source!=null)TextButton({source=null}){BilingualLabel("Clear reply target")}
            scope?.let{current->client.savedScopes(room).filter{it!=current}.forEach{saved->TextButton({recipient=saved.recipient;direct=saved.audience=="direct";source=saved.sourceEvent;postOnly=saved.kind=="message";menu=false}){BilingualLabel("Saved message",centered=false);Text(" · "+(agentNames[saved.recipient]?:saved.recipient))}}}
        }
    }
    guidance?.let{target->
        val accepts=detail.optJSONArray("deliveries")?.objects().orEmpty().any{it.optBoolean("can_steer")&&it.optJSONObject("control_scope")?.let{scope->hollywoodJsonEqual(scope,target)}==true}
        HollywoodGuidanceDialog(client,target,agentNames[target.s("agentId")]?:"Agent",accepts){guidance=null}
    }
    guidanceReceipt?.let{value->
        AlertDialog(onDismissRequest={guidanceReceipt=null},title={Text("Guidance receipt")},text={
            val control=value.getJSONObject("control")
            Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(control.s("state")+" · work "+value.s("deliveryState"),color=Muted)
                Text(control.s("body"))
                Text("Recorded input does not by itself prove that the work was completed or accepted.",color=Muted)
            }
        },confirmButton={TextButton({guidanceReceipt=null}){BilingualLabel("Close")}})
    }
    if(workflow)HollywoodWorkDialog(client,room){workflow=false}
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
    val open=attention.s("state")=="pending"&&current.s("deliveryState") in listOf("claimed","running")&&pending==null
    fun answer(decision:String?=null,inspect:Boolean=false){
        busy=true;jobs.launch{try{
            val fresh=client.call("attention/$id")
            require(hollywoodJsonEqual(source.getJSONObject("scope"),fresh.getJSONObject("scope"))){"Original attention scope changed"}
            current=fresh
            if(!inspect)require(fresh.getJSONObject("attention").s("state")=="pending"){"This request is already resolved"}
            val response=if(approval)JSONObject().put("decision",decision)else JSONObject().put("answers",JSONObject().apply{questions.forEach{q->put(q.s("id"),JSONObject().put("answers",org.json.JSONArray(listOf(answers[q.s("id")].orEmpty()))))}})
            val intent=pending?:client.captureAttention(fresh,response).also{pending=it}
            client.respond(id,intent,inspect);pending=null;changed();dismiss()
        }catch(e:Exception){if(e is CancellationException)throw e;pending=client.attentionPending(id);error=if(pending?.s("state")=="rejected")"Response was not accepted. ${e.message.orEmpty()}" else "Response remains saved. ${e.message.orEmpty()}"}finally{busy=false}}
    }
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text(if(approval)"Review approval" else "Answer question")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(attention.s("state")+" · native delivery "+current.s("deliveryState"),color=Muted)
            if(approval){Text(request.s("command",request.optJSONArray("changes")?.toString().orEmpty()));if(request.s("cwd").isNotBlank())Text(request.s("cwd"));Text(request.optJSONObject("nativeContext")?.toString().orEmpty())}
            else questions.forEach{question->
                Text(question.s("question"))
                question.optJSONArray("options")?.objects().orEmpty().forEach{option->TextButton({answers=answers+(question.s("id") to option.s("label"))},enabled=open&&!busy){Text(option.s("label"))}}
                OutlinedTextField(answers[question.s("id")].orEmpty(),{answers=answers+(question.s("id") to it)},enabled=open&&!busy,label={BilingualLabel("Your answer",centered=false)})
            }
            if(error.isNotBlank())Text(error,color=Coral)
        }
    },confirmButton={
        if(pending?.s("state")=="rejected"&&attention.s("state")=="pending"&&current.s("deliveryState") in listOf("claimed","running"))
            TextButton({
                val saved=client.editRejectedAttention(id).optJSONObject("answers")
                answers=questions.associate{q->q.s("id") to saved?.optJSONObject(q.s("id"))?.optJSONArray("answers")?.optString(0).orEmpty()}
                pending=null;error=""
            },enabled=!busy){BilingualLabel("Edit answer")}
        else if(pending!=null)TextButton({answer(inspect=true)},enabled=!busy){BilingualLabel("Check original response")}
        else if(approval)Row{TextButton({answer("decline")},enabled=open&&!busy){BilingualLabel("Decline")};TextButton({answer("accept")},enabled=open&&!busy){BilingualLabel("Approve once")}}
        else TextButton({answer()},enabled=open&&!busy&&questions.all{answers[it.s("id")].orEmpty().isNotBlank()}){BilingualLabel("Send answer")}
    },dismissButton={TextButton(dismiss,enabled=!busy){BilingualLabel("Close")}})
}


@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable private fun HollywoodGuidanceDialog(client:HollywoodClient,scope:JSONObject,name:String,accepts:Boolean,dismiss:()->Unit){
    val key=scope.s("deliveryId")+":"+scope.s("nativeTurn")
    var text by remember(key){mutableStateOf(client.guidanceDraft(scope))}
    var pending by remember(key){mutableStateOf(client.guidancePending(scope))}
    var busy by remember(key){mutableStateOf(false)}
    var error by remember(key){mutableStateOf("")}
    val jobs=rememberCoroutineScope()
    val state=pending?.s("state").orEmpty()
    val canCreate=accepts&&(pending==null||state in listOf("native_admitted","native_recorded","rejected"))
    fun send(inspect:Boolean){
        busy=true;jobs.launch{try{
            val intent=if(inspect)pending?:return@launch else client.captureGuidance(scope,text).also{pending=it}
            client.guide(intent,inspect)
            pending=client.guidancePending(scope);error=""
            if(!inspect&&text==intent.getJSONObject("body").s("body")){text="";client.saveGuidanceDraft(scope,"")}
        }catch(e:Exception){if(e is CancellationException)throw e;pending=client.guidancePending(scope);error=e.message.orEmpty()}finally{busy=false}}
    }
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text("Guide "+name)},text={
        Column(Modifier.semantics{testTagsAsResourceId=true},verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(if(accepts)"Guidance goes to this active work and may wait for its current command to finish." else "This work is no longer accepting guidance. Its original receipt remains available.",color=Muted)
            if(state.isNotBlank())Text(when(state){
                "saved"->"Saved on this device"
                "sending","unknown"->"Delivery unconfirmed · check the original"
                "pending","dispatching"->"Saved by Hollywood · awaiting the agent"
                "native_admitted"->"Admitted to the active work · awaiting history"
                "native_recorded"->"Recorded in the active conversation"
                "rejected"->"Guidance was not accepted"
                else->state
            },color=Muted)
            if(pending!=null)TextButton({send(true)},enabled=!busy){BilingualLabel("Check original delivery")}
            OutlinedTextField(text,{text=it;client.saveGuidanceDraft(scope,it)},enabled=!busy,readOnly=!canCreate,label={BilingualLabel("Your guidance",centered=false)},modifier=Modifier.testTag("native-guidance-text"))
            if(error.isNotBlank())Text(error,color=Coral)
        }
    },confirmButton={TextButton({send(false)},enabled=!busy&&canCreate&&text.isNotBlank(),modifier=Modifier.semantics{testTagsAsResourceId=true}.testTag("native-guidance-send")){BilingualLabel("Send guidance")}},
        dismissButton={TextButton(dismiss,enabled=!busy){BilingualLabel("Close")}})
}
