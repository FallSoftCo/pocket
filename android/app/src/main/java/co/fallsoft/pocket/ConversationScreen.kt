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
fun turnTime(value:Long):String=if(value<=0)"Current turn" else SimpleDateFormat("MMM d · HH:mm",Locale.getDefault()).format(Date(if(value<100000000000L)value*1000 else value))

@Composable fun NewTaskScreen(){
    val cwdKey=Pocket.key("newTaskCwd");val projectKey=Pocket.key("lastProject");val promptKey=Pocket.key("newTaskPrompt")
    var cwd by remember(Pocket.local){mutableStateOf(Pocket.prefs.getString(cwdKey,Pocket.prefs.getString(projectKey,Pocket.tasks.firstOrNull()?.cwd?:Pocket.defaultCwd))?:Pocket.defaultCwd)}
    var prompt by remember(Pocket.local){mutableStateOf(Pocket.prefs.getString(promptKey,"")?:"")}
    Column(Modifier.fillMaxSize().imePadding()){
        Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically){IconButton({Pocket.newTask=false}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Back")};Text("New task",fontSize=23.sp,fontWeight=FontWeight.Medium)}
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("Start where the work lives.",fontSize=26.sp,lineHeight=32.sp)
            Text("Choose a folder on ${Pocket.host}. Choose permissions below before starting.",color=Muted,fontSize=14.sp,lineHeight=21.sp)
            OutlinedTextField(cwd,{cwd=it;Pocket.prefs.edit().putString(cwdKey,it).apply()},label={Text("Project folder")},singleLine=true,enabled=!Pocket.starting,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(15.dp),textStyle=LocalTextStyle.current.copy(fontSize=13.sp,fontFamily=FontFamily.Monospace))
            if(Pocket.projects.isNotEmpty()){
                Label("RECENT PROJECTS")
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel)){
                    Pocket.projects.take(5).forEach{p->Row(Modifier.fillMaxWidth().clickable(enabled=!Pocket.starting){cwd=p.s("cwd");Pocket.prefs.edit().putString(cwdKey,cwd).apply()}.padding(horizontal=14.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.FolderOpen,null,tint=if(cwd==p.s("cwd"))Mint else Muted,modifier=Modifier.size(18.dp));Column(Modifier.weight(1f).padding(start=10.dp)){Text(p.s("name"),fontSize=14.sp,color=if(cwd==p.s("cwd"))Mint else Paper);Text(p.s("cwd"),fontSize=10.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)};if(cwd==p.s("cwd"))Icon(Icons.Rounded.Check,null,tint=Mint,modifier=Modifier.size(16.dp))}}
                }
            }
            TaskPermissionsControl()
            OutlinedTextField(prompt,{prompt=it;Pocket.prefs.edit().putString(promptKey,it).apply()},label={Text("What would you like Codex to do?")},placeholder={Text("Describe the task, context and what done looks like.",fontSize=14.sp)},enabled=!Pocket.starting,modifier=Modifier.fillMaxWidth().heightIn(min=150.dp),minLines=4,maxLines=10,shape=RoundedCornerShape(18.dp))
            Text("You’ll see progress here and get a notification when the task needs you or finishes.",fontSize=12.sp,color=Muted,lineHeight=19.sp)
            if(Pocket.startStatus.isNotBlank())Text(Pocket.startStatus,fontSize=13.sp,color=Mint)
            ErrorBanner();Spacer(Modifier.height(10.dp))
        }
        Button({Pocket.startTask(cwd.trim(),prompt.trim())},enabled=cwd.isNotBlank()&&prompt.isNotBlank()&&!Pocket.starting,modifier=Modifier.fillMaxWidth().padding(20.dp).height(54.dp),shape=RoundedCornerShape(16.dp)){
            if(Pocket.starting)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=Ink)else Icon(Icons.Rounded.ArrowUpward,null)
            Spacer(Modifier.width(9.dp));Text(if(Pocket.starting)"Starting…" else if(Pocket.startStatus.isNotBlank())"Check task status" else "Start task")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class,ExperimentalMaterial3Api::class)
