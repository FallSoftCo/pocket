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
import androidx.lifecycle.repeatOnLifecycle

/** Rendering is source-explicit; this view never changes global profile or dispatches commands. */
internal data class UnifiedWorkActions(
    val open:(WorkEntry<WorkCatalogPayload>,Boolean)->Unit,
    val rename:(WorkEntry<WorkCatalogPayload>,String,()->Unit)->Unit,
    val watch:(WorkEntry<WorkCatalogPayload>,Boolean)->Unit,
    val restore:(WorkEntry<WorkCatalogPayload>)->Unit,
    val archive:(WorkEntry<WorkCatalogPayload>)->Unit={}
)
private data class UnifiedWorkRow(
    val entry:WorkEntry<WorkCatalogPayload>,val source:WorkSourceSnapshot<WorkCatalogPayload>,
    val task:Task?=null,val group:SessionGroup?=null,val room:JSONObject?=null,val orphan:Boolean=false,
    val displayKey:String=entry.key.saveableKey()
)
@Composable private fun WorkOriginBadge(entry:WorkEntry<WorkCatalogPayload>,available:Boolean) {
    val kind=if(entry.key.kind==WorkKind.ROOM)"Room" else "Session"
    val route=entry.value.destination.environment.name
    val name=if(entry.key.kind==WorkKind.ROOM)"Room" else route
    Row(Modifier.widthIn(max=110.dp).semantics{contentDescription="$kind via $route; "+if(available)"catalog verified" else "catalog unavailable"},
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
    query:String="",workingOnly:Boolean=false,followingOnly:Boolean=false,showHistory:Boolean=false,attentionOnly:Boolean=false,
    onRefresh:()->Unit={},leading:(@Composable ()->Unit)?=null,showEmptyState:Boolean=true
) {
    var order by remember{mutableStateOf(emptyList<String>())}
    var touching by remember{mutableStateOf(false)}
    val rows=remember(snapshots){
        snapshots.flatMap{source->
            val sessions=source.entries.filter{it.key.kind==WorkKind.SESSION}
            val tasks=sessions.associate{entry->
                val task=catalogTask(JSONObject(entry.value.body))
                entry.key.id to if(source.available)task else task.copy(attention=task.attention?.copy(verified=false))
            }
            val parents=sessionGroups(tasks.values.toList())
            val mapped=parents.mapNotNull{group->sessions.firstOrNull{it.key.id==group.task.id}?.let{parent->
                val familyAt=maxOf(parent.workAt,group.children.maxOfOrNull{child->sessions.firstOrNull{it.key.id==child.id}?.workAt?:0}?:0)
                UnifiedWorkRow(parent.copy(workAt=familyAt),source,group.task,group)
            }}
            // Retain child ownership; children with an unloaded parent remain discoverable.
            mapped+ungroupedChildren(tasks.values.toList()).mapNotNull{task->
                sessions.firstOrNull{it.key.id==task.id}?.let{UnifiedWorkRow(it,source,task,orphan=true)}
            }
        }+fleetRoomProjections(snapshots).map{projection->
            UnifiedWorkRow(projection.route.copy(workAt=projection.content.workAt),projection.source,
                room=projection.displayRoom(),displayKey=projection.identity)
        }
    }
    val rank=order.withIndex().associate{it.value to it.index}
    val sorted=rows.sortedWith(compareByDescending<UnifiedWorkRow>{it.entry.workAt}
        .thenBy{rank[it.displayKey]?:Int.MAX_VALUE}.thenBy{it.entry.key.source.environment}.thenBy{it.entry.key.id})
    // Content can update in place during interaction. Reordering waits for reading to settle.
    var displayed by remember{mutableStateOf(emptyList<UnifiedWorkRow>())}
    val interacting=state.isScrollInProgress||touching
    LaunchedEffect(rows,interacting){
        val latest=rows.associateBy{it.displayKey}
        displayed=displayed.mapNotNull{latest[it.displayKey]}+sorted.filter{next->displayed.none{it.displayKey==next.displayKey}}
        if(!interacting){
            kotlinx.coroutines.delay(750)
            val byId=sorted.associateBy{it.displayKey}
            val ranked=sorted.map{row->SessionRank(row.displayKey,row.entry.workAt,
                row.task?.status in setOf("active","pending")||row.group?.children?.any{it.status in setOf("active","pending")}==true)}
            val next=liveSessionOrder(order.filter{it in byId},ranked,System.currentTimeMillis())
            displayed=next.mapNotNull{byId[it]};order=displayed.map{it.displayKey}
        }
    }
    val terms=query.trim().split(Regex("\\s+")).filter{it.isNotBlank()}
    // Filtering leaves the ordinary catalog order and scroll state intact. A
    // delegated request appears as its own original source row in this view.
    val candidates=if(attentionOnly)displayed.flatMap{row->
        val own=if(row.task?.needsReview()==true)listOf(row.copy(group=row.group?.copy(children=emptyList())))else emptyList()
        own+row.group?.children.orEmpty().filter{it.needsReview()}.mapNotNull{child->
            row.source.entries.firstOrNull{it.key.kind==WorkKind.SESSION&&it.key.id==child.id}?.let{entry->
                UnifiedWorkRow(entry,row.source,child,SessionGroup(child,emptyList()))
            }
        }
    }else displayed
    val shown=candidates.filter{row->
        val text=(listOf(row.entry.value.destination.environment.name,row.task?.title,row.task?.preview,row.task?.cwd,row.task?.catalogContext,row.task?.agentNickname,row.room?.s("name"),row.room?.s("purpose"))+
            row.group?.children.orEmpty().flatMap{listOf(it.title,it.preview,it.cwd,it.catalogContext,it.agentNickname,it.agentRole)}).filterNotNull().joinToString(" ")
        val active=row.task?.let{it.status in setOf("active","pending")||it.needsInput||row.group?.children?.any{child->child.status in setOf("active","pending")||child.needsInput}==true}?:row.room?.let{HollywoodRoomActivity.read(it,row.source.available)?.let{a->a.scheduled||a.needsHuman}==true}?:false
        val needsReview=row.task?.needsReview()==true||row.group?.children?.any{it.needsReview()}==true
        terms.all{text.contains(it,true)}&&(!attentionOnly||needsReview)&&(!workingOnly||active)&&(!followingOnly||row.task?.watched==true||row.group?.children?.any{it.watched}==true)&&
            (!row.orphan||terms.isNotEmpty()||showHistory||active||needsReview)&&
            (terms.isNotEmpty()||row.task==null||meaningfulCatalogTask(row.task)||row.group?.children?.any(::meaningfulCatalogTask)==true)
    }
    WorkCatalogList(modifier.pointerInput(Unit){
        awaitPointerEventScope{try{while(true){touching=awaitPointerEvent(PointerEventPass.Initial).changes.any{it.pressed}}}finally{touching=false}}
    },state=state){
        if(leading!=null)item(key="unified-context"){leading()}
        if(shown.isEmpty()&&showEmptyState)item(key="unified-empty"){
            if(discovering||snapshots.any{it.refreshing})Column(Modifier.fillMaxWidth().padding(20.dp)){Mark(64);BilingualLabel("Loading conversations")}
            else Column(Modifier.fillMaxWidth().padding(20.dp)){BilingualLabel(if(attentionOnly)"No matching items in loaded sessions"else "No matching work");TextButton(onRefresh){BilingualLabel("Refresh")}}
        }
        items(shown,key={it.displayKey}){row->
            val entry=row.entry;val identity=row.displayKey
            if(row.room!=null){
                HollywoodRoomCard(row.room,row.source.available,identityScope=identity,
                    headerBadge={WorkOriginBadge(entry,row.source.available)},onKeyboard={actions.open(entry,true)}){actions.open(entry,false)}
            }else if(row.group!=null){
                SessionGroupCard(row.group,System.currentTimeMillis(),
                    onChild={child->row.source.entries.firstOrNull{it.key.kind==WorkKind.SESSION&&it.key.id==child.id}?.let{actions.open(it,false)}},
                    identityScope=identity,preferenceKey=entry.value.destination.environment::key,
                    onArchiveChild={child->row.source.entries.firstOrNull{it.key.id==child.id}?.let{actions.archive(it)}},
                    onRestoreChild={child->row.source.entries.firstOrNull{it.key.id==child.id}?.let{actions.restore(it)}},
                    sourceNotifications=if(Pocket.captureEnvironment()?.matches(entry.value.destination)==true)Pocket.notifications else emptyList()
                ){badge->
                    TaskCard(row.task!!,headerBadge={WorkOriginBadge(entry,row.source.available);badge?.invoke()},
                        modifier=Modifier.testTag("unified-work:"+identity),identityScope=identity,
                        onArchive={actions.archive(entry)},onRestore={actions.restore(entry)},onOpen={actions.open(entry,it)},onRename={name,done->actions.rename(entry,name,done)},onWatch={actions.watch(entry,it)})
                }
            }else if(row.task!=null){
                AgentResultCard(row.task,onArchive={actions.archive(entry)},onRestore={actions.restore(entry)},onOpen={actions.open(entry,false)},parentTitle=if(row.task.parentThreadId!=null)"Parent task outside this catalog page" else "Parent identity unavailable",headerBadge={WorkOriginBadge(entry,row.source.available)})
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

/** Uses the existing session footer/search surface; destinations never key this view. */
@Composable internal fun UnifiedWorkScreen(
    toolsOpen:Boolean,onToolsOpen:(Boolean)->Unit,listState:LazyListState,view:SessionCatalogViewState
){
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle){
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED){
            val detach=UnifiedWorkCatalog.attach()
            try{kotlinx.coroutines.awaitCancellation()}finally{detach()}
        }
    }
    val snapshots=UnifiedWorkCatalog.snapshots
    val filter by view.filter;val query by view.query
    LaunchedEffect(query){if(query.isNotBlank()){kotlinx.coroutines.delay(400);UnifiedWorkCatalog.search(query)}}
    LaunchedEffect(Pocket.showArchived){UnifiedWorkCatalog.refresh()}
    val initialCatalog=snapshots.none{it.entries.isNotEmpty()}&&Pocket.catalogLoad!=CatalogLoadState.Ready
    Column(Modifier.fillMaxSize()){
        UnifiedWorkList(snapshots,UnifiedWorkCatalog.discovering.isNotEmpty(),WorkSourceActions.actions,
            modifier=Modifier.weight(1f),state=listState,query=query,workingOnly=filter==1,
            followingOnly=filter==2,showHistory=filter==4,attentionOnly=filter==5,onRefresh={UnifiedWorkCatalog.refresh()},showEmptyState=!initialCatalog,
            leading={
                ErrorBanner()
                if(initialCatalog)CatalogStartupStatus()
                if(filter==5&&snapshots.any{source->!source.available||source.entries.any{entry->entry.key.kind==WorkKind.SESSION&&catalogTask(JSONObject(entry.value.body)).attention?.verified!=true}})
                    WorkflowText("Some session checks are unavailable. Showing known items.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(12.dp))
                if(filter!=3&&filter!=5&&(filter!=1||PocketCoordinator.busy)&&(query.isBlank()||"Coordinator".contains(query,true)||PocketCoordinator.preview.contains(query,true)))CoordinatorCard()
                UnifiedWorkCatalog.discoveryErrors.forEach{(source,message)->
                    val name=Pocket.environmentProfiles.all().firstOrNull{it.id==source.environment}?.name?:source.environment
                    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                        Text("$name · $message",Modifier.weight(1f),color=Coral,maxLines=2,overflow=TextOverflow.Ellipsis)
                        TextButton({UnifiedWorkCatalog.refresh()}){BilingualLabel("Retry")}
                    }
                }
                FleetConnections.unavailable.values.distinct().forEach{message->
                    Text(message,Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),
                        color=Coral,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            })
        WorkActionFeedbackBar()
        val needsReview=snapshots.flatMap{it.entries}.filter{it.key.kind==WorkKind.SESSION&&catalogTask(JSONObject(it.value.body)).needsReview()}.distinctBy{it.key}.size
        if(needsReview>0||filter==5)Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            TextButton({view.toggleAttention();if(Pocket.showArchived){Pocket.showArchived=false;UnifiedWorkCatalog.refresh()}},
                modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("needs-you-filter"),contentPadding=PaddingValues(horizontal=12.dp)){
                BilingualLabel(if(filter==5)"All sessions"else "Needs you · $needsReview",color=if(filter==5)Mint else Coral)
            }
            if(filter==5)WorkflowText("Needs you · $needsReview",color=Coral,fontSize=12.sp,modifier=Modifier.padding(horizontal=8.dp))
        }
    }
    SessionCatalogTools(toolsOpen,onToolsOpen,view)
}

/** Failures remain visible at the thumb edge even when the failed row is far
 * down the catalog. Never offer a blind retry for an uncertain mutation. */
@Composable internal fun WorkActionFeedbackBar(){
    val entries=WorkSourceActions.feedback.values.toList()
    val action=entries.lastOrNull{!it.pending}?:entries.lastOrNull()?:return
    Surface(color=if(action.error!=null)Coral.copy(alpha=.12f)else Mint.copy(alpha=.1f),
        modifier=Modifier.fillMaxWidth().testTag("work-action-feedback")){
        Row(Modifier.padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f).semantics{liveRegion=LiveRegionMode.Polite}){
                val verb=when(action.operation){"archive"->"Archiving";"unarchive"->"Restoring";"rename"->"Renaming";else->"Updating"}
                Text("${action.source} · ${action.title}",fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(if(action.pending)"$verb…"else action.error.orEmpty(),fontSize=13.sp,color=if(action.error!=null)Coral else Paper,maxLines=3,overflow=TextOverflow.Ellipsis)
                if(entries.size>1)Text("${entries.size} actions",fontSize=11.sp,color=Muted)
            }
            if(!action.pending)TextButton({WorkSourceActions.dismiss(action.key)}){BilingualLabel("Dismiss")}
        }
    }
}
