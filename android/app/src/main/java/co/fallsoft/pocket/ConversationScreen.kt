package co.fallsoft.pocket

import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.runtime.saveable.rememberSaveable
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

@OptIn(ExperimentalFoundationApi::class,ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class,FlowPreview::class,androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable fun ConversationScreen(){
    val conversation=CodexConversations.snapshot
    val d=conversation.detail;val t=d?.optJSONObject("thread");val rows=conversation.rows
    val task=Pocket.tasks.firstOrNull{it.id==Pocket.selected}
    val failureSource=Pocket.selected;val replyFailure=Pocket.replyFailure()
    val clientReplies=conversation.deliveries
    val recoveryAlreadyShown=clientReplies.any{it.id==replyFailure?.id}||d?.optJSONArray("outgoing")?.objects()?.any{it.s("id")==replyFailure?.id}==true
    val conversationError=Pocket.error.ifBlank{if(recoveryAlreadyShown)"" else replyFailure?.message.orEmpty()}.ifBlank{PocketTranscript.readError}
    val pending=conversation.pending
    val latestTurn=rows.lastOrNull{it.s("kind")=="turn"}
    val status=conversation.execution
    val active=status in listOf(ConversationRunState.WORKING,ConversationRunState.STARTING,ConversationRunState.NEEDS_YOU)
    val latestFailure=(if(PocketTranscript.canonicalMode)d?.optJSONObject("nativeExecutionTurn")else rows.lastOrNull{it.s("kind")=="turnEnd"})?.takeIf{it.s("status")=="failed"}
    val list=key(Pocket.environmentId,Pocket.selected){rememberLazyListState()};val scope=rememberCoroutineScope();val dragged by list.interactionSource.collectIsDraggedAsState()
    DisposableEffect(Pocket.environmentId,Pocket.selected,conversation.incarnation){
        val environment=Pocket.environmentId;val id=Pocket.selected;val key=conversation.key;val incarnation=conversation.incarnation
        if(id!=null)CodexConversations.viewportMounted(environment,id)
        onDispose{CodexConversations.viewportDisposed(key,incarnation)}
    }
    LaunchedEffect(list,conversation.key,conversation.incarnation){
        snapshotFlow{list.isScrollInProgress}.collect{CodexConversations.readerMotion(conversation.key,conversation.incarnation,it)}
    }

    val restoreRevision=PocketTranscript.cacheRestoreRevision
    val readPosition=remember(Pocket.environmentId,Pocket.selected,restoreRevision,conversation.incarnation){PocketTranscript.readPosition}
    var restoredPosition by remember(Pocket.environmentId,Pocket.selected,restoreRevision,conversation.incarnation){mutableStateOf(readPosition==null||readPosition.follow)}
    var follow by remember(Pocket.environmentId,Pocket.selected,restoreRevision,conversation.incarnation){mutableStateOf(readPosition?.follow?:true)}
    var important by remember(Pocket.environmentId,Pocket.selected){mutableStateOf(false)}
    var filterPassage by remember(Pocket.environmentId,Pocket.selected){mutableStateOf<String?>(null)}
    var filterRequest by remember(Pocket.environmentId,Pocket.selected){mutableStateOf<FilterReadingRequest?>(null)}
    var filterPaddingRequest by remember(Pocket.environmentId,Pocket.selected){mutableStateOf<FilterReadingRequest?>(null)}
    var filterTailPixels by remember(Pocket.environmentId,Pocket.selected){mutableIntStateOf(0)}
    val density=LocalDensity.current
    var confirmRetry by remember(Pocket.selected){mutableStateOf<JSONObject?>(null)}
    val notes=d?.optJSONArray("notes")?.objects().orEmpty()
    var reviewOpen by rememberSaveable(Pocket.environmentId,Pocket.selected){mutableStateOf(false)}
    val reviewRequests=d?.optJSONArray("pending")?.objects().orEmpty()
    val reviewResults=remember(notes,rows){usefulConversationResults(notes,rows)}
    val otherQuestions=Pocket.attention.filter{it.s("thread_id")!=Pocket.selected&&it.s("kind") in listOf("question","approval")}
    val reviewCount=reviewRequests.size+otherQuestions.size

    fun noteFor(row:JSONObject)=notes.firstOrNull{it.s("id")==row.s("itemId",row.s("id"))||it.s("id")==row.s("id")}
    val latestAnswer=rows.lastOrNull{it.s("kind")=="message"}
    val orphanRows=notes.filter{note->rows.none{row->row.s("itemId",row.s("id"))==note.s("id")||row.s("id")==note.s("id")}}.map{note->JSONObject().put("id","retained:"+note.s("id")).put("itemId",note.s("id")).put("kind","message").put("text",note.s("text")).put("retainedExcerpt",true)}
    val historyGaps=PocketTranscript.historyGaps
    val gapBoundaries=historyGaps.map{it.id to it.beforeRow}
    val retainedAnchor=transcriptWindowAnchor(PocketTranscript.readPosition,follow)
    val windowRange=remember(rows,gapBoundaries,retainedAnchor){connectedTranscriptRange(rows,historyGaps,retainedAnchor)}
    val unplacedInputs=remember(rows){unplacedTranscriptInputs(rows)}
    val windowRows=remember(rows,gapBoundaries,windowRange,unplacedInputs){
        if(PocketTranscript.canonicalMode)rows else connectedTranscriptWindow(rows,historyGaps,windowRange).filterNot{it.s("id") in unplacedInputs}
    }
    val nativeRequests=if(PocketTranscript.canonicalMode)d?.optJSONArray("pending")?.objects()?.map{request->JSONObject().put("id","request/"+request.s("id")).put("kind","request").put("request",request)}.orEmpty()else emptyList()
    val filteredRows=(if(!important)windowRows else orphanRows+rows.filter{it.s("id") !in unplacedInputs}.filter{importantConversationKind(it.s("kind"),it.s("type"),it.s("status"),noteFor(it)!=null,it.s("phase"),it===latestAnswer)}).filterNot{it.s("kind")=="request"}
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
    LaunchedEffect(important,filterPaddingRequest,shownIds,conversation.incarnation){
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
    DisposableEffect(Pocket.environmentId,Pocket.selected,conversation.incarnation){
        val source=Pocket.selected;val owner=PocketTranscript.scopeKey()
        onDispose{
            // visibleItemsInfo also includes a preceding row inside content padding.
            // Its key must not be paired with the next viewport row's scroll offset.
            val visible=list.layoutInfo.visibleItemsInfo.filter{it.key.toString() in latestRowIds}.map{TranscriptVisibleRow(it.index,it.key.toString())}
            val position=transcriptReadPosition(visible,list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset,latestFollow)
            if(source!=null&&position!=null)PocketTranscript.rememberPosition(owner,source,position.copy(rowId=transcriptReadingAnchor(latestRunProjection.canonicalId(position.rowId),PocketTranscript.historyGaps),offset=if(position.rowId.startsWith("ui:run-group:")||position.rowId.startsWith(TranscriptHistoryGap.ROW_PREFIX))0 else position.offset))
        }
    }
    LaunchedEffect(list,Pocket.environmentId,Pocket.selected,conversation.incarnation){
        val source=Pocket.selected;val owner=PocketTranscript.scopeKey()
        snapshotFlow{
            if(!restoredPosition||PocketTranscript.loading||list.isScrollInProgress||important)null else
                transcriptReadPosition(list.layoutInfo.visibleItemsInfo.map{TranscriptVisibleRow(it.index,it.key.toString())},list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset,follow)
        }.debounce(350).filterNotNull().distinctUntilChanged().collect{position->
            if(source!=null)PocketTranscript.rememberPosition(owner,source,position.copy(rowId=transcriptReadingAnchor(latestRunProjection.canonicalId(position.rowId),PocketTranscript.historyGaps),offset=if(position.rowId.startsWith("ui:run-group:")||position.rowId.startsWith(TranscriptHistoryGap.ROW_PREFIX))0 else position.offset))
        }
    }
    LaunchedEffect(Pocket.environmentId,Pocket.selected,rows.size,restoreRevision,conversation.incarnation){
        if(!restoredPosition&&readPosition!=null&&rows.isNotEmpty()){
            val resolved=resolveTranscriptReadPosition(rows,readPosition)?:readPosition
            val index=transcriptAnchorIndex(shownIds,resolved.copy(rowId=runProjection.displayId(resolved.rowId)))
            if(PocketTranscript.mayRestorePosition(restoreRevision)&&!dragged){
                if(index>=0)list.scrollToItem(index,if(shownIds[index].startsWith("ui:run-group:"))0 else readPosition.offset.coerceAtLeast(0))else follow=true
            }
            restoredPosition=true
            CodexConversations.positionRestored(conversation.key,conversation.incarnation,restoreRevision)
        }
    }
    var actionsOpen by remember(Pocket.selected){mutableStateOf(false)}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(list,lifecycle,Pocket.environmentId,Pocket.selected,conversation.incarnation){
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
            snapshotFlow{
                val latest=PocketTranscript.rows.lastOrNull{it.s("kind") !in listOf("turn","turnEnd")&&it.s("text").isNotBlank()}?.s("id")
                val visible=latest!=null&&list.layoutInfo.visibleItemsInfo.any{it.key==latest}
                val eligible=AppWindowVisibility.focused&&!actionsOpen&&!important&&!BlackoutVisibility.active&&!PocketVoice.active&&!PocketTranscript.loading&&!PocketTranscript.updating&&!list.isScrollInProgress&&!list.canScrollForward&&visible
                eligible to PocketTranscript.revision
            }.collectLatest{(eligible,_)->
                if(eligible){delay(500);CodexConversations.presented(conversation.key,conversation.incarnation)}
            }
        }
    }
    var removingNotes by remember(Pocket.environmentId,Pocket.selected){mutableStateOf(setOf<String>())}
    val spokenRows=remember(Pocket.environmentId,Pocket.selected){mutableStateMapOf<String,Pair<String,String>>()}
    var settingsOpen by remember(Pocket.selected){mutableStateOf(false)}
    var renameOpen by remember(Pocket.selected){mutableStateOf(false)}
    var taskName by remember(Pocket.selected){mutableStateOf(TextFieldValue())}
    var confirmArchive by remember(Pocket.selected){mutableStateOf(false)}
    var editingQueue by remember(Pocket.selected){mutableStateOf<JSONObject?>(null)}
    var queuedText by remember(Pocket.selected){mutableStateOf("")}
    var queueOpen by remember(Pocket.selected){mutableStateOf(false)}
    val draftKey=Pocket.key("draft:${Pocket.selected}");val editorState=remember(Pocket.environmentId,Pocket.selected){mutableStateOf((Pocket.prefs.getString(draftKey,"")?:"").let{TextFieldValue(it,TextRange(it.length))})};var editor by editorState
    LaunchedEffect(PocketReplies.revision,draftKey,conversation.incarnation){if(PocketReplies.clearedDrafts[draftKey]?.let{it.text==editor.text&&it.generation==replyDraftGeneration(draftKey)}==true&&!Pocket.prefs.contains(draftKey))editor=TextFieldValue("")}
    var keyboardInput by remember(Pocket.selected){mutableStateOf(Pocket.openWithKeyboard)}
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val inputFocus=remember{androidx.compose.ui.focus.FocusRequester()}
    val seenQuestions=remember(Pocket.environmentId,Pocket.selected){mutableSetOf<String>()}
    LaunchedEffect(reviewRequests.map{it.s("id")}){
        val fresh=reviewRequests.any{it.s("id") !in seenQuestions}
        seenQuestions.addAll(reviewRequests.map{it.s("id")})
        if(fresh&&!keyboardInput&&!PocketVoice.active&&!BlackoutVisibility.active)reviewOpen=true
    }
    LaunchedEffect(keyboardInput,conversation.incarnation){if(keyboardInput){inputFocus.requestFocus();keyboard?.show()}}
    ConversationBack{Pocket.closeTask()}
    val hasDraft by remember(editorState){derivedStateOf{editorState.value.text.isNotBlank()}}
    val submit:(String)->Unit={mode->val submitted=editor.text;val submittedGeneration=replyDraftGeneration(draftKey);follow=true;CodexConversations.submit(conversation.key,conversation.incarnation,submitted,mode,draftKey){if(editor.text==submitted&&replyDraftGeneration(draftKey)==submittedGeneration){editor=TextFieldValue("");Pocket.prefs.edit().remove(draftKey).apply()}}}
    val waiting=d?.optJSONArray("outgoing")?.objects()?.filter{it.s("state") in listOf("queued","held")&&it.s("mode")=="queue"}?:emptyList()
    LaunchedEffect(list,conversation.incarnation){
        if(BuildConfig.DEBUG&&Pocket.prefs.getBoolean("diagnostic:scroll",false))snapshotFlow{Triple(list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset,list.isScrollInProgress)}.collect{position->
            android.util.Log.d("PocketScroll","index=${position.first} offset=${position.second} moving=${position.third} follow=$follow dragged=$dragged rows=${shownRows.size}")
        }
    }
    LaunchedEffect(list,conversation.key,conversation.incarnation){
        snapshotFlow{
            if(list.isScrollInProgress||PocketTranscript.currentLoading)emptySet()
            else list.layoutInfo.visibleItemsInfo.mapTo(linkedSetOf()){latestRunProjection.canonicalId(it.key.toString())}
        }.debounce(120).distinctUntilChanged().collect{ids->
            CodexConversations.visibleRows(conversation.key,conversation.incarnation,ids)
        }
    }
    var pagingViewport by remember(Pocket.environmentId,Pocket.selected){mutableStateOf(false)}
    var pageHydrated by remember(Pocket.environmentId,Pocket.selected){mutableStateOf(true)}
    var pageGesture by remember(Pocket.environmentId,Pocket.selected){mutableLongStateOf(0)}
    var historyGesture by remember(Pocket.environmentId,Pocket.selected){mutableLongStateOf(0)}
    var satisfiedHistoryGesture by remember(Pocket.environmentId,Pocket.selected){mutableLongStateOf(-1)}
    var satisfiedGapGesture by remember(Pocket.environmentId,Pocket.selected){mutableLongStateOf(0)}
    data class GapDemand(val gesture:Long,val rowId:String,val cursor:String)
    var gapDemand by remember(conversation.incarnation){mutableStateOf<GapDemand?>(null)}
    var pointerHistoryGesture by remember(conversation.incarnation){mutableStateOf(false)}
    fun viewportGap():TranscriptHistoryGap?{
        val visible=list.layoutInfo.visibleItemsInfo.mapTo(hashSetOf()){it.key.toString()}
        return PocketTranscript.historyGaps.lastOrNull{it.rowId in visible}
    }
    fun gapNearViewport():Boolean = viewportGap()!=null
    suspend fun hydrateViewport(gesture:Long,initial:Boolean=false){
        val budget=TranscriptViewportBudget(android.os.SystemClock.elapsedRealtime())
        do{
            if(PocketTranscript.loading||PocketTranscript.updating||list.isScrollInProgress||historyGesture!=gesture)return
            val gap=viewportGap()
            val cursor=gap?.cursor?:PocketTranscript.before.takeIf{PocketTranscript.earlier}
            if(gap?.problem?.isNotBlank()==true||!budget.next(cursor,android.os.SystemClock.elapsedRealtime()))break
            val anchor=list.layoutInfo.visibleItemsInfo.firstOrNull{item->
                item.key.toString() in latestRowIds&&!item.key.toString().startsWith(TranscriptHistoryGap.ROW_PREFIX)
            }
            val canonical=anchor?.let{latestRunProjection.canonicalId(it.key.toString())}
            if(gap!=null)CodexConversations.readGapFrom(conversation.key,conversation.incarnation,gap.cursor)
            else CodexConversations.readFrom(conversation.key,conversation.incarnation,older=true)
            androidx.compose.runtime.withFrameNanos{};androidx.compose.runtime.withFrameNanos{}
            if(historyGesture!=gesture||list.isScrollInProgress)return
            if(!follow&&anchor!=null&&canonical!=null){
                val index=latestRowIds.indexOf(latestRunProjection.displayId(canonical))
                if(index>=0)list.requestScrollToItem(index,-anchor.offset)
                androidx.compose.runtime.withFrameNanos{}
            }
            // Continue only while the reader still needs this edge. Tool-heavy
            // raw pages may fold to one row, so one HTTP page is not one viewport.
            if(initial){
                if(list.canScrollBackward)break
            }else if(viewportGap()==null&&(list.firstVisibleItemIndex>4||!PocketTranscript.earlier))break
            if(PocketTranscript.readError.isNotBlank())break
        }while(true)
        satisfiedGapGesture=maxOf(satisfiedGapGesture,gesture)
    }
    var initialViewportHydrated by remember(conversation.incarnation){mutableStateOf(false)}
    LaunchedEffect(list,conversation.incarnation){
        snapshotFlow{
            !initialViewportHydrated&&!important&&restoredPosition&&PocketTranscript.currentHistoryVerified&&
                !PocketTranscript.updating&&!PocketTranscript.loading&&!list.isScrollInProgress&&
                list.layoutInfo.visibleItemsInfo.isNotEmpty()&&!list.canScrollBackward&&
                (viewportGap()!=null||PocketTranscript.earlier)
        }.distinctUntilChanged().filter{it}.collect{
            initialViewportHydrated=true
            hydrateViewport(historyGesture,initial=true)
        }
    }
    var shortHistoryPull by remember(Pocket.environmentId,Pocket.selected){mutableStateOf(false)}
    val historyScrollObserver=remember(list,Pocket.environmentId,Pocket.selected){object:NestedScrollConnection{
        override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset{
            // Observe only; LazyColumn retains all gesture consumption and inertia.
            if(source==NestedScrollSource.UserInput&&available.y>0&&!list.canScrollBackward&&!list.canScrollForward&&(PocketTranscript.earlier||PocketTranscript.historyGap!=null))shortHistoryPull=true
            return Offset.Zero
        }
    }}
    LaunchedEffect(dragged,conversation.incarnation){
        if(dragged){CodexConversations.readerInteraction(conversation.key,conversation.incarnation);follow=false;filterPassage=null}
        else{
            if(shouldResumeTranscriptFollowing(list.canScrollForward,list.canScrollBackward,PocketTranscript.earlier||PocketTranscript.historyGap!=null,shortHistoryPull))follow=true
            shortHistoryPull=false
        }
    }
    LaunchedEffect(list,conversation.incarnation){
        var settled:Job?=null
        list.interactionSource.interactions.collect{interaction->
            when(interaction){
                is DragInteraction.Start->{
                    settled?.cancel();pointerHistoryGesture=true
                    CodexConversations.readerInteraction(conversation.key,conversation.incarnation)
                    follow=false;filterPassage=null;historyGesture++
                    gapDemand=viewportGap()?.let{GapDemand(historyGesture,it.rowId,it.cursor)}
                }
                is DragInteraction.Stop,is DragInteraction.Cancel->{
                    settled=launch{
                        withFrameNanos{}
                        snapshotFlow{list.isScrollInProgress}.first{!it}
                        pointerHistoryGesture=false
                    }
                }
            }
        }
    }
    LaunchedEffect(list,conversation.incarnation){
        // Accessibility scroll actions do not emit pointer-drag interactions.
        snapshotFlow{list.isScrollInProgress&&!follow&&!pagingViewport&&!pointerHistoryGesture}
            .distinctUntilChanged().filter{it}.collect{historyGesture++}
    }
    LaunchedEffect(list,conversation.incarnation){
        // Remember a crossed interval even if the fling settles beyond its marker.
        snapshotFlow{if(!follow&&!pagingViewport&&historyGesture>satisfiedGapGesture)viewportGap() else null}
            .filterNotNull().collect{gap->
                if(gapDemand?.gesture!=historyGesture)gapDemand=GapDemand(historyGesture,gap.rowId,gap.cursor)
            }
    }
    // Resolve following before measure; never paint the oldest retained page
    // and only later jump to the current answer. Gate on transcript changes,
    // leaving real gestures and unrelated composer recompositions alone.
    val followRequest=remember(list){arrayOfNulls<Any>(1)}
    val followIdentity=listOf(PocketTranscript.revision,clientReplies.map{Triple(it.id,it.state,it.message)},follow,important,restoreRevision,shownRows.size)
    SideEffect{
        if(followRequest[0]!=followIdentity){
            followRequest[0]=followIdentity
            if(follow&&!dragged&&!important&&restoredPosition){
                val request=shownRows.indexOfLast{it.s("kind")=="request"}
                val outgoingCount=d?.optJSONArray("outgoing")?.objects()?.count{it.s("state") !in listOf("accepted","cancelled")&&it !in waiting}?:0
                val emptyLoading=if(rows.isEmpty()&&PocketTranscript.updating)1 else 0
                val tail=emptyLoading+shownRows.size+outgoingCount+clientReplies.size+
                    (if(conversationError.isNotBlank())1 else 0)+(if(PocketTranscript.updating)1 else 0)
                list.requestScrollToItem(if(request>=0)emptyLoading+request else tail)
            }
        }
    }
    // Missing intervals hydrate a bounded viewport per explicit reading demand.
    // Raw tool pages can fold to one row; no demand downloads the whole archive.
    LaunchedEffect(list,Pocket.environmentId,Pocket.selected,important,conversation.incarnation){
        snapshotFlow{
            gapDemand?.takeIf{!important&&!follow&&!pagingViewport&&restoredPosition&&
                it.gesture>satisfiedGapGesture&&!list.isScrollInProgress&&
                !PocketTranscript.loading&&!PocketTranscript.updating}
        }.distinctUntilChanged().filterNotNull().collect{demand->
            delay(180)
            if(list.isScrollInProgress||pagingViewport||PocketTranscript.loading||PocketTranscript.updating)return@collect
            val gap=PocketTranscript.historyGaps.firstOrNull{it.rowId==demand.rowId}
            if(gap==null||gap.cursor!=demand.cursor){
                satisfiedGapGesture=maxOf(satisfiedGapGesture,demand.gesture)
            }else hydrateViewport(demand.gesture)
            if(satisfiedGapGesture>=demand.gesture&&gapDemand==demand)gapDemand=null
        }
    }
    // Thumb scrolling is the pagination control. LazyColumn's stable row keys retain
    // the visible message and pixel offset when an older page is prepended.
    LaunchedEffect(Pocket.environmentId,Pocket.selected,important,conversation.incarnation){
        snapshotFlow{!pagingViewport&&restoredPosition&&historyGesture!=satisfiedHistoryGesture&&PocketTranscript.automaticRetryAllowed&&!list.isScrollInProgress&&shouldPrefetchTranscript(follow,important,list.firstVisibleItemIndex,PocketTranscript.earlier,PocketTranscript.loading)}
            .distinctUntilChanged().filter{it}.collect{
                val gesture=historyGesture
                delay(180)
                if(!pagingViewport&&restoredPosition&&!list.isScrollInProgress){
                    hydrateViewport(gesture)
                    satisfiedHistoryGesture=gesture
                }
            }
    }
    val ConversationActivityControls:@Composable ColumnScope.()->Unit={
            if(reviewCount>0)TextButton({actionsOpen=false;reviewOpen=true},modifier=Modifier.fillMaxWidth()){BilingualLabel("Needs you · $reviewCount",color=Coral)}
            UsageDetails()
            d?.optJSONObject("turnSettings")?.let{settings->Text(listOf(settings.s("model"),settings.s("effort"),settings.s("mode")).filter{it.isNotBlank()}.joinToString(" · "),fontSize=12.sp,color=Muted)}
            TextButton({Pocket.watch(!(d?.optBoolean("watched")?:false));actionsOpen=false},modifier=Modifier.fillMaxWidth()){BilingualLabel(if(d?.optBoolean("watched")==true)"Unfollow" else "Follow")}
            if(waiting.isNotEmpty())TextButton({actionsOpen=false;queueOpen=true},modifier=Modifier.fillMaxWidth()){BilingualLabel("Queue · ${waiting.size}")}
            TextButton({rows.lastOrNull{it.s("kind")=="message"&&it.s("text").isNotBlank()}?.let{keepConversationReply(it)};actionsOpen=false},enabled=rows.any{it.s("kind")=="message"},modifier=Modifier.fillMaxWidth()){BilingualLabel("Keep latest reply in notes")}
            TextButton({actionsOpen=false;settingsOpen=true},modifier=Modifier.fillMaxWidth()){BilingualLabel("Model, effort & mode")}
            TextButton({actionsOpen=false;taskName=t?.s("name").orEmpty().let{TextFieldValue(it,TextRange(0,it.length))};renameOpen=true},modifier=Modifier.fillMaxWidth()){BilingualLabel("Rename")}
            TextButton({actionsOpen=false;confirmArchive=true},enabled=!active,modifier=Modifier.fillMaxWidth()){BilingualLabel("Archive")}
            TextButton({actionsOpen=false;submit("queue")},enabled=hasDraft&&!Pocket.sending,modifier=Modifier.fillMaxWidth()){BilingualLabel("Queue message")}
    }
    val visibleSpeechKeys by remember(list){derivedStateOf{list.layoutInfo.visibleItemsInfo.map{it.key}.toSet()}}
    val hasVisibleSpeech=shownRows.any{it.s("id") in visibleSpeechKeys&&it.s("kind")=="message"&&it.s("text").isNotBlank()}
    // This closure reads current language/viewport only on an explicit speech action.
    // Reading the changing spokenRows map during composition invalidates the footer
    // for every phrase crossing, even when its visible controls are unchanged.
    val visibleSpeech:()->String={
        val keys=list.layoutInfo.visibleItemsInfo.map{it.key}.toSet()
        shownRows.filter{it.s("id") in keys&&it.s("kind")=="message"}
            .map{row->spokenRows[row.s("id")]?.takeIf{it.first==row.s("text")}?.second?:PocketImmersion.display("row:"+row.s("id"),row.s("text"))}
            .filter{it.isNotBlank()}.joinToString("\n\n")
    }
    val reviewPanel:@Composable ()->Unit={ConversationReviewContent(reviewRequests,reviewResults,otherQuestions,
        onClose={reviewOpen=false},
        onReply={note->val context="About this result:\n"+note.s("text")+"\n\n";val next=context+editor.text;editor=TextFieldValue(next,TextRange(context.length));saveReplyDraft(draftKey,next);keyboardInput=true;reviewOpen=false},
        onSource={note->
            reviewOpen=false;follow=false;CodexConversations.readerInteraction(conversation.key,conversation.incarnation)
            val sourceThread=Pocket.selected;val destination=Pocket.captureEnvironment()
            val owns={Pocket.selected==sourceThread&&destination!=null&&Pocket.captureEnvironment()?.matches(destination)==true}
            scope.launch{val found=locateTranscriptSource(owns,{PocketTranscript.loading},{PocketTranscript.earlier},{PocketTranscript.before},
                {PocketTranscript.rows.indexOfFirst{it.s("itemId",it.s("id"))==note.s("id")||it.s("id")==note.s("id")}},
                {CodexConversations.readFrom(conversation.key,conversation.incarnation,older=true)})
                if(owns()&&found>=0){val row=PocketTranscript.rows.getOrNull(found);if(row!=null)filterRequest=FilterReadingRequest(requireNotNull(destination).environment.id,sourceThread,row.s("id"),0,false)}
                else if(owns())Pocket.error="The original result is outside available history; its saved text remains in Questions and results."
            }
        },
        onDismiss={note->val thread=Pocket.selected;val destination=Pocket.captureEnvironment();val id=note.s("id");removingNotes=removingNotes+id
            scope.launch{try{val response=Pocket.apiEnvironment(requireNotNull(destination),"/api/threads/$thread/notes/remove",JSONObject().put("id",id));require(response.optJSONArray("notes")!=null){"Removal was not acknowledged."}
                if(Pocket.selected==thread&&destination!=null&&Pocket.captureEnvironment()?.matches(destination)==true)Pocket.detail=Pocket.detail?.let{JSONObject(it.toString()).put("notes",response.optJSONArray("notes"))}
            }catch(e:Exception){if(Pocket.selected==thread&&destination!=null&&Pocket.captureEnvironment()?.matches(destination)==true)Pocket.error=PocketNetwork.error(e)}finally{removingNotes=removingNotes-id}}
        },removing=removingNotes)}
    ConversationReviewScaffold(reviewOpen,{reviewOpen=false},reviewPanel){
    Column(Modifier.fillMaxSize().imePadding()){
        Box(Modifier.weight(1f).fillMaxWidth()){
            LazyColumn(Modifier.fillMaxSize().nestedScroll(historyScrollObserver).semantics{testTagsAsResourceId=true}.testTag("conversation-history"),state=list,contentPadding=PaddingValues(start=18.dp,end=18.dp,top=12.dp,bottom=12.dp+with(density){(if(important)filterTailPixels else 0).toDp()}),verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(rows.isEmpty()&&PocketTranscript.updating)item{Box(Modifier.fillMaxWidth().padding(vertical=40.dp),contentAlignment=Alignment.Center){AnimatedMark(112)}}
                items(shownRows,key={it.s("id")}){row->
                    if(important&&noteFor(row)!=null&&!row.optBoolean("retainedExcerpt"))BilingualLabel("Saved answer",color=Mint,fontSize=12.sp)
                    if(row.optBoolean("retainedExcerpt"))BilingualLabel("Retained passage · source outside loaded history",color=Muted,fontSize=12.sp)
                    val commandGroup=runProjection.groups[row.s("id")]
                    if(row.s("kind")=="historyGap"){
                        val historyGap=historyGaps.firstOrNull{it.rowId==row.s("id")}
                        Row(Modifier.fillMaxWidth().heightIn(min=36.dp).testTag("conversation-history-gap"),
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            if(historyGap?.loading==true)AnimatedMark(20)
                            BilingualLabel(if(row.s("historyDirection")=="newer"){
                                if(historyGap?.loading==true)"Loading newer messages" else if(historyGap?.problem?.isNotBlank()==true)"Newer messages unavailable" else "Newer messages"
                            }else if(historyGap?.loading==true)"Loading earlier messages" else if(historyGap?.problem?.isNotBlank()==true)"Earlier messages unavailable" else "Earlier messages",color=Muted,fontSize=13.sp)
                        }
                    }else if(row.s("kind")=="commandRuns"&&commandGroup!=null) {
                        val expanded=commandGroup.any{it.s("id") in expandedRunIds}
                        CommandRunsHeader(commandGroup,expanded){
                            CodexConversations.readerInteraction(conversation.key,conversation.incarnation);follow=false
                            val members=commandGroup.map{it.s("id")}.toSet()
                            setRunExpansion(if(expanded)expandedRunIds-members else expandedRunIds+members)
                        }
                    } else if(isCommandRun(row))ActivityRow(row,fullyExpanded=true)
                    else {val sourceText=row.s("text");TranscriptRow(row){spokenRows[row.s("id")]=sourceText to it}}
                    if(row.s("speechThread").isNotBlank())TextButton({Pocket.open(row.s("speechThread"))}){BilingualLabel("Open source conversation")}
                    if(important)noteFor(row)?.let{note->FlowRow{
                        TextButton({val context="Regarding your answer:\n"+row.s("text")+"\n\n";val next=if(editor.text.isBlank())context else editor.text+"\n\n"+context;editor=TextFieldValue(next,TextRange(next.length));saveReplyDraft(draftKey,next);keyboardInput=true}){BilingualLabel("Reply")}
                        TextButton({
                            val sourceThread=Pocket.selected;val sourceDestination=Pocket.captureEnvironment()
                            val owns={Pocket.selected==sourceThread&&sourceDestination!=null&&Pocket.captureEnvironment()?.matches(sourceDestination)==true}
                            important=false;follow=false;CodexConversations.readerInteraction(conversation.key,conversation.incarnation)
                            scope.launch{
                                val found=locateTranscriptSource(owns,{PocketTranscript.loading},{PocketTranscript.earlier},{PocketTranscript.before},
                                    {PocketTranscript.rows.indexOfFirst{it.s("itemId",it.s("id"))==note.s("id")||it.s("id")==note.s("id")}},
                                    {CodexConversations.readFrom(conversation.key,conversation.incarnation,older=true)})
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
                if(conversationError.isNotBlank())item(key="ui:conversation-error",contentType="sync-status"){
                    if(Pocket.error.isBlank()&&replyFailure==null&&PocketTranscript.refreshDelayed)
                        WorkflowText(conversationError,color=Muted,fontSize=13.sp,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp).semantics{testTagsAsResourceId=true}.testTag("conversation-refresh-delayed"))
                    else ErrorBanner(conversationError,if(Pocket.error.isBlank()&&replyFailure!=null){{Pocket.dismissReplyFailure(failureSource,replyFailure.id)}}else null)
                }
                if(PocketTranscript.updating)item(key="ui:messages-sync",contentType="sync-status"){
                    Row(Modifier.fillMaxWidth().heightIn(min=32.dp).semantics{liveRegion=LiveRegionMode.Polite},
                        horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                        AnimatedMark(24);Spacer(Modifier.width(6.dp));BilingualLabel("Updating messages",fontSize=12.sp,color=Mint)
                    }
                }
                item(key="transcript-end-anchor",contentType="scroll-anchor"){Spacer(Modifier.height(1.dp))}
            }
            if(PocketTranscript.earlier&&PocketTranscript.loading&&!follow)
                Box(Modifier.align(Alignment.TopCenter).padding(top=10.dp).size(36.dp).background(Panel,CircleShape),contentAlignment=Alignment.Center){AnimatedMark(28)}
            if(conversationError.isBlank()&&runProjection.groups.values.any{group->group.any{it.s("id") in expandedRunIds}})
                FilledTonalButton({
                    CodexConversations.readerInteraction(conversation.key,conversation.incarnation);follow=false
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
        ConversationLiveCaption()
        InlineCaptureStatus()
        if(keyboardInput)DraftComposerScope{
        val draft=editor.text
        Box(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)){
            ConversationComposerRow{
                if(active)IconButton({CodexConversations.interrupt(conversation.key,conversation.incarnation)},modifier=Modifier.size(48.dp)){SymbolIcon("Stop","Stop agent",Modifier.size(32.dp),tint=Coral)}
                ConversationDraftField(editor,{if(editor.text!=it.text)saveReplyDraft(draftKey,it.text);editor=it},
                    modifier=Modifier.weight(1f).focusRequester(inputFocus).onPreviewKeyEvent{event->
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
                    },onSend={if(editor.text.isNotBlank()&&!Pocket.sending)submit("steer")},
                    placeholder={BilingualLabel(if(active)"Steer this task…" else "Message Codex…",centered=false,color=Muted,fontSize=15.sp)})
                val hasDraft=draft.isNotBlank()
                val enabled=!Pocket.sending&&hasDraft
                val label=if(active)"Steer running turn" else "Send message"
                ConversationSendButton(hasDraft,enabled,Pocket.sending,label,onQueue={if(hasDraft)submit("queue")}){submit("steer")}

            }
        }
        }
        if(!keyboardInput)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            if(active)IconButton({CodexConversations.interrupt(conversation.key,conversation.incarnation)},modifier=Modifier.size(48.dp)){SymbolIcon("Stop","Stop agent",Modifier.size(32.dp),tint=Coral)}
            ChatActionButton("Keyboard",Icons.Rounded.Keyboard,Modifier.weight(1f),{keyboardInput=!keyboardInput;if(!keyboardInput)keyboard?.hide()})
        }
        val pageEnvironment=Pocket.environmentId
        ConversationFooter{
            InlineSpeechVolume(Modifier.width(inlineVolumeControlWidth()))
            ConversationNotesControls(reviewCount,reviewResults.size,{reviewOpen=true},hasVisibleSpeech,visibleSpeech)
            ConversationActionsMenu(actionsOpen,{actionsOpen=it},statusIcon=status.icon,statusLabel=status.label,statusSpinning=status.spinning){
                TextButton({actionsOpen=false;keyboard?.hide();Pocket.closeTask()}){BilingualLabel("Back")}
                if(PocketSpeech.count>0)TextButton({PocketSpeech.clear();actionsOpen=false}){BilingualLabel("Clear speech from active session")}
                ConversationActivityControls()
            }
            key(important){ReaderPagingButtons(list,hasEarlier=(PocketTranscript.earlier||historyGaps.isNotEmpty())&&!important,
                onPrepare={up->
                    pagingViewport=true;pageHydrated=true
                    CodexConversations.readerInteraction(conversation.key,conversation.incarnation);follow=false;filterPassage=null
                    if(up){
                        historyGesture++;pageGesture=historyGesture
                        if(!important&&(gapNearViewport()||!list.canScrollBackward&&PocketTranscript.earlier)){
                            if(PocketTranscript.loading)snapshotFlow{PocketTranscript.loading}.first{!it}
                            val cursor=PocketTranscript.before
                            val gaps=PocketTranscript.historyGaps.map{it.id to it.cursor}
                            hydrateViewport(pageGesture)
                            pageHydrated=(!PocketTranscript.earlier&&PocketTranscript.historyGaps.isEmpty())||
                                PocketTranscript.before!=cursor||PocketTranscript.historyGaps.map{it.id to it.cursor}!=gaps
                        }
                    }
                },onSettled={up,completed->
                    pagingViewport=false
                    if(up&&completed&&pageHydrated)satisfiedHistoryGesture=pageGesture
                    if(!up&&completed&&!important&&!list.canScrollForward)follow=true
                },onError={error->if(Pocket.selected==failureSource&&Pocket.environmentId==pageEnvironment)Pocket.error=PocketNetwork.error(error)})}
        }
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
    if(renameOpen)AlertDialog(onDismissRequest={renameOpen=false},title={BilingualLabel("Rename conversation")},text={val renameFocus=remember{androidx.compose.ui.focus.FocusRequester()};LaunchedEffect(Unit){renameFocus.requestFocus()};OutlinedTextField(taskName,{taskName=it},singleLine=true,modifier=Modifier.focusRequester(renameFocus))},confirmButton={TextButton({Pocket.renameTask(taskName.text){renameOpen=false}},enabled=taskName.text.isNotBlank()){BilingualLabel("Save")}},dismissButton={TextButton({renameOpen=false}){BilingualLabel("Cancel")}})
    if(confirmArchive)AlertDialog(onDismissRequest={confirmArchive=false},title={BilingualLabel("Archive conversation?")},text={WorkflowText("This hides the conversation from recent tasks, pauses queued messages, and stops its notifications. Its history stays on the Codex host.")},confirmButton={TextButton({confirmArchive=false;Pocket.archiveTask()}){BilingualLabel("Archive")}},dismissButton={TextButton({confirmArchive=false}){BilingualLabel("Cancel")}})
    confirmRetry?.let{outgoing->AlertDialog(onDismissRequest={confirmRetry=null},title={BilingualLabel("Send this message again?")},text={WorkflowText("Codex may already have received this message. Retrying can send it twice. Check the conversation first.")},confirmButton={TextButton({Pocket.queuedReply(outgoing.s("id"),"retry",confirmUnknown=true){confirmRetry=null}},enabled=!Pocket.isReplyActionBusy(outgoing.s("id"))){BilingualLabel("Retry anyway")}},dismissButton={TextButton({confirmRetry=null}){BilingualLabel("Cancel")}})}
    editingQueue?.let{queued->AlertDialog(
        onDismissRequest={editingQueue=null},title={BilingualLabel("Edit message")},
        text={OutlinedTextField(queuedText,{queuedText=it},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=8)},
        confirmButton={TextButton({Pocket.queuedReply(queued.s("id"),"edit",queuedText){editingQueue=null}},enabled=queuedText.isNotBlank()&&!Pocket.isReplyActionBusy(queued.s("id"))){BilingualLabel("Save")}},
        dismissButton={TextButton({editingQueue=null}){BilingualLabel("Cancel")}}
    )}

}

@Composable fun TranscriptRow(row:JSONObject,speaker:String?=null,showSpeaker:Boolean=true,onDisplayedText:((String)->Unit)?=null){
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
        "user","message"->{val you=row.s("kind")=="user";ConversationMessageSurface(you){
            if(showSpeaker){
                if(speaker!=null&&!you)BilingualLabel(speaker,color=Mint,centered=false)
                else SymbolIcon(if(you)"User" else "Codex",if(you)"Your message" else "Codex message",Modifier.size(20.dp))
            }
            val sourceId="row:"+row.s("id")
            LaunchedEffect(row.s("text"),PocketImmersion.enabled,PocketImmersion.planVersion){PocketImmersion.offer(sourceId,row.s("text"),if(you)"user message" else "Codex response")}
            ImmersionSelection(row.s("text")){BilingualMessage(sourceId,row.s("text"),onDisplayedText=onDisplayedText)}

            if(row.optBoolean("truncated"))WorkflowText("Excerpt · full content remains on the workstation",fontSize=10.sp,color=Muted)
        }}
        else->ActivityRow(row)
    }
}
@Composable fun ActivityRow(row:JSONObject,fullyExpanded:Boolean=false){
    val sourceId="activity:"+row.s("id")
    val rawOperation=row.s("type") in listOf("commandExecution","roomOperation")
    LaunchedEffect(row.s("text"),PocketImmersion.enabled,PocketImmersion.planVersion){if(!rawOperation)PocketImmersion.offer(sourceId,row.s("text"),"public thought or activity")}
    var locallyExpanded by remember(row.s("id")){mutableStateOf(false)}
    val expanded=fullyExpanded||locallyExpanded
    val running=row.s("status")=="inProgress";val unknown=row.s("status")=="unknown";val failed=row.s("status") in listOf("failed","declined")||(!row.isNull("exitCode")&&row.optInt("exitCode")!=0)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel.copy(alpha=.6f)).then(if(fullyExpanded)Modifier else Modifier.clickable{locallyExpanded=!locallyExpanded}).padding(horizontal=11.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Box(Modifier.size(24.dp),contentAlignment=Alignment.Center){
                if(running)SymbolIcon(if(row.s("type")=="reasoning")"Psychology" else "Codex","Working",Modifier.size(24.dp),spinning=true)
                else SymbolIcon(if(failed||unknown)Icons.Rounded.ErrorOutline else Icons.Rounded.Check,if(unknown)"Outcome unconfirmed" else null,tint=if(failed)Coral else Muted,modifier=Modifier.size(18.dp))
            }
            BilingualLabel(row.s("title"),fontSize=12.sp,color=if(running)Mint else Muted,modifier=Modifier.weight(1f),centered=false)
            if(unknown)BilingualLabel("Unconfirmed",fontSize=10.sp,color=Muted)
            if(row.optLong("durationMs")>0)Text("${row.optLong("durationMs")/1000}s",fontSize=10.sp,color=Muted)
            if(!fullyExpanded)SymbolIcon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Collapse activity" else "Expand activity",tint=Muted,modifier=Modifier.size(16.dp))
        }
        if(row.s("text").isNotBlank()){
            val text=PocketImmersion.target(sourceId,row.s("text"))
            if(rawOperation){
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
    val operations=runs.any{it.s("type")=="roomOperation"}
    val agents=runs.map{it.s("agentId")}.filter{it.isNotBlank()}.distinct().size
    val working=runs.any{it.s("status")=="inProgress"}
    val unknown=runs.count{it.s("status")=="unknown"}
    val failed=runs.count{it.s("status") in listOf("failed","declined")||(!it.isNull("exitCode")&&it.optInt("exitCode")!=0)}
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(
        onClickLabel=if(expanded)"Collapse command runs" else "Expand command runs",onClick=onToggle
    ).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        SymbolIcon(if(working)"Codex" else if(failed>0)"Stop" else "Terminal",if(working)"Working" else if(failed>0)"Failed" else "Command runs",Modifier.size(20.dp),spinning=working)
        Text(runs.size.toString(),fontSize=13.sp,color=Muted)
        BilingualLabel(if(operations)"Operations" else "Runs",fontSize=13.sp,color=Muted)
        if(agents>1){Text(agents.toString(),fontSize=12.sp,color=Muted);BilingualLabel("Agents",fontSize=12.sp,color=Muted)}
        if(working)BilingualLabel("Working",fontSize=12.sp,color=Mint)
        if(failed>0){Text(failed.toString(),fontSize=12.sp,color=Coral);BilingualLabel("Failed",fontSize=12.sp,color=Coral)}
        if(unknown>0){Text(unknown.toString(),fontSize=12.sp,color=Muted);BilingualLabel("Unconfirmed",fontSize=12.sp,color=Muted)}
        Spacer(Modifier.weight(1f))
        SymbolIcon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Collapse command runs" else "Expand command runs",Modifier.size(20.dp),tint=Muted)
    }
}


// Keystrokes are observed in this restart scope, not by the transcript owner.
// The owner observes only empty/nonempty draft changes for its menu enablement.
@Composable private fun DraftComposerScope(content:@Composable ()->Unit){content()}
