package co.fallsoft.pocket

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.FileProvider

val Ink=Color(0xff0e1516);val Panel=Color(0xff192324);val Mint=Color(0xffb3f5cb);val Paper=Color(0xffedf2ef);val Muted=Color(0xff92a6a0);val Line=Color(0xff2b3836);val Coral=Color(0xffefb399)
class MainActivity:ComponentActivity(){
    private var initialServer by mutableStateOf("");private var initialCode by mutableStateOf("")
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT));readIntent(intent)
        if(Build.VERSION.SDK_INT>=33)permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
        if(Pocket.token.isNotBlank()){Pocket.refresh()}
        setContent{MaterialTheme(colorScheme=darkColorScheme(primary=Mint,onPrimary=Ink,background=Ink,surface=Panel,onSurface=Paper,onBackground=Paper,outline=Line,secondary=Coral)){
            Surface(Modifier.fillMaxSize(),color=Ink){if(Pocket.token.isBlank())PairScreen(initialServer,initialCode)else PocketApp()}
        }}
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);readIntent(intent)}
    private fun readIntent(i:Intent){
        initialServer=i.getStringExtra("server")?:initialServer;initialCode=i.getStringExtra("code")?:initialCode
        i.getStringExtra("thread")?.let{if(Pocket.token.isNotBlank())Pocket.open(it)}
        if(Pocket.token.isNotBlank()&&i.getStringExtra("operations_url")=="https://github.com/FallSoftCo/pocket/actions"){
            i.removeExtra("operations_url")
            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/FallSoftCo/pocket/actions")))
        }
    }
    override fun onStart(){super.onStart();if(Pocket.token.isNotBlank())PocketLive.start()}
    override fun onStop(){PocketLive.stop();super.onStop()}
    override fun onResume(){super.onResume();if(Pocket.token.isNotBlank()){Pocket.refresh();Pocket.refreshDetail()}}
}

@Composable fun Mark(size:Int=44){Box(Modifier.size(size.dp).clip(RoundedCornerShape((size/3).dp)).background(Color(0xff142323)),contentAlignment=Alignment.Center){Icon(painterResource(R.drawable.ic_pocket_mark),"Pocket",tint=Color.Unspecified,modifier=Modifier.size((size*.82f).dp))}}
@Composable fun Label(text:String,color:Color=Muted){Text(text.uppercase(),color=color,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.8.sp)}
@Composable fun ErrorBanner(){if(Pocket.error.isNotBlank())Surface(color=Coral.copy(alpha=.12f),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(vertical=8.dp)){Text(Pocket.error,color=Coral,fontSize=13.sp,modifier=Modifier.padding(16.dp))}}
@Composable fun PairScreen(server:String,code:String){
    var address by remember(server){mutableStateOf(server)};var pin by remember(code){mutableStateOf(code)}
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(22.dp)){
        Spacer(Modifier.height(34.dp));Mark(70);Spacer(Modifier.height(18.dp));Label("CODEX, WITH YOU",Mint)
        Text("Good work.\nWithin reach.",fontSize=46.sp,lineHeight=49.sp,fontWeight=FontWeight.Medium,letterSpacing=(-1.8).sp)
        Text("Your tasks, updates and next ideas.\nConnected directly to your own workstation.",color=Muted,fontSize=17.sp,lineHeight=25.sp)
        Spacer(Modifier.height(14.dp));OutlinedTextField(address,{address=it},label={Text("Your server’s HTTPS address")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp))
        OutlinedTextField(pin,{pin=it},label={Text("One-time pairing code")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp))
        ErrorBanner();Button({Pocket.pair(address,pin)},enabled=!Pocket.busy&&address.isNotBlank()&&pin.isNotBlank(),modifier=Modifier.fillMaxWidth().height(58.dp),shape=RoundedCornerShape(18.dp)){if(Pocket.busy)CircularProgressIndicator(Modifier.size(22.dp),color=Ink,strokeWidth=2.dp)else{Text("Connect my workstation",fontWeight=FontWeight.Bold);Spacer(Modifier.width(12.dp));Icon(Icons.AutoMirrored.Rounded.ArrowForward,null)}}
        Text("Self-hosted. Private by design.\nYour Codex stays exactly where it is.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
    }
}

