package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Rendering is source-explicit; this view never changes global profile or dispatches commands. */
internal data class UnifiedWorkActions(
    val open:(WorkEntry<WorkCatalogPayload>,Boolean)->Unit,
    val rename:(WorkEntry<WorkCatalogPayload>,String,()->Unit)->Unit,
    val watch:(WorkEntry<WorkCatalogPayload>,Boolean)->Unit,
    val restore:(WorkEntry<WorkCatalogPayload>)->Unit
)
private data class UnifiedWorkRow(
    val entry:WorkEntry<WorkCatalogPayload>,val source:WorkSourceSnapshot<WorkCatalogPayload>,
    val task:Task?=null,val group:SessionGroup?=null,val room:JSONObject?=null,val orphan:Boolean=false
)
@Composable private fun WorkOriginBadge(entry:WorkEntry<WorkCatalogPayload>,available:Boolean) {
    val kind=if(entry.key.kind==WorkKind.ROOM)"Room" else "Session"
    val name=entry.value.destination.environment.name
    Row(Modifier.widthIn(max=110.dp).semantics{contentDescription="$kind on $name; "+if(available)"catalog verified" else "catalog unavailable"},
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
        SymbolIcon(if(entry.key.kind==WorkKind.ROOM)"Layers" else "Codex",null,Modifier.size(16.dp),tint=if(available)Muted else Coral)
        Text(name,color=if(available)Muted else Coral,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

/** Shared all-source body for the existing session/room docks. No additional header or toolbar. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun UnifiedWorkList(
    snapshots:List<WorkSourceSnapshot<WorkCatalogPayload>>,discovering:Boolean,
    actions:UnifiedWorkActions,modifier:Modifier=Modifier,state:LazyListState=rememberLazyListState(),
    query:String="",workingOnly:Boolean=false,followingOnly:Boolean=false,showHistory:Boolean=false,
    onRefresh:()->Unit={}
) {
    var order by remember{mutableStateOf(emptyList<WorkItemKey>())}
    var touching by remember{mutableStateOf(false)}
    val rows=remember(snapshots){
        snapshots.flatMap{source->
            val sessions=source.entries.filter{it.key.kind==WorkKind.SESSION}
            val tasks=sessions.associate{it.key.id to catalogTask(JSONObject(it.value.body))}
            val parents=sessionGroups(tasks.values.toList())
            val mapped=parents.mapNotNull{group->sessions.firstOrNull{it.key.id==group.task.id}?.let{parent->
                val familyAt=maxOf(parent.workAt,group.children.maxOfOrNull{child->sessions.firstOrNull{it.key.id==child.id}?.workAt?:0}?:0)
                UnifiedWorkRow(parent.copy(workAt=familyAt),source,group.task,group)
            }}
            // Retain child ownership; children with an unloaded parent remain discoverable.
            mapped+ungroupedChildren(tasks.values.toList()).mapNotNull{task->
                sessions.firstOrNull{it.key.id==task.id}?.let{UnifiedWorkRow(it,source,task,orphan=true)}
            }+source.entries.filter{it.key.kind==WorkKind.ROOM}.map{UnifiedWorkRow(it,source,room=JSONObject(it.value.body))}
        }
    }
    val rank=order.withIndex().associate{it.value to it.index}
    val sorted=rows.sortedWith(compareByDescending<UnifiedWorkRow>{it.entry.workAt}
        .thenBy{rank[it.entry.key]?:Int.MAX_VALUE}.thenBy{it.entry.key.source.environment}.thenBy{it.entry.key.id})
    // Content can update in place during interaction. Reordering waits for reading to settle.
    var displayed by remember{mutableStateOf(emptyList<UnifiedWorkRow>())}
    val interacting=state.isScrollInProgress||touching
    LaunchedEffect(rows,interacting){
        val latest=rows.associateBy{it.entry.key}
        displayed=displayed.mapNotNull{latest[it.entry.key]}+sorted.filter{next->displayed.none{it.entry.key==next.entry.key}}
        if(!interacting){
            kotlinx.coroutines.delay(750)
            val byId=sorted.associateBy{it.entry.key.toString()}
            val ranked=sorted.map{row->SessionRank(row.entry.key.toString(),row.entry.workAt,
                row.task?.status in setOf("active","pending"))}
            val next=liveSessionOrder(order.filter{it.toString() in byId}.map{it.toString()},ranked,System.currentTimeMillis())
            displayed=next.mapNotNull{byId[it]};order=displayed.map{it.entry.key}
        }
    }
    val terms=query.trim().split(Regex("\\s+")).filter{it.isNotBlank()}
    val shown=displayed.filter{row->
        val text=(listOf(row.entry.value.destination.environment.name,row.task?.title,row.task?.preview,row.task?.cwd,row.task?.catalogContext,row.task?.agentNickname,row.room?.s("name"),row.room?.s("purpose"))+
            row.group?.children.orEmpty().flatMap{listOf(it.title,it.preview,it.cwd,it.catalogContext,it.agentNickname,it.agentRole)}).filterNotNull().joinToString(" ")
        val active=row.task?.let{it.status in setOf("active","pending")||it.needsInput||row.group?.children?.any{child->child.status in setOf("active","pending")||child.needsInput}==true}?:row.room?.let{HollywoodRoomActivity.read(it,row.source.available)?.let{a->a.scheduled||a.needsHuman}==true}?:false
        terms.all{text.contains(it,true)}&&(!workingOnly||active)&&(!followingOnly||row.task?.watched==true||row.group?.children?.any{it.watched}==true)&&
            (!row.orphan||terms.isNotEmpty()||showHistory||active)&&
            (terms.isNotEmpty()||row.task==null||meaningfulCatalogTask(row.task)||row.group?.children?.any(::meaningfulCatalogTask)==true)
    }
    WorkCatalogList(modifier.pointerInput(Unit){
        awaitPointerEventScope{try{while(true){touching=awaitPointerEvent(PointerEventPass.Initial).changes.any{it.pressed}}}finally{touching=false}}
    },state=state){
        if(shown.isEmpty())item(key="unified-empty"){
            if(discovering||snapshots.any{it.refreshing})Column(Modifier.fillMaxWidth().padding(20.dp)){Mark(64);BilingualLabel("Loading conversations")}
            else Column(Modifier.fillMaxWidth().padding(20.dp)){BilingualLabel("No matching work");TextButton(onRefresh){BilingualLabel("Refresh")}}
        }
        items(shown,key={it.entry.key}){row->
            val entry=row.entry;val identity=entry.key.toString()
            if(row.room!=null){
                HollywoodRoomCard(row.room,row.source.available,identityScope=identity,
                    headerBadge={WorkOriginBadge(entry,row.source.available)},onKeyboard={actions.open(entry,true)}){actions.open(entry,false)}
            }else if(row.group!=null){
                SessionGroupCard(row.group,System.currentTimeMillis(),
                    onChild={child->row.source.entries.firstOrNull{it.key.kind==WorkKind.SESSION&&it.key.id==child.id}?.let{actions.open(it,false)}},
                    identityScope=identity,preferenceKey=entry.value.destination.environment::key,
                    sourceNotifications=if(Pocket.captureEnvironment()?.matches(entry.value.destination)==true)Pocket.notifications else emptyList()
                ){badge->
                    TaskCard(row.task!!,headerBadge={WorkOriginBadge(entry,row.source.available);badge?.invoke()},
                        modifier=Modifier.testTag("unified-work:"+identity),identityScope=identity,
                        onRestore={actions.restore(entry)},onOpen={actions.open(entry,it)},onRename={name,done->actions.rename(entry,name,done)},onWatch={actions.watch(entry,it)})
                }
            }else if(row.task!=null){
                AgentResultCard(row.task,onOpen={actions.open(entry,false)},parentTitle=if(row.task.parentThreadId!=null)"Parent task outside this catalog page" else "Parent identity unavailable",headerBadge={WorkOriginBadge(entry,row.source.available)})
            }
        }
        snapshots.filter{it.error!=null}.forEach{source->
            item(key="source-error:"+source.key){
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                    val name=source.entries.firstOrNull()?.value?.destination?.environment?.name?:source.key.environment
                    Text(name+" · "+source.error,Modifier.weight(1f),color=Coral,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                    TextButton(onRefresh){BilingualLabel("Retry")}
                }
            }
        }
    }
}
