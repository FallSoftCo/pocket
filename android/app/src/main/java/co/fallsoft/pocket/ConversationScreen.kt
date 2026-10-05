package co.fallsoft.pocket

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
    var cwd by remember(Pocket.local){mutableStateOf(Pocket.prefs.getString(cwdKey,Pocket.prefs.getString(projectKey,Pocket.tasks.firstOrNull()?.cwd?:Pocket.defaultCwd))?:Pocket.defaultCwd)}
    var prompt by remember(Pocket.local){mutableStateOf(Pocket.prefs.getString(promptKey,"")?:"")}
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

@OptIn(ExperimentalFoundationApi::class,ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable fun ConversationScreen(){
    val d=Pocket.detail;val t=d?.optJSONObject("thread");val rows=PocketTranscript.rows
    val active=Pocket.tasks.firstOrNull{it.id==Pocket.selected}?.status?.let{it=="active"}
        ?:(t?.optJSONObject("status")?.s("type")=="active"||rows.lastOrNull{it.s("kind")=="turn"}?.s("status")=="inProgress")
    val list=rememberLazyListState();val scope=rememberCoroutineScope();val dragged by list.interactionSource.collectIsDraggedAsState()
    var follow by remember{mutableStateOf(true)}
    var important by remember(Pocket.local,Pocket.selected){mutableStateOf(false)}
    var confirmRetry by remember(Pocket.selected){mutableStateOf<JSONObject?>(null)}
    val notes=d?.optJSONArray("notes")?.objects().orEmpty()
    fun noteFor(row:JSONObject)=notes.firstOrNull{it.s("id")==row.s("itemId",row.s("id"))||it.s("id")==row.s("id")}
    val latestAnswer=rows.lastOrNull{it.s("kind")=="message"}
    val orphanRows=notes.filter{note->rows.none{row->row.s("itemId",row.s("id"))==note.s("id")||row.s("id")==note.s("id")}}.map{note->JSONObject().put("id","retained:"+note.s("id")).put("itemId",note.s("id")).put("kind","message").put("text",note.s("text")).put("retainedExcerpt",true)}
    val filteredRows=if(!important)rows else orphanRows+rows.filter{importantConversationKind(it.s("kind"),it.s("type"),it.s("status"),noteFor(it)!=null,it.s("phase"),it===latestAnswer)}
    val queuedSpeech=PocketSpeech.queue.current?.takeIf{PocketSpeech.displayedOwner==null&&it.text.isNotBlank()}
    val shownRows=if(queuedSpeech!=null&&filteredRows.none{canMergeConversationContext(Pocket.selected,PocketNotificationTitles.threadForId(queuedSpeech.id))&&sameConversationContext(it.s("text"),queuedSpeech.text)})filteredRows+JSONObject().put("id","speech:${queuedSpeech.id}").put("kind","message").put("text",queuedSpeech.text).put("speechThread",PocketNotificationTitles.threadForId(queuedSpeech.id)).put("speechTitle",queuedSpeech.title) else filteredRows
    var removingNotes by remember(Pocket.local,Pocket.selected){mutableStateOf(setOf<String>())}
    val spokenRows=remember(Pocket.local,Pocket.selected){mutableStateMapOf<String,String>()}
    var actionsOpen by remember(Pocket.selected){mutableStateOf(false)}
    var settingsOpen by remember(Pocket.selected){mutableStateOf(false)}
    var renameOpen by remember(Pocket.selected){mutableStateOf(false)}
    var taskName by remember(Pocket.selected){mutableStateOf("")}
    var confirmArchive by remember(Pocket.selected){mutableStateOf(false)}
    var editingQueue by remember(Pocket.selected){mutableStateOf<JSONObject?>(null)}
    var queuedText by remember(Pocket.selected){mutableStateOf("")}
    var queueOpen by remember(Pocket.selected){mutableStateOf(false)}
    val draftKey=Pocket.key("draft:${Pocket.selected}");var editor by remember(Pocket.local,Pocket.selected){mutableStateOf((Pocket.prefs.getString(draftKey,"")?:"").let{TextFieldValue(it,TextRange(it.length))})}
    var keyboardInput by remember(Pocket.selected){mutableStateOf(Pocket.openWithKeyboard)}
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val inputFocus=remember{androidx.compose.ui.focus.FocusRequester()}
    LaunchedEffect(keyboardInput){if(keyboardInput){inputFocus.requestFocus();keyboard?.show()}}
    ConversationBack{Pocket.closeTask()}
    val draft=editor.text
    val submit:(String)->Unit={mode->val submitted=editor.text;follow=true;Pocket.reply(submitted,mode=mode){if(editor.text==submitted){editor=TextFieldValue("");Pocket.prefs.edit().remove(draftKey).apply()}}}
    val waiting=d?.optJSONArray("outgoing")?.objects()?.filter{it.s("state") in listOf("queued","held")&&it.s("mode")=="queue"}?:emptyList()
    LaunchedEffect(dragged){if(dragged)follow=false else if(!list.canScrollForward)follow=true}
    LaunchedEffect(PocketTranscript.revision){if(follow&&!dragged){
        delay(32)
        val count=list.layoutInfo.totalItemsCount
        // Keep the question heading and its one-tap Skip visible even when a
        // multi-question form is taller than the conversation viewport.
        val request=shownRows.indexOfLast{it.s("kind")=="request"}
        if(count>0)list.scrollToItem(if(request>=0)(request+1).coerceAtMost(count-1) else count-1)
    }}
    val ConversationActivityControls:@Composable ColumnScope.()->Unit={
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
            LazyColumn(Modifier.fillMaxSize(),state=list,contentPadding=PaddingValues(horizontal=18.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                item(key="history"){if(PocketTranscript.earlier)TextButton({follow=false;scope.launch{PocketTranscript.load(true);list.scrollToItem((list.layoutInfo.totalItemsCount-1).coerceAtLeast(0))}},enabled=!PocketTranscript.loading,modifier=Modifier.fillMaxWidth()){BilingualLabel(if(PocketTranscript.loading)"Loading…" else "Load earlier activity",color=Mint)}}
                if(d==null&&rows.isEmpty()&&PocketTranscript.loading)item{Box(Modifier.fillMaxWidth().padding(vertical=40.dp),contentAlignment=Alignment.Center){AnimatedMark(112)}}
                items(shownRows,key={it.s("id")}){row->
                    if(important&&noteFor(row)!=null&&!row.optBoolean("retainedExcerpt"))BilingualLabel("Saved answer",color=Mint,fontSize=12.sp)
                    if(row.optBoolean("retainedExcerpt"))BilingualLabel("Retained passage · source outside loaded history",color=Muted,fontSize=12.sp)
                    if(row.s("speechTitle").isNotBlank())BilingualLabel("Spoken update · "+row.s("speechTitle"),color=Muted,fontSize=12.sp)
                    TranscriptRow(row){spokenRows[row.s("id")]=it}
                    if(row.s("speechThread").isNotBlank())TextButton({Pocket.open(row.s("speechThread"))}){BilingualLabel("Open source conversation")}
                    if(important)noteFor(row)?.let{note->FlowRow{
                        TextButton({val context="Regarding your answer:\n"+row.s("text")+"\n\n";val next=if(editor.text.isBlank())context else editor.text+"\n\n"+context;editor=TextFieldValue(next,TextRange(next.length));Pocket.prefs.edit().putString(draftKey,next).apply();keyboardInput=true}){BilingualLabel("Reply")}
                        TextButton({val sourceThread=Pocket.selected;val sourceProfile=Pocket.local;val sourceEndpoint=Pocket.base;important=false;follow=false;scope.launch{var found=PocketTranscript.rows.indexOfFirst{it.s("itemId",it.s("id"))==note.s("id")||it.s("id")==note.s("id")};var pages=0;while(found<0&&PocketTranscript.earlier&&pages++<12&&Pocket.selected==sourceThread&&Pocket.local==sourceProfile&&Pocket.base==sourceEndpoint){PocketTranscript.load(true);found=PocketTranscript.rows.indexOfFirst{it.s("itemId",it.s("id"))==note.s("id")||it.s("id")==note.s("id")}};delay(32);if(Pocket.selected==sourceThread&&Pocket.local==sourceProfile&&Pocket.base==sourceEndpoint&&found>=0)list.scrollToItem((found+1).coerceAtMost((list.layoutInfo.totalItemsCount-1).coerceAtLeast(0)))else if(Pocket.selected==sourceThread&&Pocket.local==sourceProfile&&Pocket.base==sourceEndpoint)Pocket.error="The original message is not available in loaded history."}}){BilingualLabel("Surrounding conversation")}
                        TextButton({val thread=Pocket.selected;val profile=Pocket.local;val endpoint=Pocket.base;val credential=Pocket.token;val id=note.s("id");removingNotes=removingNotes+id;scope.launch{try{val response=Pocket.apiFor(profile,"/api/threads/$thread/notes/remove",JSONObject().put("id",id));require(response.optJSONArray("notes")!=null){"Removal was not acknowledged. Try again."};if(Pocket.selected==thread&&Pocket.local==profile&&Pocket.base==endpoint&&Pocket.token==credential)Pocket.detail=Pocket.detail?.let{JSONObject(it.toString()).put("notes",response.optJSONArray("notes"))}}catch(e:Exception){if(Pocket.selected==thread&&Pocket.local==profile)Pocket.error=PocketNetwork.error(e)}finally{removingNotes=removingNotes-id}}},enabled=note.s("id") !in removingNotes){BilingualLabel("Unsave",color=Muted)}
                    }}
                }
                if(important&&shownRows.isEmpty())item{BilingualLabel("No important updates yet",color=Muted)}
                d?.optJSONArray("outgoing")?.objects()?.filter{it.s("state") !in listOf("accepted","cancelled")&&it !in waiting}?.forEach{r->item(key="outgoing-${r.s("id")}"){Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(6.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                    val recoverable=r.s("state") in listOf("failed","unknown");val busy="${Pocket.local}:${Pocket.selected}:${r.s("id")}" in Pocket.replyActionBusy
                    Label("YOU · ${r.s("state")}",if(recoverable)Coral else Muted);ImmersionText("outgoing:"+r.s("id"),r.s("text"),rescue=false,fontSize=14.sp)
                    if(recoverable){WorkflowText(ConnectionMessages.server(r.s("result")),fontSize=12.sp,color=Coral)
                        Row{TextButton({if(r.s("state")=="unknown")confirmRetry=r else Pocket.queuedReply(r.s("id"),"retry")},enabled=!busy){BilingualLabel("Retry")}
                            TextButton({editingQueue=r;queuedText=r.s("text")},enabled=!busy){BilingualLabel("Edit")}
                            TextButton({Pocket.queuedReply(r.s("id"),"remove")},enabled=!busy){BilingualLabel("Remove",color=Coral)}}
                    }
                }}}


                item(key="errors"){ErrorBanner()}
            }
            if(!follow&&list.canScrollForward)FilledTonalButton({follow=true;scope.launch{list.animateScrollToItem((list.layoutInfo.totalItemsCount-1).coerceAtLeast(0))}},modifier=Modifier.align(Alignment.BottomEnd).padding(14.dp)){SymbolIcon(Icons.Rounded.ArrowDownward,null,Modifier.size(16.dp));Spacer(Modifier.width(5.dp));BilingualLabel("Latest",fontSize=12.sp)}
        }

        Box{
        val visibleKeys=list.layoutInfo.visibleItemsInfo.map{it.key}.toSet()
        val speechText=shownRows.filter{it.s("id") in visibleKeys&&it.s("kind")=="message"}.map{row->spokenRows[row.s("id")]?:PocketImmersion.display("row:"+row.s("id"),row.s("text"))}.filter{it.isNotBlank()}.joinToString("\n\n")
        ConversationNotesCard(important=important,onFilter={follow=false;important=!important;scope.launch{list.scrollToItem(0)}},onControls={actionsOpen=true},speechText=speechText)
        DropdownMenu(actionsOpen,{actionsOpen=false},modifier=Modifier.width(300.dp).heightIn(max=420.dp)){ConversationActivityControls()}
        }
        if(keyboardInput)Box(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)){
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Panel).padding(start=18.dp,end=5.dp,top=5.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically){
                VoiceLaunchButton(modifier=Modifier.size(64.dp),threadId=Pocket.selected,compact=true)
                BasicTextField(editor,{editor=it;Pocket.prefs.edit().putString(draftKey,it.text).apply()},
                    modifier=Modifier.weight(1f).focusRequester(inputFocus).heightIn(min=46.dp).padding(top=12.dp,bottom=12.dp,end=8.dp).onPreviewKeyEvent{event->
                        if(event.key==Key.Enter||event.key==Key.NumPadEnter){
                            if(event.type==KeyEventType.KeyDown){
                                if(event.isShiftPressed){
                                    val position=editor.selection.min
                                    editor=TextFieldValue(editor.text.replaceRange(position,editor.selection.max,"\n"),TextRange(position+1))
                                    Pocket.prefs.edit().putString(draftKey,editor.text).apply()
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
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            ChatActionButton("Back",Icons.AutoMirrored.Rounded.ArrowBack,Modifier.weight(1f),{keyboard?.hide();Pocket.closeTask()})
            UsageDock(Modifier.width(usageDockWidth()),conversationOnly=true)
            if(active)ChatActionButton("Stop",Icons.Rounded.Stop,Modifier.weight(1f),{Pocket.interrupt()},recording=true)
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
    confirmRetry?.let{outgoing->AlertDialog(onDismissRequest={confirmRetry=null},title={BilingualLabel("Send this message again?")},text={WorkflowText("Codex may already have received this message. Retrying can send it twice. Check the conversation first.")},confirmButton={TextButton({Pocket.queuedReply(outgoing.s("id"),"retry",confirmUnknown=true){confirmRetry=null}},enabled="${Pocket.local}:${Pocket.selected}:${outgoing.s("id")}" !in Pocket.replyActionBusy){BilingualLabel("Retry anyway")}},dismissButton={TextButton({confirmRetry=null}){BilingualLabel("Cancel")}})}
    editingQueue?.let{queued->AlertDialog(
        onDismissRequest={editingQueue=null},title={BilingualLabel("Edit message")},
        text={OutlinedTextField(queuedText,{queuedText=it},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=8)},
        confirmButton={TextButton({Pocket.queuedReply(queued.s("id"),"edit",queuedText){editingQueue=null}},enabled=queuedText.isNotBlank()&&"${Pocket.local}:${Pocket.selected}:${queued.s("id")}" !in Pocket.replyActionBusy){BilingualLabel("Save")}},
        dismissButton={TextButton({editingQueue=null}){BilingualLabel("Cancel")}}
    )}

}

@Composable fun TranscriptRow(row:JSONObject,onDisplayedText:((String)->Unit)?=null){
    when(row.s("kind")){
        "turn"->Row(Modifier.fillMaxWidth().padding(top=15.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){HorizontalDivider(Modifier.weight(1f),color=Line);Text(turnTime(row.optLong("startedAt")),fontSize=10.sp,color=Muted);HorizontalDivider(Modifier.weight(1f),color=Line)}
        "turnEnd"->{val seconds=row.optLong("durationMs")/1000;WorkflowText(when(row.s("status")){"failed"->"Stopped · ${row.s("text")}";"interrupted"->"Interrupted";else->if(seconds>0)"Completed · ${seconds}s" else "Completed"},color=if(row.s("status")=="failed")Coral else Muted,fontSize=10.sp,modifier=Modifier.padding(vertical=6.dp))}
        "request"->row.optJSONObject("request")?.let{RequestCard(it)}
        "attachments"->Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Label("SHARED FILES · ${row.s("title")}",Mint);row.optJSONArray("attachments")?.objects()?.forEach{Attachment(it)}}
        "user","message"->{val you=row.s("kind")=="user";Column(Modifier.fillMaxWidth().then(if(you)Modifier.clip(RoundedCornerShape(6.dp)).background(Panel).padding(14.dp)else Modifier.padding(vertical=5.dp)),verticalArrangement=Arrangement.spacedBy(7.dp)){
            SymbolIcon(if(you)"User" else "Codex",if(you)"Your message" else "Codex message",Modifier.size(20.dp))
            val sourceId="row:"+row.s("id")
            LaunchedEffect(row.s("text"),PocketImmersion.enabled){PocketImmersion.offer(sourceId,row.s("text"),if(you)"user message" else "Codex response")}
            SelectionContainer{BilingualMessage(sourceId,row.s("text"),onDisplayedText=onDisplayedText)}

            if(row.optBoolean("truncated"))WorkflowText("Excerpt · full content remains on the workstation",fontSize=10.sp,color=Muted)
        }}
        else->ActivityRow(row)
    }
}
@Composable fun ActivityRow(row:JSONObject){
    val sourceId="activity:"+row.s("id")
    LaunchedEffect(row.s("text"),PocketImmersion.enabled){if(row.s("type")!="commandExecution")PocketImmersion.offer(sourceId,row.s("text"),"public thought or activity")}
    var expanded by remember(row.s("id")){mutableStateOf(false)}
    val running=row.s("status")=="inProgress";val failed=row.s("status") in listOf("failed","declined")||(!row.isNull("exitCode")&&row.optInt("exitCode")!=0)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel.copy(alpha=.6f)).clickable{expanded=!expanded}.padding(horizontal=11.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Box(Modifier.size(24.dp),contentAlignment=Alignment.Center){
                if(running)SymbolIcon(if(row.s("type")=="reasoning")"Psychology" else "Codex","Working",Modifier.size(24.dp),spinning=true)
                else SymbolIcon(if(failed)Icons.Rounded.ErrorOutline else Icons.Rounded.Check,null,tint=if(failed)Coral else Muted,modifier=Modifier.size(18.dp))
            }
            BilingualLabel(row.s("title"),fontSize=12.sp,color=if(running)Mint else Muted,modifier=Modifier.weight(1f),centered=false)
            if(row.optLong("durationMs")>0)Text("${row.optLong("durationMs")/1000}s",fontSize=10.sp,color=Muted)
            SymbolIcon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Collapse activity" else "Expand activity",tint=Muted,modifier=Modifier.size(16.dp))
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
