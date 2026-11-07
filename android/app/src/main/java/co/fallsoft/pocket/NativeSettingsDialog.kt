package co.fallsoft.pocket

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/** User-opened native catalogs. No periodic refresh, model calls, or session resume. */
@Composable fun NativeSettingsDialog(onDismiss:()->Unit){
    val destination=remember{Pocket.captureEnvironment()}
    val threadId=remember{Pocket.selected}
    val scope=rememberCoroutineScope()
    var section by remember{mutableStateOf("settings")}
    var revision by remember{mutableIntStateOf(0)}
    var view by remember{mutableStateOf<JSONObject?>(null)}
    var rows by remember{mutableStateOf(emptyList<JSONObject>())}
    var loading by remember{mutableStateOf(false)}
    var problem by remember{mutableStateOf("")}
    var notice by remember{mutableStateOf("")}
    var changing by remember{mutableStateOf<String?>(null)}
    var uncertain by remember{mutableStateOf(false)}
    val sections=listOf("settings" to "Runtime", "agents" to "Agents", "skills" to "Skills", "mcp" to "MCP", "apps" to "Apps", "plugins" to "Plugins", "hooks" to "Hooks")
    fun current()=destination!=null&&Pocket.captureEnvironment()?.matches(destination)==true&&Pocket.selected==threadId
    suspend fun request(target:String,cursor:String?=null):JSONObject{
        check(current()){ "Open settings again in the original environment." }
        val query="/api/native/configuration?section=$target"+(threadId?.let{"&threadId=${Uri.encode(it)}"}?:"")+(cursor?.let{"&cursor=${Uri.encode(it)}"}?:"")
        val response=Pocket.apiEnvironment(requireNotNull(destination),query)
        check(current()){ "The selected environment changed." }
        return response
    }
    LaunchedEffect(section,revision){
        loading=true;problem="";uncertain=false;view=null;rows=emptyList()
        try{val result=request(section);view=result;rows=result.optJSONArray("items")?.objects()?:emptyList()}
        catch(e:Exception){problem=e.message?:"Native catalog unavailable"}finally{loading=false}
    }
    AlertDialog(onDismissRequest=onDismiss,title={BilingualLabel("Codex settings & agents")},text={Column(Modifier.fillMaxWidth().heightIn(max=560.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        view?.let{WorkflowText("${it.s("host")} · Codex ${it.optJSONObject("runtime")?.s("version")?.ifBlank{"version unavailable"}}",fontSize=12.sp,color=Muted)}
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){sections.forEach{(key,title)->TextButton({section=key;notice=""},enabled=!loading&&changing==null){BilingualLabel(if(section==key)"• $title" else title)}}}
        if(loading)CircularProgressIndicator(Modifier.size(22.dp))
        if(problem.isNotBlank())WorkflowText(problem,color=Coral,fontSize=12.sp)
        if(notice.isNotBlank())WorkflowText(notice,fontSize=12.sp,color=Muted)
        Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            view?.let{data->
                if(data.s("state")!="available")WorkflowText(data.s("reason","Native capability unavailable"),color=Muted)
                if(data.s("notice").isNotBlank())WorkflowText(data.s("notice"),fontSize=12.sp,color=Muted)
                if(section=="settings"){
                    WorkflowText("Requested permissions: ${data.optJSONObject("requested")?.s("permissions")?.ifBlank{"No saved preference"}}",fontSize=13.sp)
                    val observed=data.optJSONObject("observed")
                    if(observed?.s("state")=="observed"){
                        val actual=observed.optJSONObject("settings")
                        WorkflowText("Last observed runtime permissions: ${actual?.optJSONObject("sandbox")?.s("type")} · approvals ${actual?.opt("approvalPolicy")}",fontSize=13.sp)
                        WorkflowText("${observed.s("observedAt")} · ${observed.s("notice")}",fontSize=11.sp,color=Muted)
                    }else WorkflowText(observed?.s("reason")?:"Runtime permissions unavailable",fontSize=12.sp,color=Muted)
                    data.optJSONObject("settings")?.let{settings->settings.keys().forEach{key->WorkflowText("${key.replace('_',' ')}: ${settings.opt(key)}",fontSize=13.sp)}}
                    data.optJSONObject("features")?.let{features->features.keys().forEach{key->val feature=features.optJSONObject(key);WorkflowText("$key: ${feature?.s("state")} · ${feature?.s("reason")}",fontSize=11.sp,color=Muted)}}
                }else{
                    val parent=data.s("parentThreadId").ifBlank{data.s("rootThreadId")}
                    if(parent.isNotBlank()&&parent!=threadId)TextButton({if(current()){onDismiss();Pocket.open(parent)}}){BilingualLabel("Open parent task")}
                    if(rows.isEmpty())WorkflowText(if(data.s("state")=="available")"No items reported by this native catalog." else "Catalog unavailable.",fontSize=13.sp)
                    rows.forEach{item->
                        HorizontalDivider()
                        BilingualLabel(item.s("name").ifBlank{item.s("id")})
                        if(item.s("description").isNotBlank())WorkflowText(item.s("description"),fontSize=12.sp,color=Muted)
                        val detail=listOf("enabled","callable","authStatus","runtimeStatus","trustStatus","handlerType","marketplace","disabledReason","model","reasoningEffort").filter{item.has(it)&&!item.isNull(it)}.joinToString(" · "){"$it: ${item.opt(it)}"}
                        if(detail.isNotBlank())WorkflowText(detail,fontSize=12.sp,color=Muted)
                        if(section=="agents"){
                            WorkflowText("Status: ${item.optJSONObject("status")?.s("type")?:item.s("status")}",fontSize=12.sp,color=Muted)
                            TextButton({if(current()){onDismiss();Pocket.open(item.s("threadId"))}}){BilingualLabel("Open agent")}
                            if(!item.optBoolean("canAcceptDirectInput")){
                                WorkflowText("Direct input is unavailable; guide this agent from its parent.",fontSize=12.sp,color=Muted)
                                if(item.s("parentThreadId").isNotBlank())TextButton({if(current()){onDismiss();Pocket.open(item.s("parentThreadId"))}}){BilingualLabel("Open owning task")}
                            }
                        }
                        item.optJSONArray("tools")?.objects()?.takeIf{it.isNotEmpty()}?.let{tools->
                            var expanded by remember(item.s("id")){mutableStateOf(false)}
                            TextButton({expanded=!expanded}){BilingualLabel("${tools.size} tools · ${if(expanded)"Hide" else "Show"}")}
                            if(expanded)tools.forEach{tool->WorkflowText("${tool.s("name")} · ${tool.s("description")}",fontSize=11.sp,color=Muted)}
                        }
                        if(item.optBoolean("configurable"))OutlinedButton(onClick={
                            if(current())scope.launch{
                                changing=item.s("id");problem=""
                                try{
                                    val body=JSONObject().put("threadId",threadId).put("section",section).put("id",item.s("id")).put("enabled",!item.optBoolean("enabled"))
                                    if(section!="skills")body.put("expectedVersion",request("settings").s("configVersion"))
                                    check(current()){"The selected environment changed."}
                                    val result=Pocket.apiEnvironment(requireNotNull(destination),"/api/native/configuration",body)
                                    if(current()){notice=result.s("notice");revision++}
                                }catch(e:Exception){problem=(e.message?:"Configuration change failed")+" Reload to inspect the outcome before retrying.";uncertain=true}finally{changing=null}
                            }
                        },enabled=!loading&&changing==null&&!uncertain){BilingualLabel("${if(item.optBoolean("enabled"))"Disable" else "Enable"} for this environment")}
                    }
                    val cursor=data.s("nextCursor")
                    if(cursor.isNotBlank())TextButton({scope.launch{loading=true;try{val more=request(section,cursor);view=more;rows=rows+(more.optJSONArray("items")?.objects()?:emptyList())}catch(e:Exception){problem=e.message?:"Could not load more"}finally{loading=false}}},enabled=!loading&&changing==null){BilingualLabel("Load more")}
                    if(data.optInt("errorCount")>0)WorkflowText("${data.optInt("errorCount")} native catalog entries could not be loaded.",fontSize=12.sp,color=Muted)
                }
            }
        }
    }},confirmButton={TextButton(onDismiss){BilingualLabel("Close")}},dismissButton={TextButton({revision++},enabled=!loading&&changing==null){BilingualLabel("Reload")}})
}
