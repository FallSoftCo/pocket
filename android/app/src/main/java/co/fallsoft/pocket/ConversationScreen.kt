package co.fallsoft.pocket

import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun codeBlock(part:String):String {
    val value=part.trim('\n','\r');val first=value.substringBefore('\n')
    return if(first.matches(Regex("[a-zA-Z0-9_+#.-]{1,24}"))&&value.contains('\n'))value.substringAfter('\n')else value
}
fun turnTime(value:Long):String=if(value<=0)"Current turn" else SimpleDateFormat("MMM d · HH:mm",if(PocketImmersion.enabled)Locale.ITALIAN else Locale.getDefault()).format(Date(if(value<100000000000L)value*1000 else value))

@Composable fun NewTaskScreen(){
    val cwdKey=Pocket.key("newTaskCwd");val projectKey=Pocket.key("lastProject");val promptKey=Pocket.key("newTaskPrompt")
    var cwd by remember(cwdKey,projectKey){mutableStateOf(Pocket.prefs.getString(cwdKey,Pocket.prefs.getString(projectKey,Pocket.tasks.firstOrNull()?.cwd?:Pocket.defaultCwd))?:Pocket.defaultCwd)}
    var prompt by remember(promptKey){mutableStateOf(Pocket.prefs.getString(promptKey,"")?:"")}
    Column(Modifier.fillMaxSize().imePadding()){
        Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically){IconButton({Pocket.newTask=false}){SymbolIcon(Icons.AutoMirrored.Rounded.ArrowBack,"Back")};BilingualLabel("New task",fontSize=23.sp,fontWeight=FontWeight.Medium)}
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            WorkflowText("Start where the work lives.",fontSize=26.sp,lineHeight=32.sp)
            WorkflowText("Choose a folder on ${Pocket.host}. Choose permissions below before starting.",color=Muted,fontSize=14.sp,lineHeight=21.sp)
            OutlinedTextField(cwd,{cwd=it;Pocket.prefs.edit().putString(cwdKey,it).apply()},label={BilingualLabel("Project folder",centered=false)},singleLine=true,enabled=!Pocket.starting,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(15.dp),textStyle=LocalTextStyle.current.copy(fontSize=13.sp,fontFamily=FontFamily.Monospace))
            if(Pocket.projects.isNotEmpty()){
                Label("RECENT PROJECTS")
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Panel)){
                    Pocket.projects.take(5).forEach{p->Row(Modifier.fillMaxWidth().clickable(enabled=!Pocket.starting){cwd=p.s("cwd");Pocket.prefs.edit().putString(cwdKey,cwd).apply()}.padding(horizontal=14.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically){SymbolIcon(Icons.Rounded.FolderOpen,null,tint=if(cwd==p.s("cwd"))Mint else Muted,modifier=Modifier.size(18.dp));Column(Modifier.weight(1f).padding(start=10.dp)){Text(p.s("name"),fontSize=14.sp,color=if(cwd==p.s("cwd"))Mint else Paper);Text(p.s("cwd"),fontSize=10.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)};if(cwd==p.s("cwd"))SymbolIcon(Icons.Rounded.Check,null,tint=Mint,modifier=Modifier.size(16.dp))}}
                }
            }
            TaskPermissionsControl()
            OutlinedTextField(prompt,{prompt=it;Pocket.prefs.edit().putString(promptKey,it).apply()},label={BilingualLabel("What would you like Codex to do?",centered=false)},placeholder={WorkflowText("Describe the task, context and what done looks like.",fontSize=14.sp)},enabled=!Pocket.starting,modifier=Modifier.fillMaxWidth().heightIn(min=150.dp),minLines=4,maxLines=10,shape=RoundedCornerShape(18.dp))
            WorkflowText("You’ll see progress here and get a notification when the task needs you or finishes.",fontSize=12.sp,color=Muted,lineHeight=19.sp)
            if(Pocket.startStatus.isNotBlank())WorkflowText(Pocket.startStatus,fontSize=13.sp,color=Mint)
            ErrorBanner();Spacer(Modifier.height(10.dp))
        }
        Button({Pocket.startTask(cwd.trim(),prompt.trim())},enabled=cwd.isNotBlank()&&prompt.isNotBlank()&&!Pocket.starting,modifier=Modifier.fillMaxWidth().padding(20.dp).height(54.dp),shape=RoundedCornerShape(6.dp)){
            if(Pocket.starting)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=Ink)else SymbolIcon(Icons.Rounded.ArrowUpward,null)
            Spacer(Modifier.width(9.dp));BilingualLabel(if(Pocket.starting)"Starting…" else if(Pocket.startStatus.isNotBlank())"Check task status" else "Start task")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class,ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class,FlowPreview::class)
