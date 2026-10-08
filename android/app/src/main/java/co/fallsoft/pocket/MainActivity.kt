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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    private val blackout by lazy { ScreenBlackout(this) }
    fun enterBlackout(){blackout.enter()}
    private var initialServer by mutableStateOf("");private var initialCode by mutableStateOf("")
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT));readIntent(intent)
        if(Build.VERSION.SDK_INT>=33)permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
        if(Pocket.token.isNotBlank()){Pocket.refresh()}
        setContent{MaterialTheme(shapes=Shapes(extraSmall=RoundedCornerShape(2.dp),small=RoundedCornerShape(4.dp),medium=RoundedCornerShape(6.dp),large=RoundedCornerShape(8.dp),extraLarge=RoundedCornerShape(10.dp)),typography=Typography(titleLarge=androidx.compose.ui.text.TextStyle(fontFamily=AppFont,fontSize=20.sp,fontWeight=FontWeight.Medium),titleMedium=androidx.compose.ui.text.TextStyle(fontFamily=AppFont,fontSize=16.sp,fontWeight=FontWeight.Medium),bodyLarge=androidx.compose.ui.text.TextStyle(fontSize=15.sp,lineHeight=22.sp,fontFamily=AppFont),bodyMedium=androidx.compose.ui.text.TextStyle(fontSize=14.sp,lineHeight=20.sp,fontFamily=AppFont),labelLarge=androidx.compose.ui.text.TextStyle(fontSize=15.sp,fontWeight=FontWeight.Medium,fontFamily=AppFont)),colorScheme=darkColorScheme(primary=Mint,onPrimary=Ink,background=Ink,surface=Panel,onSurface=Paper,onBackground=Paper,outline=Muted,secondary=Coral,onSecondary=Ink,surfaceVariant=Panel,onSurfaceVariant=Paper,surfaceContainer=Panel,surfaceContainerHigh=Panel,surfaceContainerHighest=Panel,surfaceContainerLow=Panel,surfaceContainerLowest=Ink,secondaryContainer=Panel,onSecondaryContainer=Paper,primaryContainer=Panel,onPrimaryContainer=Paper,tertiary=NextCerise,onTertiary=Ink,tertiaryContainer=Panel,onTertiaryContainer=Paper)){
            CompositionLocalProvider(LocalImmersionMotionState provides rememberImmersionMotionEnvironment(),LocalImmersionTransitionAnimated provides motionAllowed()){
            Surface(Modifier.fillMaxSize(),color=Ink){Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
                Box(Modifier.weight(1f)){if(Pocket.token.isBlank()||Pocket.pairingMode)PairScreen(initialServer.ifBlank{if(Pocket.pairingMode)"http://127.0.0.1:18880" else ""},initialCode)else BackendApp()}
            }}}
        }}
    }
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {ImmersionInteraction.touch(event.actionMasked);return super.dispatchTouchEvent(event)}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);readIntent(intent)}
    private fun readIntent(i:Intent){
        if(i.getBooleanExtra("appUpdate",false)){i.removeExtra("appUpdate");if(!PocketVoice.active){Pocket.closeTask();Pocket.newTask=false;Pocket.tab=1};PocketUpdates.installOrCheck(this)}
        initialServer=i.getStringExtra("server")?:initialServer;initialCode=i.getStringExtra("code")?:initialCode
        i.data?.takeIf{it.scheme=="pocket"&&it.host=="pair"}?.let{initialServer=it.getQueryParameter("server")?:initialServer;initialCode=it.getQueryParameter("code")?:initialCode;Pocket.pairingMode=true}
        if(i.getBooleanExtra("coordinatorReport",false)){
            val origin=i.getBooleanExtra("local",Pocket.local)
            if(PocketVoice.active&&PocketVoice.state in setOf("Listening","Starting microphone","Finishing recording")){Pocket.error="Finish this recording before opening work updates";PocketVoice.problem=Pocket.error;return}
            if(origin!=Pocket.local)Pocket.activate(origin)
            if(Pocket.token.isNotBlank()){PocketWorkUpdates.open();getSystemService(android.app.NotificationManager::class.java).cancel(i.getIntExtra("reportNotificationId",0))}
            i.removeExtra("coordinatorReport");return
        }
        if(i.hasExtra("local"))Pocket.activate(i.getBooleanExtra("local",false))
        if(initialServer.isNotBlank()&&initialCode.isNotBlank())Pocket.pairingMode=true
        i.getStringExtra("thread")?.let{if(Pocket.token.isNotBlank())Pocket.open(it)}
        if(Pocket.token.isNotBlank()&&i.getStringExtra("operations_url")=="https://github.com/FallSoftCo/pocket/actions"){
            i.removeExtra("operations_url")
            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/FallSoftCo/pocket/actions")))
        }
    }
    override fun onStart(){super.onStart();if(Pocket.token.isNotBlank())PocketUpdates.check();PocketVoice.foreground=true;if(Pocket.local&&Pocket.token.isNotBlank())LocalMonitorService.start(this);if(Pocket.token.isNotBlank())PocketLive.start()}
    override fun onStop(){captureKeys.reset();blackout.exit(restoreInput=false);PocketVoice.foreground=false;if(!Pocket.local&&!PocketVoice.active)PocketLive.stop();super.onStop()}
    private val captureKeys get()=PocketVoice.captureKeys
    private var permissionCaptureThread:String?=null
    private var permissionCaptureInPlace=false
    private var permissionCaptureProfile=""
    private val capturePermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){allowed->
        if(allowed&&PocketVoice.foreground&&permissionCaptureProfile=="${Pocket.local}:${Pocket.base}:${Pocket.token}")PocketVoice.start(this,permissionCaptureThread,inPlace=permissionCaptureInPlace)
        else if(!allowed)PocketVoice.captureFailure("Allow microphone access to record. Your draft is kept.")
    }
    // Public platform Activity hook; AndroidX core redeclares it with a library restriction.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event:android.view.KeyEvent):Boolean{
        val volume=event.keyCode in setOf(android.view.KeyEvent.KEYCODE_VOLUME_DOWN,android.view.KeyEvent.KEYCODE_VOLUME_UP)
        val destination=currentForegroundCaptureTarget()
        if(volume&&foregroundCaptureKeys(PocketVoice.foreground,Pocket.token.isNotBlank()&&!Pocket.pairingMode,BackendNavigation.teamSelected(),destination.eligible)){
            if(event.action==android.view.KeyEvent.ACTION_DOWN&&captureKeys.down(event.keyCode,event.repeatCount)){
                val target=destination.threadId
                val here=true
                if(androidx.core.content.ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)PocketVoice.start(this,target,inPlace=here)
                else{permissionCaptureThread=target;permissionCaptureInPlace=here;permissionCaptureProfile="${Pocket.local}:${Pocket.base}:${Pocket.token}";capturePermission.launch(Manifest.permission.RECORD_AUDIO)}
            }else if(event.action==android.view.KeyEvent.ACTION_UP)captureKeys.up(event.keyCode)
            return true
        }
        if(event.action==android.view.KeyEvent.ACTION_UP)captureKeys.up(event.keyCode)
        return super.dispatchKeyEvent(event)
    }
    override fun onDestroy(){blackout.exit(restoreInput=false);super.onDestroy()}
    override fun onResume(){super.onResume();if(Pocket.token.isNotBlank()){Pocket.refresh();Pocket.refreshDetail()}}
}

