package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

internal fun conversationUsageLabel(value:JSONObject?):String {
    if(value==null)return "Context usage has not been observed on this connection."
    val used=value.optJSONObject("last")?.optLong("totalTokens")?:return "Context usage unavailable."
    val window=value.optLong("modelContextWindow")
    return "$used tokens in last context"+(if(window>0)" / $window token window (${(used*100/window).coerceAtMost(100)}%)"else "")+". Total: ${value.optJSONObject("total")?.optLong("totalTokens")?:0} tokens."
}

/** All async reads/actions retain the original pairing and thread. Editing
 * inserts into the existing draft; only the ordinary Send control submits it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ConversationToolsSheet(onClose:()->Unit,onInsert:(String)->Unit){
    val thread=Pocket.selected?:return
    val destination=remember(thread,Pocket.environmentId){Pocket.captureEnvironment()}?:return
    val scope=rememberCoroutineScope()
    val root="/api/threads/"+android.net.Uri.encode(thread)
    val key=remember(destination,thread){Pocket.key("conversationOperation:$thread")}
    var data by remember(destination,thread){mutableStateOf<JSONObject?>(null)}
    var error by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    var boundary by remember{mutableStateOf("")};var query by remember{mutableStateOf("")}
    var files by remember{mutableStateOf<List<JSONObject>>(emptyList())};var diff by remember{mutableStateOf("")}
    var cwd by remember{mutableStateOf("")};var reviewTarget by remember{mutableStateOf("")}
    var confirmation by remember{mutableStateOf<JSONObject?>(null)}
    var file by remember{mutableStateOf<JSONObject?>(null)};var first by remember{mutableStateOf("1")};var last by remember{mutableStateOf("40")};var selection by remember{mutableStateOf<JSONObject?>(null)}
    fun current()=Pocket.selected==thread&&Pocket.captureEnvironment()?.matches(destination)==true
    suspend fun request(path:String,body:JSONObject?=null)=Pocket.apiEnvironment(destination,root+path,body)
    suspend fun refresh(){val response=request("/conversation-tools");if(current()){data=response;cwd=response.s("cwd");if(boundary.isBlank())boundary=response.optJSONArray("turns")?.objects()?.firstOrNull{it.s("status")!="inProgress"}?.s("id").orEmpty()}}
    fun run(action:suspend ()->Unit){if(busy)return;busy=true;scope.launch{try{error="";action()}catch(e:Exception){if(current())error=e.message?:"Operation unavailable"}finally{busy=false}}}
    suspend fun accepted(receipt:JSONObject){if(!current())return
        val state=receipt.s("state");error=receipt.s("error").ifBlank{state}
        if(state!="accepted")return
        val appliedKey=Pocket.key("conversationOperationApplied:"+receipt.s("id"))
        val result=receipt.optJSONObject("result")?:JSONObject()
        if(!Pocket.prefs.getBoolean(appliedKey,false)){
            Pocket.prefs.edit().putBoolean(appliedKey,true).apply()
            if(receipt.s("kind")=="fork"){receipt.optJSONObject("thread")?.s("id")?.takeIf{it.isNotBlank()}?.let{onClose();Pocket.refresh();Pocket.open(it)}}
            if(receipt.s("kind")=="revert"){result.optJSONObject("historyHead")?.let{CodexSessionRepository.historyReplaced(thread,it,destination)};onInsert(result.s("draft"));Pocket.refreshDetail()}
            if(receipt.s("kind")=="changeDirectory")Pocket.refreshDetail()
        }
    }
    fun prepare(kind:String,extra:JSONObject=JSONObject()){extra.put("kind",kind);if(kind=="fork"||kind=="revert")extra.put("turnId",boundary).put("expectedLastTurnId",data?.optJSONArray("turns")?.optJSONObject(0)?.s("id"));if(kind=="revert")extra.put("confirmContextOnly",true);confirmation=extra}
    LaunchedEffect(destination,thread){run{refresh()}}
    ModalBottomSheet(onDismissRequest=onClose){
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("Conversation & workspace",style=MaterialTheme.typography.titleLarge)
            Text("Working directory: ${data?.s("cwd").orEmpty()}")
            Text(conversationUsageLabel(data?.optJSONObject("usage")))
            if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            val features=data?.optJSONObject("features");val active=data?.optBoolean("active")==true
            fun available(kind:String)=features?.optJSONObject(kind)?.optBoolean("available")==true
            Button({prepare("compact")},enabled=!busy&&!active&&available("compact")){Text("Compact context")}
            Text("Native code review starts model work in this conversation. Saved notes are separate.")
            Button({prepare("review")},enabled=!busy&&!active&&available("review")){Text("Review working changes")}
            OutlinedTextField(reviewTarget,{reviewTarget=it},label={Text("Base branch or commit SHA")},modifier=Modifier.fillMaxWidth())
            Row{TextButton({prepare("review",JSONObject().put("target",JSONObject().put("type","baseBranch").put("branch",reviewTarget)))},enabled=!busy&&!active&&available("review")&&reviewTarget.isNotBlank()){Text("Review branch")};TextButton({prepare("review",JSONObject().put("target",JSONObject().put("type","commit").put("sha",reviewTarget)))},enabled=!busy&&!active&&available("review")&&reviewTarget.isNotBlank()){Text("Review commit")}}
            HorizontalDivider();Text("Branch or edit conversation",style=MaterialTheme.typography.titleMedium)
            Text(data?.s("warning").orEmpty())
            data?.optJSONArray("turns")?.objects()?.forEach{turn->
                Row(Modifier.fillMaxWidth()){RadioButton(selected=boundary==turn.s("id"),onClick={boundary=turn.s("id")},enabled=turn.s("status")!="inProgress");Text(turn.s("status")+" · "+turn.s("userText").ifBlank{turn.s("id")}.take(140),modifier=Modifier.weight(1f))}
            }
            Button({prepare("fork")},enabled=!busy&&boundary.isNotBlank()&&available("fork")){Text("Branch after selected turn")}
            Button({prepare("revert")},enabled=!busy&&!active&&boundary.isNotBlank()&&available("revert")){Text("Revert before turn & edit")}
            if(!available("revert"))Text(features?.optJSONObject("revert")?.s("reason").orEmpty())
            Text("Branches share working files. Revert inserts the selected message into your draft; Send remains a separate action.")
            HorizontalDivider();Text("Host files",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(query,{query=it},label={Text("Search files in this folder")},modifier=Modifier.fillMaxWidth())
            Button({run{val result=request("/workspace-files?q="+android.net.Uri.encode(query));if(current())files=result.optJSONArray("files")?.objects().orEmpty()}},enabled=!busy&&query.isNotBlank()){Text("Search files")}
            files.forEach{f->Text(f.s("relativePath"));Row{TextButton({if(current()){onInsert("@"+f.s("path")+" ");onClose()}}){Text("Mention")};if(f.s("type")=="file")TextButton({file=f;selection=null}){Text("Select lines")}}}
            file?.let{f->Text(f.s("path"));Row{OutlinedTextField(first,{first=it},label={Text("First line")},modifier=Modifier.weight(1f));OutlinedTextField(last,{last=it},label={Text("Last line")},modifier=Modifier.weight(1f))};TextButton({run{val result=request("/workspace-selection?path="+android.net.Uri.encode(f.s("path"))+"&start="+android.net.Uri.encode(first)+"&end="+android.net.Uri.encode(last));if(current())selection=result}},enabled=!busy){Text("Preview selection")}}
            selection?.let{s->SelectionContainer{Text(s.s("text"))};TextButton({if(current()){onInsert("\nHost file ${s.s("path")}:${s.optInt("start")}-${s.optInt("end")}\n\n${s.s("text")}\n");onClose()}}){Text("Insert selection into draft")}}
            HorizontalDivider();Button({run{val result=request("/workspace-diff");if(current())diff=result.s("status")+"\n"+result.s("diff").ifBlank{"No changes."}+if(result.optInt("omittedUntracked")>0)"\n${result.optInt("omittedUntracked")} untracked files omitted by size/count bounds."else ""}},enabled=!busy){Text("Show repository diff")}
            if(diff.isNotBlank())SelectionContainer{Text(diff)}
            OutlinedTextField(cwd,{cwd=it},label={Text("Working directory for subsequent turns")},modifier=Modifier.fillMaxWidth())
            Button({prepare("changeDirectory",JSONObject().put("cwd",cwd))},enabled=!busy&&!active&&available("changeDirectory")){Text("Use this folder")}
            Text(data?.optJSONObject("worktree")?.s("reason").orEmpty())
            HorizontalDivider();Text("Operation receipts",style=MaterialTheme.typography.titleMedium)
            data?.optJSONArray("operations")?.objects()?.forEach{receipt->
                Text(receipt.s("kind")+": "+receipt.s("state")+" "+receipt.s("error"))
                if(receipt.s("state") in listOf("dispatching","unknown"))TextButton({run{accepted(request("/conversation-operations/"+receipt.s("id")));refresh()}},enabled=!busy){Text("Check receipt")}
                if(receipt.s("state")=="unknown")TextButton({confirmation=JSONObject().put("kind","acknowledge").put("id",receipt.s("id"))},enabled=!busy){Text("Acknowledge after inspection")}
                if(receipt.s("kind")=="revert"&&receipt.s("state")=="accepted")TextButton({if(current())onInsert(receipt.optJSONObject("result")?.s("draft").orEmpty())}){Text("Restore original message to draft")}
                if(receipt.s("kind")=="fork"&&receipt.s("state")=="accepted")TextButton({receipt.optJSONObject("thread")?.s("id")?.let{onClose();Pocket.open(it)}}){Text("Open branch")}
            }
            Pocket.prefs.getString(key,null)?.let{saved->TextButton({run{val id=JSONObject(saved).s("id");accepted(request("/conversation-operations/$id"));refresh()}},enabled=!busy){Text("Check saved operation")}}
            TextButton({run{refresh()}},enabled=!busy){Text("Refresh context and receipts")}
            TextButton(onClose){Text("Close")};Spacer(Modifier.height(20.dp))
        }
    }
    confirmation?.let{operation->
        val kind=operation.s("kind");val text=when(kind){"revert"->data?.s("warning").orEmpty()+" Remove the selected turn and every later turn from conversation context?";"review"->"Start native code review? This uses model capacity.";"compact"->"Ask Codex to compact this conversation context?";"changeDirectory"->"Use this folder for subsequent turns? Existing files are not moved.";"acknowledge"->"Have you inspected the conversation and workspace for the uncertain effect? This unlocks subsequent input without retrying the operation.";else->"Create a new conversation through this turn? Working files are shared."}
        AlertDialog(onDismissRequest={confirmation=null},title={Text("Confirm conversation action")},text={Text(text)},confirmButton={TextButton({confirmation=null;run{
            if(kind=="acknowledge")request("/conversation-operations/"+operation.s("id")+"/acknowledge",JSONObject().put("inspected",true))
            else {operation.put("id",UUID.randomUUID().toString());Pocket.prefs.edit().putString(key,operation.toString()).commit();try{accepted(request("/conversation-operations",operation))}catch(e:Exception){error="Delivery is uncertain. Check the saved operation; it will not be sent again. "+e.message}}
            refresh()
        }}){Text("Confirm")}},dismissButton={TextButton({confirmation=null}){Text("Cancel")}})
    }
}