@Composable fun ConversationScreen(){
    val d=Pocket.detail;val t=d?.optJSONObject("thread");val rows=PocketTranscript.rows
    val task=Pocket.tasks.firstOrNull{it.id==Pocket.selected}
    val failureSource=Pocket.selected;val replyFailure=Pocket.replyFailure()
    val clientReplies=PocketReplies.visible(Pocket.selected.orEmpty())
    val recoveryAlreadyShown=clientReplies.any{it.id==replyFailure?.id}||d?.optJSONArray("outgoing")?.objects()?.any{it.s("id")==replyFailure?.id}==true
    val conversationError=Pocket.error.ifBlank{if(recoveryAlreadyShown)"" else replyFailure?.message.orEmpty()}
    val pending=d?.optJSONArray("pending")?.length()?:rows.count{it.s("kind")=="request"}
    val latestTurn=rows.lastOrNull{it.s("kind")=="turn"}
    val status=conversationRunState(pending,task?.status.orEmpty(),t?.optJSONObject("status")?.s("type").orEmpty(),if(PocketTranscript.currentHistoryVerified)latestTurn?.s("status").orEmpty() else "",task?.previewKind=="thinking",d!=null&&PocketTranscript.currentHistoryVerified,PocketTranscript.loading)
    val active=status in listOf(ConversationRunState.WORKING,ConversationRunState.THINKING,ConversationRunState.STARTING,ConversationRunState.NEEDS_YOU)
    val latestFailure=rows.lastOrNull{it.s("kind")=="turnEnd"}?.takeIf{it.s("status")=="failed"&&(latestTurn==null||it.s("turnId")==latestTurn.s("turnId"))}
    val list=key(Pocket.environmentId,Pocket.selected){rememberLazyListState()};val scope=rememberCoroutineScope();val dragged by list.interactionSource.collectIsDraggedAsState()
    val restoreRevision=PocketTranscript.cacheRestoreRevision
    val readPosition=remember(Pocket.environmentId,Pocket.selected,restoreRevision){PocketTranscript.readPosition}
    var restoredPosition by remember(Pocket.environmentId,Pocket.selected,restoreRevision){mutableStateOf(readPosition==null||readPosition.follow)}
    var follow by remember(Pocket.environmentId,Pocket.selected,restoreRevision){mutableStateOf(readPosition?.follow?:true)}
    var important by remember(Pocket.environmentId,Pocket.selected){mutableStateOf(false)}
    var filterPassage by remember(Pocket.environmentId,Pocket.selected){mutableStateOf<String?>(null)}
    var filterRequest by remember(Pocket.environmentId,Pocket.selected){mutableStateOf<FilterReadingRequest?>(null)}
    var filterPaddingRequest by remember(Pocket.environmentId,Pocket.selected){mutableStateOf<FilterReadingRequest?>(null)}
    var filterTailPixels by remember(Pocket.environmentId,Pocket.selected){mutableIntStateOf(0)}
    val density=LocalDensity.current
    var confirmRetry by remember(Pocket.selected){mutableStateOf<JSONObject?>(null)}
    val notes=d?.optJSONArray("notes")?.objects().orEmpty()
    fun noteFor(row:JSONObject)=notes.firstOrNull{it.s("id")==row.s("itemId",row.s("id"))||it.s("id")==row.s("id")}
    val latestAnswer=rows.lastOrNull{it.s("kind")=="message"}
    val orphanRows=notes.filter{note->rows.none{row->row.s("itemId",row.s("id"))==note.s("id")||row.s("id")==note.s("id")}}.map{note->JSONObject().put("id","retained:"+note.s("id")).put("itemId",note.s("id")).put("kind","message").put("text",note.s("text")).put("retainedExcerpt",true)}
    val filteredRows=if(!important)rows else orphanRows+rows.filter{importantConversationKind(it.s("kind"),it.s("type"),it.s("status"),noteFor(it)!=null,it.s("phase"),it===latestAnswer)}
    val runExpansionKey=Pocket.key("runExpansion:"+Pocket.selected)
    var expandedRunIds by remember(Pocket.environmentId,Pocket.selected){
        mutableStateOf(Pocket.prefs.getStringSet(runExpansionKey,emptySet()).orEmpty().toSet())
    }
    fun setRunExpansion(next:Set<String>){
        expandedRunIds=next
        Pocket.prefs.edit().putStringSet(runExpansionKey,next).apply()
    }
    val runProjection=remember(filteredRows,expandedRunIds){commandRunProjection(filteredRows,expandedRunIds)}
    val shownRows=runProjection.rows
    val latestRunProjection by rememberUpdatedState(runProjection)
    val pendingFilter=filterRequest
    SideEffect{
        if(pendingFilter!=null&&pendingFilter.environment==Pocket.environmentId&&pendingFilter.thread==Pocket.selected&&pendingFilter.important==important){
            // LazyColumn's item provider now contains the committed filtered data.
            // A frame callback before composition can still address the old index.
            val index=shownRows.indexOfFirst{it.s("id")==pendingFilter.row}
            if(index>=0)list.requestScrollToItem(index,pendingFilter.offset)
            filterRequest=null
        }
    }
    // A metadata-enriched key replaces one existing message, not the reader's
    // passage. Preserve the exact viewport offset before LazyColumn remeasures.
    SideEffect{
        if(!follow&&pendingFilter==null&&!list.isScrollInProgress){
            val visible=list.layoutInfo.visibleItemsInfo.firstOrNull{it.index==list.firstVisibleItemIndex}
            val old=visible?.key as? String
            if(old!=null&&shownRows.getOrNull(visible.index)?.s("id")!=old&&shownRows.none{it.s("id")==old}){
                val canonical=runProjection.canonicalId(old)
                val migrated=migrateTranscriptAnchors(rows,setOf(canonical))[canonical]?:canonical
                val resolved=if(old.startsWith("ui:run-group:"))runProjection.headerFor(migrated)?:runProjection.displayId(migrated) else runProjection.displayId(migrated)
                val index=shownRows.indexOfFirst{it.s("id")==resolved}
                if(index>=0)list.requestScrollToItem(index,list.firstVisibleItemScrollOffset)
            }
        }
    }
    val shownIds=shownRows.map{it.s("id")}
    LaunchedEffect(important,filterPaddingRequest,shownIds){
        if(!important){filterTailPixels=0;filterPaddingRequest=null;return@LaunchedEffect}
        val request=filterPaddingRequest?:return@LaunchedEffect
        val index=shownIds.indexOf(request.row);if(index<0)return@LaunchedEffect
        val reserved=filterTailPixels;val ordinaryBottom=with(density){12.dp.roundToPx()}
        val measured=snapshotFlow{list.layoutInfo}.first{info->
            info.afterContentPadding>=reserved+ordinaryBottom&&info.visibleItemsInfo.any{it.key==request.row&&it.index==index}
        }
        // Filtering near the end otherwise clamps the retained passage downwards.
        // Reserve only the measured missing tail, rather than moving that passage.
        val last=measured.visibleItemsInfo.firstOrNull{it.index==measured.totalItemsCount-1}
        filterTailPixels=if(last==null)0 else (measured.viewportEndOffset-last.offset-last.size-ordinaryBottom).coerceIn(0,reserved)
        filterPaddingRequest=null
    }
    val latestFollow by key(Pocket.environmentId,Pocket.selected){rememberUpdatedState(follow)}
    val latestRowIds by key(Pocket.environmentId,Pocket.selected){rememberUpdatedState(shownRows.map{it.s("id")})}
    DisposableEffect(Pocket.environmentId,Pocket.selected){
        val source=Pocket.selected;val owner=PocketTranscript.scopeKey()
        onDispose{
            // visibleItemsInfo also includes a preceding row inside content padding.
            // Its key must not be paired with the next viewport row's scroll offset.
            val visible=list.layoutInfo.visibleItemsInfo.filter{it.key.toString() in latestRowIds}.map{TranscriptVisibleRow(it.index,it.key.toString())}
            val position=transcriptReadPosition(visible,list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset,latestFollow)
            if(source!=null&&position!=null)PocketTranscript.rememberPosition(owner,source,position.copy(rowId=latestRunProjection.canonicalId(position.rowId),offset=if(position.rowId.startsWith("ui:run-group:"))0 else position.offset))
        }
    }
    LaunchedEffect(list,Pocket.environmentId,Pocket.selected){
        val source=Pocket.selected;val owner=PocketTranscript.scopeKey()
        snapshotFlow{
            if(!restoredPosition||PocketTranscript.loading||list.isScrollInProgress||important)null else
                transcriptReadPosition(list.layoutInfo.visibleItemsInfo.map{TranscriptVisibleRow(it.index,it.key.toString())},list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset,follow)
        }.debounce(350).filterNotNull().distinctUntilChanged().collect{position->
            if(source!=null)PocketTranscript.rememberPosition(owner,source,position.copy(rowId=latestRunProjection.canonicalId(position.rowId),offset=if(position.rowId.startsWith("ui:run-group:"))0 else position.offset))
        }
    }
    LaunchedEffect(Pocket.environmentId,Pocket.selected,rows.size,restoreRevision){
        if(!restoredPosition&&readPosition!=null&&rows.isNotEmpty()){
            val resolved=resolveTranscriptReadPosition(rows,readPosition)?:readPosition
            val index=transcriptAnchorIndex(shownIds,resolved.copy(rowId=runProjection.displayId(resolved.rowId)))
            if(PocketTranscript.mayRestorePosition(restoreRevision)&&!dragged){
                if(index>=0)list.scrollToItem(index,if(shownIds[index].startsWith("ui:run-group:"))0 else readPosition.offset.coerceAtLeast(0))else follow=true
            }
            restoredPosition=true
            PocketTranscript.positionRestored(Pocket.selected,restoreRevision)
        }
    }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(list,lifecycle,Pocket.environmentId,Pocket.selected){
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
            snapshotFlow{
                val latest=PocketTranscript.rows.lastOrNull{it.s("kind") !in listOf("turn","turnEnd")&&it.s("text").isNotBlank()}?.s("id")
                val visible=latest!=null&&list.layoutInfo.visibleItemsInfo.any{it.key==latest}
                val eligible=AppWindowVisibility.focused&&!important&&!BlackoutVisibility.active&&!PocketVoice.active&&!PocketTranscript.loading&&!PocketTranscript.updating&&!list.isScrollInProgress&&!list.canScrollForward&&visible
                eligible to PocketTranscript.revision
            }.collectLatest{(eligible,_)->
                if(eligible){delay(500);PocketTranscript.presentedLatest()}
            }
        }
    }
    var removingNotes by remember(Pocket.environmentId,Pocket.selected){mutableStateOf(setOf<String>())}
    val spokenRows=remember(Pocket.environmentId,Pocket.selected){mutableStateMapOf<String,String>()}
    var actionsOpen by remember(Pocket.selected){mutableStateOf(false)}
    var settingsOpen by remember(Pocket.selected){mutableStateOf(false)}
    var renameOpen by remember(Pocket.selected){mutableStateOf(false)}
    var taskName by remember(Pocket.selected){mutableStateOf("")}
    var confirmArchive by remember(Pocket.selected){mutableStateOf(false)}
    var editingQueue by remember(Pocket.selected){mutableStateOf<JSONObject?>(null)}
    var queuedText by remember(Pocket.selected){mutableStateOf("")}
    var queueOpen by remember(Pocket.selected){mutableStateOf(false)}
    val draftKey=Pocket.key("draft:${Pocket.selected}");var editor by remember(Pocket.environmentId,Pocket.selected){mutableStateOf((Pocket.prefs.getString(draftKey,"")?:"").let{TextFieldValue(it,TextRange(it.length))})}
    LaunchedEffect(PocketReplies.revision,draftKey){if(PocketReplies.clearedDrafts[draftKey]?.let{it.text==editor.text&&it.generation==replyDraftGeneration(draftKey)}==true&&!Pocket.prefs.contains(draftKey))editor=TextFieldValue("")}
    var keyboardInput by remember(Pocket.selected){mutableStateOf(Pocket.openWithKeyboard)}
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val inputFocus=remember{androidx.compose.ui.focus.FocusRequester()}
    LaunchedEffect(keyboardInput){if(keyboardInput){inputFocus.requestFocus();keyboard?.show()}}
    ConversationBack{Pocket.closeTask()}
    val draft=editor.text
    val submit:(String)->Unit={mode->val submitted=editor.text;val submittedGeneration=replyDraftGeneration(draftKey);follow=true;Pocket.reply(submitted,mode=mode){if(editor.text==submitted&&replyDraftGeneration(draftKey)==submittedGeneration){editor=TextFieldValue("");Pocket.prefs.edit().remove(draftKey).apply()}}}
    val waiting=d?.optJSONArray("outgoing")?.objects()?.filter{it.s("state") in listOf("queued","held")&&it.s("mode")=="queue"}?:emptyList()
    LaunchedEffect(list){
        if(BuildConfig.DEBUG&&Pocket.prefs.getBoolean("diagnostic:scroll",false))snapshotFlow{Triple(list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset,list.isScrollInProgress)}.collect{position->
            android.util.Log.d("PocketScroll","index=${position.first} offset=${position.second} moving=${position.third} follow=$follow dragged=$dragged rows=${shownRows.size}")
        }
    }
    var historyGesture by remember(Pocket.environmentId,Pocket.selected){mutableLongStateOf(0)}
    var satisfiedHistoryGesture by remember(Pocket.environmentId,Pocket.selected){mutableLongStateOf(-1)}
    var shortHistoryPull by remember(Pocket.environmentId,Pocket.selected){mutableStateOf(false)}
    val historyScrollObserver=remember(list,Pocket.environmentId,Pocket.selected){object:NestedScrollConnection{
        override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset{
            // Observe only; LazyColumn retains all gesture consumption and inertia.
            if(source==NestedScrollSource.UserInput&&available.y>0&&!list.canScrollBackward&&!list.canScrollForward&&PocketTranscript.earlier)shortHistoryPull=true
            return Offset.Zero
        }
    }}
    LaunchedEffect(dragged){
        if(dragged){PocketTranscript.readerInteraction();follow=false;filterPassage=null}
        else{
            if(shouldResumeTranscriptFollowing(list.canScrollForward,list.canScrollBackward,PocketTranscript.earlier,shortHistoryPull))follow=true
            shortHistoryPull=false
        }
    }
    LaunchedEffect(list){
        // Accessibility scroll actions do not emit pointer-drag interactions.
        snapshotFlow{list.isScrollInProgress&&!follow}.distinctUntilChanged().filter{it}.collect{historyGesture++}
    }
    LaunchedEffect(PocketTranscript.revision,clientReplies.map{Triple(it.id,it.state,it.message)}){if(follow&&!dragged){
        delay(32)
        if(!follow||dragged||list.isScrollInProgress)return@LaunchedEffect
        val count=list.layoutInfo.totalItemsCount
        // Keep the question heading and its one-tap Skip visible even when a
        // multi-question form is taller than the conversation viewport.
        val request=shownRows.indexOfLast{it.s("kind")=="request"}
        if(count>0)list.scrollToItem(if(request>=0)request.coerceAtMost(count-1) else count-1)
    }}
    // Thumb scrolling is the pagination control. LazyColumn's stable row keys retain
    // the visible message and pixel offset when an older page is prepended.
    LaunchedEffect(Pocket.environmentId,Pocket.selected,important){
        snapshotFlow{restoredPosition&&historyGesture!=satisfiedHistoryGesture&&PocketTranscript.automaticRetryAllowed&&!list.isScrollInProgress&&shouldPrefetchTranscript(follow,important,list.firstVisibleItemIndex,PocketTranscript.earlier,PocketTranscript.loading)}
            .distinctUntilChanged().filter{it}.collect{
                var retryMs=1000L
                while(restoredPosition&&historyGesture!=satisfiedHistoryGesture&&PocketTranscript.automaticRetryAllowed&&!list.isScrollInProgress&&shouldPrefetchTranscript(follow,important,list.firstVisibleItemIndex,PocketTranscript.earlier,PocketTranscript.loading)){
                    // Wait for finger/fling and layout to settle before another page.
                    delay(180)
                    if(list.isScrollInProgress||!shouldPrefetchTranscript(follow,important,list.firstVisibleItemIndex,PocketTranscript.earlier,PocketTranscript.loading))break
                    val cursor=PocketTranscript.before
                    val gesture=historyGesture
                    PocketTranscript.load(true,beforeApply={
                        snapshotFlow{list.isScrollInProgress}.debounce(180).first{!it}
                    })
                    androidx.compose.runtime.withFrameNanos{}
                    androidx.compose.runtime.withFrameNanos{}
                    if(PocketTranscript.before==cursor&&PocketTranscript.earlier){
                        // Remain anchored; retry only while the reader is still near this edge.
                        delay(retryMs);retryMs=(retryMs*2).coerceAtMost(15000)
                    }else{
                        // A short page is not permission to drain the whole history.
                        // A gesture made during this read remains a fresh demand.
                        satisfiedHistoryGesture=gesture;retryMs=1000L
                    }
                }
            }
    }
    val ConversationActivityControls:@Composable ColumnScope.()->Unit={
            if(pending>0)TextButton({
                actionsOpen=false;important=false;follow=false;PocketTranscript.readerInteraction()
                filterPassage=null;filterPaddingRequest=null;filterTailPixels=0
                val request=rows.firstOrNull{it.s("kind")=="request"}
                if(request!=null)filterRequest=FilterReadingRequest(Pocket.environmentId,Pocket.selected,request.s("id"),0,false)
                else Pocket.refreshDetail()
            },modifier=Modifier.fillMaxWidth()){BilingualLabel("Answer pending question",color=Coral)}
            UsageDetails()
            d?.optJSONObject("turnSettings")?.let{settings->Text(listOf(settings.s("model"),settings.s("effort"),settings.s("mode")).filter{it.isNotBlank()}.joinToString(" · "),fontSize=12.sp,color=Muted)}
            TextButton({Pocket.watch(!(d?.optBoolean("watched")?:false));actionsOpen=false},modifier=Modifier.fillMaxWidth()){BilingualLabel(if(d?.optBoolean("watched")==true)"Unfollow" else "Follow")}
            if(waiting.isNotEmpty())TextButton({actionsOpen=false;queueOpen=true},modifier=Modifier.fillMaxWidth()){BilingualLabel("Queue · ${waiting.size}")}
            TextButton({rows.lastOrNull{it.s("kind")=="message"&&it.s("text").isNotBlank()}?.let{keepConversationReply(it)};actionsOpen=false},enabled=rows.any{it.s("kind")=="message"},modifier=Modifier.fillMaxWidth()){BilingualLabel("Keep latest reply in notes")}
            TextButton({actionsOpen=false;settingsOpen=true},modifier=Modifier.fillMaxWidth()){BilingualLabel("Model, effort & mode")}
            TextButton({actionsOpen=false;taskName=t?.s("name").orEmpty();renameOpen=true},modifier=Modifier.fillMaxWidth()){BilingualLabel("Rename")}
            TextButton({actionsOpen=false;confirmArchive=true},enabled=!active,modifier=Modifier.fillMaxWidth()){BilingualLabel("Archive")}
            TextButton({actionsOpen=false;submit("queue")},enabled=draft.isNotBlank()&&!Pocket.sending,modifier=Modifier.fillMaxWidth()){BilingualLabel("Queue message")}
    }
    Column(Modifier.fillMaxSize().imePadding()){
        Box(Modifier.weight(1f).fillMaxWidth()){
            LazyColumn(Modifier.fillMaxSize().nestedScroll(historyScrollObserver).testTag("conversation-history"),state=list,contentPadding=PaddingValues(start=18.dp,end=18.dp,top=12.dp,bottom=12.dp+with(density){(if(important)filterTailPixels else 0).toDp()}),verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(d==null&&rows.isEmpty()&&PocketTranscript.loading)item{Box(Modifier.fillMaxWidth().padding(vertical=40.dp),contentAlignment=Alignment.Center){AnimatedMark(112)}}
                items(shownRows,key={it.s("id")}){row->
                    if(important&&noteFor(row)!=null&&!row.optBoolean("retainedExcerpt"))BilingualLabel("Saved answer",color=Mint,fontSize=12.sp)
                    if(row.optBoolean("retainedExcerpt"))BilingualLabel("Retained passage · source outside loaded history",color=Muted,fontSize=12.sp)
                    val commandGroup=runProjection.groups[row.s("id")]
                    if(row.s("kind")=="commandRuns"&&commandGroup!=null) {
                        val expanded=commandGroup.any{it.s("id") in expandedRunIds}
                        CommandRunsHeader(commandGroup,expanded){
                            PocketTranscript.readerInteraction();follow=false
                            val members=commandGroup.map{it.s("id")}.toSet()
                            setRunExpansion(if(expanded)expandedRunIds-members else expandedRunIds+members)
                        }
                    } else if(isCommandRun(row))ActivityRow(row,fullyExpanded=true)
                    else TranscriptRow(row){spokenRows[row.s("id")]=it}
                    if(row.s("speechThread").isNotBlank())TextButton({Pocket.open(row.s("speechThread"))}){BilingualLabel("Open source conversation")}
                    if(important)noteFor(row)?.let{note->FlowRow{
                        TextButton({val context="Regarding your answer:\n"+row.s("text")+"\n\n";val next=if(editor.text.isBlank())context else editor.text+"\n\n"+context;editor=TextFieldValue(next,TextRange(next.length));saveReplyDraft(draftKey,next);keyboardInput=true}){BilingualLabel("Reply")}
                        TextButton({
                            val sourceThread=Pocket.selected;val sourceDestination=Pocket.captureEnvironment()
                            val owns={Pocket.selected==sourceThread&&sourceDestination!=null&&Pocket.captureEnvironment()?.matches(sourceDestination)==true}
                            important=false;follow=false;PocketTranscript.readerInteraction()
                            scope.launch{
                                val found=locateTranscriptSource(owns,{PocketTranscript.loading},{PocketTranscript.earlier},{PocketTranscript.before},
                                    {PocketTranscript.rows.indexOfFirst{it.s("itemId",it.s("id"))==note.s("id")||it.s("id")==note.s("id")}},
                                    {PocketTranscript.load(true)})
                                if(owns()&&found>=0){
                                    val target=PocketTranscript.rows.indexOfFirst{it.s("itemId",it.s("id"))==note.s("id")||it.s("id")==note.s("id")}
                                    if(target>=0)filterRequest=FilterReadingRequest(requireNotNull(sourceDestination).environment.id,sourceThread,PocketTranscript.rows[target].s("id"),0,false)
                                }else if(owns())Pocket.error="The original message is not available in loaded history."
                            }
                        }){BilingualLabel("Surrounding conversation")}
                        TextButton({val thread=Pocket.selected;val destination=Pocket.captureEnvironment();val owns={Pocket.selected==thread&&destination!=null&&Pocket.captureEnvironment()?.matches(destination)==true};val id=note.s("id");removingNotes=removingNotes+id;scope.launch{try{val response=Pocket.apiEnvironment(requireNotNull(destination),"/api/threads/$thread/notes/remove",JSONObject().put("id",id));require(response.optJSONArray("notes")!=null){"Removal was not acknowledged. Try again."};if(owns())Pocket.detail=Pocket.detail?.let{JSONObject(it.toString()).put("notes",response.optJSONArray("notes"))}}catch(e:Exception){if(owns())Pocket.error=PocketNetwork.error(e)}finally{removingNotes=removingNotes-id}}},enabled=note.s("id") !in removingNotes){BilingualLabel("Unsave",color=Muted)}
                    }}
                }
                if(important&&shownRows.isEmpty())item{BilingualLabel("No important updates yet",color=Muted)}
                d?.optJSONArray("outgoing")?.objects()?.filter{it.s("state") !in listOf("accepted","cancelled")&&it !in waiting}?.forEach{r->item(key="outgoing-${r.s("id")}"){Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(6.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                    val recoverable=r.s("state") in listOf("failed","unknown");val busy=Pocket.isReplyActionBusy(r.s("id"))
                    Label("YOU · ${r.s("state")}",if(recoverable)Coral else Muted);if(!transcriptContainsReply(rows,r.s("id")))ImmersionText("outgoing:"+r.s("id"),r.s("text"),rescue=false,fontSize=14.sp,maxLines=3,overflow=TextOverflow.Ellipsis)
                    if(recoverable){WorkflowText(ConnectionMessages.server(r.s("result")),fontSize=12.sp,color=Coral)
                        Row{TextButton({if(r.s("state")=="unknown")confirmRetry=r else Pocket.queuedReply(r.s("id"),"retry")},enabled=!busy){BilingualLabel("Retry")}
                            TextButton({editingQueue=r;queuedText=r.s("text")},enabled=!busy){BilingualLabel("Edit")}
                            TextButton({Pocket.queuedReply(r.s("id"),"remove")},enabled=!busy){BilingualLabel("Remove",color=Coral)}}
                    }
                }}}



                clientReplies.forEach{intent->item(key="client-reply-"+intent.id){ClientReplyRecovery(intent,PocketReplies.matchesCurrent(intent)&&transcriptContainsReply(rows,intent.id)){value->editor=TextFieldValue(value,TextRange(value.length));saveReplyDraft(draftKey,value);keyboardInput=true}}}
                // A row may grow taller than the viewport while streaming. Its
                // start is not the transcript end: follow the explicit boundary.
                item(key="transcript-end-anchor",contentType="scroll-anchor"){Spacer(Modifier.height(1.dp))}
            }
            if(PocketTranscript.updating&&(d!=null||rows.isNotEmpty()))
                Surface(color=Panel,shape=RoundedCornerShape(16.dp),modifier=Modifier.align(Alignment.TopCenter).padding(top=8.dp).semantics{liveRegion=LiveRegionMode.Polite}){
                    Row(Modifier.padding(horizontal=12.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){AnimatedMark(24);Spacer(Modifier.width(6.dp));BilingualLabel("Updating messages",fontSize=12.sp,color=Mint)}
                }
            else if(PocketTranscript.earlier&&PocketTranscript.loading&&!follow)
                Box(Modifier.align(Alignment.TopCenter).padding(top=10.dp).size(36.dp).background(Panel,CircleShape),contentAlignment=Alignment.Center){AnimatedMark(28)}
            if(conversationError.isNotBlank())Box(Modifier.align(Alignment.BottomCenter).padding(horizontal=16.dp)){ErrorBanner(conversationError,if(Pocket.error.isBlank()&&replyFailure!=null){{Pocket.dismissReplyFailure(failureSource,replyFailure.id)}}else null)}
            if(conversationError.isBlank()&&runProjection.groups.values.any{group->group.any{it.s("id") in expandedRunIds}})
                FilledTonalButton({
                    PocketTranscript.readerInteraction();follow=false
                    val key=list.layoutInfo.visibleItemsInfo.firstOrNull{it.index==list.firstVisibleItemIndex}?.key as? String
                    val collapsed=commandRunProjection(filteredRows,emptySet())
                    val target=key?.let(collapsed::displayId)
                    val offset=list.firstVisibleItemScrollOffset
                    setRunExpansion(emptySet())
                    val index=collapsed.rows.indexOfFirst{it.s("id")==target}
                    if(index>=0)list.requestScrollToItem(index,if(target==key&&collapsed.rows[index].s("kind")!="commandRuns")offset else 0)
                },modifier=Modifier.align(Alignment.BottomStart).padding(14.dp).heightIn(min=48.dp)){
                    BilingualLabel("Collapse runs",fontSize=12.sp)
                }
            if(conversationError.isBlank()&&!follow&&list.canScrollForward)FilledTonalButton({follow=true;scope.launch{list.animateScrollToItem((list.layoutInfo.totalItemsCount-1).coerceAtLeast(0))}},modifier=Modifier.align(Alignment.BottomEnd).padding(14.dp)){SymbolIcon(Icons.Rounded.ArrowDownward,null,Modifier.size(16.dp));Spacer(Modifier.width(5.dp));BilingualLabel("Latest",fontSize=12.sp)}
        }

        if(latestFailure!=null&&!active)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
            val failureError=latestFailure.optJSONObject("error")?:latestTurn?.optJSONObject("error")
            val failure=turnFailureKind(latestFailure.s("text",failureError?.s("message").orEmpty()),failureError?.s("additionalDetails").orEmpty())
            val automaticRecovery=d?.optJSONObject("recovery")?.s("state") in listOf("waiting","dispatching","running")
            BilingualLabel(if(automaticRecovery)"Waiting to resume automatically" else failure.title,color=if(automaticRecovery)Muted else Coral,fontSize=12.sp,maxLines=2,centered=false,modifier=Modifier.weight(1f))
            if(d?.optJSONObject("recovery")?.s("state") in listOf("waiting","dispatching"))TextButton({val source=Pocket.selected;val profile=Pocket.environmentId;val destination=Pocket.captureEnvironment();scope.launch{try{Pocket.apiEnvironment(requireNotNull(destination),"/api/threads/$source/recovery",JSONObject().put("cancel",true));if(Pocket.selected==source&&Pocket.environmentId==profile)Pocket.refreshDetail()}catch(e:Exception){if(Pocket.selected==source&&Pocket.environmentId==profile)Pocket.error=e.message?:"Could not pause recovery"}}}){BilingualLabel("Pause recovery",fontSize=12.sp)}
            else TextButton({val next=continuationDraft(editor.text);editor=TextFieldValue(next,TextRange(next.length));saveReplyDraft(draftKey,next);keyboardInput=true;scope.launch{delay(32);inputFocus.requestFocus();keyboard?.show()}},enabled=!Pocket.sending){BilingualLabel(if(d?.optJSONObject("recovery")?.s("state") in listOf("waiting","running","dispatching"))"Recovery active · review" else "Review continuation",fontSize=12.sp)}
        }
        Box{
        val visibleKeys by remember(list){derivedStateOf{list.layoutInfo.visibleItemsInfo.map{it.key}.toSet()}}
        val speechText=shownRows.filter{it.s("id") in visibleKeys&&it.s("kind")=="message"}.map{row->spokenRows[row.s("id")]?:PocketImmersion.display("row:"+row.s("id"),row.s("text"))}.filter{it.isNotBlank()}.joinToString("\n\n")
        ConversationNotesCard(important=important,onFilter={
            val before=shownRows.map{it.s("id")}
            val visible=list.firstVisibleItemIndex.coerceAtMost((before.size-1).coerceAtLeast(0))
            val offset=list.firstVisibleItemScrollOffset
            val nextImportant=!important
            val after=commandRunProjection(if(nextImportant)orphanRows+rows.filter{importantConversationKind(it.s("kind"),it.s("type"),it.s("status"),noteFor(it)!=null,it.s("phase"),it===latestAnswer)}else rows,expandedRunIds).rows
            val afterIds=after.map{it.s("id")}
            val retained=conversationFilterViewportAnchor(afterIds,list.layoutInfo.visibleItemsInfo.map{FilterVisibleRow(it.key.toString(),it.offset,it.size)},if(nextImportant)null else filterPassage,list.layoutInfo.viewportEndOffset)
            val anchor=retained?.index?:conversationFilterAnchor(before,afterIds,visible)
            val targetOffset=retained?.scrollOffset?:if(after.getOrNull(anchor)?.s("id")==before.getOrNull(visible))offset else 0
            if(BuildConfig.DEBUG&&Pocket.prefs.getBoolean("diagnostic:scroll",false))
                android.util.Log.d("PocketFilter","next=$nextImportant retained=$retained passage=${afterIds.getOrNull(anchor)} visible="+list.layoutInfo.visibleItemsInfo.map{it.key to it.offset})
            filterPassage=if(nextImportant)afterIds.getOrNull(anchor)else null
            filterTailPixels=if(nextImportant)list.layoutInfo.viewportSize.height else 0
            PocketTranscript.readerInteraction();follow=false;important=nextImportant
            afterIds.getOrNull(anchor)?.let{
                val request=FilterReadingRequest(Pocket.environmentId,Pocket.selected,it,targetOffset,nextImportant)
                filterRequest=request;filterPaddingRequest=if(nextImportant)request else null
            }
        },onControls={actionsOpen=true},speechText=speechText,status=status)
        DropdownMenu(actionsOpen,{actionsOpen=false},modifier=Modifier.width(300.dp).heightIn(max=420.dp)){ConversationActivityControls()}
        }
        InlineCaptureStatus()
        if(keyboardInput)Box(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)){
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Panel).padding(start=18.dp,end=5.dp,top=5.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically){
                VoiceLaunchButton(modifier=Modifier.size(64.dp),threadId=Pocket.selected,compact=true)
                BasicTextField(editor,{if(editor.text!=it.text)saveReplyDraft(draftKey,it.text);editor=it},
                    modifier=Modifier.weight(1f).focusRequester(inputFocus).heightIn(min=46.dp).padding(top=12.dp,bottom=12.dp,end=8.dp).onPreviewKeyEvent{event->
                        if(event.key==Key.Enter||event.key==Key.NumPadEnter){
                            if(event.type==KeyEventType.KeyDown){
                                if(event.isShiftPressed){
                                    val position=editor.selection.min
                                    editor=TextFieldValue(editor.text.replaceRange(position,editor.selection.max,"\n"),TextRange(position+1))
                                    saveReplyDraft(draftKey,editor.text)
                                }else if(editor.text.isNotBlank()&&!Pocket.sending)submit("steer")
                            }
                            true
                        }else false
                    },maxLines=3,
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={if(editor.text.isNotBlank()&&!Pocket.sending)submit("steer")}),
                    textStyle=LocalTextStyle.current.copy(color=Paper,fontSize=15.sp,lineHeight=22.sp),cursorBrush=SolidColor(Mint),
                    decorationBox={inner->Box(contentAlignment=Alignment.CenterStart){if(draft.isEmpty())BilingualLabel(if(active)"Steer this task…" else "Message Codex…",centered=false,color=Muted,fontSize=15.sp);inner()}})
                val hasDraft=draft.isNotBlank()
                val enabled=!Pocket.sending&&hasDraft
                val label=if(active)"Steer running turn" else "Send message"
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Ink).border(1.dp,if(hasDraft&&enabled)Mint else Muted.copy(alpha=.4f),RoundedCornerShape(12.dp))
                    .combinedClickable(enabled=enabled,onClickLabel=label,onLongClickLabel=if(hasDraft)"Queue for next turn" else label,
                        onLongClick={if(hasDraft)submit("queue")},onClick={submit("steer")})
                    .semantics{role=androidx.compose.ui.semantics.Role.Button;contentDescription=label;if(hasDraft)customActions=listOf(CustomAccessibilityAction("Queue for next turn"){if(enabled){submit("queue");true}else false})},contentAlignment=Alignment.Center){
                    if(Pocket.sending)CircularProgressIndicator(Modifier.size(18.dp),color=Mint,strokeWidth=2.dp)
                    else SymbolIcon(Icons.Rounded.ArrowUpward,null,tint=Paper,modifier=Modifier.size(44.dp))
                }
            }
        }
        if(!keyboardInput)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            VoiceLaunchButton(modifier=Modifier.weight(1f),threadId=Pocket.selected,bar=true)
            ChatActionButton("Keyboard",Icons.Rounded.Keyboard,Modifier.weight(1f),{keyboardInput=!keyboardInput;if(!keyboardInput)keyboard?.hide()})
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton({keyboard?.hide();Pocket.closeTask()},modifier=Modifier.size(48.dp)){SymbolIcon("ArrowBack","Back",Modifier.size(32.dp))}
            InlineSpeechVolume(Modifier.weight(1f).widthIn(min=64.dp))
            UsageDock(Modifier.width(usageDockWidth()),conversationOnly=true)
            if(active)IconButton({Pocket.interrupt()},modifier=Modifier.size(48.dp)){SymbolIcon("Stop","Stop agent",Modifier.size(32.dp),tint=Coral)}
        }
    }
    if(queueOpen)ModalBottomSheet(onDismissRequest={queueOpen=false},containerColor=Panel){
        Column(Modifier.fillMaxWidth().padding(horizontal=22.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            BilingualLabel("Queued messages",fontSize=22.sp,fontWeight=FontWeight.SemiBold)
            WorkflowText(if(waiting.any{it.s("state")=="held"})"Paused until you resume." else "Sent in order when the current turn finishes.",color=Muted,fontSize=13.sp)
            if(waiting.any{it.s("state")=="held"})Button({Pocket.resumeQueue();queueOpen=false},modifier=Modifier.fillMaxWidth()){BilingualLabel("Resume queue")}
            if(waiting.isEmpty())WorkflowText("The queue is empty.",color=Muted)
            LazyColumn(Modifier.heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                items(waiting,key={it.s("id")}){r->
                    var menu by remember(r.s("id")){mutableStateOf(false)}
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Ink.copy(alpha=.3f)).clickable{editingQueue=r;queuedText=r.s("text")}.padding(start=14.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
                        ImmersionText("outgoing:"+r.s("id"),r.s("text"),rescue=false,fontSize=14.sp,lineHeight=21.sp,modifier=Modifier.weight(1f),maxLines=3,overflow=TextOverflow.Ellipsis)
                        Box{IconButton({menu=true}){SymbolIcon(Icons.Rounded.MoreVert,"Queued message actions",tint=Muted)}
                            DropdownMenu(menu,{menu=false}){
                                DropdownMenuItem(text={BilingualLabel("Edit")},onClick={menu=false;editingQueue=r;queuedText=r.s("text")})
                                DropdownMenuItem(text={BilingualLabel(if(active)"Steer now" else "Send now")},onClick={menu=false;Pocket.queuedReply(r.s("id"),"send")})
                                DropdownMenuItem(text={BilingualLabel("Remove",color=Coral)},onClick={menu=false;Pocket.queuedReply(r.s("id"),"remove")})
                            }
                        }
                    }
                }
            }
        }
    }
    if(settingsOpen)TurnSettingsDialog{settingsOpen=false}
    if(renameOpen)AlertDialog(onDismissRequest={renameOpen=false},title={BilingualLabel("Rename conversation")},text={OutlinedTextField(taskName,{taskName=it},singleLine=true)},confirmButton={TextButton({Pocket.renameTask(taskName){renameOpen=false}},enabled=taskName.isNotBlank()){BilingualLabel("Save")}},dismissButton={TextButton({renameOpen=false}){BilingualLabel("Cancel")}})
    if(confirmArchive)AlertDialog(onDismissRequest={confirmArchive=false},title={BilingualLabel("Archive conversation?")},text={WorkflowText("This hides the conversation from recent tasks, pauses queued messages, and stops its notifications. Its history stays on the Codex host.")},confirmButton={TextButton({confirmArchive=false;Pocket.archiveTask()}){BilingualLabel("Archive")}},dismissButton={TextButton({confirmArchive=false}){BilingualLabel("Cancel")}})
    confirmRetry?.let{outgoing->AlertDialog(onDismissRequest={confirmRetry=null},title={BilingualLabel("Send this message again?")},text={WorkflowText("Codex may already have received this message. Retrying can send it twice. Check the conversation first.")},confirmButton={TextButton({Pocket.queuedReply(outgoing.s("id"),"retry",confirmUnknown=true){confirmRetry=null}},enabled=!Pocket.isReplyActionBusy(outgoing.s("id"))){BilingualLabel("Retry anyway")}},dismissButton={TextButton({confirmRetry=null}){BilingualLabel("Cancel")}})}
    editingQueue?.let{queued->AlertDialog(
        onDismissRequest={editingQueue=null},title={BilingualLabel("Edit message")},
        text={OutlinedTextField(queuedText,{queuedText=it},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=8)},
        confirmButton={TextButton({Pocket.queuedReply(queued.s("id"),"edit",queuedText){editingQueue=null}},enabled=queuedText.isNotBlank()&&!Pocket.isReplyActionBusy(queued.s("id"))){BilingualLabel("Save")}},
        dismissButton={TextButton({editingQueue=null}){BilingualLabel("Cancel")}}
    )}

}

