package co.fallsoft.pocket

import androidx.compose.foundation.selection.toggleable

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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
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
import kotlinx.coroutines.flow.first
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
    // Observe the platform-owned menu rather than Compose's version-specific provider.
    private val immersionActionModes=mutableStateMapOf<android.view.ActionMode,Boolean>()
    internal val immersionSelectionActive:Boolean get()=immersionActionModes.isNotEmpty()
    override fun onActionModeStarted(mode:android.view.ActionMode){
        super.onActionModeStarted(mode)
        immersionActionModes[mode]=true
    }
    override fun onActionModeFinished(mode:android.view.ActionMode){
        immersionActionModes.remove(mode)
        super.onActionModeFinished(mode)
    }
    private val blackout by lazy { ScreenBlackout(this) }
    fun enterBlackout(){blackout.enter()}
    private var initialServer by mutableStateOf("");private var initialCode by mutableStateOf("")
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT));readIntent(intent)
        if(Build.VERSION.SDK_INT>=33)permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
        if(Pocket.token.isNotBlank()){Pocket.refresh()}
        setContent{MaterialTheme(shapes=Shapes(extraSmall=RoundedCornerShape(2.dp),small=RoundedCornerShape(4.dp),medium=RoundedCornerShape(6.dp),large=RoundedCornerShape(8.dp),extraLarge=RoundedCornerShape(10.dp)),typography=Typography(titleLarge=androidx.compose.ui.text.TextStyle(fontFamily=AppFont,fontSize=20.sp,fontWeight=FontWeight.Medium),titleMedium=androidx.compose.ui.text.TextStyle(fontFamily=AppFont,fontSize=16.sp,fontWeight=FontWeight.Medium),bodyLarge=androidx.compose.ui.text.TextStyle(fontSize=15.sp,lineHeight=22.sp,fontFamily=AppFont),bodyMedium=androidx.compose.ui.text.TextStyle(fontSize=14.sp,lineHeight=20.sp,fontFamily=AppFont),labelLarge=androidx.compose.ui.text.TextStyle(fontSize=15.sp,fontWeight=FontWeight.Medium,fontFamily=AppFont)),colorScheme=darkColorScheme(primary=Mint,onPrimary=Ink,background=Ink,surface=Panel,onSurface=Paper,onBackground=Paper,outline=Muted,secondary=Coral,onSecondary=Ink,surfaceVariant=Panel,onSurfaceVariant=Paper,surfaceContainer=Panel,surfaceContainerHigh=Panel,surfaceContainerHighest=Panel,surfaceContainerLow=Panel,surfaceContainerLowest=Ink,secondaryContainer=Panel,onSecondaryContainer=Paper,primaryContainer=Panel,onPrimaryContainer=Paper,tertiary=NextCerise,onTertiary=Ink,tertiaryContainer=Panel,onTertiaryContainer=Paper)){
            CompositionLocalProvider(LocalImmersionMotionState provides rememberImmersionMotionEnvironment(),LocalImmersionTransitionAnimated provides motionAllowed()){
            ImmersionReadingWaveHost{NextCompDepthRoot{Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
                Box(Modifier.weight(1f)){if(Pocket.token.isBlank()||Pocket.pairingMode)PairScreen(initialServer,initialCode)else BackendApp()}
            }}}}
        }}
    }
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean {ImmersionInteraction.touch(event.actionMasked);return super.dispatchTouchEvent(event)}
    fun beginPairing(server:String=""){initialServer=server;initialCode="";Pocket.pairingMode=true}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);readIntent(intent)}
    private fun readIntent(i:Intent){
        i.getStringExtra("hollywoodNotification")?.let{key->i.removeExtra("hollywoodNotification");HollywoodNotifications.openIntent(key);return}
        val requestedEnvironment=i.getStringExtra("environment")
        if(requestedEnvironment!=null&&Pocket.captureEnvironment(requestedEnvironment)==null){Pocket.error="That environment is no longer paired";return}
        if(i.getBooleanExtra("appUpdate",false)){i.removeExtra("appUpdate");if(!PocketVoice.active){Pocket.closeTask();Pocket.newTask=false;Pocket.tab=1};PocketUpdates.installOrCheck(this)}
        val pairLink=i.data?.takeIf{it.scheme=="pocket"&&it.host=="pair"}
        if(pairLink!=null||i.hasExtra("server")||i.hasExtra("code")){
            // A prefill belongs to this explicit request, not later notifications
            // or activity recreation. Never carry an old one-use code into a new host.
            initialServer=pairLink?.getQueryParameter("server")?:i.getStringExtra("server").orEmpty()
            initialCode=pairLink?.getQueryParameter("code")?:i.getStringExtra("code").orEmpty()
            Pocket.pairingMode=true
            i.removeExtra("server");i.removeExtra("code")
            if(pairLink!=null)i.data=null
        }
        if(i.getBooleanExtra("coordinatorReport",false)){
            val origin=i.getBooleanExtra("local",Pocket.local)
            if(PocketVoice.active&&PocketVoice.state in setOf("Listening","Starting microphone","Finishing recording")){Pocket.error="Finish this recording before opening work updates";PocketVoice.problem=Pocket.error;return}
            val environment=i.getStringExtra("environment")?:if(origin)"phone" else "workstation"
            Pocket.activateEnvironment(environment)
            if(Pocket.token.isNotBlank()){PocketWorkUpdates.open();getSystemService(android.app.NotificationManager::class.java).cancel(Pocket.notificationTag(environment),i.getIntExtra("reportNotificationId",0))}
            i.removeExtra("coordinatorReport");return
        }
        if(i.hasExtra("environment")){
            val destination=i.getStringExtra("environment")!!;Pocket.activateEnvironment(destination)
            if(Pocket.environmentId!=destination)return
        }else if(i.hasExtra("local")){
            val destination=if(i.getBooleanExtra("local",false))"phone" else "workstation";Pocket.activateEnvironment(destination)
            if(Pocket.environmentId!=destination)return
        }
        i.getStringExtra("thread")?.let{if(Pocket.token.isNotBlank())Pocket.open(it)}
        if(Pocket.token.isNotBlank()&&i.getStringExtra("operations_url")=="https://github.com/FallSoftCo/pocket/actions"){
            i.removeExtra("operations_url")
            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/FallSoftCo/pocket/actions")))
        }
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);AppWindowVisibility.focus(this,hasFocus)}
    override fun onStart(){super.onStart();if(Pocket.token.isNotBlank())PocketUpdates.check();PocketVoice.foreground=true;CodexConversations.onForeground(true);CodexCatalog.onForeground(true);Pocket.scope.launch{DurableVoiceWork.restore(this@MainActivity)};if(Pocket.local&&Pocket.token.isNotBlank())LocalMonitorService.start(this);if(Pocket.token.isNotBlank())PocketLive.start()}
    override fun onStop(){AppWindowVisibility.focus(this,false);captureKeys.reset();blackout.exit(restoreInput=false);PocketVoice.foreground=false;if(!isChangingConfigurations)ForegroundNotifications.background();CodexConversations.onForeground(false);CodexCatalog.onForeground(false);if(!PocketVoice.active)PocketLive.stop();super.onStop()}
    private val captureKeys get()=PocketVoice.captureKeys
    private var permissionCapture:CaptureDestination?=null
    private val capturePermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){allowed->
        val original=permissionCapture;permissionCapture=null
        if(allowed)original?.start(this)
        else PocketVoice.captureFailure("Allow microphone access to record. Your draft is kept.")
    }
    // Public platform Activity hook; AndroidX core redeclares it with a library restriction.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event:android.view.KeyEvent):Boolean{
        // Normal typing must not perform session/profile discovery for voice.
        if(event.keyCode!=android.view.KeyEvent.KEYCODE_VOLUME_DOWN&&event.keyCode!=android.view.KeyEvent.KEYCODE_VOLUME_UP)
            return super.dispatchKeyEvent(event)
        val destination=currentForegroundCaptureTarget()
        if(foregroundCaptureKeys(PocketVoice.foreground,Pocket.token.isNotBlank()&&!Pocket.pairingMode,BackendNavigation.teamSelected(),destination.eligible,nativeRoom=destination.roomDestination!=null,recording=PocketVoice.captureBusy)){
            if(event.action==android.view.KeyEvent.ACTION_DOWN&&captureKeys.down(event.keyCode,event.repeatCount)){
                if(PocketVoice.captureBusy){PocketVoice.record();return true}
                val target=CaptureDestination.capture(destination.threadId,destination.roomDestination,destination.sourceLabel)
                if(target==null)PocketVoice.captureFailure("Connect to an environment before recording. Your draft is kept.")
                else if(androidx.core.content.ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)target.start(this)
                else{permissionCapture=target;capturePermission.launch(Manifest.permission.RECORD_AUDIO)}
            }else if(event.action==android.view.KeyEvent.ACTION_UP)captureKeys.up(event.keyCode)
            return true
        }
        if(event.action==android.view.KeyEvent.ACTION_UP)captureKeys.up(event.keyCode)
        return super.dispatchKeyEvent(event)
    }
    override fun onDestroy(){immersionActionModes.clear();blackout.exit(restoreInput=false);super.onDestroy()}
    override fun onResume(){super.onResume();PresentedReads.restore();if(Pocket.token.isNotBlank()){Pocket.selected?.let{PocketReplies.reconcile(it)}}}
}

