package co.fallsoft.pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun SessionGroupCard(group:SessionGroup,now:Long){
    var expanded by remember(group.task.id,Pocket.local){mutableStateOf(Pocket.prefs.getBoolean(Pocket.key("agentsExpanded:${group.task.id}"),false))}
    var childStart by remember(group.task.id,Pocket.local){mutableIntStateOf(Pocket.prefs.getInt(Pocket.key("agentsPage:${group.task.id}"),0))}
    val start=childStart.coerceAtMost(((group.children.size-1).coerceAtLeast(0)/8)*8)
    val childIds=group.children.map{it.id}.toSet()
    val unread=maxOf(group.children.sumOf{it.unreadCount},Pocket.notifications.count{it.s("thread_id") in childIds&&!it.optBoolean("_read")&&!PocketAttention.dismissed(it.optLong("id"))})
    Column{
        TaskCard(group.task,now)
        if(group.children.isNotEmpty()){
            TextButton({expanded=!expanded;Pocket.prefs.edit().putBoolean(Pocket.key("agentsExpanded:${group.task.id}"),expanded).apply()},modifier=Modifier.fillMaxWidth()){
                BilingualLabel("${group.children.size} agents · ${group.children.count{it.status=="active"}} working"+(if(unread>0)" · $unread unread" else "")+" · "+if(expanded)"Hide" else "Show results")
            }
            if(expanded){group.children.drop(start).take(8).forEach{AgentResultCard(it)}
                if(group.children.size>8)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    TextButton({childStart=(start-8).coerceAtLeast(0);Pocket.prefs.edit().putInt(Pocket.key("agentsPage:${group.task.id}"),childStart).apply()},enabled=start>0){BilingualLabel("Previous agents")}
                    Text("${start+1}–${minOf(start+8,group.children.size)} / ${group.children.size}",color=Muted,modifier=Modifier.padding(top=14.dp))
                    TextButton({childStart=start+8;Pocket.prefs.edit().putInt(Pocket.key("agentsPage:${group.task.id}"),childStart).apply()},enabled=start+8<group.children.size){BilingualLabel("Next agents")}
                }
            }
        }
    }
}
@Composable fun AgentResultCard(task:Task){
    Column(Modifier.fillMaxWidth().background(Panel).clickable{Pocket.open(task.id)}.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text(listOf(task.agentNickname.ifBlank{task.title},task.agentRole,if(task.status=="notLoaded")"Status not yet checked" else task.status,if(task.unreadCount>0)"${task.unreadCount} unread" else "").filter{it.isNotBlank()}.joinToString(" · "),color=Mint,minLines=2,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        val parent=Pocket.tasks.firstOrNull{it.id==task.parentThreadId}
        Text(parent?.let{"Parent: "+it.agentNickname.ifBlank{it.title}}?:if(task.parentThreadId!=null)"Parent task outside this page" else "Parent identity unavailable",color=Muted,minLines=1,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(task.preview.ifBlank{"Open to review this agent’s available work."},color=Paper,minLines=3,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(if(task.canAcceptDirectInput)"Open agent · direct guidance available" else "Review results · guide through parent task",color=Muted,minLines=2,maxLines=2)
    }
}
internal fun conversationIdentityPending()=Pocket.selected!=null&&Pocket.tasks.none{it.id==Pocket.selected}&&Pocket.detail?.optJSONObject("thread")==null
internal fun readOnlyChildSelected():Boolean {
    val task=Pocket.tasks.firstOrNull{it.id==Pocket.selected}
    val t=Pocket.detail?.optJSONObject("thread")?.takeIf{it.s("id")==Pocket.selected}
    val child=t?.optBoolean("isChild")==true||t?.s("parentThreadId")?.isNotBlank()==true||task?.isChild==true
    val direct=if(t!=null&&t.has("canAcceptDirectInput")&&!t.isNull("canAcceptDirectInput"))t.optBoolean("canAcceptDirectInput") else task?.canAcceptDirectInput==true
    return child&&!direct
}
// Mic integration must not capture against an unresolved or unsupported destination.
internal fun conversationInputUnavailable()=conversationIdentityPending()||readOnlyChildSelected()
@Composable fun LoadingSessionIdentity(){Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Loading conversation…",color=Paper);ErrorBanner();CircularProgressIndicator();TextButton({Pocket.closeTask()}){BilingualLabel("Back")}}}
@Composable fun ChildConversationScreen(){
    val task=Pocket.tasks.firstOrNull{it.id==Pocket.selected};val t=Pocket.detail?.optJSONObject("thread")
    val parent=task?.parentThreadId?:t?.s("parentThreadId")?.takeIf{it.isNotBlank()}
    Column(Modifier.fillMaxSize()){
        Text(task?.agentNickname?.ifBlank{task.title}?:t?.s("name","Delegated agent")?:"Delegated agent",modifier=Modifier.padding(16.dp),color=Paper,style=MaterialTheme.typography.titleLarge)
        Text("This agent reports to its parent task. Review its results here and open the parent to send guidance. Direct replies to this agent are unavailable.",modifier=Modifier.padding(horizontal=16.dp),color=Muted)
        InlineCaptureStatus()
        if(captureControl(PocketVoice.active,PocketVoice.state,false)==CaptureControl.STOP_SEND)VoiceLaunchButton(threadId=PocketVoice.targetThread,bar=true,modifier=Modifier.padding(horizontal=16.dp))
        ErrorBanner()
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(PocketTranscript.loading)item{CircularProgressIndicator()}
            items(PocketTranscript.rows,key={it.s("id")}){row->TranscriptRow(row)}
        }
        Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            TextButton({Pocket.closeTask()}){BilingualLabel("Back")}
            if(parent!=null)Button({Pocket.open(parent,keyboard=true)}){BilingualLabel("Open parent task")}
        }
    }
}