@Composable fun TranscriptRow(row:JSONObject,onDisplayedText:((String)->Unit)?=null){
    when(row.s("kind")){
        "turn"->Row(Modifier.fillMaxWidth().padding(top=15.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){HorizontalDivider(Modifier.weight(1f),color=Line);Text(turnTime(row.optLong("startedAt")),fontSize=10.sp,color=Muted);HorizontalDivider(Modifier.weight(1f),color=Line)}
        "turnEnd"->{val seconds=row.optLong("durationMs")/1000
            if(row.s("status")=="failed"){
                val error=row.optJSONObject("error");val message=row.s("text",error?.s("message").orEmpty());val details=error?.s("additionalDetails").orEmpty();val failure=turnFailureKind(message,details)
                Column(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    WorkflowText(failure.title,color=Coral,fontSize=14.sp)
                    WorkflowText(failure.explanation,color=Paper,fontSize=13.sp,lineHeight=19.sp)
                    if(message.isNotBlank())SelectionContainer{Text(message,color=Muted,fontSize=12.sp,lineHeight=18.sp)}
                    if(details.isNotBlank()&&details!=message)SelectionContainer{Text(details,color=Muted,fontSize=12.sp,lineHeight=18.sp)}
                }
            }else WorkflowText(if(row.s("status")=="interrupted")"Interrupted" else if(seconds>0)"Completed · ${seconds}s" else "Completed",color=Muted,fontSize=10.sp,modifier=Modifier.padding(vertical=6.dp))
        }
        "request"->row.optJSONObject("request")?.let{RequestCard(it)}
        "attachments"->Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Label("SHARED FILES · ${row.s("title")}",Mint);row.optJSONArray("attachments")?.objects()?.forEach{Attachment(it)}}
        "user","message"->{val you=row.s("kind")=="user";Column(Modifier.fillMaxWidth().then(if(you)Modifier.clip(RoundedCornerShape(6.dp)).background(Panel).padding(14.dp)else Modifier.padding(vertical=5.dp)),verticalArrangement=Arrangement.spacedBy(7.dp)){
            SymbolIcon(if(you)"User" else "Codex",if(you)"Your message" else "Codex message",Modifier.size(20.dp))
            val sourceId="row:"+row.s("id")
            LaunchedEffect(row.s("text"),PocketImmersion.enabled){PocketImmersion.offer(sourceId,row.s("text"),if(you)"user message" else "Codex response")}
            ImmersionSelection(row.s("text")){BilingualMessage(sourceId,row.s("text"),onDisplayedText=onDisplayedText)}

            if(row.optBoolean("truncated"))WorkflowText("Excerpt · full content remains on the workstation",fontSize=10.sp,color=Muted)
        }}
        else->ActivityRow(row)
    }
}
@Composable fun ActivityRow(row:JSONObject,fullyExpanded:Boolean=false){
    val sourceId="activity:"+row.s("id")
    LaunchedEffect(row.s("text"),PocketImmersion.enabled){if(row.s("type")!="commandExecution")PocketImmersion.offer(sourceId,row.s("text"),"public thought or activity")}
    var locallyExpanded by remember(row.s("id")){mutableStateOf(false)}
    val expanded=fullyExpanded||locallyExpanded
    val running=row.s("status")=="inProgress";val failed=row.s("status") in listOf("failed","declined")||(!row.isNull("exitCode")&&row.optInt("exitCode")!=0)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel.copy(alpha=.6f)).then(if(fullyExpanded)Modifier else Modifier.clickable{locallyExpanded=!locallyExpanded}).padding(horizontal=11.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Box(Modifier.size(24.dp),contentAlignment=Alignment.Center){
                if(running)SymbolIcon(if(row.s("type")=="reasoning")"Psychology" else "Codex","Working",Modifier.size(24.dp),spinning=true)
                else SymbolIcon(if(failed)Icons.Rounded.ErrorOutline else Icons.Rounded.Check,null,tint=if(failed)Coral else Muted,modifier=Modifier.size(18.dp))
            }
            BilingualLabel(row.s("title"),fontSize=12.sp,color=if(running)Mint else Muted,modifier=Modifier.weight(1f),centered=false)
            if(row.optLong("durationMs")>0)Text("${row.optLong("durationMs")/1000}s",fontSize=10.sp,color=Muted)
            if(!fullyExpanded)SymbolIcon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Collapse activity" else "Expand activity",tint=Muted,modifier=Modifier.size(16.dp))
        }
        if(row.s("text").isNotBlank()){
            val text=PocketImmersion.target(sourceId,row.s("text"))
            if(row.s("type")=="commandExecution"){
                Text(row.s("text"),fontSize=11.sp,lineHeight=17.sp,color=Paper.copy(alpha=.82f),fontFamily=FontFamily.Monospace,maxLines=if(expanded)Int.MAX_VALUE else 2,overflow=TextOverflow.Ellipsis)
            }else if(expanded)BilingualMessage(sourceId,row.s("text"))
            else MarkdownPreview(text,fontSize=11.sp,lineHeight=17.sp,color=Paper.copy(alpha=.82f),maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        if(expanded&&row.s("detail").isNotBlank())SelectionContainer{Text(row.s("detail"),fontSize=11.sp,lineHeight=16.sp,fontFamily=FontFamily.Monospace,color=Muted,modifier=Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))}
        if(expanded&&row.optBoolean("truncated"))WorkflowText("Output excerpt · full content remains on the workstation",fontSize=10.sp,color=Muted)
    }
}

