package co.fallsoft.pocket

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Host OAuth URLs are transient display state, never saved preferences or drafts. */
@Composable internal fun NativeMcpAuthDialog(destination:EnvironmentRequest?,threadId:String?,name:String,onDismiss:()->Unit){
    val scope=rememberCoroutineScope()
    var alive by remember{mutableStateOf(true)}
    DisposableEffect(Unit){onDispose{alive=false}}
    val generation=remember{Pocket.nativeAuthGeneration}
    LaunchedEffect(Pocket.nativeAuthGeneration){if(generation!=Pocket.nativeAuthGeneration)onDismiss()}
    LaunchedEffect(Pocket.connected){if(!Pocket.connected)onDismiss()}
    fun current()=alive&&Pocket.connected&&generation==Pocket.nativeAuthGeneration&&destination!=null&&Pocket.captureEnvironment()?.matches(destination)==true&&Pocket.selected==threadId
    var view by remember{mutableStateOf<JSONObject?>(null)}
    var receipt by remember{mutableStateOf<JSONObject?>(null)}
    var busy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf("")}
    var revision by remember{mutableIntStateOf(0)}
    var dismissing by remember{mutableStateOf<String?>(null)}
    var attemptId by remember{mutableStateOf<String?>(null)}
    // Review state belongs to Android's UI thread even when a caller supplies
    // a non-main effect dispatcher. Transport still executes on Dispatchers.IO.
    LaunchedEffect(revision){withContext(Dispatchers.Main.immediate){
        if(!current()){busy=false;return@withContext}
        busy=true;view=null;receipt=null;message=""
        try{val result=Pocket.apiEnvironment(requireNotNull(destination),"/api/native/mcp-auth?name=${Uri.encode(name)}"+(threadId?.let{"&threadId=${Uri.encode(it)}"}?:""));if(current()){view=result;if(result.optBoolean("canStart"))attemptId=null}}
        catch(e:CancellationException){throw e}catch(_:Exception){if(current())message="Sign-in details unavailable. Inspect the original host before trying again."}finally{busy=false}
    }}
    AlertDialog(onDismissRequest=onDismiss,title={BilingualLabel("MCP sign-in")},text={Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        WorkflowText("${destination?.environment?.name?:"Original environment"} · $name")
        if(!current())WorkflowText("Reopen sign-in in the original environment and conversation.")
        if(busy)CircularProgressIndicator(Modifier.size(22.dp))
        if(message.isNotBlank())WorkflowText(message)
        view?.let{data->WorkflowText(data.s("notice"));WorkflowText("Observed authentication: ${data.s("observedAuthStatus")}. This observation is not an attempt completion receipt.")}
        val receipts=receipt?.let{listOf(it)}?:view?.optJSONArray("receipts")?.objects().orEmpty()
        receipts.forEach{r->
            WorkflowText("Original conversation: ${r.s("threadId").ifBlank{"host settings"}}")
            WorkflowText(r.s("notice"))
            if(r.optBoolean("canAcknowledge")){
                TextButton({if(current())dismissing=r.s("attemptId")},enabled=current()&&!busy){BilingualLabel("I inspected the host; review dismissal")}
                if(dismissing==r.s("attemptId")){
                    WorkflowText("This does not cancel the original flow. Starting again can duplicate an unfinished sign-in.")
                    OutlinedButton({if(current()&&!busy)scope.launch(Dispatchers.Main.immediate){
                        busy=true;view=null;receipt=null
                        try{val result=Pocket.apiEnvironment(requireNotNull(destination),"/api/native/configuration",JSONObject().put("section","mcp").put("action","oauthAcknowledge").put("threadId",r.opt("threadId")).put("name",name).put("attemptId",r.s("attemptId")).put("confirmInspected",true));if(current()){receipt=result;view=null;dismissing=null}}
                        catch(e:CancellationException){throw e}catch(_:Exception){if(current())message="Dismissal unconfirmed. Reload the original receipt."}finally{busy=false}
                    }},enabled=current()&&!busy){BilingualLabel("Dismiss inspected sign-in receipt")}
                }
            }
            if(current()&&r.s("authorizationUrl").isNotBlank()){
                WorkflowText("Copy this private sign-in URL to a browser on the original host. The callback may not work from this device.")
                SelectionContainer{Text(r.s("authorizationUrl"))}
            }
        }
        if(receipt==null&&view?.optBoolean("canStart")==true)OutlinedButton({
            if(current()&&!busy)scope.launch(Dispatchers.Main.immediate){
                busy=true;attemptId=attemptId?:UUID.randomUUID().toString()
                try{
                    val body=JSONObject().put("section","mcp").put("action","oauthStart").put("threadId",threadId).put("name",name).put("attemptId",attemptId).put("reviewToken",view?.s("reviewToken")).put("confirmHostAuthentication",true)
                    val result=Pocket.apiEnvironment(requireNotNull(destination),"/api/native/configuration",body)
                    if(current()){receipt=result;view=null}
                }catch(e:CancellationException){throw e}catch(_:Exception){if(current()){view=null;message="Sign-in outcome unconfirmed. Reload the original receipt; do not start another attempt."}}finally{busy=false}
            }
        },enabled=current()&&!busy){BilingualLabel("Start sign-in on original host")}
    }},confirmButton={TextButton(onDismiss){BilingualLabel("Close")}},dismissButton={TextButton({if(current()&&!busy){busy=true;revision++}},enabled=current()&&!busy){BilingualLabel("Reload sign-in receipt")}})
}
