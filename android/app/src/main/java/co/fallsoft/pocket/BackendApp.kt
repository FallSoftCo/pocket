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
        if(PocketVoice.active||backend !in listOf("codex","losangelex"))return
        val owner=backendOwner(Pocket.base,Pocket.token)
        desired=owner to backend
        notification=null
        Pocket.prefs.edit().putString("backend:$owner",backend).apply()
    }
    fun consumed(){notification=null}
    fun teamSelected()=Pocket.prefs.getString("backend:"+backendOwner(Pocket.base,Pocket.token),"codex")=="losangelex"
}

internal fun sameTeamHistoryScope(current:TeamScope,destination:TeamScope)=current.copy(replyTo=null)==destination.copy(replyTo=null)
internal fun teamReplyRecipient(scope:TeamScope,author:String)=if(author in listOf("you","system"))if(scope.direct)scope.recipient else "" else author
internal fun teamEnterSends(enter:Boolean,shift:Boolean)=enter&&!shift

private data class BackendSetting(val selected:String,val select:(String)->Unit)
private val LocalBackendSetting=staticCompositionLocalOf<BackendSetting?>{null}

@Composable fun BackendSettingsControl(){
    val setting=LocalBackendSetting.current?:return
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        BilingualLabel("Backend",centered=false)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("codex" to "Codex","losangelex" to "Losangelex").forEach{(id,label)->
                FilterChip(selected=setting.selected==id,onClick={setting.select(id)},label={BilingualLabel(label)},enabled=!PocketVoice.active,modifier=Modifier.weight(1f).heightIn(min=48.dp))
            }
        }
    }
}

@Composable fun BackendApp(){
    val owner=backendOwner(Pocket.base,Pocket.token)
    var backend by remember(owner){mutableStateOf(Pocket.prefs.getString("backend:$owner","codex")?:"codex")}
    var losangelex by remember(owner){mutableStateOf(JSONObject())}
    var discovering by remember(owner){mutableStateOf(true)}
    var teamSettings by remember(owner){mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    suspend fun discover(){
        discovering=true
        try{val status=Pocket.api("/api/backends");losangelex=status.optJSONArray("backends")?.objects()?.firstOrNull{it.s("id")=="losangelex"}?:JSONObject()}
        catch(_:Exception){losangelex=JSONObject()}
        finally{discovering=false}
    }
    LaunchedEffect(owner){discover()}
    LaunchedEffect(owner,BackendNavigation.desired){BackendNavigation.desired?.takeIf{it.first==owner}?.let{backend=it.second;teamSettings=false}}
    val requestedNotification=BackendNavigation.notification
    LaunchedEffect(owner,requestedNotification){if(requestedNotification?.first==owner)teamSettings=false}
    CompositionLocalProvider(LocalBackendSetting provides BackendSetting(backend){chosen->
        BackendNavigation.select(chosen);backend=chosen;teamSettings=false
    }){
    Box(Modifier.fillMaxSize()){
    Column(Modifier.fillMaxSize().imePadding()){
        if(backend=="losangelex")ProfileSwitcher()
        Box(Modifier.weight(1f)){
            if(backend=="codex")PocketApp()
            else if(discovering)Column(Modifier.padding(24.dp)){CircularProgressIndicator();Text("Checking Losangelex…")}
            else if(!losangelex.optBoolean("configured"))Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("Connect Losangelex",style=MaterialTheme.typography.titleLarge)
                TextButton({teamSettings=true}){BilingualLabel("Settings")}
                Text("Your NextComp host needs a Losangelex backend connection. Register its private endpoint and token file on the host, then reload.")
                Button({scope.launch{discover()}}){Text("Reload backends")}
                TextButton({BackendNavigation.select("codex");backend="codex"}){Text("Use Codex")}
            }
            else key(owner,losangelex.s("environmentId")){LosangelexScreen(owner+losangelex.s("environmentId"),onSettings={teamSettings=true})}
        }
    }
    BackHandler(enabled=backend=="losangelex"&&teamSettings){teamSettings=false}
    if(backend=="losangelex"&&teamSettings)Surface(Modifier.fillMaxSize()){
        Column{TextButton({teamSettings=false}){BilingualLabel("Back")};Box(Modifier.weight(1f)){SettingsScreen()}}
    }
    }
    }
}