@Composable fun TurnSettingsDialog(onDismiss:()->Unit){
    val stored=Pocket.detail?.optJSONObject("turnSettings");val thread=Pocket.detail?.optJSONObject("thread")
    var models by remember{mutableStateOf(listOf<JSONObject>())}
    var model by remember{mutableStateOf(stored?.s("model")?:thread?.s("model").orEmpty())}
    var effort by remember{mutableStateOf(stored?.s("effort")?:thread?.s("reasoningEffort").orEmpty())}
    var plan by remember{mutableStateOf(stored?.s("mode")=="plan")}
    var modelMenu by remember{mutableStateOf(false)};var effortMenu by remember{mutableStateOf(false)}
    var problem by remember{mutableStateOf("")};var loading by remember{mutableStateOf(true)}
    LaunchedEffect(Unit){try{
        models=Pocket.api("/api/models").optJSONArray("models")?.objects()?:emptyList()
        val current=models.firstOrNull{it.s("model")==model}?:models.firstOrNull()
        current?.let{model=it.s("model");if(it.optJSONArray("efforts")?.objects()?.none{e->e.s("reasoningEffort")==effort}!=false)effort=it.s("defaultEffort")}
    }catch(e:Exception){problem=e.message?:"Could not load models"}finally{loading=false}}
    val selected=models.firstOrNull{it.s("model")==model}
    val efforts=selected?.optJSONArray("efforts")?.objects()?:emptyList()
    AlertDialog(onDismissRequest=onDismiss,title={BilingualLabel("Next-turn controls")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        WorkflowText("Changes apply when the next turn starts. Steering keeps the running turn's settings.",fontSize=13.sp,color=Muted)
        if(loading)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
        if(problem.isNotBlank())WorkflowText(problem,color=Coral,fontSize=12.sp)
        if(Pocket.error.isNotBlank())WorkflowText(Pocket.error,color=Coral,fontSize=12.sp)
        Box{OutlinedButton({modelMenu=true},enabled=models.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text(selected?.s("name")?:model.ifBlank{"Model"});Spacer(Modifier.weight(1f));SymbolIcon(Icons.Rounded.ExpandMore,null)}
            DropdownMenu(modelMenu,{modelMenu=false}){models.forEach{m->DropdownMenuItem(text={Text(m.s("name"))},onClick={model=m.s("model");effort=m.s("defaultEffort");modelMenu=false})}}}
        Box{OutlinedButton({effortMenu=true},enabled=efforts.isNotEmpty(),modifier=Modifier.fillMaxWidth()){BilingualLabel("Reasoning: ${effort.ifBlank{"default"}}");Spacer(Modifier.weight(1f));SymbolIcon(Icons.Rounded.ExpandMore,null)}
            DropdownMenu(effortMenu,{effortMenu=false}){efforts.forEach{e->DropdownMenuItem(text={BilingualLabel(e.s("reasoningEffort"))},onClick={effort=e.s("reasoningEffort");effortMenu=false})}}}
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){BilingualLabel(if(plan)"Plan mode" else "Build mode");WorkflowText(if(plan)"Propose a plan before implementation" else "Carry out the requested work",fontSize=11.sp,color=Muted)};Switch(plan,{plan=it})}
    }},confirmButton={TextButton({Pocket.updateTurnSettings(JSONObject().put("model",model).put("effort",effort).put("mode",if(plan)"plan" else "default"),onDismiss)},enabled=selected!=null&&effort.isNotBlank()&&!loading){BilingualLabel("Save")}},dismissButton={TextButton(onDismiss){BilingualLabel("Cancel")}})
}

