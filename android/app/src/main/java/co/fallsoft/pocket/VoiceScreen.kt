package co.fallsoft.pocket

import org.json.JSONObject
import kotlinx.coroutines.launch

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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

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
                item(key="assistant:${turn.id}"){VoiceChatMessage("Codex",turn.second,false);PocketVoice.routes[turn.id].orEmpty().forEach{route->CoordinatorRouteReceipt(route,{keyboard?.hide();PocketVoice.stop();openCoordinatorRoute(route)},{PocketVoice.correctionOf=route;draft=route.correction+draft;keyboardInput=true})}}
            }
            if(PocketVoice.heard.isNotBlank()&&PocketVoice.messages.lastOrNull()?.first!=PocketVoice.heard)item{VoiceChatMessage("You",PocketVoice.heard,true)}
        }
        ForegroundWorkNotice()
        if(!PocketVoice.captureFeedbackVisible){SpeechCaptionBanner();SpeechPlayer()}
        InlineCaptureStatus(always=true)
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(!keyboardInput)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                ChatActionButton("Keyboard",Icons.Rounded.Keyboard,Modifier.weight(1f),{keyboardInput=!keyboardInput;if(!keyboardInput)keyboard?.hide()})
            }
            if(keyboardInput)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
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
    LaunchedEffect(text,PocketImmersion.enabled,PocketImmersion.planVersion){PocketImmersion.offer(sourceId,text,if(user)"user message" else "Codex response")}
    Column(Modifier.fillMaxWidth(),horizontalAlignment=if(user)Alignment.End else Alignment.Start){
        SymbolIcon(if(user)"User" else "Codex",PocketImmersion.label(if(user)"Your message" else "Codex message"),Modifier.size(20.dp))
        Surface(color=if(user)Mint.copy(alpha=0.12f) else MaterialTheme.colorScheme.surfaceVariant,shape=androidx.compose.foundation.shape.RoundedCornerShape(6.dp),modifier=Modifier.padding(top=5.dp)){Column(Modifier.padding(14.dp)){BilingualMessage(sourceId,text)}}
    }
}
/** Capture feedback belongs to the existing chat, including while its editor stays focused. */
@Composable internal fun InlineCaptureStatus(always:Boolean=false,modifier:Modifier=Modifier){
    if((!always&&!PocketVoice.inPlace&&PocketVoice.problem.isBlank())||!PocketVoice.captureFeedbackVisible)return
    var elapsed by remember{mutableLongStateOf(0)}
    LaunchedEffect(PocketVoice.state,PocketVoice.recordingStartedAt){while(PocketVoice.state=="Listening"){elapsed=(android.os.SystemClock.elapsedRealtime()-PocketVoice.recordingStartedAt)/1000;kotlinx.coroutines.delay(1000)}}
    val target=PocketVoice.targetSourceLabel?:if(PocketVoice.targetRoom!=null)"Room" else if(PocketVoice.targetThread==null)"Coordinator" else "Conversation"
    var expandedError by remember(PocketVoice.problem){mutableStateOf(false)}
    val help=if(PocketVoice.problem.isNotBlank())Modifier.clickable(onClickLabel=if(expandedError)"Collapse capture error" else "Show full capture error"){expandedError=!expandedError}else Modifier
    Row(modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        WorkflowText(if(PocketVoice.problem.isNotBlank())target+" · "+PocketVoice.problem else "${if(PocketVoice.state=="Listening")"Recording %02d:%02d".format(elapsed/60,elapsed%60) else PocketVoice.deliveryStatus.ifBlank{PocketVoice.state}} · $target",Modifier.weight(1f).then(help),color=if(PocketVoice.problem.isNotBlank())Coral else Mint,fontSize=14.sp,maxLines=if(expandedError)Int.MAX_VALUE else 2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if(PocketVoice.state=="Listening")LinearProgressIndicator(progress={PocketVoice.captureLevel},modifier=Modifier.width(48.dp),color=Coral)
        TextButton({PocketVoice.stop()},modifier=Modifier.widthIn(min=64.dp).heightIn(min=48.dp)){BilingualLabel("End voice",fontSize=14.sp,maxLines=2)}
    }
}

@Composable internal fun inlineVolumeControlWidth():androidx.compose.ui.unit.Dp{
    val density=androidx.compose.ui.platform.LocalDensity.current
    val measure=androidx.compose.ui.text.rememberTextMeasurer()
    val pixels=measure.measure(androidx.compose.ui.text.AnnotatedString("100%"),style=LocalTextStyle.current.copy(fontSize=15.sp)).size.width
    return with(density){pixels.toDp()+12.dp}.coerceAtLeast(48.dp)
}

/** Three deliberate playback presets; mounting never changes an existing mute. */
@Composable fun InlineSpeechVolume(modifier:Modifier=Modifier){
    val context=LocalContext.current
    val audio=context.getSystemService(android.media.AudioManager::class.java)
    val max=remember(audio){audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}
    val middle=(max+1)/2
    var volume by remember(audio){mutableIntStateOf(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))}
    var problem by remember{mutableStateOf("")}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(audio,lifecycle){lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
        while(true){volume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);kotlinx.coroutines.delay(500)}
    }}
    // Report pre-existing custom system levels honestly until the first deliberate tap.
    val percent=when{max<=0||volume<=0->0;volume==max->100;volume==middle->50;else->(volume*100.0/max).toInt()}
    TextButton({
        try{
            val current=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val next=when{current<middle->middle;current<max->max;else->0}
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,next,0)
            volume=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);PocketVoice.service?.playbackVolumeChanged(volume);problem=""
        }catch(e:Exception){
            problem=e.message?:"Could not change playback volume"
            android.widget.Toast.makeText(context,problem,android.widget.Toast.LENGTH_LONG).show()
        }
    },enabled=max>0&&!audio.isVolumeFixed,modifier=modifier.widthIn(min=48.dp).heightIn(min=48.dp).semantics{
        contentDescription="Speech volume";stateDescription="$percent%"+if(problem.isBlank())"" else "; $problem"
    },contentPadding=PaddingValues(horizontal=4.dp,vertical=2.dp)){
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(0.dp)){
            SymbolIcon("VolumeUp",null,Modifier.size(24.dp),tint=if(problem.isNotBlank())Coral else if(volume==0)Muted else Mint)
            Text(if(problem.isBlank())"$percent%" else "!",fontSize=15.sp,lineHeight=18.sp,color=if(problem.isNotBlank())Coral else Paper,maxLines=1)
        }
    }
}

