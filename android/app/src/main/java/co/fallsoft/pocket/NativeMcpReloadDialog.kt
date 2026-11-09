package co.fallsoft.pocket

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** Explicit host-wide action. Receipt inspection never repeats its mutation. */
@Composable internal fun NativeMcpReloadDialog(destination:EnvironmentRequest?,threadId:String?,onDismiss:()->Unit){
    val scope=rememberCoroutineScope()
    var alive by remember{mutableStateOf(true)}
    DisposableEffect(Unit){onDispose{alive=false}}
    val generation=remember{Pocket.nativeAuthGeneration}
    LaunchedEffect(Pocket.nativeAuthGeneration,Pocket.connected){if(generation!=Pocket.nativeAuthGeneration||!Pocket.connected)onDismiss()}
    fun current()=alive&&Pocket.connected&&generation==Pocket.nativeAuthGeneration&&destination!=null&&Pocket.captureEnvironment()?.matches(destination)==true&&Pocket.selected==threadId
    var view by remember{mutableStateOf<JSONObject?>(null)}
    var receipt by remember{mutableStateOf<JSONObject?>(null)}
    var busy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf("")}
    var revision by remember{mutableIntStateOf(0)}
    var dismissing by remember{mutableStateOf<String?>(null)}
    var attemptId by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(revision){
        if(!current())return@LaunchedEffect
        busy=true;view=null;receipt=null;message=""
        try{val result=Pocket.apiEnvironment(requireNotNull(destination),"/api/native/configuration?section=mcp&reloadReview=1"+(threadId?.let{"&threadId=${Uri.encode(it)}"}?:""));if(current()){
            view=result
            if(result.optJSONArray("receipts")?.objects()?.any{it.s("attemptId")==attemptId&&it.s("state") !in listOf("dispatching","unknown")}==true)attemptId=null
        }}catch(e:CancellationException){throw e}catch(_:Exception){if(current())message="Reload review unavailable. No reload was submitted by this inspection."}finally{if(current())busy=false}
    }
    AlertDialog(onDismissRequest=onDismiss,title={BilingualLabel("Host MCP reload")},text={Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        WorkflowText(destination?.environment?.name?:"Original environment")
        if(busy)CircularProgressIndicator(Modifier.size(22.dp))
        if(message.isNotBlank())WorkflowText(message)
        view?.let{WorkflowText(it.s("notice"))}
        val receipts=receipt?.let{listOf(it)}?:view?.optJSONArray("receipts")?.objects().orEmpty()
        receipts.forEach{r->
            WorkflowText("${r.s("state")}: ${r.s("notice")}")
            if(r.optBoolean("canAcknowledge")){
                TextButton({if(current())dismissing=r.s("attemptId")},enabled=current()&&!busy){BilingualLabel("I inspected the host; review dismissal")}
                if(dismissing==r.s("attemptId")){
                    WorkflowText("Dismissal does not cancel the reload. A new reload can repeat changes if the original outcome remains unknown.")
                    OutlinedButton({if(current()&&!busy)scope.launch{
                        busy=true
                        try{val result=Pocket.apiEnvironment(requireNotNull(destination),"/api/native/configuration",JSONObject().put("section","mcp").put("action","reloadAcknowledge").put("threadId",r.opt("threadId")).put("attemptId",r.s("attemptId")).put("confirmInspected",true));if(current()){receipt=result;view=null;dismissing=null}}
                        catch(e:CancellationException){throw e}catch(_:Exception){if(current())message="Dismissal unconfirmed. Check reload receipts."}finally{if(current())busy=false}
                    }},enabled=current()&&!busy){BilingualLabel("Dismiss inspected reload receipt")}
                }
            }
        }
        if(view?.optBoolean("canReload")==true)OutlinedButton({if(current()&&!busy)scope.launch{
            busy=true;attemptId=attemptId?:UUID.randomUUID().toString()
            val reviewed=view
            try{val result=Pocket.apiEnvironment(requireNotNull(destination),"/api/native/configuration",JSONObject().put("section","mcp").put("action","reload").put("threadId",threadId).put("attemptId",attemptId).put("reviewToken",reviewed?.s("reviewToken")).put("confirmHostWide",true));if(current()){receipt=result;view=null;message="Original reload receipt. Check MCP status separately for connection readiness."}}
            catch(e:CancellationException){throw e}catch(_:Exception){if(current()){view=null;message="Reload delivery unconfirmed. Check original reload receipts; do not submit again blindly."}}finally{if(current())busy=false}
        }},enabled=current()&&!busy){BilingualLabel("Reload MCP across this host")}
    }},confirmButton={TextButton({if(current()&&!busy)revision++},enabled=current()&&!busy){BilingualLabel("Check reload receipts")}},dismissButton={TextButton(onDismiss){BilingualLabel("Close")}})
}
