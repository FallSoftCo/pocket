package co.fallsoft.pocket

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.MessageDigest

fun backendOwner(base:String,token:String)=MessageDigest.getInstance("SHA-256").digest((base+"\u0000"+token).toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
data class TeamScope(val team:String,val task:String,val recipient:String="",val direct:Boolean=false,val replyTo:Long?=null) {
    fun draftKey(owner:String)="team-draft:$owner:$team:$task:${if(direct)"direct" else "room"}:$recipient:${replyTo?:"root"}"
    fun message(id:String,text:String)=JSONObject().put("commandId",id).put("task",task).put("body",text)
        .put("targets",org.json.JSONArray(if(recipient.isBlank())emptyList<String>() else listOf(recipient)))
        .put("visibility",if(direct)"direct" else "room").apply{replyTo?.let{put("replyTo",it)}}
}

object BackendNavigation {
    var desired by mutableStateOf<Pair<String,String>?>(null);private set
    var notification by mutableStateOf<Pair<String,String>?>(null);private set
    fun open(id:String):Boolean {
        val owner=backendOwner(Pocket.base,Pocket.token)
        val team=id.startsWith("lx-")
        desired=owner to if(team)"losangelex" else "codex"
        Pocket.prefs.edit().putString("backend:$owner",desired!!.second).apply()
        notification=if(team)owner to id else null
        return team
    }
    fun select(backend:String){
        if(PocketVoice.active||backend !in listOf("codex","losangelex","lax"))return
        val owner=backendOwner(Pocket.base,Pocket.token)
        desired=owner to backend
        notification=null
        Pocket.prefs.edit().putString("backend:$owner",backend).apply()
        if(backend in listOf("losangelex","lax"))Pocket.pauseConversationReads()else Pocket.refreshDetail()
    }
    fun consumed(){notification=null}
    fun nativeSelected()=Pocket.prefs.getString("backend:"+backendOwner(Pocket.base,Pocket.token),"codex")=="lax"
    fun teamSelected()=Pocket.prefs.getString("backend:"+backendOwner(Pocket.base,Pocket.token),"codex") in listOf("losangelex","lax")
}

internal fun sameTeamHistoryScope(current:TeamScope,destination:TeamScope)=
    current.copy(replyTo=null,recipient=if(current.direct)current.recipient else "")==destination.copy(replyTo=null,recipient=if(destination.direct)destination.recipient else "")
internal fun teamReplyRecipient(scope:TeamScope,author:String)=if(author in listOf("you","system"))if(scope.direct)scope.recipient else "" else author
internal fun teamEnterSends(enter:Boolean,shift:Boolean)=enter&&!shift

private data class BackendSetting(val selected:String,val choices:List<Pair<String,String>>,val select:(String)->Unit,val workRecord:()->Unit)
private val LocalBackendSetting=staticCompositionLocalOf<BackendSetting?>{null}

@Composable fun BackendSettingsControl(){
    val setting=LocalBackendSetting.current?:return
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedButton(setting.workRecord,Modifier.fillMaxWidth(),enabled=!PocketVoice.active){InterfaceText("Work record")}
        BilingualLabel("Backend",centered=false)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            setting.choices.forEach{(id,label)->
                FilterChip(selected=setting.selected==id,onClick={setting.select(id)},label={BilingualLabel(label)},enabled=!PocketVoice.active,modifier=Modifier.weight(1f).heightIn(min=48.dp))
            }
        }
    }
}