internal data class FilterVisibleRow(val id:String,val offset:Int,val size:Int=0)
internal data class FilterReadingRequest(val environment:String,val thread:String?,val row:String,val offset:Int,val important:Boolean)
internal data class FilterViewportAnchor(val index:Int,val scrollOffset:Int)
/** Keep a surviving native item at its pixel position, even if the leading row disappears. */
internal fun conversationFilterViewportAnchor(after:List<String>,visible:List<FilterVisibleRow>,preferred:String?=null,viewportEnd:Int=Int.MAX_VALUE):FilterViewportAnchor? {
    val positions=after.withIndex().associate{it.value to it.index}
    val survivors=visible.filter{it.id in positions}
    val passage=survivors.firstOrNull{it.id==preferred}
        ?:survivors.firstOrNull{it.offset<viewportEnd&&(it.size==0||it.offset.toLong()+it.size>0)}
        ?:survivors.firstOrNull()?:return null
    return FilterViewportAnchor(positions.getValue(passage.id),-passage.offset)
}

internal fun conversationFilterAnchor(before:List<String>,after:List<String>,visible:Int):Int {
    if(after.isEmpty())return -1
    if(before.isEmpty())return after.lastIndex
    val position=visible.coerceIn(0,before.lastIndex)
    val direct=after.indexOf(before[position]);if(direct>=0)return direct
    for(distance in 1..before.size){
        val next=position+distance;if(next<before.size){val found=after.indexOf(before[next]);if(found>=0)return found}
        val previous=position-distance;if(previous>=0){val found=after.indexOf(before[previous]);if(found>=0)return found}
    }
    return after.lastIndex
}

