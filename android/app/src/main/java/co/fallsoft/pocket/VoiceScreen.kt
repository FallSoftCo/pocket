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

@Composable fun VoiceLaunchButton(modifier:Modifier=Modifier,threadId:String?=null,compact:Boolean=false){
    val c=LocalContext.current
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ok->if(ok)PocketVoice.start(c,threadId)else PocketVoice.problem="Microphone access is needed for voice mode. Enable it in Android app settings."}
    var showError by remember{mutableStateOf(false)}
    LaunchedEffect(PocketVoice.problem){showError=PocketVoice.problem.isNotBlank()&&!PocketVoice.active}
    val startVoice:()->Unit={if(ContextCompat.checkSelfPermission(c,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)PocketVoice.start(c,threadId)else permission.launch(Manifest.permission.RECORD_AUDIO)}
    if(compact)IconButton(startVoice,modifier=modifier){Icon(Icons.Rounded.Mic,"Start voice in this conversation",tint=Mint)}else ExtendedFloatingActionButton(onClick=startVoice,modifier=modifier.height(72.dp),containerColor=Mint,contentColor=Ink,icon={Icon(Icons.Rounded.Mic,"Start voice",Modifier.size(30.dp))},text={Text("Talk to Codex",fontWeight=FontWeight.Bold,fontSize=17.sp)})
    if(showError)AlertDialog(onDismissRequest={showError=false},title={Text("Voice setup")},text={Text(PocketVoice.problem)},confirmButton={TextButton({showError=false;c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${c.packageName}")))}){Text("App settings")}},dismissButton={TextButton({showError=false}){Text("Close")}})
}
@Composable fun VoiceScreen(){
    val c=LocalContext.current
    var draft by remember{mutableStateOf("")}
    var keyboardInput by remember{mutableStateOf(false)}
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val inputFocus=remember{androidx.compose.ui.focus.FocusRequester()}
    LaunchedEffect(keyboardInput){if(keyboardInput){inputFocus.requestFocus();keyboard?.show()}}
    val scroll=androidx.compose.foundation.lazy.rememberLazyListState()
    BackHandler{PocketVoice.stop()}
    LaunchedEffect(PocketVoice.messages.size,PocketVoice.heard,PocketVoice.response){if(scroll.layoutInfo.totalItemsCount>0)scroll.animateScrollToItem(scroll.layoutInfo.totalItemsCount-1)}
    Column(Modifier.fillMaxSize().imePadding()){
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton({PocketVoice.stop()}){Icon(Icons.Rounded.ArrowBack,"Back to conversations")}
            Column(Modifier.weight(1f)){Text(Pocket.tasks.firstOrNull{it.id==PocketVoice.targetThread}?.title?:"Pocket coordinator",maxLines=1,fontSize=20.sp,fontWeight=FontWeight.Medium);Text(PocketVoice.state,color=Mint,fontSize=13.sp)}
            IconButton({PocketVoice.playback()}){Icon(if(PocketVoice.state=="Speaking")Icons.Rounded.Pause else Icons.Rounded.PlayArrow,"Pause or replay response")}
        }
        HorizontalDivider()
        androidx.compose.foundation.lazy.LazyColumn(state=scroll,modifier=Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            if(PocketVoice.historyEarlier)item{TextButton({PocketVoice.olderHistory()},enabled=!PocketVoice.historyLoading){Text("Load earlier messages")}}
            if(PocketVoice.historyLoading)item{Text("Loading history…",color=Muted)}
            if(PocketVoice.historyProblem.isNotBlank())item{Text(PocketVoice.historyProblem,color=Coral);TextButton({PocketVoice.service?.loadHistory()}){Text("Retry history")}}
            items(PocketVoice.messages.size){i->val turn=PocketVoice.messages[i];VoiceChatMessage("You",turn.first,true);Spacer(Modifier.height(12.dp));VoiceChatMessage("Codex",turn.second,false)}
            if(PocketVoice.heard.isNotBlank()&&PocketVoice.messages.lastOrNull()?.first!=PocketVoice.heard)item{VoiceChatMessage("You",PocketVoice.heard,true)}
            if(PocketVoice.messages.isEmpty()&&PocketVoice.heard.isBlank())item{Text("Talk naturally to manage your sessions or work with Codex.",color=Muted)}
            if(PocketVoice.problem.isNotBlank())item{Text(PocketVoice.problem,color=Coral);TextButton({PocketVoice.retry()}){Text("Retry saved turn")}}
        }
        VoiceVolumeControls()
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            val recording=PocketVoice.state in listOf("Listening","Starting microphone","Finishing recording")
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                ExtendedFloatingActionButton(onClick={PocketVoice.record()},modifier=Modifier.weight(1f).height(88.dp),containerColor=if(recording)Coral else Mint,contentColor=Ink,icon={Icon(if(recording)Icons.Rounded.Stop else Icons.Rounded.Mic,if(recording)"Stop and send recording" else "Start recording",Modifier.size(36.dp))},text={Text(if(recording)"Stop & send" else "Tap to talk",fontSize=20.sp,fontWeight=FontWeight.SemiBold)})
                FilledTonalIconButton(onClick={keyboardInput=!keyboardInput;if(!keyboardInput)keyboard?.hide()},modifier=Modifier.size(52.dp)){Icon(Icons.Rounded.Keyboard,if(keyboardInput)"Hide keyboard input" else "Show keyboard input")}
            }
            if(keyboardInput)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                OutlinedTextField(value=draft,onValueChange={value->if(value.contains('\n')){PocketVoice.sendText(value.replace("\n","").trim());draft=""}else draft=value},placeholder={Text("Message coordinator")},modifier=Modifier.weight(1f).focusRequester(inputFocus).onPreviewKeyEvent{event->if(event.key==Key.Enter||event.key==Key.NumPadEnter){if(event.type==KeyEventType.KeyDown){PocketVoice.sendText(draft);draft=""};true}else false},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Send),keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSend={PocketVoice.sendText(draft);draft=""}))
                IconButton({PocketVoice.sendText(draft);draft=""},enabled=draft.isNotBlank()){Icon(Icons.Rounded.Send,"Send message")}
            }
        }
    }
}
@Composable private fun VoiceChatMessage(speaker:String,text:String,user:Boolean){
    if(text.isBlank())return
    Column(Modifier.fillMaxWidth(),horizontalAlignment=if(user)Alignment.End else Alignment.Start){
        Text(speaker,color=Muted,fontSize=12.sp)
        Surface(color=if(user)Mint.copy(alpha=0.12f) else MaterialTheme.colorScheme.surfaceVariant,shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),modifier=Modifier.padding(top=5.dp)){Text(text,modifier=Modifier.padding(14.dp))}
    }
}
@Composable fun VoiceVolumeControls(){
    val audio=LocalContext.current.getSystemService(android.media.AudioManager::class.java)
    val max=remember{audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}
    var volume by remember{mutableIntStateOf(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))}
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
        Icon(Icons.Rounded.VolumeUp,"Speech volume",tint=Muted)
        Slider(value=volume.toFloat(),onValueChange={volume=it.toInt();audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,volume,0)},valueRange=0f..max.toFloat(),steps=(max-1).coerceAtLeast(0),modifier=Modifier.weight(1f).padding(horizontal=12.dp))
        Text("$volume/$max",color=Muted,fontSize=12.sp)
    }
}

@Composable fun CoordinatorCard(){
    val c=LocalContext.current
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ok->if(ok)PocketVoice.start(c,recordImmediately=false)}
    Surface(color=Mint.copy(alpha=0.10f),border=androidx.compose.foundation.BorderStroke(1.dp,Mint.copy(alpha=0.35f)),shape=androidx.compose.foundation.shape.RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()){
        Row(Modifier.clickable{if(ContextCompat.checkSelfPermission(c,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)PocketVoice.start(c,recordImmediately=false)else permission.launch(Manifest.permission.RECORD_AUDIO)}.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            Icon(Icons.Rounded.PushPin,"Pinned coordinator",tint=Mint,modifier=Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text("Pocket coordinator",fontWeight=FontWeight.SemiBold);Text(PocketVoice.messages.lastOrNull()?.second?:"Manage all your sessions",color=Muted,fontSize=12.sp,maxLines=2)}
            VoiceLaunchButton(compact=true)
        }
    }
}
