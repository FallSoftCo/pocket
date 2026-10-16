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
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

/** Native Hollywood conversation; existing session surfaces, canonical room identities. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,ExperimentalLayoutApi::class)
@Composable internal fun HollywoodScreen(owner:String,binding:JSONObject,onSettings:()->Unit){
    val client=remember(owner,binding.s("bindingId")){HollywoodClient(owner,binding)}
    val jobs=rememberCoroutineScope()
    var room by remember(client.bindingId){mutableStateOf(Pocket.prefs.getString("hollywood-room:$owner:${client.bindingId}",client.defaultRoomId)?:client.defaultRoomId)}
    var browsing by remember(client.bindingId){mutableStateOf(false)}
    val catalog=remember(client){HollywoodRoomCatalog{client.call("rooms")}}
    val rooms=catalog.rooms
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var headRefreshRevision by remember(client.bindingId){mutableIntStateOf(0)}
    val feeds=remember(client.bindingId){mutableMapOf<String,HollywoodRoomFeed>()}
    val feed=feeds.getOrPut(room){HollywoodRoomFeed(room){path->client.call(path)}}
    val detail=feed.detail
    val events=feed.events
    val attention=feed.attention
    val work=remember(detail,feed.detailError,attention,feed.justAccepted){hollywoodRoomWork(detail,feed.detailError,attention,feed.justAccepted)}
    var stopping by remember(client.bindingId,room){mutableStateOf(emptySet<String>())}
    val older=feed.older
    var recipient by remember(client.bindingId,room){mutableStateOf("")}
    var direct by remember(client.bindingId,room){mutableStateOf(false)}
    var source by remember(client.bindingId,room){mutableStateOf<String?>(null)}
    var postOnly by remember(client.bindingId,room){mutableStateOf(false)}
    var error by remember(client.bindingId,room){mutableStateOf("")}
    var notice by remember(client.bindingId,room){mutableStateOf("")}
    var busy by remember(client.bindingId,room){mutableStateOf(false)}
    var expanded by remember(client.bindingId,room,direct,recipient){mutableStateOf(emptySet<String>())}
    var menu by remember{mutableStateOf(false)}
    var addressMenu by remember(client.bindingId,room){mutableStateOf(false)}
    var workflow by remember(client.bindingId,room){mutableStateOf(false)}
    var inspecting by remember{mutableStateOf<JSONObject?>(null)}
    var guidance by remember(client.bindingId,room){mutableStateOf<JSONObject?>(null)}
    var guidanceReceipt by remember(client.bindingId,room){mutableStateOf<JSONObject?>(null)}
    val destination=recipient.ifBlank{detail.optJSONObject("room")?.s("owner").orEmpty()}
    val scope=if(hollywoodUuid(destination))HollywoodScope(room,destination,if(direct)"direct" else "room",source,if(postOnly)"message" else "request")else null
    val currentScope by rememberUpdatedState(scope)
    val inputState=remember(scope){mutableStateOf(TextFieldValue(scope?.let(client::draft).orEmpty()))}
    var input by inputState
    val hasDraft by remember(inputState){derivedStateOf{inputState.value.text.isNotBlank()}}
    var pending by remember(scope){mutableStateOf(scope?.let(client::pending))}
    val list=rememberLazyListState()
    var following by remember(client.bindingId,room,direct,recipient){mutableStateOf(true)}
    val actors=detail.optJSONArray("actors")?.objects().orEmpty()
    val agentNames=actors.associate{it.s("id") to it.s("name")}
    val visible=remember(events,direct,destination,client.principalId){events.filter{!direct||it.s("audience")=="direct"&&setOf(it.s("author"),it.s("recipient"))==setOf(client.principalId,destination)}}
    val transcript by key(owner,client.bindingId,room){produceState(commandRunProjection(emptyList(),expanded),visible,expanded,agentNames,client.principalId){
        value=withContext(Dispatchers.Default){commandRunProjection(roomTranscriptRows(visible,"hollywood:$owner:${client.bindingId}:$room",client.principalId,agentNames),expanded)}
    }}
    LaunchedEffect(list){snapshotFlow{list.isScrollInProgress to list.canScrollForward}.collect{(scrolling,forward)->if(scrolling)following=!forward}}
    val latestContent=transcript.rows.lastOrNull()?.let{listOf(it.s("id"),it.s("text"),it.s("status"))}
    LaunchedEffect(latestContent,transcript.rows.size,room,browsing){if(!browsing&&following&&transcript.rows.isNotEmpty()){
        withFrameNanos{};if(list.layoutInfo.totalItemsCount>0)list.scrollToItem(list.layoutInfo.totalItemsCount-1)
    }}
    LaunchedEffect(list,browsing,room){if(!browsing)snapshotFlow{list.layoutInfo.totalItemsCount to list.layoutInfo.viewportEndOffset}.collect{(count,_)->if(following&&count>0)list.scrollToItem(count-1)}}
    suspend fun refresh(selected:String){
        if(room!=selected||feed.room!=selected)return
        coroutineScope{launch{feed.refreshHistory()};launch{feed.refreshDetail()};launch{feed.refreshAttention()}}
    }
    LaunchedEffect(feed,headRefreshRevision,PocketLive.connectionRevision,browsing,lifecycle){
        if(browsing)return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){
        // Each read lane publishes independently. A status/attention outage must
        // never hold back successfully fetched messages or their live cursor.
        coroutineScope{
            launch{feed.refreshHistory(preferTail=true);while(isActive){delay(3000);feed.refreshHistory()}}
            launch{while(isActive){
                snapshotFlow{feed.catchingUp}.first{it}
                repeat(4){if(feed.catchingUp)feed.fillGapPage()}
                delay(750)
            }}
            launch{while(isActive){feed.refreshDetail();delay(3000)}}
            launch{while(isActive){feed.refreshAttention();delay(5000)}}
        }
        }
    }
    LaunchedEffect(catalog,browsing,PocketLive.connectionRevision,lifecycle){
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(isActive){catalog.refresh();delay(15000)}}
    }
    LaunchedEffect(feed,list){
        snapshotFlow{Triple(list.isScrollInProgress,list.firstVisibleItemIndex,following)}.collect{(scrolling,index,follow)->
            if(scrolling&&!follow&&index<3&&!feed.loading&&!feed.loadingOlder&&feed.older!=null)feed.loadOlder()
        }
    }
    fun choose(value:String){following=true;room=value;recipient="";direct=false;source=null;browsing=false;Pocket.prefs.edit().putString("hollywood-room:$owner:${client.bindingId}",value).apply()}
    fun send(inspect:Boolean=false){
        val captured=scope?:return;if(busy)return
        val intent=pending?:runCatching{client.capture(captured,input.text)}.getOrElse{error=it.message.orEmpty();return}
        pending=intent;busy=true
        jobs.launch{
            var accepted=false
            try{
                val receipt=if(inspect)client.inspect(captured,intent)else client.submit(captured,intent)
                if(!inspect)feed.accepted(receipt)
                accepted=true
                if(currentScope==captured){pending=null;if(input.text==intent.getJSONObject("body").s("body")){input=TextFieldValue();client.saveDraft(captured,"")};notice="Accepted by Hollywood";error=""}
            }catch(e:CancellationException){throw e}
            catch(e:HollywoodAcceptedReceiptNotSaved){if(!inspect)feed.accepted(e.receipt);if(currentScope==captured){notice="Accepted by Hollywood";error=e.message.orEmpty()}}
            catch(e:Exception){if(currentScope==captured)error="Delivery remains saved. ${e.message.orEmpty()}"}
            finally{if(currentScope==captured)busy=false}
            // A validated send receipt is final for transport, even if the next
            // history/status read fails. Never relabel it unknown or submit again.
            if(accepted&&currentScope==captured)refresh(captured.room)
        }
    }
    fun stop(target:JSONObject,inspect:Boolean=false){
        val selected=room;val id=target.s("id");if(id in stopping)return
        stopping=stopping+id
        jobs.launch{
            var confirmed=false
            try{
                val result=client.stopDelivery(selected,target,inspect)
                if(room==selected){notice=when(result.s("stopConfirmation")){"cancelled"->"Stopped";"finished"->"Work finished before stop was confirmed";else->"Stop requested"};error=""}
                confirmed=true
            }catch(e:CancellationException){throw e}
            catch(e:HollywoodStopReceiptNotSaved){if(room==selected){notice=if(e.confirmation=="cancelled")"Stopped" else if(e.confirmation=="finished")"Work finished before stop was confirmed" else "Stop requested";error=e.message.orEmpty()}}
            catch(e:Exception){if(room==selected)error="Stop remains unconfirmed. Check the original delivery. "+e.message.orEmpty()}
            finally{if(room==selected)stopping=stopping-id}
            if(confirmed&&room==selected)refresh(selected)
        }
    }
    val requested=HollywoodNotifications.target
    LaunchedEffect(client.bindingId,requested){
        requested?.takeIf{it.owner==owner&&it.binding==client.bindingId}?.let{target->
            choose(target.room)
            target.reply?.let{reply->recipient=reply.recipient;direct=reply.audience=="direct";source=reply.sourceEvent;postOnly=reply.kind=="message"}
            inspecting=target.attention
            headRefreshRevision++
            HollywoodNotifications.consumed(target)
        }
    }
    if(!browsing)ConversationBack(hideKeyboard=!menu&&!addressMenu&&guidance==null&&guidanceReceipt==null&&inspecting==null&&!workflow){
        when{
            addressMenu->addressMenu=false
            menu->menu=false
            guidance!=null->guidance=null
            guidanceReceipt!=null->guidanceReceipt=null
            inspecting!=null->inspecting=null
            workflow->workflow=false
            else->browsing=true
        }
    }
    if(browsing){
        Column(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}.imePadding()){
            if(catalog.loading&&rooms.isEmpty())LinearProgressIndicator(Modifier.fillMaxWidth().testTag("native-room-catalog-loading"))
            if(!catalog.available&&!catalog.loading){
                BilingualLabel("Rooms could not refresh",Modifier.padding(horizontal=16.dp),color=Coral)
                TextButton({jobs.launch{catalog.refresh()}},Modifier.testTag("native-room-catalog-retry")){BilingualLabel("Retry")}
            }
            if(catalog.available&&rooms.isEmpty())BilingualLabel("No rooms available",Modifier.padding(16.dp),color=Muted)
            WorkCatalogList(Modifier.weight(1f),reverseLayout=true){
                val ordered=rooms.sortedWith(compareBy<JSONObject>{it.s("id")!=client.defaultRoomId}.thenBy{it.s("name").lowercase()})
                items(ordered,key={it.s("id")}){item->
                    HollywoodRoomCard(item,catalog.available){choose(item.s("id"))}
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton({browsing=false},Modifier.weight(1f)){BilingualLabel("Back")};Box(Modifier.weight(2f)){ProfileSwitcher{}};TextButton(onSettings,Modifier.weight(1f)){BilingualLabel("Settings")}}
        };return
    }
    Column(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}.imePadding().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Box(Modifier.weight(1f)){
            TextButton({addressMenu=true},Modifier.fillMaxWidth().testTag("native-room-address")){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    Text(detail.optJSONObject("room")?.s("name").orEmpty(),Modifier.weight(1f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    BilingualLabel(if(direct)"Private" else "Room")
                    BilingualLabel(if(postOnly)"Message" else "Request")
                    Text("→")
                    if(agentNames[destination]!=null)Text(agentNames.getValue(destination),Modifier.weight(1f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    else BilingualLabel("Choose recipient")
                }
            }
            DropdownMenu(addressMenu,{addressMenu=false},modifier=Modifier.width(300.dp).heightIn(max=420.dp).semantics{testTagsAsResourceId=true},
                properties=androidx.compose.ui.window.PopupProperties(focusable=false)){
            Row{TextButton({recipient="";source=null;direct=false;postOnly=false}){BilingualLabel("Room default")};Switch(direct,{direct=it;source=null},modifier=Modifier.testTag("native-room-private"));BilingualLabel("Private")}
            actors.filter{it.s("id")!=client.principalId}.forEach{actor->TextButton({recipient=actor.s("id");source=null;postOnly=actor.s("kind")=="human";addressMenu=false},Modifier.testTag("native-recipient:"+actor.s("id"))){Text(actor.s("name"))}}
            Row{Checkbox(postOnly,{postOnly=it},Modifier.testTag("native-post-only"));BilingualLabel("Post without requesting work")}
            if(source!=null)TextButton({source=null}){BilingualLabel("Clear reply target")}
            scope?.let{current->client.savedScopes(room).filter{it!=current}.forEach{saved->TextButton({recipient=saved.recipient;direct=saved.audience=="direct";source=saved.sourceEvent;postOnly=saved.kind=="message";addressMenu=false}){BilingualLabel("Saved message",centered=false);Text(" · "+(agentNames[saved.recipient]?:saved.recipient))}}}
            }
            }
        }
        if(error.isNotBlank())Text(error,color=Coral)
        if(feed.historyError.isNotBlank())Text("Messages need refreshing · "+feed.historyError,color=Coral,modifier=Modifier.testTag("native-room-read-error"))
        if(feed.loading&&events.isEmpty())LinearProgressIndicator(Modifier.fillMaxWidth().testTag("native-room-loading"))
        if(notice.isNotBlank())BilingualLabel(notice,centered=false,color=Muted)
        if(feed.catchingUp)Text("Loading missed messages…",color=Muted,modifier=Modifier.testTag("native-room-gap"))
        LazyColumn(Modifier.weight(1f).testTag("native-room-history"),state=list,verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(older!=null)item(key="room-history-edge"){
                if(feed.loadingOlder)Text("Loading earlier messages…",color=Muted)
                else TextButton({following=false;jobs.launch{feed.loadOlder()}}){BilingualLabel("Earlier messages")}
            }
            itemsIndexed(transcript.rows,key={_,row->row.s("id")}){index,row->
                Column(verticalArrangement=Arrangement.spacedBy(7.dp)){
                    if(roomAuthorStartsBlock(transcript.rows,index))Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        if(row.s("roomAuthor")=="You")BilingualLabel("You",color=Mint)else Text(row.s("roomAuthor"),color=Mint,style=MaterialTheme.typography.labelMedium)
                        if(row.s("roomAudience")=="direct"){
                            BilingualLabel("Private",Modifier.testTag("native-room-private-context"),color=Muted)
                            if(row.s("roomRecipient")==client.principalId)BilingualLabel("To you",color=Muted)
                            else agentNames[row.s("roomRecipient")]?.let{Text("→ "+it,color=Muted,style=MaterialTheme.typography.labelMedium)}
                        }
                    }
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
        if(pending!=null)TextButton({send(true)},enabled=!busy){BilingualLabel("Check original delivery")}
        ConversationComposerRow{
            RoomDraftComposerScope{
            ConversationDraftField(inputState.value,{inputState.value=it;scope?.let{target->client.saveDraft(target,it.text)}},Modifier.weight(1f),enabled=!busy,
                onSend={if(!busy&&pending==null&&inputState.value.text.isNotBlank()&&detail.optJSONObject("permissions")?.optBoolean(if(postOnly)"can_post" else "can_request")==true)send()},placeholder={BilingualLabel("Message…",centered=false)})
            }
            ConversationSendButton(hasDraft,scope!=null&&!busy&&pending==null&&hasDraft&&
                detail.optJSONObject("permissions")?.optBoolean(if(postOnly)"can_post" else "can_request")==true,busy,"Send message"){send()}
        }
        if(binding.optBoolean("voice")&&scope!=null&&!postOnly&&detail.optJSONObject("permissions")?.optBoolean("can_request")==true){
            val voiceDestination=JSONObject().put("backend","lax").put("bindingId",client.bindingId).put("authorityId",client.authorityId)
                .put("roomId",room).put("recipientId",scope.recipient).put("audience",scope.audience)
                .apply{scope.sourceEvent?.let{put("sourceEventId",it)}}
            VoiceLaunchButton(modifier=Modifier.fillMaxWidth(),bar=true,roomDestination=voiceDestination)
        }
        FlowRow(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.SpaceEvenly){
        InlineSpeechVolume(Modifier.width(inlineVolumeControlWidth()))
        ConversationActionsMenu(menu,{menu=it},modifier=Modifier.testTag("native-room-controls"),statusIcon=work.icon,statusLabel=work.label,statusSpinning=work.spinning,bottomActions=true){
            val account=Pocket.weeklyUsage
            if(account.remainingPercent!=null)Text(account.remainingLabel(),color=Muted)
            account.credits.compact()?.let{Text(it,color=Muted)}
            if(account.remainingPercent==null&&account.credits.compact()==null)BilingualLabel("Account usage unavailable",centered=false,color=Muted)
            PocketUpdateControl()
            TextButton({menu=false;browsing=true},Modifier.testTag("native-room-catalog")){BilingualLabel("Rooms")}
            TextButton({menu=false;onSettings()}){BilingualLabel("Settings")}
            ProfileSwitcher{menu=false}
            val context=androidx.compose.ui.platform.LocalContext.current
            TextButton({menu=false;context.nextCompActivity()?.enterBlackout()}){BilingualLabel("Blackout")}
            client.savedGuidanceScopes(room).forEach{saved->
                TextButton({guidance=saved;menu=false},modifier=Modifier.testTag("native-guidance-saved:"+saved.s("deliveryId")+":"+saved.s("nativeTurn"))){Text("Saved guidance · "+(client.guidancePending(saved)?.s("state")?:"draft"))}
            }
            TextButton({menu=false;workflow=true},Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("native-workflow")){BilingualLabel("Goals & work")}
            RoomWorkControls(work,agentNames,client,room,stopping,feed.detailError,feed.attentionError,
                onAttention={menu=false;inspecting=it},onGuidance={menu=false;guidance=it},onStop={target,inspect->stop(target,inspect)})
        }
        if(!following||list.canScrollForward)TextButton({following=true;jobs.launch{feed.refreshHistory(preferTail=true);withFrameNanos{};if(list.layoutInfo.totalItemsCount>0)list.scrollToItem(list.layoutInfo.totalItemsCount-1)}},Modifier.heightIn(min=48.dp).testTag("native-room-latest")){BilingualLabel("Latest")}
        ReaderPagingButtons(list,hasEarlier=feed.older!=null,onPrepare={up->
            following=false
            if(up&&!list.canScrollBackward)feed.loadOlder()
        },onSettled={up,done->if(done&&!up&&!list.canScrollForward)following=true},onError={error=it.message.orEmpty()})
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

@Composable private fun RoomDraftComposerScope(content:@Composable ()->Unit){content()}

internal fun roomDeliveryLabel(delivery:JSONObject):String {
    val state=when(delivery.s("state")){
        "pending"->"Queued"
        "claimed"->"Starting"
        "running"->if(delivery.optBoolean("cancellation_requested"))"Stop requested" else "Running"
        "cancel_requested"->"Stop requested"
        "blocked"->"Blocked"
        "unknown"->"Outcome unknown"
        "completed"->"Execution completed"
        "failed"->"Failed"
        "cancelled"->"Stopped"
        else->"Status unavailable"
    }
    val reason=delivery.s("reason",delivery.s("error")).take(180)
    return state+if(reason.isNotBlank())" · $reason" else ""
}