@Composable fun PocketApp(){
    BackHandler(Pocket.selected!=null||Pocket.newTask){if(Pocket.newTask)Pocket.newTask=false else{Pocket.selected=null;Pocket.detail=null}}
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
        if(Pocket.newTask)NewTaskScreen()
        else if(Pocket.selected!=null)key(Pocket.selected){ConversationScreen()}
        else{
            Box(Modifier.weight(1f)){when(Pocket.tab){0->WorkScreen();1->UpdatesScreen();else->SettingsScreen()}}
            Row(Modifier.fillMaxWidth().background(Ink).border(1.dp,Line).padding(vertical=12.dp),horizontalArrangement=Arrangement.SpaceEvenly){
                listOf(Triple("Work",Icons.Rounded.Layers,0),Triple("Updates",Icons.Rounded.NotificationsNone,1),Triple("Settings",Icons.Rounded.Tune,2)).forEach{(title,icon,index)->
                    Column(Modifier.width(88.dp).clip(RoundedCornerShape(18.dp)).clickable{Pocket.tab=index;Pocket.refresh()}.padding(6.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Icon(icon,title,tint=if(Pocket.tab==index)Mint else Muted,modifier=Modifier.size(24.dp));Text(title,fontSize=11.sp,color=if(Pocket.tab==index)Mint else Muted,fontWeight=FontWeight.Medium)
                    }
                }
            }
        }
    }
}
@Composable fun ConnectionPill(){val ok=Pocket.connected&&Pocket.codexOnline;Row(Modifier.clip(CircleShape).background(Panel).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){Box(Modifier.size(6.dp).background(if(ok)Mint else Coral,CircleShape));Text(if(ok)"Connected" else "Reconnecting",fontSize=11.sp,color=if(ok)Mint else Coral)}}
@Composable fun WorkScreen(){
    var filter by remember{mutableIntStateOf(0)};var query by remember{mutableStateOf("")}
    val shown=Pocket.tasks.filter{(filter!=1||it.status=="active")&&(filter!=2||it.watched)&&(query.isBlank()||it.title.contains(query,true)||it.cwd.contains(query,true))}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(top=20.dp,bottom=24.dp)){
        item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Mark(30);Text("Pocket",fontSize=22.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(start=9.dp).weight(1f));ConnectionPill()}}
        item{Row(Modifier.fillMaxWidth().padding(top=14.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Your sessions",fontSize=27.sp,fontWeight=FontWeight.Medium);Text(Pocket.host,color=Muted,fontSize=12.sp)};FilledTonalButton({Pocket.composeTask()},shape=RoundedCornerShape(14.dp)){Icon(Icons.Rounded.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text("New task")}}}
        item{OutlinedTextField(query,{query=it},placeholder={Text("Find a session or project",fontSize=13.sp)},leadingIcon={Icon(Icons.Rounded.Search,null,tint=Muted)},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Line,focusedBorderColor=Mint))}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Recent","Working","Following").forEachIndexed{i,s->FilterChip(selected=filter==i,onClick={filter=i},label={Text(s,fontSize=12.sp)},shape=CircleShape,colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Mint,selectedLabelColor=Ink))}};ErrorBanner()}
        val attention=Pocket.attention.filter{!PocketAttention.dismissed(it.optLong("id"))}
        if(attention.isNotEmpty()){item{Label("NEEDS YOU · ${attention.size}",Coral)};items(attention,key={"attention-${it.optLong("id")}"}){AttentionCard(it)}}
        if(shown.isEmpty())item{Empty("No sessions here",if(query.isNotBlank())"Try another name or project." else "Start a task from your phone.")}
        items(shown,key={it.id}){TaskCard(it)}
    }
}

