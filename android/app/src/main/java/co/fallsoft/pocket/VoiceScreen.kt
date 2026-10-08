package co.fallsoft.pocket

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable fun VoiceLaunchButton(modifier:Modifier=Modifier,threadId:String?=null,compact:Boolean=false,bar:Boolean=false,dock:Boolean=false,cardRegion:Boolean=false){
    val c=LocalContext.current
    val here=compact||bar||dock
    val control=captureControl(PocketVoice.active||PocketVoice.launching,PocketVoice.state,Pocket.prefs.getString("voicePendingId",null)!=null)
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ok->if(ok)PocketVoice.start(c,threadId,inPlace=here,checkSaved=control==CaptureControl.CHECK_SAVED)else PocketVoice.captureFailure("Allow microphone access to record. Your draft is kept.",here)}
    val label=when(control){CaptureControl.START->"Start recording";CaptureControl.STOP_SEND->"Stop and send recording";CaptureControl.PROCESSING->PocketVoice.state;CaptureControl.CHECK_SAVED->"Check saved turn"}
    val capture:()->Unit={
        if(control==CaptureControl.CHECK_SAVED&&PocketVoice.active)PocketVoice.retry()
        else if(ContextCompat.checkSelfPermission(c,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)PocketVoice.start(c,threadId,inPlace=here,checkSaved=control==CaptureControl.CHECK_SAVED)
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    if(cardRegion)CardInteractionRegion(PocketImmersion.label("Mic"),modifier,capture)
    else if(dock)Column(modifier.height(usageDockHeight()),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
        CaptureMic(capture,control,label,compact=true)
    }
    else Row(modifier,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        CaptureMic(capture,control,label,compact)
        if(!compact)Column(Modifier.weight(1f)){
            BilingualLabel(label,centered=false,color=Mint,fontSize=14.sp,maxLines=2)
        }
    }
}
@Composable private fun CaptureMic(capture:()->Unit,control:CaptureControl,label:String,compact:Boolean){
    FilledTonalIconButton(capture,enabled=control!=CaptureControl.PROCESSING&&!PocketVoice.launching,modifier=Modifier.size(if(compact)64.dp else 72.dp),shape=CircleShape,colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=if(control==CaptureControl.STOP_SEND)Coral.copy(alpha=.22f) else Mint.copy(alpha=.16f),contentColor=Paper)){
        Column(horizontalAlignment=Alignment.CenterHorizontally){
            SymbolIcon(if(control==CaptureControl.PROCESSING)"Codex" else if(control==CaptureControl.STOP_SEND)"Stop" else if(control==CaptureControl.CHECK_SAVED)"Inbox" else "Mic",PocketImmersion.label(if(PocketVoice.problem.isNotBlank())PocketVoice.problem else label),Modifier.size(if(compact)32.dp else 44.dp),tint=if(PocketVoice.problem.isNotBlank())Coral else Paper,spinning=control==CaptureControl.PROCESSING)
            if(compact)BilingualLabel(when{PocketVoice.problem.isNotBlank()->"Retry";control==CaptureControl.STOP_SEND->"Send";control==CaptureControl.PROCESSING->"Wait";control==CaptureControl.CHECK_SAVED->"Check";else->"Talk"},fontSize=12.sp,color=if(PocketVoice.problem.isNotBlank())Coral else Paper)
        }
    }
}
@Composable fun VoiceScreen(){
    val c=LocalContext.current
    val draftKey=Pocket.key("voiceDraft:"+(PocketVoice.targetThread?:"coordinator"))
    var draft by remember(draftKey){mutableStateOf(Pocket.prefs.getString(draftKey,"").orEmpty())}
    LaunchedEffect(draft,draftKey){Pocket.prefs.edit().putString(draftKey,draft).apply()}
    fun sendDraft(value:String=draft){draft=voiceDraftAfterSubmit(value,PocketVoice.sendText(value.replace('\n',' ').trim()))}
    var keyboardInput by remember{mutableStateOf(false)}
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val inputFocus=remember{androidx.compose.ui.focus.FocusRequester()}
    LaunchedEffect(keyboardInput){if(keyboardInput){inputFocus.requestFocus();keyboard?.show()}}
    val scroll=androidx.compose.foundation.lazy.rememberLazyListState()
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val coverage=remember(Pocket.local,Pocket.token){CoordinatorReadingCoverage()}
    LaunchedEffect(scroll,lifecycle,coverage){lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
        snapshotFlow{if(BlackoutVisibility.active)emptyList()else scroll.layoutInfo.let{layout->layout.visibleItemsInfo.filter{item->item.key.toString().startsWith("assistant:")}.map{item->CoordinatorItemExposure(item.key.toString().substringAfter(":"),item.offset,item.size,layout.viewportStartOffset,layout.viewportEndOffset)}}}
            .collect{items->if(PocketVoice.foreground)items.forEach{item->if(coverage.expose(item.id,item.offset,item.size,item.start,item.end))PocketVoice.presented(item.id)}}
    }}
    ConversationBack{PocketVoice.stop();Pocket.closeTask()}
    LaunchedEffect(PocketVoice.messages.size,PocketVoice.heard,PocketVoice.response){if(scroll.layoutInfo.totalItemsCount>0)scroll.animateScrollToItem(scroll.layoutInfo.totalItemsCount-1)}
    Column(Modifier.fillMaxSize().imePadding()){
        HorizontalDivider()
        androidx.compose.foundation.lazy.LazyColumn(state=scroll,modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            if(PocketVoice.historyEarlier)item{TextButton({PocketVoice.olderHistory()},enabled=!PocketVoice.historyLoading){BilingualLabel("Load earlier messages")}}
            if(PocketVoice.historyLoading)item{BilingualLabel("Loading history…",color=Muted)}
            if(PocketVoice.historyProblem.isNotBlank())item{ImmersionText("voice:history-problem",PocketVoice.historyProblem,color=Coral);TextButton({PocketVoice.service?.loadHistory()}){BilingualLabel("Retry history")}}
            PocketVoice.messages.forEach{turn->
                item(key="user:${turn.id}"){VoiceChatMessage("You",turn.first,true)}
                item(key="assistant:${turn.id}"){VoiceChatMessage("Codex",turn.second,false);PocketVoice.routes[turn.id].orEmpty().forEach{route->CoordinatorRouteReceipt(route,{keyboard?.hide();PocketVoice.stop();Pocket.open(route.threadId)},{PocketVoice.correctionOf=route;draft=route.correction+draft;keyboardInput=true})}}
            }
            if(PocketVoice.heard.isNotBlank()&&PocketVoice.messages.lastOrNull()?.first!=PocketVoice.heard)item{VoiceChatMessage("You",PocketVoice.heard,true)}
        }
        if(!PocketVoice.captureFeedbackVisible){SpeechCaptionBanner();SpeechPlayer()}
        InlineCaptureStatus(always=true)
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(!keyboardInput)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                VoiceLaunchButton(Modifier.weight(1f),threadId=PocketVoice.targetThread,bar=true)
                ChatActionButton("Keyboard",Icons.Rounded.Keyboard,Modifier.weight(1f),{keyboardInput=!keyboardInput;if(!keyboardInput)keyboard?.hide()})
            }
            if(keyboardInput)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                VoiceLaunchButton(Modifier.size(64.dp),threadId=PocketVoice.targetThread,compact=true)
                OutlinedTextField(value=draft,onValueChange={value->if(value.contains('\n'))sendDraft(value)else draft=value},placeholder={BilingualLabel("Message coordinator",centered=false)},modifier=Modifier.weight(1f).focusRequester(inputFocus).onPreviewKeyEvent{event->if(event.key==Key.Enter||event.key==Key.NumPadEnter){if(event.type==KeyEventType.KeyDown)sendDraft();true}else false},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Send),keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSend={sendDraft()}))
                IconButton({sendDraft()},enabled=draft.isNotBlank(),modifier=Modifier.size(64.dp)){SymbolIcon(Icons.Rounded.Send,PocketImmersion.label("Send message"))}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                IconButton({PocketVoice.stop();Pocket.closeTask()},modifier=Modifier.size(48.dp)){SymbolIcon("ArrowBack","Back",Modifier.size(32.dp))}
                InlineSpeechVolume(Modifier.weight(1f).widthIn(min=64.dp))
                UsageDock(Modifier.width(usageDockWidth()))
                IconButton({PocketVoice.playback()},modifier=Modifier.size(48.dp)){SymbolIcon(if(PocketVoice.state=="Speaking")"Pause" else "PlayArrow",if(PocketVoice.state=="Speaking")"Pause" else "Replay",Modifier.size(32.dp))}
            }
        }
    }
}
@Composable internal fun VoiceChatMessage(speaker:String,text:String,user:Boolean){
    if(text.isBlank())return
    val sourceId="voice:"+text.hashCode()+":"+user
    LaunchedEffect(text,PocketImmersion.enabled){PocketImmersion.offer(sourceId,text,if(user)"user message" else "Codex response")}
    Column(Modifier.fillMaxWidth(),horizontalAlignment=if(user)Alignment.End else Alignment.Start){
        SymbolIcon(if(user)"User" else "Codex",PocketImmersion.label(if(user)"Your message" else "Codex message"),Modifier.size(20.dp))
        Surface(color=if(user)Mint.copy(alpha=0.12f) else MaterialTheme.colorScheme.surfaceVariant,shape=androidx.compose.foundation.shape.RoundedCornerShape(6.dp),modifier=Modifier.padding(top=5.dp)){Column(Modifier.padding(14.dp)){BilingualMessage(sourceId,text)}}
    }
}
/** Capture feedback belongs to the existing chat, including while its editor stays focused. */
@Composable internal fun InlineCaptureStatus(always:Boolean=false){
    if((!always&&!PocketVoice.inPlace&&PocketVoice.problem.isBlank())||!PocketVoice.captureFeedbackVisible)return
    var elapsed by remember{mutableLongStateOf(0)}
    LaunchedEffect(PocketVoice.state,PocketVoice.recordingStartedAt){while(PocketVoice.state=="Listening"){elapsed=(android.os.SystemClock.elapsedRealtime()-PocketVoice.recordingStartedAt)/1000;kotlinx.coroutines.delay(1000)}}
    val target=if(PocketVoice.targetThread==null)"Coordinator" else Pocket.tasks.firstOrNull{it.id==PocketVoice.targetThread}?.title?:"Conversation"
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        WorkflowText(if(PocketVoice.problem.isNotBlank())PocketVoice.problem else "${if(PocketVoice.state=="Listening")"Recording %02d:%02d".format(elapsed/60,elapsed%60) else PocketVoice.state} · $target",Modifier.weight(1f),color=if(PocketVoice.problem.isNotBlank())Coral else Mint,fontSize=14.sp,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if(PocketVoice.state=="Listening")LinearProgressIndicator(progress={PocketVoice.captureLevel},modifier=Modifier.width(48.dp),color=Coral)
        TextButton({PocketVoice.stop()},modifier=Modifier.widthIn(min=64.dp).heightIn(min=48.dp)){BilingualLabel("End voice",fontSize=14.sp,maxLines=2)}
    }
}

