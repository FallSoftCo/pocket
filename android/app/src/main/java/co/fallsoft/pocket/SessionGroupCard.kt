package co.fallsoft.pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

@Composable fun SessionGroupCard(group:SessionGroup,now:Long,
    onChild:(Task)->Unit={Pocket.open(it.id)},parentTitle:String?=null,
    identityScope:String=Pocket.environmentId,preferenceKey:(String)->String={Pocket.key(it)},
    sourceNotifications:List<org.json.JSONObject> = Pocket.notifications,
    attentionOnly:Boolean=false,onParent:(()->Unit)?=null,
    onArchiveChild:(Task)->Unit={Pocket.archiveTask(it.id)},onRestoreChild:(Task)->Unit={Pocket.restoreTask(it.id)},
    parentCard:(@Composable ((@Composable ()->Unit)?)->Unit)?=null){
    var expanded by remember(group.task.id,identityScope){mutableStateOf(Pocket.prefs.getBoolean(preferenceKey("agentsExpanded:${group.task.id}"),false))}
    var childStart by remember(group.task.id,identityScope){mutableIntStateOf(Pocket.prefs.getInt(preferenceKey("agentsPage:${group.task.id}"),0))}
    var attentionStart by remember(group.task.id,identityScope,attentionOnly){mutableIntStateOf(0)}
    val start=(if(attentionOnly)attentionStart else childStart).coerceAtMost(((group.children.size-1).coerceAtLeast(0)/8)*8)
    fun page(next:Int){if(attentionOnly)attentionStart=next else {childStart=next;Pocket.prefs.edit().putInt(preferenceKey("agentsPage:${group.task.id}"),next).apply()}}
    val childIds=group.children.map{it.id}.toSet()
    val unread=maxOf(group.children.sumOf{it.unreadCount},sourceNotifications.count{it.s("thread_id") in childIds&&!it.optBoolean("_read")&&!PocketAttention.dismissed(it.optLong("id"))})
    Column{
        val badge:(@Composable ()->Unit)?=if(group.children.isEmpty()||attentionOnly)null else {{
            val working=group.children.count{it.status in listOf("active","pending")}
            Surface(onClick={expanded=!expanded;Pocket.prefs.edit().putBoolean(preferenceKey("agentsExpanded:${group.task.id}"),expanded).apply()},
                color=if(unread>0)Mint.copy(alpha=.16f)else Ink,
                shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier=Modifier.height(48.dp).widthIn(min=48.dp).semantics{
                    contentDescription="${group.children.size} agents, $working working, $unread unread; "+if(expanded)"hide agents" else "show agents"
                }){
                Row(Modifier.padding(horizontal=8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    SymbolIcon("Layers",null,Modifier.size(18.dp))
                    Text(group.children.size.toString(),color=Paper,style=MaterialTheme.typography.labelMedium)
                    if(working>0||unread>0)Box(Modifier.size(5.dp).background(if(unread>0)Mint else Coral,androidx.compose.foundation.shape.CircleShape))
                }
            }
        }}
        if(parentCard!=null)parentCard(badge)else TaskCard(group.task,now,headerBadge=badge)
        if(group.children.isNotEmpty()){
            if(expanded||attentionOnly){group.children.drop(start).take(8).forEach{task->AgentResultCard(task,onOpen={onChild(task)},parentTitle=parentTitle?:"Parent: ${group.task.title}",onParent=onParent,onArchive={onArchiveChild(task)},onRestore={onRestoreChild(task)})}
                if(group.children.size>8)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    TextButton({page((start-8).coerceAtLeast(0))},enabled=start>0){BilingualLabel("Previous agents")}
                    Text("${start+1}–${minOf(start+8,group.children.size)} / ${group.children.size}",color=Muted,modifier=Modifier.padding(top=14.dp))
                    TextButton({page(start+8)},enabled=start+8<group.children.size){BilingualLabel("Next agents")}
                }
            }
        }
    }
}
@Composable fun AgentResultCard(task:Task,onOpen:()->Unit={Pocket.open(task.id)},parentTitle:String?=null,headerBadge:(@Composable ()->Unit)?=null,onArchive:(()->Unit)?={Pocket.archiveTask(task.id)},onRestore:()->Unit={Pocket.restoreTask(task.id)},onParent:(()->Unit)?=null){
    SessionArchiveReveal(task,onArchive){
    Column(Modifier.fillMaxWidth().background(Panel).clickable(onClick=onOpen).padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
            Text(listOf(task.agentNickname.ifBlank{task.title},task.agentRole,if(task.status=="notLoaded")"Status not yet checked" else task.status,if(task.unreadCount>0)"${task.unreadCount} unread" else "").filter{it.isNotBlank()}.joinToString(" · "),modifier=Modifier.weight(1f),color=Mint,minLines=2,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            headerBadge?.invoke()
            SessionRestoreAction(task,onRestore)
        }
        val parent=if(parentTitle==null)Pocket.tasks.firstOrNull{it.id==task.parentThreadId}else null
        Text(parentTitle?:parent?.let{"Parent: "+it.agentNickname.ifBlank{it.title}}?:if(task.parentThreadId!=null)"Parent task outside this page" else "Parent identity unavailable",color=Muted,minLines=1,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        task.attentionLabel()?.let{BilingualLabel(it,color=Coral,centered=false)}
        Text(task.preview.ifBlank{"Open to review this agent’s available work."},color=Paper,minLines=3,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            TextButton(onOpen,modifier=Modifier.weight(1f).heightIn(min=48.dp)){BilingualLabel(if(task.attention?.items?.any{it.kind in setOf("question","approval")}==true)"Review request"else "Review agent")}
            if(onParent!=null)TextButton(onParent,modifier=Modifier.weight(1f).heightIn(min=48.dp)){BilingualLabel("Open parent task")}
        }
    }
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
@Composable fun LoadingSessionIdentity(){Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){InterfaceText("Loading conversation…",color=Paper);ErrorBanner();CircularProgressIndicator();TextButton({Pocket.closeTask()}){BilingualLabel("Back")}}}
@Composable fun ChildConversationScreen(){
    val task=Pocket.tasks.firstOrNull{it.id==Pocket.selected};val t=Pocket.detail?.optJSONObject("thread")
    val parent=task?.parentThreadId?:t?.s("parentThreadId")?.takeIf{it.isNotBlank()}
    val parentTask=Pocket.tasks.firstOrNull{it.id==parent}
    val requests=Pocket.detail?.takeIf{it.optJSONObject("thread")?.s("id")==Pocket.selected}?.optJSONArray("pending")?.objects().orEmpty()
    Column(Modifier.fillMaxSize()){
        Text(task?.agentNickname?.ifBlank{task.title}?:t?.s("name","Delegated agent")?:"Delegated agent",modifier=Modifier.padding(16.dp),color=Paper,style=MaterialTheme.typography.titleLarge)
        if(parent!=null)Text("Parent: "+(parentTask?.title?.takeIf{it.isNotBlank()}?:parent),modifier=Modifier.padding(horizontal=16.dp),color=Paper)
        InterfaceText("Answer this agent’s questions and approvals here. To guide or recover its work, open the parent task. Direct messages to this agent are unavailable.",modifier=Modifier.padding(horizontal=16.dp),color=Muted)
        InlineCaptureStatus()
        ErrorBanner()
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            items(requests,key={"pending:"+it.s("id")}){RequestCard(it)}
            if(PocketTranscript.loading)item{CircularProgressIndicator()}
            items(PocketTranscript.rows.filter{it.s("kind")!="request"},key={it.s("id")}){row->TranscriptRow(row)}
        }
        ForegroundWorkNotice()
        Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            TextButton({Pocket.closeTask()}){BilingualLabel("Back")}
            if(parent!=null)Button({Pocket.open(parent,keyboard=true)}){BilingualLabel("Open parent task")}
        }
    }
}
