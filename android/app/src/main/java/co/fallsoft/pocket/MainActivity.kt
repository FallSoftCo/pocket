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
import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
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

// Neutral grays and the exact RGB accents in Paul Rand's official NeXT logo SVG.
val Ink=Color(0xff000000);val Panel=Color(0xff181818);val Mint=Color(0xffffc600);val Paper=Color(0xffffffff);val Muted=Color(0xffaaaaaa);val Line=Color(0xff383838);val Coral=Color(0xffff675d)
val AppFont=FontFamily(androidx.compose.ui.text.font.Font(R.font.inter_regular,FontWeight.Normal),androidx.compose.ui.text.font.Font(R.font.inter_medium,FontWeight.Medium),androidx.compose.ui.text.font.Font(R.font.inter_semibold,FontWeight.SemiBold),androidx.compose.ui.text.font.Font(R.font.inter_bold,FontWeight.Bold))
val NextGreen=Color(0xff00a85d);val NextCerise=Color(0xffeb4d97)

class MainActivity:ComponentActivity(){
    private var initialServer by mutableStateOf("");private var initialCode by mutableStateOf("")
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT));readIntent(intent)
        if(Build.VERSION.SDK_INT>=33)permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
        if(Pocket.token.isNotBlank()){Pocket.refresh()}
        setContent{MaterialTheme(shapes=Shapes(extraSmall=RoundedCornerShape(2.dp),small=RoundedCornerShape(4.dp),medium=RoundedCornerShape(6.dp),large=RoundedCornerShape(8.dp),extraLarge=RoundedCornerShape(10.dp)),typography=Typography(titleLarge=androidx.compose.ui.text.TextStyle(fontFamily=AppFont,fontSize=20.sp,fontWeight=FontWeight.Medium),titleMedium=androidx.compose.ui.text.TextStyle(fontFamily=AppFont,fontSize=16.sp,fontWeight=FontWeight.Medium),bodyLarge=androidx.compose.ui.text.TextStyle(fontSize=15.sp,lineHeight=22.sp,fontFamily=AppFont),bodyMedium=androidx.compose.ui.text.TextStyle(fontSize=14.sp,lineHeight=20.sp,fontFamily=AppFont),labelLarge=androidx.compose.ui.text.TextStyle(fontSize=15.sp,fontWeight=FontWeight.Medium,fontFamily=AppFont)),colorScheme=darkColorScheme(primary=Mint,onPrimary=Ink,background=Ink,surface=Panel,onSurface=Paper,onBackground=Paper,outline=Muted,secondary=Coral,onSecondary=Ink,surfaceVariant=Panel,onSurfaceVariant=Paper,surfaceContainer=Panel,surfaceContainerHigh=Panel,surfaceContainerHighest=Panel,surfaceContainerLow=Panel,surfaceContainerLowest=Ink,secondaryContainer=Panel,onSecondaryContainer=Paper,primaryContainer=Panel,onPrimaryContainer=Paper,tertiary=NextCerise,onTertiary=Ink,tertiaryContainer=Panel,onTertiaryContainer=Paper)){
            Surface(Modifier.fillMaxSize(),color=Ink){Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
                Box(Modifier.weight(1f)){if(Pocket.token.isBlank()||Pocket.pairingMode)PairScreen(initialServer.ifBlank{if(Pocket.pairingMode)"http://127.0.0.1:18880" else ""},initialCode)else PocketApp()}
            }}
        }}
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);readIntent(intent)}
    private fun readIntent(i:Intent){
        initialServer=i.getStringExtra("server")?:initialServer;initialCode=i.getStringExtra("code")?:initialCode
        i.data?.takeIf{it.scheme=="pocket"&&it.host=="pair"}?.let{initialServer=it.getQueryParameter("server")?:initialServer;initialCode=it.getQueryParameter("code")?:initialCode;Pocket.pairingMode=true}
        if(i.hasExtra("local"))Pocket.activate(i.getBooleanExtra("local",false))
        if(initialServer.isNotBlank()&&initialCode.isNotBlank())Pocket.pairingMode=true
        i.getStringExtra("thread")?.let{if(Pocket.token.isNotBlank())Pocket.open(it)}
        if(Pocket.token.isNotBlank()&&i.getStringExtra("operations_url")=="https://github.com/FallSoftCo/pocket/actions"){
            i.removeExtra("operations_url")
            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/FallSoftCo/pocket/actions")))
        }
    }
    override fun onStart(){super.onStart();PocketVoice.foreground=true;if(Pocket.local&&Pocket.token.isNotBlank())LocalMonitorService.start(this);if(Pocket.token.isNotBlank())PocketLive.start()}
    override fun onStop(){PocketVoice.foreground=false;if(!Pocket.local&&!PocketVoice.active)PocketLive.stop();super.onStop()}
    override fun onKeyDown(keyCode:Int,event:android.view.KeyEvent):Boolean=PocketVoice.key(event)||super.onKeyDown(keyCode,event)
    override fun onKeyUp(keyCode:Int,event:android.view.KeyEvent):Boolean=PocketVoice.key(event)||super.onKeyUp(keyCode,event)
    override fun onResume(){super.onResume();if(Pocket.token.isNotBlank()){Pocket.refresh();Pocket.refreshDetail()}}
}

