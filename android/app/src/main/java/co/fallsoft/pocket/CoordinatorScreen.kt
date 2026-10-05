package co.fallsoft.pocket

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
    LaunchedEffect(Pocket.local,Pocket.token){PocketCoordinator.loadHistory()}
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
    var draft by remember(Pocket.local,Pocket.token){mutableStateOf(Pocket.prefs.getString(draftKey,"").orEmpty())}
    fun updateDraft(value:String){draft=value;Pocket.prefs.edit().putString(draftKey,value).apply()}
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=remember{FocusRequester()}
    val scroll=rememberLazyListState()
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
            itemsIndexed(PocketCoordinator.messages,key={index,_->"coordinator-turn-$index"}){_,turn->Column{VoiceChatMessage("You",turn.first,true);Spacer(Modifier.height(12.dp));VoiceChatMessage("Codex",turn.second,false)}}
            if(PocketCoordinator.pendingText.isNotBlank()&&!PocketCoordinator.pendingInHistory)item{VoiceChatMessage("You",PocketCoordinator.pendingText,true)}
            if(PocketCoordinator.problem.isNotBlank())item{ImmersionText("coordinator:problem",PocketCoordinator.problem,color=Coral);TextButton({PocketCoordinator.retry()}){BilingualLabel("Retry saved turn")}}
            item(key="coordinator-end"){Spacer(Modifier.height(1.dp))}
        }
        val contextHost="context-coordinator:${Pocket.local}:${Pocket.base}:${Pocket.token.hashCode()}"
        val contextEntries=mutableListOf<ConversationContextEntry>()
        PocketCoordinator.messages.lastOrNull()?.second?.let{contextEntries.add(ConversationContextEntry("coordinator-reply",it,"Coordinator reply","coordinator"))}
        val displayedOwner=PocketSpeech.displayedOwner
        if(displayedOwner?.startsWith("$contextHost:")==true&&PocketSpeech.displayedRunning)contextEntries.add(ConversationContextEntry("manual:$displayedOwner",PocketSpeech.displayedText,PocketSpeech.displayedTitle,"coordinator",owner=displayedOwner))
        if(displayedOwner==null)PocketSpeech.queue.current?.let{speech->contextEntries.add(ConversationContextEntry("caption:${speech.id}",speech.text,speech.title,PocketNotificationTitles.threadForId(speech.id),captionId=speech.id))}
        ConversationContextHost(contextEntries,contextHost,sourceVisible={entry->entry.id=="coordinator-reply"&&scroll.layoutInfo.visibleItemsInfo.any{it.key=="coordinator-turn-${PocketCoordinator.messages.lastIndex}"&&it.offset>=scroll.layoutInfo.viewportStartOffset&&it.offset+it.size<=scroll.layoutInfo.viewportEndOffset}},onGoTo={entry->entry.thread?.takeIf{it!="coordinator"}?.let{PocketCoordinator.close();Pocket.open(it)}})
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(typing)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                VoiceLaunchButton(modifier=Modifier.size(56.dp),compact=true)
                OutlinedTextField(draft,{value->
                    val enter=value.length==draft.length+1&&value.contains('\n')&&value.replace("\n","")==draft
                    updateDraft(value.replace('\n',' '));if(enter)send()
                },placeholder={Text(PocketImmersion.label("Message coordinator"))},singleLine=true,modifier=Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent{event->
                    if(event.key==Key.Enter||event.key==Key.NumPadEnter){if(event.type==KeyEventType.KeyDown)send();true}else false
                },keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}))
                FilledTonalIconButton({send()},enabled=draft.isNotBlank()&&!sending,modifier=Modifier.size(64.dp)){SymbolIcon(Icons.Rounded.Send,PocketImmersion.label("Send coordinator message"),Modifier.size(40.dp),tint=Paper)}
            }else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                VoiceLaunchButton(modifier=Modifier.weight(1f),bar=true)
                ChatActionButton("Keyboard",Icons.Rounded.Keyboard,Modifier.weight(1f),{PocketCoordinator.keyboard(true)})
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                ChatActionButton("Back",Icons.Rounded.ArrowBack,Modifier.weight(1f),{back()})
                UsageDock(Modifier.width(usageDockWidth()),conversationOnly=true)
            }
        }
    }
}