/** Media volume stays visible in the toolbar and never steals editor focus. */
@Composable fun InlineSpeechVolume(modifier:Modifier=Modifier){
    val audio=LocalContext.current.getSystemService(android.media.AudioManager::class.java)
    val max=remember{audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}
    var volume by remember{mutableIntStateOf(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))}
    LaunchedEffect(audio){while(true){volume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);kotlinx.coroutines.delay(500)}}
    Slider(value=volume.toFloat(),onValueChange={volume=it.toInt();audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,volume,0)},valueRange=0f..max.toFloat(),steps=(max-1).coerceAtLeast(0),modifier=modifier.heightIn(min=48.dp).semantics{contentDescription="Speech volume"})
}

@Composable fun ChatActionButton(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier=Modifier,onClick:()->Unit,recording:Boolean=false){
    val density=androidx.compose.ui.platform.LocalDensity.current
    val large=density.fontScale>1.4f
    val height=if(large)with(density){24.sp.toDp()+52.dp}else 72.dp
    Surface(onClick=onClick,modifier=modifier.height(height),shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,if(recording)Coral else Line),color=if(recording)androidx.compose.ui.graphics.Color(0xff451e2a)else Panel,contentColor=Paper){
        if(large)Column(Modifier.fillMaxSize().pressMotion().padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            SymbolIcon(icon,null,Modifier.size(40.dp),tint=Paper)
            BilingualLabel(label,fontSize=15.sp,fontWeight=FontWeight.Medium)
        }else Row(Modifier.pressMotion().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
            SymbolIcon(icon,null,Modifier.size(40.dp),tint=Paper)
            Spacer(Modifier.width(10.dp))
            BilingualLabel(label,fontSize=15.sp,fontWeight=FontWeight.Medium)
        }
    }
}

@Composable internal fun CoordinatorRouteReceipt(route:CoordinatorRoute,onOpen:()->Unit,onCorrect:()->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        TextButton(onOpen,modifier=Modifier.weight(1f).heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=0.dp,vertical=4.dp)){
            Text("${route.label} · ${route.name.ifBlank{"Conversation"}}${if(route.mode in listOf("steer","queue"))" · ${route.mode}" else ""}",color=Mint,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if(route.operation!="read")TextButton(onCorrect,modifier=Modifier.heightIn(min=48.dp)){BilingualLabel("Change target")}
    }
}

internal fun coordinatorItemFullyVisible(offset:Int,size:Int,start:Int,end:Int)=size>0&&offset>=start&&offset+size<=end