@Composable fun Mark(size:Int=44){AnimatedMark(size)}
/** Public workflow prose shares the teacher's contextual inline immersion plan. */
@Composable fun WorkflowText(text:String,modifier:Modifier=Modifier,color:Color=Paper,fontSize:TextUnit=16.sp,lineHeight:TextUnit=TextUnit.Unspecified,fontWeight:FontWeight?=null,maxLines:Int=Int.MAX_VALUE,overflow:TextOverflow=TextOverflow.Clip,letterSpacing:TextUnit=TextUnit.Unspecified){
    ImmersionText("workflow:"+text,text,modifier=modifier,color=color,fontSize=fontSize,lineHeight=if(lineHeight==TextUnit.Unspecified)(fontSize.value*1.4f).sp else lineHeight,maxLines=maxLines,fontWeight=fontWeight,kind="workflow text",rescue=false,phraseRescue=true)
}
@Composable fun Label(text:String,color:Color=Muted){BilingualLabel(text,color=color,fontSize=10.sp,fontWeight=FontWeight.Bold,centered=false)}
@Composable fun ErrorBanner(){if(Pocket.error.isNotBlank())Surface(color=Coral.copy(alpha=.12f),shape=RoundedCornerShape(6.dp),modifier=Modifier.fillMaxWidth().padding(vertical=8.dp)){Column(Modifier.padding(16.dp)){WorkflowText(ConnectionMessages.server(Pocket.error),color=Coral,fontSize=13.sp,lineHeight=19.sp);if(Pocket.token.isNotBlank())TextButton({Pocket.retryConnection()}){BilingualLabel("Reload conversation",color=Mint)}}}}
@Composable fun ConnectionNotice(){
    if(Pocket.connected&&Pocket.codexOnline)return
    if(!Pocket.connected&&!PocketLive.outageVisible)return
    val place=if(Pocket.local)"this phone" else "your workstation"
    val draining=Pocket.codexConnectionMessage==ConnectionMessages.draining
    Surface(color=Panel,modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(horizontal=18.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            WorkflowText(if(draining)"Codex restarting on $place" else if(Pocket.connected)"Waiting for Codex on $place" else "Reconnecting live updates",color=Coral,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            WorkflowText(if(Pocket.connected)Pocket.codexConnectionMessage.ifBlank{"NextComp reached ${Pocket.host}, but Codex is not responding there. Retrying automatically."} else "The live update channel is reconnecting. This does not cancel your conversations or messages.",color=Muted,fontSize=12.sp,lineHeight=17.sp)
            TextButton({Pocket.retryConnection()},contentPadding=PaddingValues(0.dp)){BilingualLabel(if(draining)"Check connection" else "Retry now",color=Mint)}
        }
    }
}
@Composable fun PairScreen(server:String,code:String){
    var address by remember(server){mutableStateOf(server)};var pin by remember(code){mutableStateOf(code)}
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(22.dp)){
        Spacer(Modifier.height(34.dp));Mark(70);Spacer(Modifier.height(18.dp));Label("CODEX, WITH YOU",Mint)
        WorkflowText("Good work.\nWithin reach.",fontSize=46.sp,lineHeight=49.sp,fontWeight=FontWeight.Medium,letterSpacing=(-1.8).sp)
        WorkflowText("Your tasks, updates and next ideas.\nConnected to Codex here or on your workstation.",color=Muted,fontSize=17.sp,lineHeight=25.sp)
        Spacer(Modifier.height(14.dp));OutlinedTextField(address,{address=it},label={WorkflowText("NextComp server address")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(6.dp))
        OutlinedTextField(pin,{pin=it},label={WorkflowText("One-time pairing code")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(6.dp))
        ErrorBanner();Button({Pocket.pair(address,pin)},enabled=!Pocket.busy&&address.isNotBlank()&&pin.isNotBlank(),modifier=Modifier.fillMaxWidth().height(58.dp),shape=RoundedCornerShape(6.dp)){if(Pocket.busy)CircularProgressIndicator(Modifier.size(22.dp),color=Ink,strokeWidth=2.dp)else{BilingualLabel("Connect Codex",fontWeight=FontWeight.Bold);Spacer(Modifier.width(12.dp));SymbolIcon(Icons.AutoMirrored.Rounded.ArrowForward,null)}}
        if(Pocket.token.isNotBlank())TextButton({Pocket.pairingMode=false},modifier=Modifier.fillMaxWidth()){BilingualLabel("Cancel",color=Muted)}

    }
}

@Composable fun PocketApp(){
    if(PocketWorkUpdates.visible){WorkUpdatesScreen();return}
    if(PocketVoice.active&&!PocketVoice.inPlace){VoiceScreen();return}
    if(PocketCoordinator.visible){CoordinatorScreen();return}

    BackHandler(Pocket.tab==1&&Pocket.selected==null&&!Pocket.newTask){Pocket.tab=0}
    var workToolsOpen by remember{mutableStateOf(false)}
    val workListState=key(Pocket.local,Pocket.token,Pocket.showArchived){rememberLazyListState()}
    val workCatalogView=remember(Pocket.local,Pocket.token){SessionCatalogViewState(Pocket.showArchived)}
    BackHandler(Pocket.selected!=null||Pocket.newTask){if(Pocket.newTask)Pocket.newTask=false else Pocket.closeTask()}
    Column(Modifier.fillMaxSize()){
        ConnectionNotice()
        PhoneControlNotice()
        if(Pocket.newTask)NewTaskScreen()
        else if(Pocket.selected!=null)key(Pocket.selected){if(conversationIdentityPending())LoadingSessionIdentity()else if(readOnlyChildSelected())ChildConversationScreen()else ConversationScreen()}
        else{
            AnimatedContent(targetState=Pocket.tab,modifier=Modifier.weight(1f).fillMaxWidth(),label="tab",transitionSpec={
                (fadeIn(tween(180))+slideInHorizontally(tween(220)){it/12}) togetherWith (fadeOut(tween(100))+slideOutHorizontally(tween(180)){-it/12}) using SizeTransform(clip=false)
            }){tab->when(tab){0->WorkScreen(workToolsOpen,{workToolsOpen=it},workListState,workCatalogView);else->SettingsScreen()}}
            ConversationSpeechDock()
            InlineCaptureStatus()
            PocketDock(onFind={Pocket.tab=0;workToolsOpen=true},onTab={workToolsOpen=false})
        }
    }
}
@Composable fun PocketDock(onFind:()->Unit,onTab:()->Unit){
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xff101114)).border(1.dp,Paper.copy(alpha=.10f),RoundedCornerShape(24.dp)).padding(4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
        IconButton({Pocket.tab=0;onFind()},modifier=Modifier.size(48.dp)){SymbolIcon("Search","Find",Modifier.size(32.dp),tint=Mint)}
        VoiceLaunchButton(modifier=Modifier.width(64.dp),dock=true)
        InlineSpeechVolume(Modifier.weight(1f).widthIn(min=64.dp))
        UsageDock(Modifier.width(usageDockWidth()))
    }
}
@Composable fun PhoneControlNotice(){
    if(!Pocket.local||!PocketAutomation.allowed)return
    val c=LocalContext.current
    var enabled by remember{mutableStateOf(PocketAutomation.systemEnabled(c)||PocketAutomation.connected)}
    LaunchedEffect(Pocket.local){while(true){enabled=PocketAutomation.systemEnabled(c)||PocketAutomation.connected;delay(1500)}}
    if(!enabled)Surface(color=Coral.copy(alpha=.12f),modifier=Modifier.fillMaxWidth()){
        Row(Modifier.padding(horizontal=18.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){WorkflowText("Phone control needs Android access",color=Coral,fontSize=13.sp,fontWeight=FontWeight.SemiBold);WorkflowText("An update turned off NextComp’s Accessibility service.",color=Muted,fontSize=11.sp)}
            TextButton({c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){BilingualLabel("Restore",color=Mint)}
        }
    }
}
@Composable fun SpeechPlayer(containerColor:androidx.compose.ui.graphics.Color=Panel){
    if(PocketSpeech.count==0)return
    Surface(color=containerColor,modifier=Modifier.fillMaxWidth()){
        Row(Modifier.padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
            Box(Modifier.weight(1f)){SpeechSourceLabel()}
            SpeechPlaybackControls()
        }
    }
}

@Composable fun SpeechSourceLabel(){
    var sourceOpen by remember{mutableStateOf(false)}
    Box{
        TextButton({sourceOpen=true},contentPadding=PaddingValues(horizontal=0.dp),modifier=Modifier.heightIn(min=48.dp)){
            WorkflowText(PocketSpeech.title.ifBlank{"Choose speech session"}+" · "+(if(PocketSpeech.activeCount>0)PocketSpeech.activeCount else PocketSpeech.count)+" ▾",color=if(PocketSpeech.paused)Muted else Mint,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded=sourceOpen,onDismissRequest={sourceOpen=false},properties=androidx.compose.ui.window.PopupProperties(focusable=false)){
            PocketSpeech.sources.forEach{(source,details)->DropdownMenuItem(text={Text("${if(source==PocketSpeech.activeSource)"• " else ""}${details.first} · ${details.second}")},onClick={sourceOpen=false;PocketSpeech.selectSource(source)})}
            DropdownMenuItem(text={Text("Clear all saved speech")},onClick={sourceOpen=false;PocketSpeech.clearAll()})
        }
    }
}

@Composable fun ConnectionPill(){val ok=Pocket.connected&&Pocket.codexOnline;Row(Modifier.clip(CircleShape).background(Panel).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){Box(Modifier.size(6.dp).background(if(ok)Mint else Coral,CircleShape));BilingualLabel(if(ok)"Connected" else if(Pocket.connected)"Codex offline" else "Reconnecting",fontSize=11.sp,color=if(ok)Mint else Coral)}}
@Composable fun ProfileSwitcher(){
    if(Pocket.savedToken(false).isBlank()||Pocket.savedToken(true).isBlank())return
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        FilterChip(selected=!Pocket.local,onClick={Pocket.activate(false)},label={BilingualLabel("Workstation")},leadingIcon={SymbolIcon(Icons.Rounded.Computer,null,Modifier.size(16.dp))},modifier=Modifier.weight(1f))
        FilterChip(selected=Pocket.local,onClick={Pocket.activate(true)},label={BilingualLabel("This phone")},leadingIcon={SymbolIcon(Icons.Rounded.PhoneAndroid,null,Modifier.size(16.dp))},modifier=Modifier.weight(1f))
    }
}
class SessionCatalogViewState(archived:Boolean=false){val filter=mutableIntStateOf(if(archived)3 else 0);val query=mutableStateOf("")}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WorkScreen(toolsOpen:Boolean=false,onToolsOpen:(Boolean)->Unit={},listState:androidx.compose.foundation.lazy.LazyListState=rememberLazyListState(),view:SessionCatalogViewState=remember{SessionCatalogViewState(Pocket.showArchived)}){
    LaunchedEffect(Pocket.local,Pocket.token){while(true){delay(5000);if(Pocket.connected)Pocket.refresh()}}
    var filter by view.filter;var query by view.query
    var touching by remember{mutableStateOf(false)}
    var listClock by remember{mutableLongStateOf(System.currentTimeMillis())}
    var displayed by remember(Pocket.local,Pocket.token,Pocket.showArchived){mutableStateOf(Pocket.tasks)}
    val orderKey=Pocket.key(if(Pocket.showArchived)"archivedWorkActivityOrderV2" else "workActivityOrderV2")
    var order by remember(Pocket.local,Pocket.token,Pocket.showArchived){mutableStateOf((try{org.json.JSONArray(Pocket.prefs.getString(orderKey,"[]")).let{a->(0 until a.length()).map{a.getString(it)}}}catch(_:Exception){emptyList()}).ifEmpty{Pocket.tasks.sortedByDescending{taskWorkTime(it)}.map{it.id}})}
    LaunchedEffect(Pocket.local,Pocket.token,Pocket.showArchived){
        val pacing=SessionListPacing();var observed=Pocket.tasks.associateBy{it.id}
        while(true){
            val latest=Pocket.tasks;val now=android.os.SystemClock.elapsedRealtime()
            if(System.currentTimeMillis()-listClock>=5_000)listClock=System.currentTimeMillis()
            val changed=latest.count{observed[it.id]!=it};observed=latest.associateBy{it.id}
            pacing.record(now,changed)
            val active=latest.count{it.status in listOf("active","pending")}
            val interacting=listState.isScrollInProgress||touching||toolsOpen
            val pacedOrderDue=pacing.orderDue(now,active,interacting)
            val explicitBoundary=sessionReconcileAllowed(Pocket.sessionOrderRequest!=Pocket.sessionOrderHandled,Pocket.sessionSnapshotComplete,Pocket.connected,Pocket.codexOnline,pacing.interactionSettled(now,interacting))
            val orderDue=(pacedOrderDue&&Pocket.sessionSnapshotComplete&&Pocket.connected&&Pocket.codexOnline)||explicitBoundary
            if(pacing.contentDue(now,active)){
                // Deliver live text/status in place; membership changes wait for a safe moment.
                displayed=sessionContentInPlace(displayed,latest){it.id};pacing.contentDelivered(now)
            }
            if(orderDue){
                // Safe bounded delivery promotes genuine activity; live peers remain tied.
                val families=sessionGroups(latest).associateBy{it.task.id}
                val entries=latest.map{task->val children=families[task.id]?.children.orEmpty();SessionRank(task.id,maxOf(taskWorkTime(task),children.maxOfOrNull{taskWorkTime(it)}?:0),task.status in listOf("active","pending")||children.any{it.status=="active"})}
                val next=if(Pocket.showArchived)stableSessionOrder(order,entries,System.currentTimeMillis()) else liveSessionOrder(order,entries,System.currentTimeMillis(),explicitBoundary)
                if(next!=order)order=next
                val encoded=org.json.JSONArray(next).toString()
                if(Pocket.prefs.getString(orderKey,null)!=encoded)Pocket.prefs.edit().putString(orderKey,encoded).apply()
                displayed=latest
                Pocket.finishSessionOrderRequest(Pocket.sessionOrderRequest,latest.map{it.id}.toSet())
                if(explicitBoundary)listState.scrollToItem(0)
                pacing.orderDelivered(now)
            }
            delay(100)
        }
    }
    val rank=order.withIndex().associate{it.value to it.index}
    val showCoordinator=filter!=3&&(filter!=1||PocketCoordinator.busy)&&(query.isBlank()||"Coordinator".contains(query,true)||PocketCoordinator.preview.contains(query,true))
    LaunchedEffect(query,Pocket.local,Pocket.showArchived){if(query.isNotBlank()){delay(400);Pocket.moreSessions(query)}}
    val groups=sessionGroups(displayed.sortedBy{rank[it.id]?:Int.MAX_VALUE})
    fun matches(t:Task)=query.isBlank()||t.title.contains(query,true)||t.cwd.contains(query,true)||t.preview.contains(query,true)||t.catalogContext.contains(query,true)||t.agentNickname.contains(query,true)
    val shown=groups.filter{filter==4||query.isNotBlank()||meaningfulCatalogTask(it.task)||it.children.any(::meaningfulCatalogTask)}.sortedBy{rank[it.task.id]?:Int.MAX_VALUE}.filter{group->val t=group.task;(filter!=1||t.status in listOf("active","pending")||group.children.any{it.status=="active"})&&(filter!=2||t.watched||group.children.any{it.watched})&&(matches(t)||group.children.any(::matches))}
    val orphans=if(filter==4||query.isNotBlank())ungroupedChildren(displayed).filter(::matches).sortedByDescending{taskWorkTime(it)} else emptyList()
    Column(Modifier.fillMaxSize()){
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal=16.dp).pointerInput(Unit){
        awaitPointerEventScope{try{while(true){touching=awaitPointerEvent(PointerEventPass.Initial).changes.any{it.pressed}}}finally{touching=false}}
    },state=listState,reverseLayout=true,verticalArrangement=Arrangement.spacedBy(8.dp,Alignment.Bottom),contentPadding=PaddingValues(top=12.dp,bottom=12.dp)){

        item{ErrorBanner()}
        if(showCoordinator)item(key="nextcomp-coordinator"){CoordinatorCard()}

        val attention=Pocket.attention.filter{!PocketAttention.dismissed(it.optLong("id"))}
        if(attention.isNotEmpty()){item{Label("NEEDS YOU · ${attention.size}",Coral)};items(attention,key={"attention-${it.optLong("id")}"}){AttentionCard(it)}}
        if(shown.isEmpty()&&!showCoordinator)item{Empty("No sessions here",if(query.isNotBlank())"Try another name or project." else "Start a task from your phone.")}
        items(shown,key={it.task.id}){group->SessionGroupCard(if(query.isNotBlank()&&!matches(group.task))group.copy(children=group.children.filter(::matches))else group,listClock)}
        if(orphans.isNotEmpty()){item(key="unloaded-parent-agents"){Label("AGENT HISTORY · PARENT OUTSIDE THIS LIST",Muted)};items(orphans,key={it.id}){AgentResultCard(it)}}
        if(Pocket.sessionCursor!=null)item(key="older-session-page"){TextButton({Pocket.moreSessions()},enabled=!Pocket.loadingSessions){BilingualLabel(if(Pocket.loadingSessions)"Loading history…" else "Load older sessions")}}
    }
    WorkUpdatesEntry()

    }
    if(toolsOpen)ModalBottomSheet(onDismissRequest={onToolsOpen(false)},containerColor=Panel){
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=16.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(query,{query=it},placeholder={BilingualLabel("Find sessions",centered=false)},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Recent","Working","Following","Archived","History").forEachIndexed{i,title->FilterChip(selected=filter==i,onClick={filter=i;if(i==0)Pocket.requestSessionOrder();if(Pocket.showArchived!=(i==3)){Pocket.showArchived=i==3;Pocket.tasks=emptyList();Pocket.refresh()}},label={BilingualLabel(title)})}}
            if(Pocket.sessionCursor!=null)TextButton({Pocket.moreSessions()},enabled=!Pocket.loadingSessions){BilingualLabel(if(Pocket.loadingSessions)"Loading history…" else "Load older sessions")}
            ProfileSwitcher()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Button({onToolsOpen(false);Pocket.composeTask()},modifier=Modifier.weight(1f).heightIn(min=56.dp)){BilingualLabel("New task",maxLines=2)}
                FilledTonalButton({if(query.isBlank()&&filter==0)Pocket.requestSessionOrder();onToolsOpen(false)},modifier=Modifier.weight(1f).heightIn(min=56.dp)){BilingualLabel("Show sessions",maxLines=2)}
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
@Composable fun TaskCard(t:Task,now:Long=System.currentTimeMillis()){
    val active=t.status in listOf("active","pending");val activityTime=taskWorkTime(t);val ageColor=sessionAgeColor(activityTime,now)
    LaunchedEffect(t.preview,PocketImmersion.enabled){PocketImmersion.offer("card:"+t.id,t.preview,if(t.previewKind=="thinking")"public reasoning summary" else "session activity")}
    val previewHeight=with(LocalDensity.current){40.sp.toDp()}.coerceAtLeast(48.dp)
    var rename by remember(t.id){mutableStateOf(false)};var name by remember(t.id){mutableStateOf(t.title)}
    val highlight=remember(t.id){Animatable(0f)}
    val revision=Triple(t.preview,t.status,t.activityAt)
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
            }else Box(Modifier.weight(1f),contentAlignment=Alignment.CenterStart){
                LaunchedEffect(t.title,PocketImmersion.enabled){PocketImmersion.offer("session-title:"+t.id,t.title,"session title")}
                val titleConfiguration=androidx.compose.ui.platform.LocalViewConfiguration.current
                CompositionLocalProvider(androidx.compose.ui.platform.LocalViewConfiguration provides object:androidx.compose.ui.platform.ViewConfiguration by titleConfiguration {
                    override val minimumTouchTargetSize=androidx.compose.ui.unit.DpSize.Zero
                }){
                    BilingualLabel(t.title,color=Paper,fontSize=16.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium,maxLines=1,centered=false,onActivate={name=t.title;rename=true})
                }
            }
            val age=now-activityTime
            val ageLabel=if(age in 20_000..59_999)"${age/1000}s" else lastActivity(activityTime)
            if(age>=20_000)Text(ageLabel,color=Muted,fontSize=11.sp,modifier=Modifier.semantics{contentDescription="Last active "+ageLabel}.widthIn(max=70.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
            Box(Modifier.size(24.dp)){if(active)SymbolIcon(if(t.previewKind=="thinking")"Psychology" else "Codex",if(t.status=="pending")"Starting" else "Working",Modifier.fillMaxSize(),spinning=true) else SymbolIcon(if(t.needsInput)Icons.Rounded.HelpOutline else Icons.Rounded.Pause,if(t.needsInput)"Needs your input" else "Idle",tint=if(t.needsInput)Coral else Muted,modifier=Modifier.size(18.dp))}
            Surface(onClick={Pocket.watchTask(t.id,!t.watched)},modifier=Modifier.size(48.dp).semantics{contentDescription=if(t.watched)"Notifications on; tap to turn off" else "Notifications off; tap to turn on"},color=if(t.watched)Mint.copy(alpha=.2f)else Ink,shape=RoundedCornerShape(12.dp)){
                Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                    SymbolIcon(if(t.watched)Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,null,modifier=Modifier.size(24.dp),tint=if(t.watched)Paper else Muted.copy(alpha=.65f))
                    BilingualLabel(if(t.watched)"On" else "Off",fontSize=10.sp,color=if(t.watched)Mint else Muted)
                }
            }
            if(t.archived)IconButton({Pocket.restoreTask(t.id)},modifier=Modifier.size(48.dp)){SymbolIcon(Icons.Rounded.Unarchive,"Restore conversation",tint=Mint,modifier=Modifier.size(18.dp))}

        }
        Row(Modifier.fillMaxWidth().height(previewHeight),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
            Box(Modifier.width(18.dp).height(previewHeight)){
            if(t.previewRole in listOf("user","assistant"))SymbolIcon(if(t.previewRole=="user")"User" else "Codex",null,Modifier.size(18.dp))
            else if(t.previewRole=="activity")SymbolIcon(when(t.previewKind){"command"->Icons.Rounded.Terminal;"edit"->Icons.Rounded.EditNote;"search"->Icons.Rounded.TravelExplore;"thinking"->Icons.Rounded.Psychology;else->Icons.Rounded.Build},null,tint=Mint,modifier=Modifier.size(18.dp))
            }
            ImmersionText("card:"+t.id,if(!active&&t.catalogContext.isNotBlank())t.catalogContext else if(!active)"Idle · "+t.preview.ifBlank{"No messages yet"} else t.preview,color=if(t.needsInput&&!active)Coral else Paper,fontSize=14.sp,lineHeight=20.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f),rescue=false,phraseRescue=false,links=false)
        }

    }
    }

}
@Composable fun Empty(title:String,body:String){Column(Modifier.fillMaxWidth().padding(vertical=45.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){SymbolIcon(Icons.Rounded.Inbox,null,tint=Muted,modifier=Modifier.size(40.dp));WorkflowText(title,fontSize=19.sp);WorkflowText(body,color=Muted,fontSize=13.sp)}}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun AttentionCard(n:JSONObject){
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Coral.copy(alpha=.12f)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
        Column(Modifier.fillMaxWidth().clickable{Pocket.open(n.s("thread_id"))}){
        Label(when(n.s("kind")){"approval"->"REVIEW A REQUEST";"question"->"ANSWER A QUESTION";else->"CHECK A PROBLEM"},Coral)
        ImmersionText("notification-title:"+n.optLong("id"),n.s("title"),rescue=false,fontSize=17.sp,fontWeight=FontWeight.Medium)
        ImmersionText("notification-body:"+n.optLong("id"),n.s("body"),rescue=false,color=Muted,fontSize=13.sp,lineHeight=19.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
        }
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            TextButton({Pocket.open(n.s("thread_id"))}){BilingualLabel("Review",color=Mint)}
            val task=Pocket.tasks.firstOrNull{it.id==n.s("thread_id")};val direct=n.optBoolean("canAcceptDirectInput",task?.canAcceptDirectInput?:true)
            val target=if(direct)n.s("thread_id")else n.s("parentThreadId",task?.parentThreadId?:"")
            if(target.isNotBlank())TextButton({Pocket.open(target,keyboard=true)}){BilingualLabel(if(direct)"Reply" else "Guide parent",color=Mint)}
            if(n.s("kind")=="question")TextButton({PocketQuestionActions.skipNotification(n.optLong("id"),Pocket.local)}){BilingualLabel("Skip",color=Mint)}
            TextButton({PocketAttention.snooze(n.optLong("id"))}){BilingualLabel("Later · 30m",color=Muted)}
            TextButton({PocketAttention.dismiss(n.optLong("id"))}){BilingualLabel("Dismiss",color=Muted)}
        }
    }
}
@Composable fun UpdatesScreen(){LazyColumn(Modifier.fillMaxSize().padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=26.dp)){
    item{Label("THE MOMENTS THAT MATTER",Mint);Spacer(Modifier.height(12.dp));WorkflowText("Your updates.",fontSize=36.sp,letterSpacing=(-1).sp);Spacer(Modifier.height(8.dp));WorkflowText("Results, questions, and a way forward.",color=Muted,fontSize=15.sp)}
    if(Pocket.notifications.isEmpty())item{Empty("You’re all caught up","Ask Codex to notify you when something is ready.")}
    items(Pocket.notifications,key={it.optLong("id")}){n->Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).clickable{n.s("thread_id").takeIf{it.isNotBlank()}?.let{Pocket.open(it)}}.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row{Label(n.s("kind","update"),if(n.s("kind")=="error")Coral else Mint);Spacer(Modifier.weight(1f));Text(relative(n.optLong("created_at")),fontSize=11.sp,color=Muted)}
        ImmersionText("notification-title:"+n.optLong("id"),n.s("title"),rescue=false,fontSize=19.sp,fontWeight=FontWeight.Medium);ImmersionText("notification-body:"+n.optLong("id"),n.s("body"),rescue=false,fontSize=14.sp,color=Muted,lineHeight=21.sp,maxLines=5,overflow=TextOverflow.Ellipsis)
        n.optJSONArray("attachments")?.objects()?.forEach{Attachment(it)}
        if(n.s("thread_id").isNotBlank())WorkflowText("Open conversation  ↗",color=Mint,fontSize=12.sp)
    }}
}}
@Composable fun TaskPermissionsControl(){
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){WorkflowText("Full permissions for new tasks",modifier=Modifier.weight(1f));Switch(checked=Pocket.fullPermissions,onCheckedChange={Pocket.updateFullPermissions(it)},enabled=!Pocket.starting)}
        WorkflowText(if(Pocket.fullPermissions)"Full device access. Commands run without approval prompts." else if(Pocket.local)"Commands can ask for approval. Phone tasks retain full filesystem access." else "Workspace access. Commands can ask for approval.",color=Muted,fontSize=12.sp,lineHeight=18.sp)
        WorkflowText("Applies to future tasks on the workstation and this phone.",color=Muted,fontSize=12.sp)
    }
}
@Composable fun SettingsScreen(){val c=LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(23.dp)){
        BackendSettingsControl()
        PocketUpdateControl()
        OutlinedButton({c.nextCompActivity()?.enterBlackout()},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){BilingualLabel("Blackout · keep screen awake")}
        WorkflowText("Tap the black screen to return. Voice keeps working; Home and Lock remain available.",color=Muted,fontSize=13.sp,lineHeight=20.sp)
        TaskPermissionsControl()
        CoordinatorReportingControl()
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){BilingualLabel("Italian immersion",modifier=Modifier.weight(1f),centered=false);Switch(PocketImmersion.enabled,{PocketImmersion.setEnabled(it)})}
        if(PocketImmersion.enabled)ImmersionDensityControl()
        if(PocketImmersion.enabled)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){BilingualLabel("Automatic language cycling",modifier=Modifier.weight(1f),centered=false);Switch(PocketImmersion.motionEnabled,{PocketImmersion.setMotionEnabled(it)})}
        if(PocketImmersion.enabled)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){BilingualLabel("English support",modifier=Modifier.weight(1f),centered=false);Switch(PocketImmersion.supportEnabled,{PocketImmersion.setSupportEnabled(it)})}
        if(PocketImmersion.enabled&&PocketImmersion.motionEnabled&&PocketImmersion.supportEnabled&&!motionAllowed())BilingualLabel("Reduced motion · cycling without animation",color=Muted,maxLines=2,centered=false)
        if(PocketImmersion.unavailable)WorkflowText("Translation unavailable · showing originals",color=Muted,fontSize=12.sp)
        Label("MADE TO BE YOURS",Mint);WorkflowText("Your connection.",fontSize=34.sp,letterSpacing=(-1).sp)
        Surface(color=Panel,shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            ConnectionPill();Text(Pocket.host,fontSize=23.sp);Text(Pocket.base,color=Muted,fontSize=12.sp)
            if(Pocket.savedToken(false).isNotBlank()&&Pocket.savedToken(true).isNotBlank())Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=!Pocket.local,onClick={Pocket.activate(false)},label={BilingualLabel("Workstation")})
                FilterChip(selected=Pocket.local,onClick={Pocket.activate(true)},label={BilingualLabel("This phone")})
            }
            if(Pocket.savedToken(true).isBlank())OutlinedButton({Pocket.pairingMode=true},modifier=Modifier.fillMaxWidth()){BilingualLabel("Connect Codex on this phone")}
            HorizontalDivider(color=Line);WorkflowText(Pocket.pushStatus,fontSize=16.sp,color=Mint)
            WorkflowText(if(Pocket.local)"NextComp connects over this phone’s loopback interface. A visible monitor keeps local task results and requests flowing while the app is closed." else "Notifications arrive through Firebase, even when NextComp is closed. Conversations and files load from your workstation.",color=Muted,fontSize=14.sp,lineHeight=21.sp)
        }}
        if(!Pocket.connected||!Pocket.codexOnline){
            if(Pocket.connectionError.isNotBlank())WorkflowText(Pocket.connectionError,color=Coral,fontSize=13.sp,lineHeight=20.sp)
            if(Pocket.refreshError.isNotBlank())WorkflowText("Background refresh: "+Pocket.refreshError,color=Muted,fontSize=12.sp,lineHeight=18.sp)
            OutlinedButton({Pocket.retryConnection()},modifier=Modifier.fillMaxWidth()){BilingualLabel("Reconnect now")}
        }
        if(Pocket.savedToken(true).isNotBlank())PhoneControlSettings()
        NotificationAudioSettings()
        Button({Pocket.test()},modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(17.dp)){SymbolIcon(Icons.Rounded.NotificationsActive,null);Spacer(Modifier.width(10.dp));BilingualLabel("Send a test notification")}
        OutlinedButton({c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName))},modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(17.dp)){BilingualLabel("Notification settings")}
        WorkflowText(if(Pocket.local)"The local monitor uses no cloud push service. Keep its notification enabled while Codex is working on this phone." else "Keep notifications enabled. Tailscale connects you to your conversations and lets you reply. Firebase handles alerts without a permanent connection to the app.",color=Muted,fontSize=13.sp,lineHeight=20.sp)
        ErrorBanner();OutlinedButton({Pocket.disconnect()},modifier=Modifier.fillMaxWidth()){BilingualLabel("Disconnect this phone",color=Coral)}
        Spacer(Modifier.height(12.dp));Label("NEXTCOMP ${BuildConfig.VERSION_NAME} · FALLSOFT")
    }
}

