package co.fallsoft.pocket

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

private fun pluginYesNo(value:Any?):String=when(value){true->"Yes";false->"No";else->"Unavailable"}
private fun pluginPolicyLabel(value:String):String=when(value){
    "AVAILABLE"->"Installation allowed"
    "NOT_AVAILABLE"->"Installation restricted"
    "INSTALLED_BY_DEFAULT"->"Managed installation"
    else->"Installation policy unavailable"
}
private fun pluginAuthLabel(value:String):String=when(value){
    "ON_INSTALL"->"Authentication during setup"
    "ON_USE"->"Authentication when used"
    else->"Authentication policy unavailable"
}
internal fun pluginOperationLabel(value:String):String=when(value){
    "dispatching"->"Waiting for Codex"
    "acknowledged"->"Reply received"
    "unknown"->"Outcome unconfirmed"
    else->"Status unavailable"
}

/** Plugin controls capture the same environment and thread as their settings opening.
 * No automatic auth, retry, or ready claim. Durable native receipts own recovery. */
@Composable internal fun NativePluginDialog(destination:EnvironmentRequest?,threadId:String?,pluginKey:String,onDismiss:()->Unit){
    val scope=rememberCoroutineScope()
    val uriHandler=LocalUriHandler.current
    var alive by remember{mutableStateOf(true)}
    DisposableEffect(Unit){onDispose{alive=false}}
    fun current()=alive&&destination!=null&&Pocket.captureEnvironment()?.matches(destination)==true&&Pocket.selected==threadId
    var view by remember{mutableStateOf<JSONObject?>(null)}
    var loading by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf("")}
    var revision by remember{mutableIntStateOf(0)}
    var confirmation by remember{mutableStateOf<Pair<String,String?>?>(null)}
    var interstitial by remember{mutableStateOf(false)}
    LaunchedEffect(revision){
        if(!current())return@LaunchedEffect
        loading=true;view=null;confirmation=null;message=""
        try{
            val result=Pocket.apiEnvironment(requireNotNull(destination),"/api/native/plugins/detail?key=${Uri.encode(pluginKey)}"+(threadId?.let{"&threadId=${Uri.encode(it)}"}?:""))
            if(current())view=result
        }catch(e:CancellationException){throw e}catch(e:Exception){if(current()){
            message=e.message?:"Plugin details unavailable"
            try{val saved=Pocket.apiEnvironment(requireNotNull(destination),"/api/native/plugins/receipts?key=${Uri.encode(pluginKey)}");if(current())message+="\n"+saved.optJSONArray("receipts")?.objects().orEmpty().joinToString("\n"){"${it.s("name")} · ${it.s("action")} · ${pluginOperationLabel(it.s("state"))}: ${it.s("notice")}"}}catch(c:CancellationException){throw c}catch(_:Exception){}
        }}finally{if(current())loading=false}
    }
    fun confirm(action:String,id:String?=null){if(current()){confirmation=action to id;interstitial=false}}
    fun submit(snapshot:JSONObject,action:String,id:String?,acceptedInterstitial:Boolean){
        if(!current()||loading)return
        // Create once for this explicit action; no retry uses a fresh attempt.
        val attempt=id?:UUID.randomUUID().toString()
        loading=true;confirmation=null;view=null;message="Awaiting native acknowledgement…"
        scope.launch{
            try{
                if(!current())return@launch
                val body=JSONObject().put("section","plugins").put("threadId",threadId).put("key",pluginKey).put("reviewToken",snapshot.s("reviewToken")).put("action",action).put("attemptId",attempt).put("confirmEffects",true).put("confirmInterstitial",acceptedInterstitial)
                val result=Pocket.apiEnvironment(requireNotNull(destination),"/api/native/configuration",body)
                if(current())message=result.s("notice")
            }catch(e:CancellationException){throw e}catch(_:Exception){if(current())message="Delivery unconfirmed. Reload details to inspect the original attempt. Do not repeat the operation."}finally{if(current())loading=false}
        }
    }
    AlertDialog(onDismissRequest=onDismiss,title={BilingualLabel("Plugin details")},text={Column(Modifier.fillMaxWidth().heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        WorkflowText(destination?.environment?.name?:"Original environment",fontSize=12.sp,color=Muted)
        if(!current())WorkflowText("Open plugin settings again in the original environment and conversation.",color=Coral)
        if(loading)CircularProgressIndicator(Modifier.size(22.dp))
        if(message.isNotBlank())WorkflowText(message,fontSize=13.sp,color=Muted)
        view?.let{snapshot->
            val detail=snapshot.optJSONObject("detail")?:JSONObject()
            BilingualLabel(detail.s("name"));WorkflowText(detail.s("marketplace"),fontSize=12.sp,color=Muted)
            WorkflowText("Installed: ${pluginYesNo(detail.opt("installed"))} · Enabled: ${pluginYesNo(detail.opt("enabled"))} · ${pluginPolicyLabel(detail.s("installPolicy"))} · ${pluginAuthLabel(detail.s("authPolicy"))}",fontSize=12.sp)
            val pending=confirmation
            if(pending!=null){
                val action=pending.first
                WorkflowText(when(action){"install"->snapshot.s("effects");"uninstall"->snapshot.s("removalEffects");else->"A missing catalog entry does not prove the earlier operation had no effect or has ended. Acknowledge only after reviewing this environment and account. The original outcome remains unconfirmed and is never replayed."},fontSize=13.sp)
                if(action=="install"){
                    WorkflowText(detail.s("description"),fontSize=12.sp)
                    listOf("capabilities","skills","hooks","mcpServers").forEach{key->detail.optJSONArray(key)?.let{a->if(a.length()>0)WorkflowText("$key: "+(0 until a.length()).joinToString(", "){a.optString(it)},fontSize=12.sp)}}
                    detail.optJSONObject("links")?.let{links->links.keys().forEach{key->val url=links.s(key);TextButton({if(current())runCatching{uriHandler.openUri(url)}},enabled=current()){BilingualLabel(key)}}}
                }
                val required=action=="install"&&detail.optBoolean("mustShowInstallationInterstitial")
                if(required){Row{Checkbox(interstitial,{interstitial=it},enabled=current()&&!loading);WorkflowText("I reviewed the required installation notice, plugin capabilities and host authentication effects.",fontSize=12.sp)}}
                OutlinedButton({submit(snapshot,action,pending.second,interstitial)},enabled=current()&&!loading&&(!required||interstitial)){BilingualLabel(when(action){"install"->"Confirm installation";"uninstall"->"Confirm removal";else->"Acknowledge uncertain outcome"})}
                TextButton({confirmation=null},enabled=!loading){BilingualLabel("Go back")}
            }else{
                if(detail.s("description").isNotBlank())WorkflowText(detail.s("description"),fontSize=13.sp)
                listOf("capabilities" to "Capabilities","skills" to "Skills","hooks" to "Hooks","mcpServers" to "MCP servers").forEach{(key,label)->detail.optJSONArray(key)?.let{a->if(a.length()>0)WorkflowText("$label: "+(0 until a.length()).joinToString(", "){a.optString(it)},fontSize=12.sp)}}
                detail.optJSONArray("apps")?.objects()?.takeIf{it.isNotEmpty()}?.let{apps->WorkflowText("Apps: "+apps.joinToString(", "){it.s("name").ifBlank{it.s("id")}},fontSize=12.sp)}
                WorkflowText("Readiness is unverified. Review MCP and Apps status after installation; host authentication cannot be completed here.",fontSize=12.sp,color=Muted)
                if(snapshot.s("reason").isNotBlank())WorkflowText(snapshot.s("reason"),fontSize=12.sp,color=Coral)
                snapshot.optJSONArray("receipts")?.objects()?.forEach{receipt->
                    HorizontalDivider();WorkflowText("${receipt.s("action")} · ${pluginOperationLabel(receipt.s("state"))}"+(if(receipt.optLong("acknowledgedAt")>0)" · reviewed" else ""),fontSize=12.sp)
                    WorkflowText(receipt.s("notice"),fontSize=12.sp,color=Muted)
                    receipt.optJSONArray("appsNeedingAuth")?.objects()?.takeIf{it.isNotEmpty()}?.let{apps->WorkflowText("Apps requiring authentication: "+apps.joinToString(", "){it.s("name").ifBlank{it.s("id")}},fontSize=12.sp)}
                    if(receipt.optBoolean("canAcknowledge"))TextButton({confirm("acknowledge",receipt.s("attemptId"))},enabled=current()&&!loading){BilingualLabel("Review uncertain outcome")}
                }
                if(snapshot.optBoolean("canInstall"))OutlinedButton({confirm("install")},enabled=current()&&!loading){BilingualLabel("Review installation")}
                if(snapshot.optBoolean("canUninstall"))OutlinedButton({confirm("uninstall")},enabled=current()&&!loading){BilingualLabel("Review removal")}
            }
        }
    }},confirmButton={TextButton(onDismiss){BilingualLabel("Back to plugins")}},dismissButton={TextButton({if(current())revision++},enabled=current()&&!loading){BilingualLabel("Reload plugin details")}})
}
