package co.fallsoft.pocket

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.*

@Composable fun CoordinatorCard(){
    val shape=RoundedCornerShape(8.dp)
    val height=with(androidx.compose.ui.platform.LocalDensity.current){(if(PocketImmersion.enabled&&PocketImmersion.supportEnabled)84.sp else 64.sp).toDp()+32.dp}.coerceAtLeast(96.dp)
    LaunchedEffect(Pocket.environmentId,Pocket.token){PocketCoordinator.loadHistory()}
    LaunchedEffect(PocketCoordinator.preview,PocketImmersion.enabled){PocketImmersion.offer("coordinator:preview",PocketCoordinator.preview,"coordinator message")}
    Box(Modifier.fillMaxWidth().height(height).clip(shape).background(Panel).border(1.dp,Mint.copy(alpha=.55f),shape)){
        Row(Modifier.matchParentSize()){
            VoiceLaunchButton(modifier=Modifier.weight(.22f).fillMaxHeight(),cardRegion=true)
            CardInteractionRegion(PocketImmersion.label("Coordinator chat"),Modifier.weight(.56f).fillMaxHeight()){PocketCoordinator.open()}
            CardInteractionRegion(PocketImmersion.label("Coordinator keyboard"),Modifier.weight(.22f).fillMaxHeight()){PocketCoordinator.open(keyboard=true)}
        }
        Column(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                SymbolIcon("Codex",PocketImmersion.label("Coordinator"),Modifier.size(24.dp),spinning=PocketCoordinator.state in listOf("Sending","Thinking"))
                BilingualLabel("Coordinator",fontSize=16.sp,fontWeight=FontWeight.Medium,color=Mint,centered=false)
            }
            MarkdownPreview(PocketImmersion.display("coordinator:preview",PocketCoordinator.preview).ifBlank{PocketImmersion.label("Text or talk to coordinate your sessions.")},fontSize=13.sp,lineHeight=19.sp,color=Paper,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}

@Composable fun CoordinatorScreen(){
    val draftKey=Pocket.key("coordinatorDraft")
    var draft by remember(Pocket.environmentId,Pocket.token){mutableStateOf(Pocket.prefs.getString(draftKey,"").orEmpty())}
    fun updateDraft(value:String){draft=value;Pocket.prefs.edit().putString(draftKey,value).apply()}
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=remember{FocusRequester()}
    val scroll=rememberLazyListState()
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val coverage=remember(Pocket.environmentId,Pocket.token){CoordinatorReadingCoverage()}
    LaunchedEffect(scroll,lifecycle,coverage){lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
        snapshotFlow{if(BlackoutVisibility.active)emptyList()else scroll.layoutInfo.let{layout->layout.visibleItemsInfo.filter{item->item.key.toString().startsWith("coordinator-assistant:")}.map{item->CoordinatorItemExposure(item.key.toString().substringAfter(":"),item.offset,item.size,layout.viewportStartOffset,layout.viewportEndOffset)}}}.collect{items->if(PocketVoice.foreground)items.forEach{item->if(coverage.expose(item.id,item.offset,item.size,item.start,item.end))PocketCoordinator.presented(item.id)}}
    }}
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    var followLatest by remember{mutableStateOf(true)}
    val typing=PocketCoordinator.keyboardRequested
    val sending=PocketCoordinator.state in listOf("Sending","Thinking")
    fun back(){keyboard?.hide();PocketCoordinator.close();Pocket.closeTask()}
    fun send(){if(draft.isNotBlank()&&!sending){if(PocketCoordinator.send(draft.trim())){followLatest=true;updateDraft("")}}}
    ConversationBack{back()}
    LaunchedEffect(Unit){PocketCoordinator.loadHistory()}
    LaunchedEffect(typing){if(typing){focus.requestFocus();keyboard?.show()}}
    LaunchedEffect(dragged){
        if(dragged)followLatest=false
        else if(scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.key=="coordinator-end")followLatest=true
    }
    LaunchedEffect(scroll){
        snapshotFlow {
            val layout=scroll.layoutInfo
            if(followLatest&&!dragged&&!scroll.isScrollInProgress&&layout.totalItemsCount>0&&
                layout.visibleItemsInfo.lastOrNull()?.key!="coordinator-end")layout.totalItemsCount-1 else null
        }.filterNotNull().collect{scroll.scrollToItem(it)}
    }
    Column(Modifier.fillMaxSize().imePadding()){
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            SymbolIcon("Codex",PocketImmersion.label("Coordinator"),Modifier.size(28.dp),spinning=sending)
            BilingualLabel("Coordinator",Modifier.weight(1f),color=Paper,fontSize=16.sp,fontWeight=FontWeight.Medium,centered=false)
            if(sending)BilingualLabel(PocketCoordinator.state,color=Mint,fontSize=12.sp)
        }
        LazyColumn(state=scroll,modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            if(PocketCoordinator.historyEarlier)item{TextButton({followLatest=false;PocketCoordinator.olderHistory()},enabled=!PocketCoordinator.historyLoading){BilingualLabel("Load earlier messages")}}
            if(PocketCoordinator.historyLoading)item{BilingualLabel("Loading history…",color=Muted)}
            if(PocketCoordinator.historyProblem.isNotBlank())item{ImmersionText("coordinator:history-problem",PocketCoordinator.historyProblem,color=Coral);TextButton({PocketCoordinator.loadHistory()}){BilingualLabel("Retry history")}}
            PocketCoordinator.messages.forEachIndexed{index,turn->
                val id=turn.id
                item(key="coordinator-user:$id"){VoiceChatMessage("You",turn.first,true)}
                item(key="coordinator-assistant:$id"){
                    Column{
                        VoiceChatMessage("Codex",turn.second,false)
                        PocketCoordinator.routes[id].orEmpty().forEach{route->CoordinatorRouteReceipt(route,{keyboard?.hide();PocketCoordinator.close();Pocket.open(route.threadId)},{PocketCoordinator.correctionOf=route;updateDraft(route.correction+draft);PocketCoordinator.keyboard(true)})}
                    }
                }
            }
            if(PocketCoordinator.pendingText.isNotBlank()&&!PocketCoordinator.pendingInHistory)item{VoiceChatMessage("You",PocketCoordinator.pendingText,true)}
            if(PocketCoordinator.problem.isNotBlank())item{ImmersionText("coordinator:problem",PocketCoordinator.problem,color=Coral);TextButton({PocketCoordinator.retry()}){BilingualLabel("Retry saved turn")}}
            item(key="coordinator-end"){Spacer(Modifier.height(1.dp))}
        }
        CoordinatorSpeechControls()
        InlineCaptureStatus()
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(typing)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                VoiceLaunchButton(modifier=Modifier.size(56.dp),compact=true)
                OutlinedTextField(draft,{value->
                    val enter=value.length==draft.length+1&&value.contains('\n')&&value.replace("\n","")==draft
                    updateDraft(value.replace('\n',' '));if(enter)send()
                },placeholder={BilingualLabel("Message coordinator",centered=false)},singleLine=true,modifier=Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent{event->
                    if(event.key==Key.Enter||event.key==Key.NumPadEnter){if(event.type==KeyEventType.KeyDown)send();true}else false
                },keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}))
                FilledTonalIconButton({send()},enabled=draft.isNotBlank()&&!sending,modifier=Modifier.size(64.dp)){SymbolIcon(Icons.Rounded.Send,PocketImmersion.label("Send coordinator message"),Modifier.size(40.dp),tint=Paper)}
            }else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                VoiceLaunchButton(modifier=Modifier.weight(1f),bar=true)
                ChatActionButton("Keyboard",Icons.Rounded.Keyboard,Modifier.weight(1f),{PocketCoordinator.keyboard(true)})
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                IconButton({back()},modifier=Modifier.size(48.dp)){SymbolIcon("ArrowBack","Back",Modifier.size(32.dp))}
                InlineSpeechVolume(Modifier.weight(1f).widthIn(min=112.dp))
                UsageDock(Modifier.width(usageDockWidth()),conversationOnly=true)
            }
        }
    }
}