@Composable fun Mark(size:Int=44){AnimatedMark(size)}
@Composable fun Label(text:String,color:Color=Muted){Text(text.uppercase(),color=color,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.8.sp)}
@Composable fun ErrorBanner(){if(Pocket.error.isNotBlank())Surface(color=Coral.copy(alpha=.12f),shape=RoundedCornerShape(6.dp),modifier=Modifier.fillMaxWidth().padding(vertical=8.dp)){Column(Modifier.padding(16.dp)){Text(Pocket.error,color=Coral,fontSize=13.sp,lineHeight=19.sp);if(Pocket.token.isNotBlank())TextButton({Pocket.retryConnection()}){Text("Reload conversation",color=Mint)}}}}
@Composable fun ConnectionNotice(){
    if(Pocket.connected&&Pocket.codexOnline)return
    if(!Pocket.connected&&!PocketLive.outageVisible)return
    val place=if(Pocket.local)"this phone" else "your workstation"
    Surface(color=Panel,modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(horizontal=18.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Text(if(Pocket.connected)"Waiting for Codex on $place" else "Reconnecting live updates",color=Coral,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            Text(if(Pocket.connected)Pocket.codexConnectionMessage.ifBlank{"NextComp reached ${Pocket.host}, but Codex is not responding there. Retrying automatically."} else "The live update channel is reconnecting. This does not cancel your conversations or messages.",color=Muted,fontSize=12.sp,lineHeight=17.sp)
            TextButton({Pocket.retryConnection()},contentPadding=PaddingValues(0.dp)){Text("Retry now",color=Mint)}
        }
    }
}
@Composable fun PairScreen(server:String,code:String){
    var address by remember(server){mutableStateOf(server)};var pin by remember(code){mutableStateOf(code)}
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(22.dp)){
        Spacer(Modifier.height(34.dp));Mark(70);Spacer(Modifier.height(18.dp));Label("CODEX, WITH YOU",Mint)
        Text("Good work.\nWithin reach.",fontSize=46.sp,lineHeight=49.sp,fontWeight=FontWeight.Medium,letterSpacing=(-1.8).sp)
        Text("Your tasks, updates and next ideas.\nConnected to Codex here or on your workstation.",color=Muted,fontSize=17.sp,lineHeight=25.sp)
        Spacer(Modifier.height(14.dp));OutlinedTextField(address,{address=it},label={Text("NextComp server address")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(6.dp))
        OutlinedTextField(pin,{pin=it},label={Text("One-time pairing code")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(6.dp))
        ErrorBanner();Button({Pocket.pair(address,pin)},enabled=!Pocket.busy&&address.isNotBlank()&&pin.isNotBlank(),modifier=Modifier.fillMaxWidth().height(58.dp),shape=RoundedCornerShape(6.dp)){if(Pocket.busy)CircularProgressIndicator(Modifier.size(22.dp),color=Ink,strokeWidth=2.dp)else{Text("Connect Codex",fontWeight=FontWeight.Bold);Spacer(Modifier.width(12.dp));SymbolIcon(Icons.AutoMirrored.Rounded.ArrowForward,null)}}
        if(Pocket.token.isNotBlank())TextButton({Pocket.pairingMode=false},modifier=Modifier.fillMaxWidth()){Text("Cancel",color=Muted)}

    }
}

@Composable fun PocketApp(){
    if(PocketVoice.active){VoiceScreen();return}

    var workToolsOpen by remember{mutableStateOf(false)}
    BackHandler(Pocket.selected!=null||Pocket.newTask){if(Pocket.newTask)Pocket.newTask=false else Pocket.closeTask()}
    Column(Modifier.fillMaxSize()){
        ConnectionNotice()
        PhoneControlNotice()
        if(Pocket.newTask)NewTaskScreen()
        else if(Pocket.selected!=null)key(Pocket.selected){ConversationScreen()}
        else{
            AnimatedContent(targetState=Pocket.tab,modifier=Modifier.weight(1f).fillMaxWidth(),label="tab",transitionSpec={
                (fadeIn(tween(180))+slideInHorizontally(tween(220)){it/12}) togetherWith (fadeOut(tween(100))+slideOutHorizontally(tween(180)){-it/12}) using SizeTransform(clip=false)
            }){tab->when(tab){0->WorkScreen(workToolsOpen,{workToolsOpen=it});else->SettingsScreen()}}
            SpeechCaptionBanner()
            SpeechPlayer()
            PocketDock(onFind={Pocket.tab=0;workToolsOpen=true},onTab={workToolsOpen=false})
        }
    }
}
@Composable fun PocketDock(onFind:()->Unit,onTab:()->Unit){
    val rows=if(LocalDensity.current.fontScale>1.4f)listOf(0..2,3..4)else listOf(0..4)
    Column(Modifier.fillMaxWidth().padding(horizontal=deviceCornerInset(),vertical=8.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xff101114)).border(1.dp,Paper.copy(alpha=.10f),RoundedCornerShape(24.dp)).padding(4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
        rows.forEach{range->Row(Modifier.fillMaxWidth().height(usageDockHeight()),horizontalArrangement=Arrangement.spacedBy(2.dp)){
            range.forEach{index->when(index){
                0,1->{val (title,icon)=listOf("Work" to Icons.Rounded.Layers,"Settings" to Icons.Rounded.Tune)[index]
                    DockButton(title,icon,Modifier.weight(1f),selected=Pocket.tab==index){onTab();Pocket.tab=index;Pocket.refresh()}}
                2->DockButton("Find",Icons.Rounded.Search,Modifier.weight(1f),primary=true,onClick=onFind)
                3->VoiceLaunchButton(modifier=Modifier.weight(1f),dock=true)
                else->UsageDock(Modifier.weight(1f))
            }}
        }}
    }
}
@Composable fun PhoneControlNotice(){
    if(!Pocket.local||!PocketAutomation.allowed)return
    val c=LocalContext.current
    var enabled by remember{mutableStateOf(PocketAutomation.systemEnabled(c)||PocketAutomation.connected)}
    LaunchedEffect(Pocket.local){while(true){enabled=PocketAutomation.systemEnabled(c)||PocketAutomation.connected;delay(1500)}}
    if(!enabled)Surface(color=Coral.copy(alpha=.12f),modifier=Modifier.fillMaxWidth()){
        Row(Modifier.padding(horizontal=18.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text("Phone control needs Android access",color=Coral,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Text("An update turned off NextComp’s Accessibility service.",color=Muted,fontSize=11.sp)}
            TextButton({c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("Restore",color=Mint)}
        }
    }
}
@Composable fun SpeechPlayer(){
    if(PocketSpeech.count==0)return
    var discard by remember{mutableStateOf(false)}
    Surface(color=Panel,modifier=Modifier.fillMaxWidth()){
        Row(Modifier.padding(horizontal=18.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(if(PocketSpeech.paused)"Speech paused · ${PocketSpeech.count} saved" else "Listening · ${PocketSpeech.count} queued",color=Mint,fontSize=12.sp)
                Text(if(PocketSpeech.paused)PocketSpeech.status else PocketSpeech.title,color=Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            TextButton({PocketSpeech.control(if(PocketSpeech.paused)"resume" else "pause")}){Text(if(PocketSpeech.paused)"Resume" else "Pause")}
            if(PocketSpeech.paused)Box{
                IconButton({discard=true}){SymbolIcon(Icons.Rounded.MoreVert,"Speech options",tint=Muted)}
                DropdownMenu(discard,{discard=false}){DropdownMenuItem(text={Text("Clear saved speech")},onClick={discard=false;PocketSpeech.clear()})}
            }
        }
    }
}

@Composable fun ConnectionPill(){val ok=Pocket.connected&&Pocket.codexOnline;Row(Modifier.clip(CircleShape).background(Panel).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){Box(Modifier.size(6.dp).background(if(ok)Mint else Coral,CircleShape));Text(if(ok)"Connected" else if(Pocket.connected)"Codex offline" else "Reconnecting",fontSize=11.sp,color=if(ok)Mint else Coral)}}
@Composable fun ProfileSwitcher(){
    if(Pocket.savedToken(false).isBlank()||Pocket.savedToken(true).isBlank())return
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        FilterChip(selected=!Pocket.local,onClick={Pocket.activate(false)},label={Text("Workstation")},leadingIcon={SymbolIcon(Icons.Rounded.Computer,null,Modifier.size(16.dp))},modifier=Modifier.weight(1f))
        FilterChip(selected=Pocket.local,onClick={Pocket.activate(true)},label={Text("This phone")},leadingIcon={SymbolIcon(Icons.Rounded.PhoneAndroid,null,Modifier.size(16.dp))},modifier=Modifier.weight(1f))
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WorkScreen(toolsOpen:Boolean=false,onToolsOpen:(Boolean)->Unit={}){
    LaunchedEffect(Pocket.local,Pocket.token){while(true){delay(5000);if(Pocket.connected)Pocket.refresh()}}
    var filter by remember{mutableIntStateOf(if(Pocket.showArchived)3 else 0)};var query by remember{mutableStateOf("")}
    val listState=rememberLazyListState()
    var touching by remember{mutableStateOf(false)}
    var displayed by remember(Pocket.local,Pocket.token,Pocket.showArchived){mutableStateOf(Pocket.tasks)}
    var order by remember(Pocket.local,Pocket.token,Pocket.showArchived){mutableStateOf((try{org.json.JSONArray(Pocket.prefs.getString(Pocket.key("sessionOrder"),"[]")).let{a->(0 until a.length()).map{a.getString(it)}}}catch(_:Exception){emptyList()}).ifEmpty{Pocket.tasks.sortedByDescending{if(it.updated<100000000000L)it.updated*1000 else it.updated}.map{it.id}})}
    LaunchedEffect(Pocket.local,Pocket.token,Pocket.showArchived){
        val pacing=SessionListPacing();var observed=Pocket.tasks.associateBy{it.id}
        while(true){
            val latest=Pocket.tasks;val now=android.os.SystemClock.elapsedRealtime()
            val changed=latest.count{observed[it.id]!=it};observed=latest.associateBy{it.id}
            pacing.record(now,changed)
            val active=latest.count{it.status=="active"}
            if(pacing.contentDue(now,active)){displayed=latest;pacing.contentDelivered(now)}
            if(pacing.orderDue(now,active,listState.isScrollInProgress||touching||toolsOpen)){
                // Keep equal timestamps stable; bottom-first layout retains its visible keyed anchor.
                val next=stableSessionOrder(order,latest.map{SessionRank(it.id,if(it.updated<100000000000L)it.updated*1000 else it.updated,it.status=="active")},System.currentTimeMillis())
                if(next!=order){order=next;Pocket.prefs.edit().putString(Pocket.key("sessionOrder"),org.json.JSONArray(next).toString()).apply()}
                pacing.orderDelivered(now)
            }
            delay(100)
        }
    }
    val rank=order.withIndex().associate{it.value to it.index}
    val shown=displayed.sortedBy{rank[it.id]?:Int.MAX_VALUE}.filter{(filter!=1||it.status=="active")&&(filter!=2||it.watched)&&(query.isBlank()||it.title.contains(query,true)||it.cwd.contains(query,true)||it.preview.contains(query,true))}
    Column(Modifier.fillMaxSize()){
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=16.dp).pointerInput(Unit){
        awaitPointerEventScope{try{while(true){touching=awaitPointerEvent(PointerEventPass.Initial).changes.any{it.pressed}}}finally{touching=false}}
    },state=listState,reverseLayout=true,verticalArrangement=Arrangement.spacedBy(8.dp,Alignment.Bottom),contentPadding=PaddingValues(top=12.dp,bottom=12.dp)){

        item{ErrorBanner()}

        val attention=Pocket.attention.filter{!PocketAttention.dismissed(it.optLong("id"))}
        if(attention.isNotEmpty()){item{Label("NEEDS YOU · ${attention.size}",Coral)};items(attention,key={"attention-${it.optLong("id")}"}){AttentionCard(it)}}
        if(shown.isEmpty())item{Empty("No sessions here",if(query.isNotBlank())"Try another name or project." else "Start a task from your phone.")}
        items(shown,key={it.id}){TaskCard(it)}
    }

    }
    if(toolsOpen)ModalBottomSheet(onDismissRequest={onToolsOpen(false)},containerColor=Panel){
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=16.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(query,{query=it},placeholder={Text("Find sessions")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Recent","Working","Following","Archived").forEachIndexed{i,title->FilterChip(selected=filter==i,onClick={filter=i;if(Pocket.showArchived!=(i==3)){Pocket.showArchived=i==3;Pocket.tasks=emptyList();Pocket.refresh()}},label={Text(title)})}}
            ProfileSwitcher()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Button({onToolsOpen(false);Pocket.composeTask()},modifier=Modifier.weight(1f).height(56.dp)){Text("New task")}
                FilledTonalButton({onToolsOpen(false)},modifier=Modifier.weight(1f).height(56.dp)){Text("Show sessions")}
            }
        }
    }
}

fun project(cwd:String)=cwd.trimEnd('/').substringAfterLast('/').ifBlank{"Workspace"}
fun relative(time:Long):String{val seconds=(System.currentTimeMillis()-(if(time<100000000000L)time*1000 else time))/1000;return when{seconds<60->"now";seconds<3600->"${seconds/60}m";seconds<86400->"${seconds/3600}h";else->"${seconds/86400}d"}}
fun lastActivity(time:Long):String{
    if(time<=0)return "unknown"
    val millis=if(time<100000000000L)time*1000 else time
    return if(System.currentTimeMillis()-millis<7*86400000L)relative(time) else java.text.SimpleDateFormat(if(System.currentTimeMillis()-millis<365*86400000L)"MMM d" else "MMM yy",java.util.Locale.getDefault()).format(java.util.Date(millis))
}
fun sessionAgeColor(time:Long,now:Long=System.currentTimeMillis()):Color{
    val millis=if(time<100000000000L)time*1000 else time
    val days=((now-millis).coerceAtLeast(0)/86400000.0)
    val p=if(days<=1)days*0.35 else 0.35+0.65*(kotlin.math.ln(days)/kotlin.math.ln(90.0)).coerceIn(0.0,1.0)
    return when{p<=0.35->androidx.compose.ui.graphics.lerp(NextGreen,Mint,(p/0.35).toFloat());p<=0.7->androidx.compose.ui.graphics.lerp(Mint,Coral,((p-0.35)/0.35).toFloat());else->androidx.compose.ui.graphics.lerp(Coral,NextCerise,((p-0.7)/0.3).toFloat())}
}
@Composable fun TaskCard(t:Task){
    val active=t.status=="active";val ageColor=sessionAgeColor(t.updated)
    LaunchedEffect(t.preview,PocketImmersion.enabled){PocketImmersion.offer("card:"+t.id,t.preview,if(t.previewKind=="thinking")"public reasoning summary" else "session activity")}
    val previewHeight=with(LocalDensity.current){40.sp.toDp()}.coerceAtLeast(48.dp)
    var rename by remember(t.id){mutableStateOf(false)};var name by remember(t.id){mutableStateOf(t.title)}
    val highlight=remember(t.id){Animatable(0f)}
    val revision=Triple(t.preview,t.status,t.updated)
    var observed by remember(t.id){mutableStateOf(revision)}
    LaunchedEffect(revision){if(observed!=revision){observed=revision;highlight.snapTo(1f);highlight.animateTo(0f,tween(durationMillis=1800,delayMillis=700))}}
    val shape=RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(Panel).drawBehind{
        drawRoundRect(Brush.radialGradient(colors=listOf(ageColor.copy(alpha=.26f),ageColor.copy(alpha=.06f),Color.Transparent),center=Offset(size.width*.85f,0f),radius=size.width*.95f),cornerRadius=CornerRadius(8.dp.toPx()))
    }.border(1.dp,Line.copy(alpha=.7f),shape).border(2.dp,Mint.copy(alpha=highlight.value),shape)){
        if(!t.archived)Row(Modifier.matchParentSize()){
            VoiceLaunchButton(modifier=Modifier.weight(.22f).fillMaxHeight(),threadId=t.id,cardRegion=true)
            CardInteractionRegion("Chat",Modifier.weight(.56f).fillMaxHeight()){Pocket.open(t.id)}
            CardInteractionRegion("Keyboard",Modifier.weight(.22f).fillMaxHeight()){Pocket.open(t.id,keyboard=true)}
        }
        Column(Modifier.padding(start=12.dp,end=12.dp,top=2.dp,bottom=4.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            if(rename){
                val focus=remember{androidx.compose.ui.focus.FocusRequester()}
                LaunchedEffect(Unit){focus.requestFocus()}
                OutlinedTextField(name,{name=it.take(120)},singleLine=true,modifier=Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent{event->if(event.key==Key.Enter&&event.type==KeyEventType.KeyDown){if(name.trim().isNotEmpty())Pocket.renameTask(t.id,name.trim()){rename=false};true}else false},textStyle=androidx.compose.ui.text.TextStyle(fontSize=16.sp,color=Paper),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Done),keyboardActions=androidx.compose.foundation.text.KeyboardActions(onDone={if(name.trim().isNotEmpty())Pocket.renameTask(t.id,name.trim()){rename=false}}))
            }else Box(Modifier.weight(1f).heightIn(min=48.dp),contentAlignment=Alignment.CenterStart){
                Text(t.title,color=Paper,fontSize=16.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.clickable{name=t.title;rename=true})
            }
            val updatedMillis=if(t.updated<100000000000L)t.updated*1000 else t.updated
            if(System.currentTimeMillis()-updatedMillis>=3600000L)Text(lastActivity(t.updated),color=Muted,fontSize=10.sp,modifier=Modifier.semantics{contentDescription="Last active "+lastActivity(t.updated)}.widthIn(max=70.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
            Box(Modifier.size(24.dp)){if(active)SymbolIcon(if(t.previewKind=="thinking")"Psychology" else "Codex","Working",Modifier.fillMaxSize(),spinning=true)}
            Surface(onClick={Pocket.watchTask(t.id,!t.watched)},modifier=Modifier.size(48.dp).semantics{contentDescription=if(t.watched)"Notifications on; tap to turn off" else "Notifications off; tap to turn on"},color=if(t.watched)Mint.copy(alpha=.2f)else Ink,shape=RoundedCornerShape(12.dp)){
                Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                    SymbolIcon(if(t.watched)Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,null,modifier=Modifier.size(24.dp),tint=if(t.watched)Paper else Muted.copy(alpha=.65f))
                    Text(if(t.watched)"On" else "Off",fontSize=10.sp,lineHeight=12.sp,color=if(t.watched)Mint else Muted)
                }
            }
            if(t.archived)IconButton({Pocket.restoreTask(t.id)},modifier=Modifier.size(48.dp)){SymbolIcon(Icons.Rounded.Unarchive,"Restore conversation",tint=Mint,modifier=Modifier.size(18.dp))}

        }
        Row(Modifier.fillMaxWidth().height(previewHeight),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
            Box(Modifier.width(18.dp).height(previewHeight)){
            if(t.previewRole in listOf("user","assistant"))SymbolIcon(if(t.previewRole=="user")"User" else "Codex",null,Modifier.size(18.dp))
            else if(t.previewRole=="activity")SymbolIcon(when(t.previewKind){"command"->Icons.Rounded.Terminal;"edit"->Icons.Rounded.EditNote;"search"->Icons.Rounded.TravelExplore;"thinking"->Icons.Rounded.Psychology;else->Icons.Rounded.Build},null,tint=Mint,modifier=Modifier.size(18.dp))
            }
            MarkdownPreview(PocketImmersion.display("card:"+t.id,t.preview),color=Paper,fontSize=14.sp,lineHeight=20.sp,minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
        }

    }
    }

}
@Composable fun Empty(title:String,body:String){Column(Modifier.fillMaxWidth().padding(vertical=45.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){SymbolIcon(Icons.Rounded.Inbox,null,tint=Muted,modifier=Modifier.size(40.dp));Text(title,fontSize=19.sp);Text(body,color=Muted,fontSize=13.sp)}}
@Composable fun AttentionCard(n:JSONObject){
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Coral.copy(alpha=.12f)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
        Label(when(n.s("kind")){"approval"->"REVIEW A REQUEST";"question"->"ANSWER A QUESTION";else->"CHECK A PROBLEM"},Coral)
        Text(n.s("title"),fontSize=17.sp,fontWeight=FontWeight.Medium)
        Text(n.s("body"),color=Muted,fontSize=13.sp,lineHeight=19.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            TextButton({Pocket.open(n.s("thread_id"))}){Text("Review",color=Mint)}
            if(n.s("kind")=="question")TextButton({PocketQuestionActions.skipNotification(n.optLong("id"),Pocket.local)}){Text("Skip",color=Mint)}
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
@Composable fun TaskPermissionsControl(){
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text("Full permissions for new tasks",modifier=Modifier.weight(1f));Switch(checked=Pocket.fullPermissions,onCheckedChange={Pocket.updateFullPermissions(it)},enabled=!Pocket.starting)}
        Text(if(Pocket.fullPermissions)"Full device access. Commands run without approval prompts." else if(Pocket.local)"Commands can ask for approval. Phone tasks retain full filesystem access." else "Workspace access. Commands can ask for approval.",color=Muted,fontSize=12.sp,lineHeight=18.sp)
        Text("Applies to future tasks on the workstation and this phone.",color=Muted,fontSize=12.sp)
    }
}
@Composable fun SettingsScreen(){val c=LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(23.dp)){
        TaskPermissionsControl()
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Italian immersion",modifier=Modifier.weight(1f));Switch(PocketImmersion.enabled,{PocketImmersion.setEnabled(it)})}
        if(PocketImmersion.unavailable)Text("Translation unavailable · showing originals",color=Muted,fontSize=12.sp)
        Label("MADE TO BE YOURS",Mint);Text("Your connection.",fontSize=34.sp,letterSpacing=(-1).sp)
        Surface(color=Panel,shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            ConnectionPill();Text(Pocket.host,fontSize=23.sp);Text(Pocket.base,color=Muted,fontSize=12.sp)
            if(Pocket.savedToken(false).isNotBlank()&&Pocket.savedToken(true).isNotBlank())Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=!Pocket.local,onClick={Pocket.activate(false)},label={Text("Workstation")})
                FilterChip(selected=Pocket.local,onClick={Pocket.activate(true)},label={Text("This phone")})
            }
            if(Pocket.savedToken(true).isBlank())OutlinedButton({Pocket.pairingMode=true},modifier=Modifier.fillMaxWidth()){Text("Connect Codex on this phone")}
            HorizontalDivider(color=Line);Text(Pocket.pushStatus,fontSize=16.sp,color=Mint)
            Text(if(Pocket.local)"NextComp connects over this phone’s loopback interface. A visible monitor keeps local task results and requests flowing while the app is closed." else "Notifications arrive through Firebase, even when NextComp is closed. Conversations and files load from your workstation.",color=Muted,fontSize=14.sp,lineHeight=21.sp)
        }}
        if(!Pocket.connected||!Pocket.codexOnline){
            if(Pocket.connectionError.isNotBlank())Text(Pocket.connectionError,color=Coral,fontSize=13.sp,lineHeight=20.sp)
            if(Pocket.refreshError.isNotBlank())Text("Background refresh: "+Pocket.refreshError,color=Muted,fontSize=12.sp,lineHeight=18.sp)
            OutlinedButton({Pocket.retryConnection()},modifier=Modifier.fillMaxWidth()){Text("Reconnect now")}
        }
        if(Pocket.savedToken(true).isNotBlank())PhoneControlSettings()
        NotificationAudioSettings()
        Button({Pocket.test()},modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(17.dp)){SymbolIcon(Icons.Rounded.NotificationsActive,null);Spacer(Modifier.width(10.dp));Text("Send a test notification")}
        OutlinedButton({c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName))},modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(17.dp)){Text("Notification settings")}
        Text(if(Pocket.local)"The local monitor uses no cloud push service. Keep its notification enabled while Codex is working on this phone." else "Keep notifications enabled. Tailscale connects you to your conversations and lets you reply. Firebase handles alerts without a permanent connection to the app.",color=Muted,fontSize=13.sp,lineHeight=20.sp)
        ErrorBanner();OutlinedButton({Pocket.disconnect()},modifier=Modifier.fillMaxWidth()){Text("Disconnect this phone",color=Coral)}
        Spacer(Modifier.height(12.dp));Label("NEXTCOMP ${BuildConfig.VERSION_NAME} · FALLSOFT")
    }
}