@Composable fun ConversationScreen(){
    val d=Pocket.detail;val t=d?.optJSONObject("thread");val rows=PocketTranscript.rows
    val active=t?.optJSONObject("status")?.s("type")=="active"||rows.lastOrNull{it.s("kind")=="turn"}?.s("status")=="inProgress"
    val list=rememberLazyListState();val scope=rememberCoroutineScope();val dragged by list.interactionSource.collectIsDraggedAsState()
    var follow by remember{mutableStateOf(true)}
    var actionsOpen by remember(Pocket.selected){mutableStateOf(false)}
    var settingsOpen by remember(Pocket.selected){mutableStateOf(false)}
    var renameOpen by remember(Pocket.selected){mutableStateOf(false)}
    var taskName by remember(Pocket.selected){mutableStateOf("")}
    var confirmArchive by remember(Pocket.selected){mutableStateOf(false)}
    var editingQueue by remember(Pocket.selected){mutableStateOf<JSONObject?>(null)}
    var queuedText by remember(Pocket.selected){mutableStateOf("")}
    var queueOpen by remember(Pocket.selected){mutableStateOf(false)}
    val draftKey=Pocket.key("draft:${Pocket.selected}");var editor by remember(Pocket.local,Pocket.selected){mutableStateOf((Pocket.prefs.getString(draftKey,"")?:"").let{TextFieldValue(it,TextRange(it.length))})}
    val draft=editor.text
    val submit:(String)->Unit={mode->val submitted=editor.text;follow=true;Pocket.reply(submitted,mode=mode){if(editor.text==submitted){editor=TextFieldValue("");Pocket.prefs.edit().remove(draftKey).apply()}}}
    val waiting=d?.optJSONArray("outgoing")?.objects()?.filter{it.s("state") in listOf("queued","held")&&it.s("mode")=="queue"}?:emptyList()
    LaunchedEffect(dragged){if(dragged)follow=false else if(!list.canScrollForward)follow=true}
    LaunchedEffect(PocketTranscript.revision){if(follow&&!dragged){delay(32);val count=list.layoutInfo.totalItemsCount;if(count>0)list.scrollToItem(count-1)}}
    Column(Modifier.fillMaxSize().imePadding()){
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton({Pocket.closeTask()}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Back",tint=Paper)}
            Column(Modifier.weight(1f)){Text(t?.s("name")?.ifBlank{t.s("preview").take(80)}?.ifBlank{"New task"}?:(if(Pocket.error.isNotBlank())"Couldn’t load task" else "Opening task…"),fontSize=15.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(listOf(project(t?.s("cwd")?:""),t?.s("model")?.takeIf{it.isNotBlank()},if(!Pocket.connected)"workstation unreachable" else if(!Pocket.codexOnline)"waiting for Codex" else if(active)"working" else "ready").filterNotNull().joinToString(" · "),fontSize=10.sp,color=if(active)Mint else Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}
            IconButton({Pocket.watch(!(d?.optBoolean("watched")?:false))}){Icon(if(d?.optBoolean("watched")==true)Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,"Follow task",tint=Mint,modifier=Modifier.size(21.dp))}
            VoiceLaunchButton(threadId=Pocket.selected,compact=true)
            Box{
                IconButton({actionsOpen=true}){Icon(Icons.Rounded.MoreVert,"Task actions",tint=Muted)}
                DropdownMenu(expanded=actionsOpen,onDismissRequest={actionsOpen=false}){
                    DropdownMenuItem(text={Text("Model, effort & mode")},onClick={actionsOpen=false;settingsOpen=true},leadingIcon={Icon(Icons.Rounded.Tune,null)})
                    DropdownMenuItem(text={Text("Rename conversation")},onClick={actionsOpen=false;taskName=t?.s("name").orEmpty();renameOpen=true},leadingIcon={Icon(Icons.Rounded.Edit,null)})
                    DropdownMenuItem(text={Text("Archive conversation…")},enabled=!active,onClick={actionsOpen=false;confirmArchive=true},leadingIcon={Icon(Icons.Rounded.Archive,null)})
                    DropdownMenuItem(text={Text("Queue message")},enabled=draft.isNotBlank()&&!Pocket.sending,onClick={actionsOpen=false;submit("queue")},leadingIcon={Icon(Icons.AutoMirrored.Rounded.PlaylistAdd,null)})
                    DropdownMenuItem(text={Text("Stop task",color=if(active)Coral else Muted)},enabled=active,onClick={actionsOpen=false;Pocket.interrupt()},leadingIcon={Icon(Icons.Rounded.StopCircle,null,tint=if(active)Coral else Muted)})
                }
            }
        }
        if(PocketTranscript.browsingEarlier)TextButton({follow=true;PocketTranscript.latest()},modifier=Modifier.fillMaxWidth()){Text("Viewing earlier activity · Back to latest")}
        HorizontalDivider(color=Line)
        Box(Modifier.weight(1f).fillMaxWidth()){
            LazyColumn(Modifier.fillMaxSize(),state=list,contentPadding=PaddingValues(horizontal=18.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                item(key="history"){if(PocketTranscript.earlier)TextButton({follow=false;scope.launch{PocketTranscript.load(true);list.scrollToItem((list.layoutInfo.totalItemsCount-1).coerceAtLeast(0))}},enabled=!PocketTranscript.loading,modifier=Modifier.fillMaxWidth()){Text(if(PocketTranscript.loading)"Loading…" else "Load earlier activity",color=Mint)}}
                if(d==null&&rows.isEmpty()&&PocketTranscript.loading)item{Box(Modifier.fillMaxWidth().padding(35.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(color=Mint,strokeWidth=2.dp)}}
                items(rows,key={it.s("id")}){row->TranscriptRow(row)}
                d?.optJSONArray("outgoing")?.objects()?.filter{it.s("state")!="accepted"&&it !in waiting}?.forEach{r->item(key="outgoing-${r.s("id")}"){Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(13.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Label("YOU · ${r.s("state")}",if(r.s("state") in listOf("failed","unknown"))Coral else Muted);Text(r.s("text"),fontSize=14.sp);if(r.s("state") in listOf("failed","unknown"))Text(ConnectionMessages.server(r.s("result")),fontSize=12.sp,color=Coral)}}}
                if(active)item(key="working"){Row(Modifier.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){CircularProgressIndicator(Modifier.size(13.dp),strokeWidth=1.5.dp,color=Mint);Text("Codex is working",fontSize=12.sp,color=Mint)}}
                if(!active&&rows.isEmpty()&&d!=null)item{Text("Your task is ready. Send a message to begin.",color=Muted,fontSize=14.sp)}
                item(key="errors"){ErrorBanner()}
            }
            if(!follow&&list.canScrollForward)FilledTonalButton({follow=true;scope.launch{list.animateScrollToItem((list.layoutInfo.totalItemsCount-1).coerceAtLeast(0))}},modifier=Modifier.align(Alignment.BottomEnd).padding(14.dp)){Icon(Icons.Rounded.ArrowDownward,null,Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text("Latest",fontSize=12.sp)}
        }
        Box(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp)){
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(25.dp)).background(Panel).padding(start=18.dp,end=5.dp,top=5.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically){
                BasicTextField(editor,{editor=it;Pocket.prefs.edit().putString(draftKey,it.text).apply()},
                    modifier=Modifier.weight(1f).heightIn(min=46.dp).padding(top=12.dp,bottom=12.dp,end=8.dp).onPreviewKeyEvent{event->
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
                    },maxLines=6,
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={if(editor.text.isNotBlank()&&!Pocket.sending)submit("steer")}),
                    textStyle=LocalTextStyle.current.copy(color=Paper,fontSize=15.sp,lineHeight=22.sp),cursorBrush=SolidColor(Mint),
                    decorationBox={inner->Box(contentAlignment=Alignment.CenterStart){if(draft.isEmpty())Text(if(active)"Steer this task…" else "Message Codex…",color=Muted,fontSize=15.sp);inner()}})
                if(waiting.isNotEmpty())TextButton({queueOpen=true},modifier=Modifier.height(48.dp),contentPadding=PaddingValues(horizontal=10.dp)){
                    Icon(if(waiting.any{it.s("state")=="held"})Icons.Rounded.Pause else Icons.AutoMirrored.Rounded.PlaylistAdd,"Open queued messages",tint=if(waiting.any{it.s("state")=="held"})Coral else Mint,modifier=Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp));Text("${waiting.size}",fontSize=12.sp,color=Paper)
                }
                val hasDraft=draft.isNotBlank();val stop=active&&!hasDraft&&!Pocket.sending
                val enabled=!Pocket.sending&&(hasDraft||stop)
                val label=if(stop)"Stop current turn" else if(active)"Steer running turn" else "Send message"
                Box(Modifier.size(48.dp).clip(CircleShape).background(if(hasDraft&&enabled)Mint else if(stop)Paper.copy(alpha=.08f) else Color.Transparent)
                    .combinedClickable(enabled=enabled,onClickLabel=label,onLongClickLabel=if(hasDraft)"Queue for next turn" else label,
                        onLongClick={if(hasDraft)submit("queue")else if(stop)Pocket.interrupt()},onClick={if(stop)Pocket.interrupt() else submit("steer")})
                    .semantics{role=androidx.compose.ui.semantics.Role.Button;contentDescription=label;if(hasDraft)customActions=listOf(CustomAccessibilityAction("Queue for next turn"){if(enabled){submit("queue");true}else false})},contentAlignment=Alignment.Center){
                    if(Pocket.sending)CircularProgressIndicator(Modifier.size(18.dp),color=Mint,strokeWidth=2.dp)
                    else Icon(if(stop)Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,null,tint=if(hasDraft&&enabled)Ink else if(stop)Paper else Muted.copy(alpha=.45f),modifier=Modifier.size(if(stop)19.dp else 23.dp))
                }
            }
        }
    }
    if(queueOpen)ModalBottomSheet(onDismissRequest={queueOpen=false},containerColor=Panel){
        Column(Modifier.fillMaxWidth().padding(horizontal=22.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Queued messages",fontSize=22.sp,fontWeight=FontWeight.SemiBold)
            Text(if(waiting.any{it.s("state")=="held"})"Paused until you resume." else "Sent in order when the current turn finishes.",color=Muted,fontSize=13.sp)
            if(waiting.any{it.s("state")=="held"})Button({Pocket.resumeQueue();queueOpen=false},modifier=Modifier.fillMaxWidth()){Text("Resume queue")}
            if(waiting.isEmpty())Text("The queue is empty.",color=Muted)
            LazyColumn(Modifier.heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                items(waiting,key={it.s("id")}){r->
                    var menu by remember(r.s("id")){mutableStateOf(false)}
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.copy(alpha=.3f)).clickable{editingQueue=r;queuedText=r.s("text")}.padding(start=14.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(r.s("text"),fontSize=14.sp,lineHeight=21.sp,modifier=Modifier.weight(1f),maxLines=3,overflow=TextOverflow.Ellipsis)
                        Box{IconButton({menu=true}){Icon(Icons.Rounded.MoreVert,"Queued message actions",tint=Muted)}
                            DropdownMenu(menu,{menu=false}){
                                DropdownMenuItem(text={Text("Edit")},onClick={menu=false;editingQueue=r;queuedText=r.s("text")})
                                DropdownMenuItem(text={Text(if(active)"Steer now" else "Send now")},onClick={menu=false;Pocket.queuedReply(r.s("id"),"send")})
                                DropdownMenuItem(text={Text("Remove",color=Coral)},onClick={menu=false;Pocket.queuedReply(r.s("id"),"remove")})
                            }
                        }
                    }
                }
            }
        }
    }
    if(settingsOpen)TurnSettingsDialog{settingsOpen=false}
    if(renameOpen)AlertDialog(onDismissRequest={renameOpen=false},title={Text("Rename conversation")},text={OutlinedTextField(taskName,{taskName=it},singleLine=true)},confirmButton={TextButton({Pocket.renameTask(taskName){renameOpen=false}},enabled=taskName.isNotBlank()){Text("Save")}},dismissButton={TextButton({renameOpen=false}){Text("Cancel")}})
    if(confirmArchive)AlertDialog(onDismissRequest={confirmArchive=false},title={Text("Archive conversation?")},text={Text("This hides the conversation from recent tasks, pauses queued messages, and stops its notifications. Its history stays on the Codex host.")},confirmButton={TextButton({confirmArchive=false;Pocket.archiveTask()}){Text("Archive")}},dismissButton={TextButton({confirmArchive=false}){Text("Cancel")}})
    editingQueue?.let{queued->AlertDialog(
        onDismissRequest={editingQueue=null},title={Text("Edit queued message")},
        text={OutlinedTextField(queuedText,{queuedText=it},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=8)},
        confirmButton={TextButton({Pocket.queuedReply(queued.s("id"),"edit",queuedText){editingQueue=null}},enabled=queuedText.isNotBlank()){Text("Save")}},
        dismissButton={TextButton({editingQueue=null}){Text("Cancel")}}
    )}

}

@Composable fun TranscriptRow(row:JSONObject){
    when(row.s("kind")){
        "turn"->Row(Modifier.fillMaxWidth().padding(top=15.dp,bottom=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){HorizontalDivider(Modifier.weight(1f),color=Line);Text(turnTime(row.optLong("startedAt")),fontSize=10.sp,color=Muted);HorizontalDivider(Modifier.weight(1f),color=Line)}
        "turnEnd"->{val seconds=row.optLong("durationMs")/1000;Text(when(row.s("status")){"failed"->"Stopped · ${row.s("text")}";"interrupted"->"Interrupted";else->if(seconds>0)"Completed · ${seconds}s" else "Completed"},color=if(row.s("status")=="failed")Coral else Muted,fontSize=10.sp,modifier=Modifier.padding(vertical=6.dp))}
        "request"->row.optJSONObject("request")?.let{RequestCard(it)}
        "attachments"->Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Label("SHARED FILES · ${row.s("title")}",Mint);row.optJSONArray("attachments")?.objects()?.forEach{Attachment(it)}}
        "user","message"->{val you=row.s("kind")=="user";Column(Modifier.fillMaxWidth().then(if(you)Modifier.clip(RoundedCornerShape(14.dp)).background(Panel).padding(14.dp)else Modifier.padding(vertical=5.dp)),verticalArrangement=Arrangement.spacedBy(7.dp)){
            Text(if(you)"› You" else row.s("title","Codex"),fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=if(you)Mint else Muted)
            SelectionContainer{RichText(row.s("text"))}
            if(row.optBoolean("truncated"))Text("Excerpt · full content remains on the workstation",fontSize=10.sp,color=Muted)
        }}
        else->ActivityRow(row)
    }
}
@Composable fun ActivityRow(row:JSONObject){
    var expanded by remember(row.s("id")){mutableStateOf(false)}
    val running=row.s("status")=="inProgress";val failed=row.s("status") in listOf("failed","declined")||(!row.isNull("exitCode")&&row.optInt("exitCode")!=0)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel.copy(alpha=.6f)).clickable{expanded=!expanded}.padding(horizontal=11.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            if(running)CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp,color=Mint)else Icon(if(failed)Icons.Rounded.ErrorOutline else Icons.Rounded.Check,null,tint=if(failed)Coral else Muted,modifier=Modifier.size(13.dp))
            Text(row.s("title"),fontSize=12.sp,color=if(running)Mint else Muted,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            if(row.optLong("durationMs")>0)Text("${row.optLong("durationMs")/1000}s",fontSize=10.sp,color=Muted)
            Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Collapse activity" else "Expand activity",tint=Muted,modifier=Modifier.size(16.dp))
        }
        if(row.s("text").isNotBlank())Text(row.s("text"),fontSize=11.sp,lineHeight=17.sp,color=Paper.copy(alpha=.82f),fontFamily=if(row.s("type")=="commandExecution")FontFamily.Monospace else FontFamily.Default,maxLines=if(expanded)Int.MAX_VALUE else 2,overflow=TextOverflow.Ellipsis)
        if(expanded&&row.s("detail").isNotBlank())SelectionContainer{Text(row.s("detail"),fontSize=11.sp,lineHeight=16.sp,fontFamily=FontFamily.Monospace,color=Muted,modifier=Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))}
        if(expanded&&row.optBoolean("truncated"))Text("Output excerpt · full content remains on the workstation",fontSize=10.sp,color=Muted)
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
    AlertDialog(onDismissRequest=onDismiss,title={Text("Next-turn controls")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Changes apply when the next turn starts. Steering keeps the running turn's settings.",fontSize=13.sp,color=Muted)
        if(loading)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
        if(problem.isNotBlank())Text(problem,color=Coral,fontSize=12.sp)
        if(Pocket.error.isNotBlank())Text(Pocket.error,color=Coral,fontSize=12.sp)
        Box{OutlinedButton({modelMenu=true},enabled=models.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text(selected?.s("name")?:model.ifBlank{"Model"});Spacer(Modifier.weight(1f));Icon(Icons.Rounded.ExpandMore,null)}
            DropdownMenu(modelMenu,{modelMenu=false}){models.forEach{m->DropdownMenuItem(text={Text(m.s("name"))},onClick={model=m.s("model");effort=m.s("defaultEffort");modelMenu=false})}}}
        Box{OutlinedButton({effortMenu=true},enabled=efforts.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text("Reasoning: ${effort.ifBlank{"default"}}");Spacer(Modifier.weight(1f));Icon(Icons.Rounded.ExpandMore,null)}
            DropdownMenu(effortMenu,{effortMenu=false}){efforts.forEach{e->DropdownMenuItem(text={Text(e.s("reasoningEffort"))},onClick={effort=e.s("reasoningEffort");effortMenu=false})}}}
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(if(plan)"Plan mode" else "Build mode");Text(if(plan)"Propose a plan before implementation" else "Carry out the requested work",fontSize=11.sp,color=Muted)};Switch(plan,{plan=it})}
    }},confirmButton={TextButton({Pocket.updateTurnSettings(JSONObject().put("model",model).put("effort",effort).put("mode",if(plan)"plan" else "default"),onDismiss)},enabled=selected!=null&&effort.isNotBlank()&&!loading){Text("Save")}},dismissButton={TextButton(onDismiss){Text("Cancel")}})
}