/** Compact command history; failures remain evident even when details are folded. */
@Composable internal fun CommandRunsHeader(runs:List<JSONObject>,expanded:Boolean,onToggle:()->Unit) {
    val working=runs.any{it.s("status")=="inProgress"}
    val failed=runs.count{it.s("status") in listOf("failed","declined")||(!it.isNull("exitCode")&&it.optInt("exitCode")!=0)}
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(
        onClickLabel=if(expanded)"Collapse command runs" else "Expand command runs",onClick=onToggle
    ).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        SymbolIcon(if(working)"Codex" else if(failed>0)"Stop" else "Terminal",if(working)"Working" else if(failed>0)"Failed" else "Command runs",Modifier.size(20.dp),spinning=working)
        Text(runs.size.toString(),fontSize=13.sp,color=Muted)
        BilingualLabel("Runs",fontSize=13.sp,color=Muted)
        if(working)BilingualLabel("Working",fontSize=12.sp,color=Mint)
        if(failed>0){Text(failed.toString(),fontSize=12.sp,color=Coral);BilingualLabel("Failed",fontSize=12.sp,color=Coral)}
        Spacer(Modifier.weight(1f))
        SymbolIcon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Collapse command runs" else "Expand command runs",Modifier.size(20.dp),tint=Muted)
    }
}