@Composable fun PhoneControlSettings(){val c=LocalContext.current;val systemEnabled=PocketAutomation.systemEnabled(c)||PocketAutomation.connected
    Surface(color=Panel,shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text("Control this phone",fontSize=21.sp,fontWeight=FontWeight.Medium);Text(if(systemEnabled)"Android access is enabled" else "Android access still needs to be enabled",color=if(systemEnabled)Mint else Coral,fontSize=12.sp)}
            Switch(PocketAutomation.allowed&&systemEnabled,{PocketAutomation.allowed=it},enabled=systemEnabled)
        }
        Text("When both controls are enabled, Codex on this phone can inspect the foreground screen, take screenshots, tap, scroll, enter non-password text, and use Back, Home or Recents.",color=Muted,fontSize=13.sp,lineHeight=20.sp)
        if(!systemEnabled)OutlinedButton({c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},modifier=Modifier.fillMaxWidth()){Text("Enable NextComp in Accessibility")}
        Text(if(PocketAutomation.allowed&&systemEnabled)"Phone control is live · pause it here at any time" else if(!systemEnabled&&PocketAutomation.allowed)"Android disabled the service · restore access above" else "Phone control is paused",color=if(PocketAutomation.allowed&&systemEnabled)Mint else if(!systemEnabled&&PocketAutomation.allowed)Coral else Muted,fontSize=12.sp)
        Text("Screen structure and screenshots are sent to your signed-in Codex only when its phone tools are used. Password fields are never returned or filled.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
    }}
}