fun project(cwd:String)=cwd.trimEnd('/').substringAfterLast('/').ifBlank{"Workspace"}
fun relative(time:Long):String{val seconds=(System.currentTimeMillis()-(if(time<100000000000L)time*1000 else time))/1000;return when{seconds<60->"now";seconds<3600->"${seconds/60}m";seconds<86400->"${seconds/3600}h";else->"${seconds/86400}d"}}
@Composable fun TaskCard(t:Task){val active=t.status=="active"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).clickable{Pocket.open(t.id)}.padding(19.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.FolderOpen,null,tint=Muted,modifier=Modifier.size(14.dp));Text(project(t.cwd),color=Muted,fontSize=11.sp,modifier=Modifier.padding(start=6.dp).weight(1f),maxLines=1);Text(relative(t.updated),color=Muted,fontSize=11.sp)}
        Text(t.title,fontSize=18.sp,lineHeight=24.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
        Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(6.dp).background(if(active)Mint else Muted.copy(alpha=.5f),CircleShape));Text(if(active)"Working" else "Ready to continue",color=if(active)Mint else Muted,fontSize=11.sp,modifier=Modifier.padding(start=7.dp).weight(1f));if(t.watched)Icon(Icons.Rounded.NotificationsActive,"Following",tint=Mint,modifier=Modifier.size(15.dp));Spacer(Modifier.width(8.dp));Icon(Icons.AutoMirrored.Rounded.ArrowForward,null,tint=Muted,modifier=Modifier.size(17.dp))}
    }
}
@Composable fun Empty(title:String,body:String){Column(Modifier.fillMaxWidth().padding(vertical=45.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Icon(Icons.Rounded.Inbox,null,tint=Muted,modifier=Modifier.size(40.dp));Text(title,fontSize=19.sp);Text(body,color=Muted,fontSize=13.sp)}}
@Composable fun AttentionCard(n:JSONObject){
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xff35312a)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
        Label(when(n.s("kind")){"approval"->"REVIEW A REQUEST";"question"->"ANSWER A QUESTION";else->"CHECK A PROBLEM"},Coral)
        Text(n.s("title"),fontSize=17.sp,fontWeight=FontWeight.Medium)
        Text(n.s("body"),color=Muted,fontSize=13.sp,lineHeight=19.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            TextButton({Pocket.open(n.s("thread_id"))}){Text("Review",color=Mint)}
            TextButton({PocketAttention.snooze(n.optLong("id"))}){Text("Later · 30m",color=Muted)}
            TextButton({PocketAttention.dismiss(n.optLong("id"))}){Text("Dismiss",color=Muted)}
        }
    }
}
@Composable fun UpdatesScreen(){LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=26.dp)){
    item{Label("THE MOMENTS THAT MATTER",Mint);Spacer(Modifier.height(12.dp));Text("Your updates.",fontSize=36.sp,letterSpacing=(-1).sp);Spacer(Modifier.height(8.dp));Text("Results, questions, and a way forward.",color=Muted,fontSize=15.sp)}
    if(Pocket.notifications.isEmpty())item{Empty("You’re all caught up","Ask Codex to notify you when something is ready.")}
    items(Pocket.notifications,key={it.optLong("id")}){n->Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).clickable{n.s("thread_id").takeIf{it.isNotBlank()}?.let{Pocket.open(it)}}.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row{Label(n.s("kind","update"),if(n.s("kind")=="error")Coral else Mint);Spacer(Modifier.weight(1f));Text(relative(n.optLong("created_at")),fontSize=11.sp,color=Muted)}
        Text(n.s("title"),fontSize=19.sp,fontWeight=FontWeight.Medium);Text(n.s("body"),fontSize=14.sp,color=Muted,lineHeight=21.sp,maxLines=5,overflow=TextOverflow.Ellipsis)
        n.optJSONArray("attachments")?.objects()?.forEach{Attachment(it)}
        if(n.s("thread_id").isNotBlank())Text("Open conversation  ↗",color=Mint,fontSize=12.sp)
    }}
}}
@Composable fun SettingsScreen(){val c=LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(23.dp)){
        Label("MADE TO BE YOURS",Mint);Text("Your connection.",fontSize=34.sp,letterSpacing=(-1).sp)
        Surface(color=Panel,shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){ConnectionPill();Text(Pocket.host,fontSize=23.sp);Text(Pocket.base,color=Muted,fontSize=12.sp);HorizontalDivider(color=Line);Text(Pocket.pushStatus,fontSize=16.sp,color=Mint);Text("Notifications arrive through Firebase, even when Pocket is closed. Conversations and files load from your workstation.",color=Muted,fontSize=14.sp,lineHeight=21.sp)}}
        NotificationAudioSettings()
        Button({Pocket.test()},modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(17.dp)){Icon(Icons.Rounded.NotificationsActive,null);Spacer(Modifier.width(10.dp));Text("Send a test notification")}
        OutlinedButton({c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName))},modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(17.dp)){Text("Notification settings")}
        Text("Keep notifications enabled. Tailscale connects you to your conversations and lets you reply. Firebase handles alerts without a permanent connection to the app.",color=Muted,fontSize=13.sp,lineHeight=20.sp)
        ErrorBanner();OutlinedButton({Pocket.disconnect()},modifier=Modifier.fillMaxWidth()){Text("Disconnect this phone",color=Coral)}
        Spacer(Modifier.height(12.dp));Label("POCKET ${BuildConfig.VERSION_NAME} · FALLSOFT");Text("Built around stock Codex.\nNo fork. No hosted account. No app store.",color=Muted,fontSize=12.sp,lineHeight=20.sp)
    }
}