@Composable fun PhoneControlSettings(){val c=LocalContext.current;val systemEnabled=PocketAutomation.systemEnabled(c)||PocketAutomation.connected
    Surface(color=Panel,shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){WorkflowText("Control this phone",fontSize=21.sp,fontWeight=FontWeight.Medium);WorkflowText(if(systemEnabled)"Android access is enabled" else "Android access still needs to be enabled",color=if(systemEnabled)Mint else Coral,fontSize=12.sp)}
            Switch(PocketAutomation.allowed&&systemEnabled,{PocketAutomation.allowed=it},enabled=systemEnabled)
        }
        WorkflowText("When both controls are enabled, Codex on this phone can inspect the foreground screen, take screenshots, tap, scroll, enter non-password text, and use Back, Home or Recents.",color=Muted,fontSize=13.sp,lineHeight=20.sp)
        if(!systemEnabled)OutlinedButton({c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},modifier=Modifier.fillMaxWidth()){BilingualLabel("Enable NextComp in Accessibility")}
        WorkflowText(if(PocketAutomation.allowed&&systemEnabled)"Phone control is live · pause it here at any time" else if(!systemEnabled&&PocketAutomation.allowed)"Android disabled the service · restore access above" else "Phone control is paused",color=if(PocketAutomation.allowed&&systemEnabled)Mint else if(!systemEnabled&&PocketAutomation.allowed)Coral else Muted,fontSize=12.sp)
        WorkflowText("Screen structure and screenshots are sent to your signed-in Codex only when its phone tools are used. Password fields are never returned or filled.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
    }}
}