@Composable fun BackendApp(){
    val owner=backendOwner(Pocket.base,Pocket.token)
    var backend by remember(owner){mutableStateOf(Pocket.prefs.getString("backend:$owner","codex")?:"codex")}
    var losangelex by remember(owner){mutableStateOf(JSONObject())}
    var lax by remember(owner){mutableStateOf(JSONObject())}
    var choices by remember(owner){mutableStateOf(listOf("codex" to "Codex","losangelex" to "Losangelex"))}
    var discovering by remember(owner){mutableStateOf(true)}
    var teamSettings by remember(owner){mutableStateOf(false)}
    var workRecord by remember(owner){mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    suspend fun discover(){
        discovering=true
        try{
            val status=Pocket.api("/api/backends");val available=status.optJSONArray("backends")?.objects().orEmpty()
            losangelex=available.firstOrNull{it.s("id")=="losangelex"}?:JSONObject()
            lax=available.firstOrNull{it.s("id")=="lax"}?:JSONObject()
            if(lax.optBoolean("available"))Pocket.captureEnvironment(Pocket.environmentId)?.let{HollywoodNotifications.rememberBinding(it,lax);HollywoodNotifications.catchUp(it,lax)}
            choices=available.filter{it.optBoolean("configured")}.map{it.s("id") to it.s("name")}
            if(status.optBoolean("canonical")&&status.s("defaultBackend")=="lax"){
                backend="lax";Pocket.prefs.edit().putString("backend:$owner","lax").apply();Pocket.pauseConversationReads()
            }
        }
        catch(_:Exception){losangelex=JSONObject()}
        finally{discovering=false}
    }
    LaunchedEffect(PocketVoice.active){if(PocketVoice.active)workRecord=false}
    LaunchedEffect(owner){discover()}
    LaunchedEffect(owner,BackendNavigation.desired){BackendNavigation.desired?.takeIf{it.first==owner}?.let{backend=it.second;teamSettings=false}}
    val requestedNotification=BackendNavigation.notification
    LaunchedEffect(owner,requestedNotification){if(requestedNotification?.first==owner)teamSettings=false}
    CompositionLocalProvider(LocalBackendSetting provides BackendSetting(backend,choices,{chosen->
        BackendNavigation.select(chosen);backend=chosen;teamSettings=false
    },{workRecord=true})){
    Box(Modifier.fillMaxSize()){
    Column(Modifier.fillMaxSize().imePadding()){
        Box(Modifier.weight(1f)){
            if(backend=="lax"){
                if(discovering)Column(Modifier.padding(24.dp)){CircularProgressIndicator();InterfaceText("Connecting to Hollywood…")}
                else if(!lax.optBoolean("available"))Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    InterfaceText("Hollywood connection unavailable");TextButton({scope.launch{discover()}}){BilingualLabel("Reconnect")};TextButton({teamSettings=true}){BilingualLabel("Settings")}
                }else key(owner,lax.s("bindingId")){HollywoodScreen(owner,lax,onSettings={teamSettings=true})}
            }
            else if(backend=="codex")PocketApp()
            else if(discovering)Column(Modifier.padding(24.dp)){CircularProgressIndicator();InterfaceText("Checking Losangelex…")}
            else if(!losangelex.optBoolean("configured"))Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                InterfaceText("Connect Losangelex",style=MaterialTheme.typography.titleLarge)
                TextButton({teamSettings=true}){BilingualLabel("Settings")}
                InterfaceText("Your NextComp host needs a Losangelex backend connection. Register its private endpoint and token file on the host, then reload.")
                Button({scope.launch{discover()}}){BilingualLabel("Reload backends")}
                TextButton({BackendNavigation.select("codex");backend="codex"}){BilingualLabel("Use Codex")}
            }
            else key(owner,losangelex.s("environmentId")){LosangelexScreen(owner+losangelex.s("environmentId"),onSettings={teamSettings=true})}
        }
    }
    BackHandler(enabled=backend in listOf("losangelex","lax")&&teamSettings&&!workRecord){teamSettings=false}
    if(backend in listOf("losangelex","lax")&&teamSettings)Surface(Modifier.fillMaxSize()){
        Column{TextButton({teamSettings=false}){BilingualLabel("Back")};Box(Modifier.weight(1f)){SettingsScreen()}}
    }
    if(workRecord)Surface(Modifier.fillMaxSize()){WorkRecordScreen{workRecord=false}}
    }
    }
}

/** A group opens through backend callbacks; these IDs are never stock Codex destinations. */
internal fun roomSessionGroup(room:JSONObject):SessionGroup {
    val members=room.optJSONArray("members")?.objects().orEmpty()
    val task=Task("lx-room:"+room.s("id"),room.s("title"),"",if(members.any{it.s("status")=="working"})"active" else "idle",
        room.optLong("updated_at"),false,preview=room.s("preview").ifBlank{room.s("purpose")})
    val children=members.filter{it.s("thread").isNotBlank()}.map{member->
        Task("${task.id}:${member.s("id")}",member.s("agent"),"",if(member.s("status")=="working")"active" else "idle",0,false,
            preview=member.s("preview"),agentNickname=member.s("agent"),agentRole=member.s("role"))
    }
    return SessionGroup(task,children)
}
internal fun notificationTeamScope(team:String,task:String,agent:String,visibility:String)=
    TeamScope(team,task,if(visibility.startsWith("direct:"))agent else "",visibility.startsWith("direct:"))

internal fun scopedRoomMessage(scope:TeamScope,id:String,text:String,room:JSONObject?,lobby:String="",coordinator:String=""):JSONObject {
    if(scope.direct||scope.recipient.isNotBlank())return scope.message(id,text)
    val observed=requireNotNull(room){"Conversation destination is unavailable"}
    require(observed.s("task")==scope.task){"Conversation destination is unavailable"}
    // The lobby is the coordinator's conversation. Its membership is discovery,
    // never an implicit broadcast or a remembered notification recipient.
    if(lobby.isNotBlank()&&scope.task==lobby){
        require(coordinator.isNotBlank()){"Conversation coordinator is unavailable"}
        return scope.message(id,text).put("targets",org.json.JSONArray(listOf(coordinator)))
    }
    val targets=observed.optJSONArray("members")?.objects().orEmpty().map{it.s("agent")}.filter{it.isNotBlank()}.distinct()
    require(targets.isNotEmpty()){"Conversation has no destinations"}
    return scope.message(id,text).put("targets",org.json.JSONArray(targets))
}
