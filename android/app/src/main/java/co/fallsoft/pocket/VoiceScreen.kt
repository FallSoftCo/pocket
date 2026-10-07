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
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ok->if(ok)PocketVoice.start(c,threadId)else PocketVoice.problem="Microphone access is needed for voice mode. Enable it in Android app settings."}
    var showError by remember{mutableStateOf(false)}
    var attempted by remember{mutableStateOf(false)}
    LaunchedEffect(PocketVoice.problem,PocketVoice.active,attempted){showError=attempted&&PocketVoice.problem.isNotBlank()&&!PocketVoice.active;if(PocketVoice.active)attempted=false}
    val startVoice:()->Unit={attempted=true;keyboard?.hide();if(ContextCompat.checkSelfPermission(c,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)PocketVoice.start(c,threadId)else permission.launch(Manifest.permission.RECORD_AUDIO)}
    if(cardRegion)CardInteractionRegion(PocketImmersion.label("Mic"),modifier,startVoice)else if(dock)DockButton("Talk",Icons.Rounded.Mic,modifier,primary=true,onClick=startVoice)else if(bar)ChatActionButton("Talk",Icons.Rounded.Mic,modifier,startVoice)else if(compact)IconButton(startVoice,modifier=modifier){SymbolIcon(Icons.Rounded.Mic,PocketImmersion.label("Start voice in this conversation"),tint=Mint,modifier=Modifier.size(44.dp))}else ExtendedFloatingActionButton(onClick=startVoice,modifier=modifier.height(72.dp),containerColor=Panel,contentColor=Paper,icon={SymbolIcon(Icons.Rounded.Mic,PocketImmersion.label("Start voice"),Modifier.size(44.dp))},text={BilingualLabel("Talk to Codex",fontWeight=FontWeight.Bold,fontSize=17.sp)})
    if(showError)AlertDialog(onDismissRequest={showError=false},title={BilingualLabel("Voice setup")},text={ImmersionText("voice:problem",PocketVoice.problem)},confirmButton={TextButton({showError=false;c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${c.packageName}")))}){BilingualLabel("App settings")}},dismissButton={TextButton({showError=false}){BilingualLabel("Close")}})
}
@Composable fun VoiceScreen(){
    val c=LocalContext.current
    var draft by remember{mutableStateOf("")}
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
        SpeechCaptionBanner()
        SpeechPlayer()
        VoiceVolumeControls(Modifier.fillMaxWidth().padding(horizontal=16.dp))
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            val recording=PocketVoice.state in listOf("Listening","Starting microphone","Finishing recording")
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                SymbolIcon(Icons.Rounded.Mic,null,Modifier.size(24.dp),tint=if(recording)Coral else Mint)
                BilingualLabel(PocketVoice.state,Modifier.weight(1f),centered=false,color=if(recording)Coral else Mint,fontSize=14.sp)
                TextButton({c.nextCompActivity()?.enterBlackout()}){BilingualLabel("Blackout",fontSize=12.sp)}
            }
            if(PocketVoice.problem.isNotBlank())Column(Modifier.fillMaxWidth()){
                ImmersionText("voice:problem",PocketVoice.problem,color=Coral,fontSize=13.sp)
                if(Pocket.prefs.getString("voicePendingId",null)!=null)TextButton({PocketVoice.retry()}){BilingualLabel("Check saved turn")}
            }
            if(!keyboardInput)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                ChatActionButton(if(recording)"Stop & send" else "Talk",if(recording)Icons.Rounded.Stop else Icons.Rounded.Mic,Modifier.weight(1f),{PocketVoice.record()},recording)
                ChatActionButton("Keyboard",Icons.Rounded.Keyboard,Modifier.weight(1f),{keyboardInput=!keyboardInput;if(!keyboardInput)keyboard?.hide()})
            }
            if(keyboardInput)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                FilledTonalIconButton(onClick={keyboardInput=false;keyboard?.hide();PocketVoice.record()},modifier=Modifier.size(64.dp)){SymbolIcon(if(recording)Icons.Rounded.Stop else Icons.Rounded.Mic,PocketImmersion.label(if(recording)"Stop and send recording" else "Talk"),Modifier.size(32.dp))}
                OutlinedTextField(value=draft,onValueChange={value->if(value.contains('\n'))sendDraft(value)else draft=value},placeholder={Text(PocketImmersion.label("Message coordinator"))},modifier=Modifier.weight(1f).focusRequester(inputFocus).onPreviewKeyEvent{event->if(event.key==Key.Enter||event.key==Key.NumPadEnter){if(event.type==KeyEventType.KeyDown)sendDraft();true}else false},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Send),keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSend={sendDraft()}))
                IconButton({sendDraft()},enabled=draft.isNotBlank(),modifier=Modifier.size(64.dp)){SymbolIcon(Icons.Rounded.Send,PocketImmersion.label("Send message"))}
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                ChatActionButton("Back",Icons.Rounded.ArrowBack,Modifier.weight(1f),{keyboard?.hide();PocketVoice.stop();Pocket.closeTask()})
                UsageDock(Modifier.width(usageDockWidth()))
                ChatActionButton(if(PocketVoice.state=="Speaking")"Pause" else "Replay",if(PocketVoice.state=="Speaking")Icons.Rounded.Pause else Icons.Rounded.PlayArrow,Modifier.weight(1f),{PocketVoice.playback()})
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
@Composable fun VoiceVolumeControls(modifier:Modifier=Modifier){
    val audio=LocalContext.current.getSystemService(android.media.AudioManager::class.java)
    val max=remember{audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}
    var volume by remember{mutableIntStateOf(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))}
    Row(modifier,verticalAlignment=Alignment.CenterVertically){
        SymbolIcon(Icons.Rounded.VolumeUp,PocketImmersion.label("Speech volume"),tint=Muted)
        Slider(value=volume.toFloat(),onValueChange={volume=it.toInt();audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,volume,0)},valueRange=0f..max.toFloat(),steps=(max-1).coerceAtLeast(0),modifier=Modifier.weight(1f).padding(horizontal=16.dp))
        Text("$volume/$max",color=Muted,fontSize=12.sp)
    }
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