/** Playback controls never repeat a coordinator reply or saved notification passage. */
@Composable internal fun CoordinatorSpeechControls(){
    if(PocketVoice.captureFeedbackVisible)return
    val owner="speech-coordinator:${Pocket.local}:${Pocket.base}:${Pocket.token.hashCode()}"
    DisposableEffect(owner){onDispose{if(PocketSpeech.displayedOwner==owner)PocketSpeech.stopDisplayed(owner)}}
    val queued=PocketSpeech.queue.current
    val speechThread=queued?.threadId
    val caption=coordinatorLiveSpeechCaption(owner,PocketSpeech.displayedOwner,queued?.id,PocketSpeech.paused,PocketSpeechCaptions.state)
    val readingHere=PocketSpeech.displayedOwner==owner
    val lastReply=PocketCoordinator.messages.lastOrNull()?.second.orEmpty()
    if(PocketSpeech.count>0||lastReply.isNotBlank())Column(Modifier.fillMaxWidth()){
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.End){
            if(PocketSpeech.count>0)Box(Modifier.weight(1f)){SpeechSourceLabel()}
            if(PocketSpeech.displayedOwner==null&&PocketSpeech.count>0){
                speechThread?.takeIf{it!="coordinator"}?.let{thread->
                    IconButton({PocketCoordinator.close();Pocket.open(thread)}){SymbolIcon(Icons.Rounded.OpenInNew,PocketImmersion.label("Open source conversation"),Modifier.size(28.dp))}
                }
                SpeechPlaybackControls(iconOnly=true)
            }else if(readingHere){
                SpeechPlaybackControls(iconOnly=true)
            }else IconButton({PocketSpeech.speakDisplayed(owner,PocketImmersion.display("voice:"+lastReply.hashCode()+":false",lastReply),"Coordinator")},enabled=lastReply.isNotBlank()){
                SymbolIcon(Icons.Rounded.VolumeUp,PocketImmersion.label("Speak latest coordinator reply"),Modifier.size(30.dp),tint=Mint)
            }
        }
        if(caption.isNotBlank())Text(caption,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=2.dp),color=Muted,fontSize=14.sp,lineHeight=20.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        if(readingHere&&PocketSpeech.displayedProblem.isNotBlank())Text(PocketSpeech.displayedProblem,modifier=Modifier.padding(horizontal=16.dp),color=Coral,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

/** Coordinator history has turn IDs, not a notification thread ID; manual reader ownership is explicit. */
internal fun coordinatorLiveSpeechCaption(owner:String,displayedOwner:String?,speechId:Long?,paused:Boolean,caption:SpeechCaptionState):String {
    if(owner.isBlank()||displayedOwner!=owner)return ""
    return conversationLiveSpeechCaption(owner,owner,speechId,paused,null,caption)
}