@Composable fun NotificationAudioSettings(){
    LaunchedEffect(Unit){if(PocketAudio.mode=="voice")PocketAudio.prepareVoice()}
    Surface(color=Panel,shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Hear what happened.",fontSize=21.sp,fontWeight=FontWeight.Medium)
        Text("A different sound for results, questions, approvals and problems.",color=Muted,fontSize=13.sp,lineHeight=20.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("tones" to "Tones","voice" to "Speech","system" to "System").forEach{(value,label)->
                FilterChip(selected=PocketAudio.mode==value,onClick={PocketAudio.select(value)},label={Text(label,fontSize=12.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Mint,selectedLabelColor=Ink))
            }
        }
        Text(when(PocketAudio.mode){"voice"->"Short labels such as “Codex needs your input.” Task text stays on screen.";"system"->"Uses your phone’s default notification sound.";else->"Finished rises, problems fall, and questions have a distinct two-note cue."},color=Muted,fontSize=13.sp,lineHeight=20.sp)
        if(PocketAudio.status.isNotBlank())Text(PocketAudio.status,color=Mint,fontSize=12.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            listOf("complete" to "Finished","question" to "Question","error" to "Problem").forEach{(kind,label)->TextButton({PocketAudio.preview(kind)}){Text("▶ $label",color=Mint,fontSize=11.sp)}}
        }
        Text("Uses your notification volume and Do Not Disturb settings.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
        HorizontalDivider(color=Line)
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Remind me when I’m needed",fontSize=14.sp);Text("Questions, approvals and problems only",color=Muted,fontSize=11.sp)};Switch(PocketAttention.enabled,{PocketAttention.toggle(it)})}
        Text("Up to three reminders: 5, 15, then 30 minutes apart. Dismiss, reply or resolve the request to stop. Later snoozes for 30 minutes.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
    }}
}

@Composable fun RichText(raw:String){
    val c=LocalContext.current;val pieces=raw.split("```")
    Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
        pieces.forEachIndexed{index,part->
            if(index%2==1)Surface(color=Color(0xff070d0e),shape=RoundedCornerShape(12.dp)){Text(codeBlock(part),fontFamily=FontFamily.Monospace,fontSize=12.sp,color=Mint,lineHeight=18.sp,modifier=Modifier.padding(14.dp).horizontalScroll(rememberScrollState()))}
            else if(part.isNotBlank()){
                val display=part.trim().replace(Regex("\\*\\*(.*?)\\*\\*"),"$1").replace(Regex("\\[([^]]+)\\]\\(([^)]+)\\)"),"$1 ↗")
                Text(display,fontSize=15.sp,lineHeight=24.sp,color=Paper.copy(alpha=.93f))
                Regex("\\[([^]]+)\\]\\((https?://[^)]+)\\)").findAll(part).forEach{link->TextButton({try{c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link.groupValues[2])))}catch(_:Exception){}}){Text(link.groupValues[1],fontSize=12.sp,color=Mint);Icon(Icons.AutoMirrored.Rounded.OpenInNew,null,Modifier.padding(start=5.dp).size(14.dp),tint=Mint)}}
            }
        }
    }
}
@Composable fun Attachment(a:JSONObject){val c=LocalContext.current
    fun open(){Pocket.scope.launch{try{val file=withContext(Dispatchers.IO){val folder=File(c.cacheDir,"shared");folder.mkdirs();val f=File(folder,a.s("id")+"-"+a.s("name").substringAfterLast('/'));val req=okhttp3.Request.Builder().url(Pocket.base+a.s("url")).header("Authorization","Bearer ${Pocket.token}").build();Pocket.http.newCall(req).execute().use{r->if(!r.isSuccessful)throw Exception("Could not download attachment");f.writeBytes(r.body!!.bytes())};f};val u=FileProvider.getUriForFile(c,"co.fallsoft.pocket.files",file);c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u,a.s("mime")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}catch(e:Exception){Pocket.error=e.message?:"No app can open this file"}}}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).clickable{open()},verticalArrangement=Arrangement.spacedBy(6.dp)){
        if(a.s("mime").startsWith("image/"))AsyncImage(model=ImageRequest.Builder(c).data(Pocket.base+a.s("url")).addHeader("Authorization","Bearer ${Pocket.token}").build(),contentDescription=a.s("name"),modifier=Modifier.fillMaxWidth().heightIn(min=100.dp,max=300.dp))
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.AttachFile,null,tint=Mint,modifier=Modifier.size(18.dp));Text(a.s("name"),color=Mint,fontSize=12.sp,modifier=Modifier.padding(start=8.dp).weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);Icon(Icons.AutoMirrored.Rounded.OpenInNew,null,tint=Muted,modifier=Modifier.size(16.dp))}
    }
}
@Composable fun RequestCard(r:JSONObject){val p=r.optJSONObject("params")?:return;val id=r.s("id");val isQuestion=r.s("method").contains("requestUserInput");val answers=remember(id){mutableStateMapOf<String,String>()}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xff35312a)).border(1.dp,Coral.copy(alpha=.3f),RoundedCornerShape(22.dp)).padding(19.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Label(if(isQuestion)"YOUR INPUT" else "NEEDS YOUR APPROVAL",Coral)
        if(isQuestion){p.optJSONArray("questions")?.objects()?.forEach{q->Text(q.s("question"),fontSize=16.sp,lineHeight=23.sp);q.optJSONArray("options")?.objects()?.forEach{o->val label=o.s("label");OutlinedButton({answers[q.s("id")]=label},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.outlinedButtonColors(containerColor=if(answers[q.s("id")]==label)Mint.copy(alpha=.14f)else Color.Transparent)){Text(label,color=Paper)}};OutlinedTextField(answers[q.s("id")]?:"",{answers[q.s("id")]=it},label={Text("Your answer")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp))};Button({val a=JSONObject();answers.forEach{(k,v)->a.put(k,v)};Pocket.answer(id,JSONObject().put("answers",a))}){Text("Send answers")}}
        else{Text(p.s("reason",p.s("message","Review this request before continuing.")),fontSize=15.sp);RichText(p.s("command",p.toString(2)));if(r.s("method").contains("commandExecution")||r.s("method").contains("fileChange")){Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Button({Pocket.answer(id,JSONObject().put("decision","accept"))}){Text("Allow once")};OutlinedButton({Pocket.answer(id,JSONObject().put("decision","decline"))}){Text("Decline",color=Coral)}}}else Text("Answer this request in the terminal.",color=Coral,fontSize=13.sp)}
    }
}