@Composable fun Mark(size:Int=44){AnimatedMark(size)}
/** Public workflow prose shares the teacher's contextual inline immersion plan. */
@Composable fun WorkflowText(text:String,modifier:Modifier=Modifier,color:Color=Paper,fontSize:TextUnit=16.sp,lineHeight:TextUnit=TextUnit.Unspecified,fontWeight:FontWeight?=null,maxLines:Int=Int.MAX_VALUE,overflow:TextOverflow=TextOverflow.Clip,letterSpacing:TextUnit=TextUnit.Unspecified){
    InterfaceText(text,modifier=modifier,color=color,fontSize=fontSize,lineHeight=if(lineHeight==TextUnit.Unspecified)(fontSize.value*1.4f).sp else lineHeight,maxLines=maxLines,fontWeight=fontWeight,overflow=overflow,letterSpacing=letterSpacing)
}
@Composable fun Label(text:String,color:Color=Muted){BilingualLabel(text,color=color,fontSize=10.sp,fontWeight=FontWeight.Bold,centered=false)}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ErrorBanner(message:String=Pocket.error,onDismiss:(()->Unit)?=null){if(message.isNotBlank())Surface(color=Coral.copy(alpha=.12f),shape=RoundedCornerShape(6.dp),modifier=Modifier.fillMaxWidth().padding(vertical=8.dp)){Column(Modifier.padding(16.dp)){WorkflowText(ConnectionMessages.server(message),color=Coral,fontSize=13.sp,lineHeight=19.sp);FlowRow{if(Pocket.token.isNotBlank())TextButton({Pocket.retryConnection()}){BilingualLabel("Reload conversation",color=Mint)};if(onDismiss!=null)TextButton(onDismiss){BilingualLabel("Dismiss",color=Muted)}}}}}
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
    var address by remember(server){mutableStateOf(server)};var pin by remember(code){mutableStateOf(code)};var environmentName by remember{mutableStateOf("")}
    Column(Modifier.fillMaxSize().imePadding()){
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){
                Mark(48)
                BilingualLabel("Environment",fontSize=24.sp,fontWeight=FontWeight.Medium,centered=false)
            }
            OutlinedTextField(address,{address=it},label={WorkflowText("NextComp server address")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(6.dp))
            OutlinedTextField(environmentName,{environmentName=it},label={WorkflowText("Environment name")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(6.dp))
            OutlinedTextField(pin,{pin=it},label={WorkflowText("One-time pairing code")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(6.dp))
            ErrorBanner()
        }
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Button({Pocket.pair(address,pin,environmentName)},enabled=!Pocket.busy&&address.isNotBlank()&&pin.isNotBlank(),modifier=Modifier.fillMaxWidth().height(58.dp),shape=RoundedCornerShape(12.dp)){
                if(Pocket.busy)CircularProgressIndicator(Modifier.size(22.dp),color=Ink,strokeWidth=2.dp)
                else{BilingualLabel("Connect Codex",fontWeight=FontWeight.Bold);Spacer(Modifier.width(12.dp));SymbolIcon(Icons.AutoMirrored.Rounded.ArrowForward,null)}
            }
            if(Pocket.token.isNotBlank())TextButton({Pocket.pairingMode=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){BilingualLabel("Cancel",color=Muted)}
        }
    }
}

