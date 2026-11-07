package co.fallsoft.pocket

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.MessageDigest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

fun backendOwner(base:String,token:String):String {
    // Persisted namespaces must retain their exact historical digest. Avoid
    // locale formatters in the navigation/composition hot path.
    val bytes=MessageDigest.getInstance("SHA-256").digest((base+"\u0000"+token).toByteArray(Charsets.UTF_8))
    val alphabet="0123456789abcdef";val result=CharArray(bytes.size*2)
    bytes.forEachIndexed{index,byte->val value=byte.toInt() and 255;result[index*2]=alphabet[value ushr 4];result[index*2+1]=alphabet[value and 15]}
    return String(result)
}
data class TeamScope(val team:String,val task:String,val recipient:String="",val direct:Boolean=false,val replyTo:Long?=null) {
    fun draftKey(owner:String)="team-draft:$owner:$team:$task:${if(direct)"direct" else "room"}:$recipient:${replyTo?:"root"}"
    fun message(id:String,text:String)=JSONObject().put("commandId",id).put("task",task).put("body",text)
        .put("targets",org.json.JSONArray(if(recipient.isBlank())emptyList<String>() else listOf(recipient)))
        .put("visibility",if(direct)"direct" else "room").apply{replyTo?.let{put("replyTo",it)}}
}

/** Retired room notification IDs must never become stock thread destinations. */
object BackendNavigation {
    var desired by mutableStateOf<Pair<String,String>?>(null);private set
    var notification by mutableStateOf<Pair<String,String>?>(null);private set
    fun open(id:String):Boolean {
        if(id.startsWith("lx-")){Pocket.error="This notification belongs to retired room work";return true}
        select("codex");return false
    }
    fun select(backend:String){
        if(backend!="codex")return
        val owner=backendOwner(Pocket.base,Pocket.token)
        desired=owner to "codex";notification=null
        Pocket.prefs.edit().putString("backend:$owner","codex").apply()
    }
    fun consumed(){notification=null}
    fun nativeSelected()=false
    fun teamSelected()=false
}

internal fun sameTeamHistoryScope(current:TeamScope,destination:TeamScope)=
    current.copy(replyTo=null,recipient=if(current.direct)current.recipient else "")==destination.copy(replyTo=null,recipient=if(destination.direct)destination.recipient else "")
internal fun teamReplyRecipient(scope:TeamScope,author:String)=if(author in listOf("you","system"))if(scope.direct)scope.recipient else "" else author
internal fun teamEnterSends(enter:Boolean,shift:Boolean)=enter&&!shift

private val LocalWorkRecord=staticCompositionLocalOf<(() -> Unit)?>{null}

@Composable fun BackendSettingsControl(){
    val open=LocalWorkRecord.current?:return
    OutlinedButton(open,Modifier.fillMaxWidth(),enabled=!PocketVoice.active){InterfaceText("Work record")}
}

/** One stock Codex navigation tree, including upgrades from a saved room tab. */
@Composable fun BackendApp(){
    val owner=remember(Pocket.base,Pocket.token){backendOwner(Pocket.base,Pocket.token)}
    var workRecord by remember(owner){mutableStateOf(false)}
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(owner){BackendNavigation.select("codex")}
    LaunchedEffect(PocketVoice.active){if(PocketVoice.active)workRecord=false}
    CompositionLocalProvider(LocalWorkRecord provides {focus.clearFocus(force=true);keyboard?.hide();workRecord=true}){
        Box(Modifier.fillMaxSize()){
            PocketApp()
            if(workRecord)Surface(Modifier.fillMaxSize()){
                Column(Modifier.fillMaxSize().imePadding().semantics{testTagsAsResourceId=true}){
                    Box(Modifier.weight(1f)){WorkRecordScreen{workRecord=false}}
                    BackendOverlayControls({workRecord=false})
                }
            }
            BackHandler(enabled=workRecord){workRecord=false}
        }
    }
}

/** Visible controls for the retained work context while settings/records cover the chat. */
@Composable private fun BackendOverlayControls(onBack:()->Unit){
    InlineCaptureStatus(modifier=Modifier.testTag("native-settings-capture-status"))
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),
        horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
        TextButton(onBack,Modifier.heightIn(min=48.dp).testTag("native-settings-back")){BilingualLabel("Back")}
        InlineSpeechVolume(Modifier.width(inlineVolumeControlWidth()))
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