@Composable fun NotificationAudioSettings(){
    LaunchedEffect(Unit){if(PocketAudio.mode=="voice")PocketAudio.prepareVoice()}
    Surface(color=Panel,shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Hear what happened.",fontSize=21.sp,fontWeight=FontWeight.Medium)
        Text("A different sound for results, questions, approvals and problems.",color=Muted,fontSize=13.sp,lineHeight=20.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("tones" to "Tones","voice" to "Labels","system" to "System").forEach{(value,label)->
                FilterChip(selected=PocketAudio.mode==value,onClick={PocketAudio.select(value)},label={Text(label,fontSize=12.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Mint,selectedLabelColor=Ink))
            }
        }
        FilterChip(selected=PocketAudio.mode=="summaries",onClick={PocketAudio.select("summaries")},label={Text("Speak messages")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Mint,selectedLabelColor=Ink))
        Text(when(PocketAudio.mode){"summaries"->"A short tone, then the complete spoken message in an offline voice. Pause and resume from the player or notification, including while locked. Other audio pauses speech and saves your place. Quiet mode and Do Not Disturb silence speech.";"voice"->"Short labels such as “Codex needs your input.” Task text stays on screen.";"system"->"Uses your phone’s default notification sound.";else->"Finished rises, problems fall, and questions have a distinct two-note cue."},color=Muted,fontSize=13.sp,lineHeight=20.sp)
        if(PocketAudio.status.isNotBlank())Text(PocketAudio.status,color=Mint,fontSize=12.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            listOf("complete" to "Finished","question" to "Question","error" to "Problem").forEach{(kind,label)->TextButton({PocketAudio.preview(kind)}){Text("▶ $label",color=Mint,fontSize=11.sp)}}
        }
        Text("Speech uses media volume; quiet mode, notification mute and Do Not Disturb still silence it.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
        HorizontalDivider(color=Line)
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Remind me when I’m needed",fontSize=14.sp);Text("Questions, approvals and problems only",color=Muted,fontSize=11.sp)};Switch(PocketAttention.enabled,{PocketAttention.toggle(it)})}
        Text("Up to three reminders: 5, 15, then 30 minutes apart. Dismiss, reply or resolve the request to stop. Later snoozes for 30 minutes.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
    }}
}

@Composable fun Attachment(a:JSONObject){val c=LocalContext.current
    fun open(){Pocket.scope.launch{try{val file=withContext(Dispatchers.IO){val folder=File(c.cacheDir,"shared");folder.mkdirs();val f=File(folder,a.s("id")+"-"+a.s("name").substringAfterLast('/'));val req=okhttp3.Request.Builder().url(Pocket.base+a.s("url")).header("Authorization","Bearer ${Pocket.token}").build();Pocket.http.newCall(req).execute().use{r->if(!r.isSuccessful)throw Exception("Could not download attachment");f.writeBytes(r.body!!.bytes())};f};val u=FileProvider.getUriForFile(c,"co.fallsoft.pocket.files",file);c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u,a.s("mime")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}catch(e:Exception){Pocket.error=e.message?:"No app can open this file"}}}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).clickable{open()},verticalArrangement=Arrangement.spacedBy(6.dp)){
        if(a.s("mime").startsWith("image/"))AsyncImage(model=ImageRequest.Builder(c).data(Pocket.base+a.s("url")).addHeader("Authorization","Bearer ${Pocket.token}").build(),contentDescription=a.s("name"),modifier=Modifier.fillMaxWidth().heightIn(min=100.dp,max=300.dp))
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){SymbolIcon(Icons.Rounded.AttachFile,null,tint=Mint,modifier=Modifier.size(18.dp));Text(a.s("name"),color=Mint,fontSize=12.sp,modifier=Modifier.padding(start=8.dp).weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);SymbolIcon(Icons.AutoMirrored.Rounded.OpenInNew,null,tint=Muted,modifier=Modifier.size(16.dp))}
    }
}
@Composable fun RequestCard(r:JSONObject){val p=r.optJSONObject("params")?:return;val id=r.s("id");val isQuestion=r.s("method").contains("requestUserInput");val answers=remember(id){mutableStateMapOf<String,String>()}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Coral.copy(alpha=.12f)).border(1.dp,Coral.copy(alpha=.3f),RoundedCornerShape(22.dp)).padding(19.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.weight(1f)){Label(if(isQuestion)"YOUR INPUT" else "NEEDS YOUR APPROVAL",Coral)}
            if(isQuestion)TextButton({PocketQuestionActions.skipRequest(id)},modifier=Modifier.heightIn(min=48.dp).widthIn(min=64.dp)){Text("Skip",color=Mint)}
        }
        if(isQuestion){p.optJSONArray("questions")?.objects()?.forEach{q->
            val questionId="request:$id:"+q.s("id")
            val prompt=q.s("question")
            LaunchedEffect(prompt,PocketImmersion.enabled){PocketImmersion.offer(questionId,prompt,"question")}
            MarkdownPreview(PocketImmersion.display(questionId,prompt),fontSize=16.sp,lineHeight=23.sp)
            q.optJSONArray("options")?.objects()?.forEachIndexed{index,o->
                val label=o.s("label");val optionId="$questionId:option:$index"
                val description=o.s("description")
                LaunchedEffect(label,description,PocketImmersion.enabled){PocketImmersion.offer(optionId,label,"question option");PocketImmersion.offer("$optionId:description",description,"question option explanation")}
                OutlinedButton({answers[q.s("id")]=label},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.outlinedButtonColors(containerColor=if(answers[q.s("id")]==label)Mint.copy(alpha=.14f)else Color.Transparent)){
                    Column(Modifier.fillMaxWidth()){
                        MarkdownPreview(PocketImmersion.display(optionId,label),color=Paper)
                        if(description.isNotBlank())MarkdownPreview(PocketImmersion.display("$optionId:description",description),color=Muted,fontSize=12.sp)
                    }
                }
            };OutlinedTextField(answers[q.s("id")]?:"",{answers[q.s("id")]=it},label={Text("Your answer")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp))};Button({val a=JSONObject();answers.forEach{(k,v)->a.put(k,v)};Pocket.answer(id,JSONObject().put("answers",a))}){Text("Send answers")}}
        else{Text(p.s("reason",p.s("message","Review this request before continuing.")),fontSize=15.sp);RichText(p.s("command",p.toString(2)));if(r.s("method").contains("commandExecution")||r.s("method").contains("fileChange")){Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Button({Pocket.answer(id,JSONObject().put("decision","accept"))}){Text("Allow once")};OutlinedButton({Pocket.answer(id,JSONObject().put("decision","decline"))}){Text("Decline",color=Coral)}}}else Text("Answer this request in the terminal.",color=Coral,fontSize=13.sp)}
    }
}

@Composable fun DockButton(label:String,icon:ImageVector,modifier:Modifier=Modifier,selected:Boolean=false,primary:Boolean=false,onClick:()->Unit){
    val color by animateColorAsState(if(selected||primary)Mint else Muted,label="dock tint")
    val surface by animateColorAsState(if(primary)Mint.copy(alpha=.15f) else if(selected)Paper.copy(alpha=.07f) else Color.Transparent,label="dock surface")
    Surface(onClick=onClick,modifier=modifier.height(usageDockHeight()),color=surface,shape=RoundedCornerShape(20.dp)){
        Column(Modifier.fillMaxSize().pressMotion().padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            SymbolIcon(icon,label,Modifier.size(if(primary)44.dp else 36.dp),tint=color)
            Spacer(Modifier.height(3.dp));Text(label,color=color,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