@Composable fun PocketApp(){
    if(PocketWorkUpdates.visible){WorkUpdatesScreen();return}
    if(PocketVoice.active&&!PocketVoice.inPlace){VoiceScreen();return}
    if(PocketCoordinator.visible){CoordinatorScreen();return}

    BackHandler(Pocket.tab==1&&Pocket.selected==null&&!Pocket.newTask){Pocket.tab=0}
    var workToolsOpen by remember{mutableStateOf(false)}
    val workListState=rememberLazyListState()
    val workCatalogView=remember{SessionCatalogViewState(Pocket.showArchived)}
    BackHandler(Pocket.selected!=null||Pocket.newTask){if(Pocket.newTask)Pocket.newTask=false else Pocket.closeTask()}
    Column(Modifier.fillMaxSize()){
        ConnectionNotice()
        PhoneControlNotice()
        if(Pocket.newTask)NewTaskScreen()
        else if(Pocket.selected!=null)key(CodexConversations.displayKey){if(conversationIdentityPending())LoadingSessionIdentity()else if(readOnlyChildSelected())ChildConversationScreen()else ConversationScreen()}
        else{
            Box(Modifier.weight(1f).fillMaxWidth()){
            AnimatedContent(targetState=Pocket.tab,modifier=Modifier.fillMaxSize(),label="tab",transitionSpec={
                (fadeIn(tween(180))+slideInHorizontally(tween(220)){it/12}) togetherWith (fadeOut(tween(100))+slideOutHorizontally(tween(180)){-it/12}) using SizeTransform(clip=false)
            }){tab->when(tab){0->WorkScreen(workToolsOpen,{workToolsOpen=it},workListState,workCatalogView);else->SettingsScreen()}}
            }
            ConversationSpeechDock()
            InlineCaptureStatus()
            PocketDock(onFind={Pocket.tab=0;workToolsOpen=true},onTab={workToolsOpen=false},listState=workListState)
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun PocketDock(onFind:()->Unit,onTab:()->Unit,listState:androidx.compose.foundation.lazy.LazyListState?=null){
    WorkCatalogDock{
        IconButton({Pocket.tab=0;onFind()},modifier=Modifier.size(48.dp)){SymbolIcon("Search","Find",Modifier.size(32.dp),tint=Mint)}
        if(Pocket.tab==0&&listState!=null)ReaderPagingButtons(listState,hasEarlier=UnifiedWorkCatalog.hasEarlier,onPrepare={up->
            if(up&&!listState.canScrollForward&&UnifiedWorkCatalog.hasEarlier)UnifiedWorkCatalog.more()
        })
        InlineSpeechVolume(Modifier.width(inlineVolumeControlWidth()))
        UsageDock(Modifier.width(compactUsageDockWidth()),compact=true)
    }
}
@Composable fun CatalogStartupStatus(){
    Column(Modifier.fillMaxWidth().padding(vertical=28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
        when(val state=Pocket.catalogLoad){
            CatalogLoadState.Loading->{Mark(88);BilingualLabel("Loading conversations",color=Paper,fontSize=17.sp);BilingualLabel(Pocket.environmentProfiles.all().firstOrNull{it.id==Pocket.environmentId}?.name?:Pocket.host,color=Muted,fontSize=13.sp,maxLines=1)}
            is CatalogLoadState.Failed->{WorkflowText("Conversations unavailable",fontSize=17.sp);WorkflowText(ConnectionMessages.server(state.message),color=Coral,fontSize=13.sp);Button({Pocket.refresh()},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){BilingualLabel("Retry")}}
            CatalogLoadState.Ready->Unit
        }
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
    if(PocketSpeech.displayedOwner!=null){
        WorkflowText(PocketSpeech.title,color=Paper,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        return
    }
    var sourceOpen by remember{mutableStateOf(false)}
    Box{
        TextButton({sourceOpen=true},contentPadding=PaddingValues(horizontal=0.dp),modifier=Modifier.heightIn(min=48.dp)){
            WorkflowText(PocketSpeech.title.ifBlank{"Choose speech session"}+" · "+(if(PocketSpeech.activeCount>0)PocketSpeech.activeCount else PocketSpeech.count)+" ▾",color=if(PocketSpeech.paused)Muted else Mint,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded=sourceOpen,onDismissRequest={sourceOpen=false},properties=androidx.compose.ui.window.PopupProperties(focusable=false)){
            PocketSpeech.sources.forEach{(source,details)->DropdownMenuItem(text={Text("${if(source==PocketSpeech.activeSource)"• " else ""}${details.first} · ${details.second}")},onClick={sourceOpen=false;PocketSpeech.selectSource(source)})}
        }
    }
}

@Composable fun ConnectionPill(){val ok=Pocket.connected&&Pocket.codexOnline;Row(Modifier.clip(CircleShape).background(Panel).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){Box(Modifier.size(6.dp).background(if(ok)Mint else Coral,CircleShape));BilingualLabel(if(ok)"Connected" else if(Pocket.connected)"Codex offline" else "Reconnecting",fontSize=11.sp,color=if(ok)Mint else Coral)}}
/** Embedded menus render environment choices in their existing anchored surface. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable fun ProfileSwitcher(inlineOptions:Boolean=false,onSelected:()->Unit={}){
    val c=LocalContext.current
    val profiles=Pocket.environmentProfiles.all();if(profiles.isEmpty())return
    var expanded by remember{mutableStateOf(false)}
    val select:(EnvironmentIdentity)->Unit={profile->
        expanded=false;Pocket.activateEnvironment(profile.id)
        if(Pocket.environmentId==profile.id)onSelected()
    }
    val add:()->Unit={expanded=false;c.nextCompActivity()?.beginPairing();onSelected()}
    val choices:@Composable ColumnScope.()->Unit={
        profiles.forEach{profile->DropdownMenuItem(
            text={Text(profile.name,maxLines=2,overflow=TextOverflow.Ellipsis)},
            leadingIcon={RadioButton(profile.id==Pocket.environmentId,onClick=null)},
            modifier=Modifier.testTag("environment-choice:"+profile.id),
            onClick={select(profile)})}
        DropdownMenuItem(text={BilingualLabel("Add environment")},onClick=add)
    }
    if(inlineOptions){
        Column(Modifier.fillMaxWidth().semantics{testTagsAsResourceId=true}.testTag("environment-picker-inline")){
            DropdownMenuItem(text={Text(profiles.firstOrNull{it.id==Pocket.environmentId}?.name?:Pocket.host,maxLines=1,overflow=TextOverflow.Ellipsis)},
                leadingIcon={SymbolIcon(if(Pocket.local)"PhoneAndroid" else "Computer",null,Modifier.size(24.dp))},
                trailingIcon={SymbolIcon(if(expanded)"ExpandLess" else "ExpandMore",null,Modifier.size(20.dp))},
                modifier=Modifier.testTag("environment-picker-toggle").semantics{contentDescription="Development environment: "+(profiles.firstOrNull{it.id==Pocket.environmentId}?.name?:Pocket.host)},
                onClick={expanded=!expanded})
            if(expanded)choices()
        }
        return
    }
    Box(Modifier.fillMaxWidth()){
        OutlinedButton({expanded=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).semantics{contentDescription="Development environment: "+(profiles.firstOrNull{it.id==Pocket.environmentId}?.name?:Pocket.host)}){
            SymbolIcon(if(Pocket.local)Icons.Rounded.PhoneAndroid else Icons.Rounded.Computer,null,Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp));Text(profiles.firstOrNull{it.id==Pocket.environmentId}?.name?:Pocket.host,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            SymbolIcon(Icons.Rounded.ExpandMore,null,Modifier.size(20.dp))
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},content=choices)
    }
}

class SessionCatalogViewState(archived:Boolean=false){val filter=mutableIntStateOf(if(archived)3 else 0);val query=mutableStateOf("")}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WorkScreen(toolsOpen:Boolean=false,onToolsOpen:(Boolean)->Unit={},listState:androidx.compose.foundation.lazy.LazyListState=rememberLazyListState(),view:SessionCatalogViewState=remember{SessionCatalogViewState(Pocket.showArchived)}){
    UnifiedWorkScreen(toolsOpen,onToolsOpen,listState,view)
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SessionCatalogTools(toolsOpen:Boolean,onToolsOpen:(Boolean)->Unit,view:SessionCatalogViewState){
    var query by view.query;var filter by view.filter
    if(toolsOpen)ModalBottomSheet(onDismissRequest={onToolsOpen(false)},containerColor=Panel){
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal=16.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(query,{query=it},placeholder={BilingualLabel("Find sessions",centered=false)},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Recent","Working","Following","Archived","History").forEachIndexed{i,title->FilterChip(selected=filter==i,onClick={filter=i;if(i==0)Pocket.requestSessionOrder();if(Pocket.showArchived!=(i==3)){Pocket.showArchived=i==3;Pocket.refresh();UnifiedWorkCatalog.refresh()}},label={BilingualLabel(title)})}}
            if(UnifiedWorkCatalog.hasEarlier)TextButton({Pocket.scope.launch{UnifiedWorkCatalog.more()}},enabled=!Pocket.loadingSessions){BilingualLabel(if(Pocket.loadingSessions)"Loading history…" else "Load older sessions")}
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
@Composable fun TaskCard(t:Task,now:Long=System.currentTimeMillis(),headerBadge:(@Composable ()->Unit)?=null,
    modifier:Modifier=Modifier,identityScope:String=Pocket.environmentId,onRestore:()->Unit={Pocket.restoreTask(t.id)},onOpen:(Boolean)->Unit={Pocket.open(t.id,keyboard=it)},
    onRename:((String,()->Unit)->Unit)?={name,done->Pocket.renameTask(t.id,name,done)},
    onWatch:((Boolean)->Unit)?={Pocket.watchTask(t.id,it)}){
    LaunchedEffect(identityScope,t.id){if(!t.archived)CodexSessionRepository.previewVisible(identityScope,t.id)}
    val active=t.status in listOf("active","pending");val activityTime=taskWorkTime(t);val ageColor=sessionAgeColor(activityTime,now)
    LaunchedEffect(identityScope,t.id,t.preview,PocketImmersion.enabled,PocketImmersion.planVersion){PocketImmersion.offer("card:"+identityScope+":"+t.id,t.preview,if(t.previewKind=="thinking")"public reasoning summary" else "session activity")}
    val previewHeight=with(LocalDensity.current){40.sp.toDp()}.coerceAtLeast(48.dp)
    var rename by remember(identityScope,t.id){mutableStateOf(false)};var name by remember(identityScope,t.id){mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(t.title))}
    val highlight=remember(identityScope,t.id){Animatable(0f)}
    val revision=Triple(t.preview,t.status,t.activityAt)
    var observed by remember(identityScope,t.id){mutableStateOf(revision)}
    LaunchedEffect(identityScope,t.id,revision){if(observed!=revision){observed=revision;highlight.snapTo(1f);highlight.animateTo(0f,tween(durationMillis=1800,delayMillis=700))}}
    val cardText=Paper
    val stateLabel=when{active->if(t.status=="pending")"Starting" else "Working";t.needsInput->"Needs your input";t.status=="idle"->"Idle";t.status=="blocked"->"Blocked";t.status=="failed"->"Failed";else->"Status unavailable"}
    WorkCardSurface(active,t.needsInput,if(t.needsInput&&!active)Coral else ageColor,highlight.value,modifier,knownIdle=t.status=="idle"&&!t.needsInput){
        if(!t.archived)Row(Modifier.matchParentSize()){
            CardInteractionRegion("Chat",Modifier.weight(.78f).fillMaxHeight(),t.title){onOpen(false)}
            CardInteractionRegion("Keyboard",Modifier.weight(.22f).fillMaxHeight(),t.title){onOpen(true)}
        }
        Column(Modifier.padding(start=12.dp,end=12.dp,top=2.dp,bottom=4.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(Modifier.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            if(rename){
                val focus=remember{androidx.compose.ui.focus.FocusRequester()}
                LaunchedEffect(Unit){focus.requestFocus()}
                OutlinedTextField(name,{name=it.copy(text=it.text.take(120))},singleLine=true,modifier=Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent{event->if(event.key==Key.Enter&&event.type==KeyEventType.KeyDown){if(name.text.trim().isNotEmpty())onRename?.invoke(name.text.trim()){rename=false};true}else false},textStyle=androidx.compose.ui.text.TextStyle(fontSize=16.sp,color=Paper),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Done),keyboardActions=androidx.compose.foundation.text.KeyboardActions(onDone={if(name.text.trim().isNotEmpty())onRename?.invoke(name.text.trim()){rename=false}}))
            }else Box(Modifier.weight(1f),contentAlignment=Alignment.CenterStart){
                LaunchedEffect(identityScope,t.id,t.title,PocketImmersion.enabled,PocketImmersion.planVersion){PocketImmersion.offer("session-title:"+identityScope+":"+t.id,t.title,"session title")}
                val titleConfiguration=androidx.compose.ui.platform.LocalViewConfiguration.current
                CompositionLocalProvider(androidx.compose.ui.platform.LocalViewConfiguration provides object:androidx.compose.ui.platform.ViewConfiguration by titleConfiguration {
                    override val minimumTouchTargetSize=androidx.compose.ui.unit.DpSize.Zero
                }){
                    BilingualLabel(t.title,color=cardText,fontSize=16.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium,maxLines=1,centered=false,onActivate=if(onRename==null)null else {{name=androidx.compose.ui.text.input.TextFieldValue(t.title,androidx.compose.ui.text.TextRange(0,t.title.length));rename=true}})
                }
            }
            if(!rename)headerBadge?.invoke()
            val age=now-activityTime
            val ageLabel=if(age in 20_000..59_999)"${age/1000}s" else lastActivity(activityTime)
            if(activityTime>0&&age>=20_000)Text(ageLabel,color=Muted,fontSize=11.sp,modifier=Modifier.semantics{contentDescription="Last active "+ageLabel}.widthIn(max=70.dp),maxLines=1,overflow=TextOverflow.Ellipsis)
            Box(Modifier.size(24.dp)){if(active)SymbolIcon(if(t.previewKind=="thinking")"Psychology" else "Codex",if(t.status=="pending")"Starting" else "Working",Modifier.fillMaxSize(),spinning=true) else SymbolIcon(if(stateLabel=="Idle")Icons.Rounded.Pause else Icons.Rounded.ErrorOutline,stateLabel,tint=if(t.needsInput||t.status in listOf("blocked","failed"))Coral else Muted,modifier=Modifier.size(18.dp))}
            if(onWatch!=null)Surface(onClick={onWatch(!t.watched)},modifier=Modifier.width(48.dp).heightIn(min=48.dp).semantics{contentDescription=if(t.watched)"Notifications on; tap to turn off" else "Notifications off; tap to turn on"},color=if(t.watched)Mint.copy(alpha=.2f)else Ink,shape=RoundedCornerShape(12.dp)){
                Column(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                    SymbolIcon(if(t.watched)Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,null,modifier=Modifier.size(24.dp),tint=if(t.watched)Paper else Muted.copy(alpha=.65f))
                    BilingualLabel(if(t.watched)"On" else "Off",fontSize=10.sp,color=if(t.watched)Mint else Muted)
                }
            }
            if(t.archived)IconButton(onRestore,modifier=Modifier.size(48.dp)){SymbolIcon(Icons.Rounded.Unarchive,"Restore conversation",tint=Mint,modifier=Modifier.size(18.dp))}

        }
        Row(Modifier.fillMaxWidth().height(previewHeight),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
            Box(Modifier.width(18.dp).height(previewHeight)){
            if(t.previewRole in listOf("user","assistant"))SymbolIcon(if(t.previewRole=="user")"User" else "Codex",null,Modifier.size(18.dp))
            else if(t.previewRole=="activity")SymbolIcon(when(t.previewKind){"command"->Icons.Rounded.Terminal;"edit"->Icons.Rounded.EditNote;"search"->Icons.Rounded.TravelExplore;"thinking"->Icons.Rounded.Psychology;else->Icons.Rounded.Build},null,tint=Mint,modifier=Modifier.size(18.dp))
            }
            ImmersionText("card:"+identityScope+":"+t.id,if(!active&&t.catalogContext.isNotBlank())t.catalogContext else if(!active)(stateLabel+" · ")+t.preview.ifBlank{"No messages yet"} else t.preview,color=if(t.needsInput&&!active)Coral else cardText,fontSize=14.sp,lineHeight=20.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f),rescue=false,phraseRescue=false,links=false)
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
        WorkflowText("Applies to future tasks in this environment.",color=Muted,fontSize=12.sp)
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
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=PocketImmersion.enabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange={PocketImmersion.setEnabled(it)}),verticalAlignment=Alignment.CenterVertically){BilingualLabel("Italian immersion",modifier=Modifier.weight(1f),centered=false);Switch(PocketImmersion.enabled,onCheckedChange=null)}
        if(PocketImmersion.enabled)ImmersionDensityControl()
        if(PocketImmersion.enabled)Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=PocketImmersion.motionEnabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange={PocketImmersion.setMotionEnabled(it)}),verticalAlignment=Alignment.CenterVertically){BilingualLabel("Automatic language cycling",modifier=Modifier.weight(1f),centered=false);Switch(PocketImmersion.motionEnabled,onCheckedChange=null)}
        if(PocketImmersion.enabled)Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=PocketImmersion.supportEnabled,role=androidx.compose.ui.semantics.Role.Switch,onValueChange={PocketImmersion.setSupportEnabled(it)}),verticalAlignment=Alignment.CenterVertically){BilingualLabel("English support",modifier=Modifier.weight(1f),centered=false);Switch(PocketImmersion.supportEnabled,onCheckedChange=null)}
        if(PocketImmersion.enabled&&PocketImmersion.motionEnabled&&PocketImmersion.supportEnabled&&!motionAllowed())BilingualLabel("Reduced motion · cycling without animation",color=Muted,maxLines=2,centered=false)
        if(PocketImmersion.unavailable)WorkflowText("Translation unavailable · showing originals",color=Muted,fontSize=12.sp)
        Label("MADE TO BE YOURS",Mint);WorkflowText("Your connection.",fontSize=34.sp,letterSpacing=(-1).sp)
        Surface(color=Panel,shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            ConnectionPill();Text(Pocket.host,fontSize=23.sp);Text(Pocket.base,color=Muted,fontSize=12.sp)
            ProfileSwitcher()
            if(Pocket.savedToken(true).isBlank())OutlinedButton({c.nextCompActivity()?.beginPairing("http://127.0.0.1:18880")},modifier=Modifier.fillMaxWidth()){BilingualLabel("Connect Codex on this phone")}
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
        WorkflowText(when(PocketAudio.mode){"voice"->"Short labels such as “Codex needs your input.” Task text stays on screen.";"system"->"Uses your phone’s default notification sound.";else->"Finished rises, problems fall, and questions have a distinct two-note cue."},color=Muted,fontSize=13.sp,lineHeight=20.sp)
        WorkflowText("Replies speak in voice mode or when you tap Speak. Notifications never build a speech backlog.",color=Muted,fontSize=13.sp,lineHeight=20.sp)
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
@Composable fun RequestCard(r:JSONObject){val p=r.optJSONObject("params")?:return;val id=r.s("id");val isQuestion=r.s("method").contains("requestUserInput")
    val fingerprint=remember(r.toString()){java.security.MessageDigest.getInstance("SHA-256").digest(r.toString().toByteArray()).take(12).joinToString(""){"%02x".format(it)}}
    val draftKey=Pocket.key("questionDraft:"+backendOwner(Pocket.base,Pocket.token)+":"+Pocket.selected+":"+id+":"+fingerprint)
    val answers=remember(draftKey){mutableStateMapOf<String,String>().apply{runCatching{JSONObject(Pocket.prefs.getString(draftKey,"{}")?:"{}").let{saved->saved.keys().forEach{key->put(key,saved.s(key))}}}}}
    fun saveAnswer(key:String,value:String){answers[key]=value;val saved=JSONObject();answers.forEach{(k,v)->saved.put(k,v)};Pocket.prefs.edit().putString(draftKey,saved.toString()).apply()}
    val delivery=QuestionDelivery.state(draftKey);val editable=delivery !in listOf("sending","submitted","unconfirmed","expired")
    fun submit(body:JSONObject)=QuestionDelivery.submit(draftKey,id,body)

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Coral.copy(alpha=.12f)).border(1.dp,Coral.copy(alpha=.3f),RoundedCornerShape(6.dp)).padding(19.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.weight(1f)){Label(if(isQuestion)"YOUR INPUT" else "NEEDS YOUR APPROVAL",Coral)}
            if(isQuestion)TextButton({submit(JSONObject().put("skip",true))},enabled=editable,modifier=Modifier.heightIn(min=48.dp).widthIn(min=64.dp)){BilingualLabel("Skip",color=Mint)}
        }
        if(isQuestion){p.optJSONArray("questions")?.objects()?.forEach{q->
            val questionId="request:$id:"+q.s("id")
            val prompt=q.s("question")
            LaunchedEffect(prompt,PocketImmersion.enabled,PocketImmersion.planVersion){PocketImmersion.offer(questionId,prompt,"question")}
            ImmersionText(questionId,prompt,fontSize=16.sp,lineHeight=23.sp,kind="question")
            q.optJSONArray("options")?.objects()?.forEachIndexed{index,o->
                val label=o.s("label");val optionId="$questionId:option:$index"
                val description=o.s("description")
                LaunchedEffect(label,description,PocketImmersion.enabled,PocketImmersion.planVersion){PocketImmersion.offer(optionId,label,"question option");PocketImmersion.offer("$optionId:description",description,"question option explanation")}
                OutlinedButton({saveAnswer(q.s("id"),label)},enabled=editable,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.outlinedButtonColors(containerColor=if(answers[q.s("id")]==label)Mint.copy(alpha=.14f)else Color.Transparent)){
                    Column(Modifier.fillMaxWidth()){
                        ImmersionText(optionId,label,color=Paper,kind="question option",rescue=false)
                        if(description.isNotBlank())ImmersionText("$optionId:description",description,color=Muted,fontSize=14.sp,kind="question option explanation",rescue=false)
                    }
                }
            };OutlinedTextField(answers[q.s("id")]?:"",{saveAnswer(q.s("id"),it)},readOnly=!editable,label={BilingualLabel("Your answer")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp))};Button({val a=JSONObject();answers.forEach{(k,v)->a.put(k,v)};submit(JSONObject().put("answers",a))},enabled=editable){BilingualLabel("Send answers")}}
        else{ImmersionText("approval:"+id,p.s("reason",p.s("message","Review this request before continuing.")),fontSize=15.sp);RichText(p.s("command",p.toString(2)));if(r.s("method").contains("commandExecution")||r.s("method").contains("fileChange")){Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Button({submit(JSONObject().put("decision","accept"))},enabled=editable){BilingualLabel("Allow once")};OutlinedButton({submit(JSONObject().put("decision","decline"))},enabled=editable){BilingualLabel("Decline",color=Coral)}}}else WorkflowText("Answer this request in the terminal.",color=Coral,fontSize=13.sp)}
        if(delivery.isNotBlank()){WorkflowText(when(delivery){"sending"->"Submitting decision…";"submitted"->"Decision submitted";"expired"->"This request was answered or expired. Check current work.";"unconfirmed"->"Delivery unconfirmed. Check current work before trying again.";else->QuestionDelivery.problem(draftKey).ifBlank{"Decision was not accepted. Review and try again."}},color=if(delivery=="unconfirmed")Coral else Muted,fontSize=13.sp)
            if(delivery in listOf("unconfirmed","expired"))TextButton({Pocket.refreshDetail();Pocket.refresh()}){BilingualLabel("Check current work")}}

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
    val clearable=available||PocketSpeech.count>0
    TextButton({PocketSpeech.clearAll()},enabled=clearable,
        modifier=Modifier.heightIn(min=48.dp).widthIn(min=72.dp).semantics{contentDescription=PocketImmersion.label("Clear all saved speech")},
        contentPadding=PaddingValues(horizontal=10.dp)){
        BilingualLabel("Clear all",color=if(clearable)Coral else Muted,fontSize=14.sp)
    }
}

/** Captions supply the playback context, so conversations need only one speech surface. */
@Composable fun ConversationSpeechDock(containerColor:androidx.compose.ui.graphics.Color=Panel){
    ForegroundWorkNotice()
    if(PocketVoice.captureFeedbackVisible)return
    if(PocketSpeechCaptions.state.visible)SpeechCaptionBanner(playbackControls=PocketSpeech.count>0,containerColor=containerColor)
    else SpeechPlayer(containerColor)
}