@Composable fun NotificationAudioSettings(){
    LaunchedEffect(Unit){if(PocketAudio.mode=="voice")PocketAudio.prepareVoice()}
    Surface(color=Panel,shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        WorkflowText("Hear what happened.",fontSize=21.sp,fontWeight=FontWeight.Medium)
        WorkflowText("A different sound for results, questions, approvals and problems.",color=Muted,fontSize=13.sp,lineHeight=20.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("tones" to "Tones","voice" to "Labels","system" to "System").forEach{(value,label)->
                FilterChip(selected=PocketAudio.mode==value,onClick={PocketAudio.select(value)},label={BilingualLabel(label,fontSize=12.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Mint,selectedLabelColor=Ink))
            }
        }
        FilterChip(selected=PocketAudio.mode=="summaries",onClick={PocketAudio.select("summaries")},label={BilingualLabel("Speak messages")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Mint,selectedLabelColor=Ink))
        WorkflowText(when(PocketAudio.mode){"summaries"->"A short tone, then the spoken message using your Codex account allowance. Pause and resume from the player or notification, including while locked. Other audio pauses speech and saves your place. Quiet mode and Do Not Disturb silence speech.";"voice"->"Short labels such as “Codex needs your input.” Task text stays on screen.";"system"->"Uses your phone’s default notification sound.";else->"Finished rises, problems fall, and questions have a distinct two-note cue."},color=Muted,fontSize=13.sp,lineHeight=20.sp)
        WorkflowText(SpeechUsage.summary(),color=Muted,fontSize=12.sp,lineHeight=18.sp)
        WorkflowText("Generated/played counters cover notification and manual speech. Voice-mode connection time includes idle time. Playback does not prove audibility. Cancelled generation can still consume allowance; these are local activity counters, not billing totals.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
        if(PocketAudio.status.isNotBlank())WorkflowText(PocketAudio.status,color=Mint,fontSize=12.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            listOf("complete" to "Finished","question" to "Question","error" to "Problem").forEach{(kind,label)->TextButton({PocketAudio.preview(kind)}){BilingualLabel("▶ $label",color=Mint,fontSize=11.sp)}}
        }
        WorkflowText("Speech uses media volume; quiet mode, notification mute and Do Not Disturb still silence it.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
        HorizontalDivider(color=Line)
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){WorkflowText("Remind me when I’m needed",fontSize=14.sp);WorkflowText("Questions, approvals and problems only",color=Muted,fontSize=11.sp)};Switch(PocketAttention.enabled,{PocketAttention.toggle(it)})}
        WorkflowText("Up to three reminders: 5, 15, then 30 minutes apart. Dismiss, reply or resolve the request to stop. Later snoozes for 30 minutes.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
    }}
}

@Composable fun Attachment(a:JSONObject){val c=LocalContext.current
    if(a.s("mime").startsWith("image/")){
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)){
            LinkedImagePreview(MarkdownImage(a.s("url"),a.s("name")))
            Text(a.s("name"),color=Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        return
    }
    fun open(){Pocket.scope.launch{try{val file=withContext(Dispatchers.IO){val folder=File(c.cacheDir,"shared");folder.mkdirs();val f=File(folder,a.s("id")+"-"+a.s("name").substringAfterLast('/'));val req=okhttp3.Request.Builder().url(Pocket.base+a.s("url")).header("Authorization","Bearer ${Pocket.token}").build();Pocket.http.newCall(req).execute().use{r->if(!r.isSuccessful)throw Exception("Could not download attachment");f.writeBytes(r.body!!.bytes())};f};val u=FileProvider.getUriForFile(c,"co.fallsoft.pocket.files",file);c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u,a.s("mime")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}catch(e:Exception){Pocket.error=e.message?:"No app can open this file"}}}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).clickable{open()},verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){SymbolIcon(Icons.Rounded.AttachFile,null,tint=Mint,modifier=Modifier.size(18.dp));Text(a.s("name"),color=Mint,fontSize=12.sp,modifier=Modifier.padding(start=8.dp).weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);SymbolIcon(Icons.AutoMirrored.Rounded.OpenInNew,null,tint=Muted,modifier=Modifier.size(16.dp))}
    }
}
@Composable fun RequestCard(r:JSONObject){val p=r.optJSONObject("params")?:return;val id=r.s("id");val isQuestion=r.s("method").contains("requestUserInput");val answers=remember(id){mutableStateMapOf<String,String>()}
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Coral.copy(alpha=.12f)).border(1.dp,Coral.copy(alpha=.3f),RoundedCornerShape(22.dp)).padding(19.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.weight(1f)){Label(if(isQuestion)"YOUR INPUT" else "NEEDS YOUR APPROVAL",Coral)}
            if(isQuestion)TextButton({PocketQuestionActions.skipRequest(id)},modifier=Modifier.heightIn(min=48.dp).widthIn(min=64.dp)){BilingualLabel("Skip",color=Mint)}
        }
        if(isQuestion){p.optJSONArray("questions")?.objects()?.forEach{q->
            val questionId="request:$id:"+q.s("id")
            val prompt=q.s("question")
            LaunchedEffect(prompt,PocketImmersion.enabled){PocketImmersion.offer(questionId,prompt,"question")}
            ImmersionText(questionId,prompt,fontSize=16.sp,lineHeight=23.sp,kind="question")
            q.optJSONArray("options")?.objects()?.forEachIndexed{index,o->
                val label=o.s("label");val optionId="$questionId:option:$index"
                val description=o.s("description")
                LaunchedEffect(label,description,PocketImmersion.enabled){PocketImmersion.offer(optionId,label,"question option");PocketImmersion.offer("$optionId:description",description,"question option explanation")}
                OutlinedButton({answers[q.s("id")]=label},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.outlinedButtonColors(containerColor=if(answers[q.s("id")]==label)Mint.copy(alpha=.14f)else Color.Transparent)){
                    Column(Modifier.fillMaxWidth()){
                        ImmersionText(optionId,label,color=Paper,kind="question option",rescue=false)
                        if(description.isNotBlank())ImmersionText("$optionId:description",description,color=Muted,fontSize=14.sp,kind="question option explanation",rescue=false)
                    }
                }
            };OutlinedTextField(answers[q.s("id")]?:"",{answers[q.s("id")]=it},label={BilingualLabel("Your answer")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp))};Button({val a=JSONObject();answers.forEach{(k,v)->a.put(k,v)};Pocket.answer(id,JSONObject().put("answers",a))}){BilingualLabel("Send answers")}}
        else{ImmersionText("approval:"+id,p.s("reason",p.s("message","Review this request before continuing.")),fontSize=15.sp);RichText(p.s("command",p.toString(2)));if(r.s("method").contains("commandExecution")||r.s("method").contains("fileChange")){Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Button({Pocket.answer(id,JSONObject().put("decision","accept"))}){BilingualLabel("Allow once")};OutlinedButton({Pocket.answer(id,JSONObject().put("decision","decline"))}){BilingualLabel("Decline",color=Coral)}}}else WorkflowText("Answer this request in the terminal.",color=Coral,fontSize=13.sp)}
    }
}