@Composable fun ChatActionButton(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier=Modifier,onClick:()->Unit,recording:Boolean=false){
    Surface(onClick=onClick,modifier=modifier.heightIn(min=88.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),border=androidx.compose.foundation.BorderStroke(1.dp,if(recording)Coral else Line),color=if(recording)androidx.compose.ui.graphics.Color(0xff451e2a)else Panel,contentColor=Paper){
        Column(Modifier.pressMotion().padding(horizontal=8.dp,vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
            SymbolIcon(icon,null,Modifier.size(44.dp),tint=Paper)
            BilingualLabel(label,fontSize=15.sp,lineHeight=18.sp,maxLines=2,fontWeight=FontWeight.Medium)
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

@Composable internal fun SavedVoiceRecordingControls(){
    RecordingQueueControls()
    val c=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope()
    var entries by remember{mutableStateOf(emptyList<JSONObject>())}
    var expanded by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var checking by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(PocketVoice.launching,PocketVoice.state){entries=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){SavedVoiceRecordings.list(c)}}
    if(entries.isEmpty())return
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)){
        TextButton({expanded=!expanded}){BilingualLabel("Earlier recordings");Text(" · "+entries.size)}
        if(expanded)entries.forEach{entry->
            val id=entry.s("id")
            Column(Modifier.fillMaxWidth()){
                Text(entry.s("voicePendingSourceLabel","Recording")+" · "+entry.s("state"),maxLines=2)
                TextButton({checking=id;error="";scope.launch{
                    try{
                        val source=requireNotNull(Pocket.captureEnvironment(entry.s("voicePendingEnvironment"))){"Original environment unavailable"}
                        check(backendOwner(source.endpoint,source.token)==entry.s("voicePendingOwner")){"Original pairing changed; recording retained"}
                        val result=Pocket.apiEnvironment(source,"/api/voice/turns/"+android.net.Uri.encode(id))
                        check(result.s("id")==id){"Receipt did not match this recording"}
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){SavedVoiceRecordings.update(c,id,result)}
                        entries=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){SavedVoiceRecordings.list(c)}
                    }catch(e:Exception){error="Could not confirm this recording. It remains saved and has not been resent."}finally{checking=null}
                }},enabled=checking==null){BilingualLabel(if(checking==id)"Checking" else "Check status")}
                entry.optJSONObject("result")?.s("response")?.takeIf{it.isNotBlank()}?.let{WorkflowText(it,maxLines=8)}
            }
        }
        if(error.isNotBlank())WorkflowText(error,color=Coral,maxLines=3)
    }
}