@Composable fun DockButton(label:String,icon:ImageVector,modifier:Modifier=Modifier,selected:Boolean=false,primary:Boolean=false,onClick:()->Unit){
    val color by animateColorAsState(if(selected||primary)Mint else Muted,label="dock tint")
    val surface by animateColorAsState(if(primary)Mint.copy(alpha=.15f) else if(selected)Paper.copy(alpha=.07f) else Color.Transparent,label="dock surface")
    Surface(onClick=onClick,modifier=modifier.height(usageDockHeight()).immersionRescue(label),color=surface,shape=RoundedCornerShape(20.dp)){
        Column(Modifier.fillMaxSize().pressMotion().padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            SymbolIcon(icon,label,Modifier.size(if(primary)44.dp else 36.dp),tint=color)
            Spacer(Modifier.height(3.dp));BilingualLabel(label,color=color,fontSize=10.sp)
        }
    }
}

/** Direct, non-focus-stealing controls shared by speech and its live caption surface. */
@Composable fun SpeechPlaybackControls(iconOnly:Boolean=false){
    val available=PocketSpeech.currentMessageId!=null
    IconButton({PocketSpeech.control(if(PocketSpeech.paused)"resume" else "pause")},enabled=available,modifier=Modifier.size(48.dp)){
        SymbolIcon(if(PocketSpeech.paused)Icons.Rounded.PlayArrow else Icons.Rounded.Pause,PocketImmersion.label(if(PocketSpeech.paused)"Resume" else "Pause"),modifier=Modifier.size(28.dp),tint=if(available)Mint else Muted)
    }
    if(iconOnly)IconButton({PocketSpeech.clear()},enabled=available,modifier=Modifier.size(48.dp)){
        Icon(Icons.Rounded.Close,PocketImmersion.label("Clear speech from active session"),Modifier.size(28.dp),tint=if(available)Coral else Muted)
    }else TextButton({PocketSpeech.clear()},enabled=available,modifier=Modifier.heightIn(min=48.dp).widthIn(min=64.dp).semantics{contentDescription=PocketImmersion.label("Clear speech from active session")},contentPadding=PaddingValues(horizontal=8.dp)){
        BilingualLabel("Clear session",color=if(available)Coral else Muted,fontSize=14.sp)
    }
}

/** Captions supply the playback context, so conversations need only one speech surface. */
@Composable fun ConversationSpeechDock(containerColor:androidx.compose.ui.graphics.Color=Panel){
    if(PocketVoice.captureFeedbackVisible)return
    if(PocketSpeechCaptions.state.visible)SpeechCaptionBanner(playbackControls=PocketSpeech.count>0,containerColor=containerColor)
    else SpeechPlayer(containerColor)
}
