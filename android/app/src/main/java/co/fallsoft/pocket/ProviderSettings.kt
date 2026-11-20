package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Provider credentials stay in transient form state and are written by Codex. */
@Composable internal fun ProviderSettingsDialog(onDismiss:()->Unit){
    val destination=remember{Pocket.captureEnvironment()}
    val auth=remember{Pocket.nativeAuthGeneration}
    val scope=rememberCoroutineScope()
    var view by remember{mutableStateOf<JSONObject?>(null)}
    var problem by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var revision by remember{mutableIntStateOf(0)}
    var id by remember{mutableStateOf("deepseek")}
    var name by remember{mutableStateOf("DeepSeek")}
    var url by remember{mutableStateOf("https://api.deepseek.com")}
    var key by remember{mutableStateOf("")}
    var envKey by remember{mutableStateOf("")}
    fun current()=destination!=null&&Pocket.captureEnvironment()?.matches(destination)==true&&auth==Pocket.nativeAuthGeneration
    LaunchedEffect(Pocket.environmentId,Pocket.nativeAuthGeneration){if(!current())onDismiss()}
    LaunchedEffect(revision){
        busy=true
        try{val result=Pocket.apiEnvironment(requireNotNull(destination),"/api/model-providers");if(current())view=result}
        catch(e:CancellationException){throw e}catch(e:Exception){if(current())problem=e.message?:"Providers unavailable"}
        finally{busy=false}
    }
    SettingsDrawer(true,onDismiss,"Model providers"){
        WorkflowText("${Pocket.host} · New sessions choose their provider. Existing sessions keep theirs.",color=Muted,fontSize=13.sp)
        view?.optJSONArray("providers")?.objects()?.forEach{p->
            WorkflowText(p.s("name")+" · "+p.s("id"),fontSize=16.sp)
            if(p.s("baseUrl").isNotBlank())WorkflowText(p.s("baseUrl"),fontSize=12.sp,color=Muted)
        }
        if(busy)CircularProgressIndicator(Modifier.size(22.dp))
        if(problem.isNotBlank())WorkflowText(problem,color=Coral)
        if(view?.optBoolean("canConfigure")==true){
            BilingualLabel("Add a Responses API provider")
            OutlinedTextField(name,{name=it},label={BilingualLabel("Name")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(id,{id=it},label={BilingualLabel("Provider ID")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(url,{url=it},label={BilingualLabel("API base URL")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(key,{key=it;envKey=""},label={BilingualLabel("API key")},visualTransformation=PasswordVisualTransformation(),enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(envKey,{envKey=it;key=""},label={BilingualLabel("Or existing host key variable")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
            WorkflowText("Your key is stored in Codex’s private configuration on this environment. This uses the provider’s billing, separately from ChatGPT. Saving does not send a model request.",fontSize=13.sp,color=Muted)
            Button({
                if(current()&&!busy){busy=true;problem="";val request=JSONObject().put("id",id.trim()).put("name",name.trim()).put("baseUrl",url.trim()).put("apiKey",key).put("envKey",envKey.trim()).put("expectedVersion",view?.s("configVersion"))
                    key=""
                    scope.launch{try{val response=Pocket.apiEnvironment(requireNotNull(destination),"/api/model-providers",request);if(current()){problem=response.s("notice");revision++}}
                    catch(e:CancellationException){throw e}catch(e:Exception){if(current()){problem=e.message?:"Provider save was not confirmed. Reload to inspect it.";view=null}}
                    finally{request.remove("apiKey");busy=false}}
                }
            },enabled=current()&&!busy&&id.isNotBlank()&&url.isNotBlank()&&(key.isNotBlank()||envKey.isNotBlank()),modifier=Modifier.fillMaxWidth()){BilingualLabel("Save provider")}
        }else if(!busy)WorkflowText(view?.s("notice")?:"Reload to inspect the original save before trying again.",color=Muted)
        TextButton({revision++},enabled=!busy){BilingualLabel("Reload providers")}
    }
}

@Composable internal fun ProviderChoice(value:JSONObject?,onChange:(JSONObject?)->Unit,enabled:Boolean,onManage:()->Unit,revision:Int=0,explicitModel:Boolean=false){
    val destination=remember(Pocket.environmentId){Pocket.captureEnvironment()}
    var view by remember(destination){mutableStateOf<JSONObject?>(null)}
    var problem by remember(destination){mutableStateOf("")}
    var menu by remember{mutableStateOf(false)}
    LaunchedEffect(destination,revision){try{
        val result=Pocket.apiEnvironment(requireNotNull(destination),"/api/model-providers")
        if(Pocket.captureEnvironment()?.matches(destination)==true){view=result;if(value==null&&result.s("state")=="available")onChange(JSONObject().put("providerId",result.s("defaultProviderId")))}
    }catch(e:CancellationException){throw e}catch(e:Exception){problem=e.message?:"Provider list unavailable"}}
    val providers=view?.optJSONArray("providers")?.objects().orEmpty()
    val provider=value?.s("providerId").orEmpty()
    Box{
        OutlinedButton({menu=true},enabled=enabled&&providers.isNotEmpty(),modifier=Modifier.fillMaxWidth()){
            Text("Provider: "+(providers.firstOrNull{it.s("id")==provider}?.s("name")?:"Codex default"))
        }
        DropdownMenu(menu,{menu=false}){providers.forEach{p->DropdownMenuItem(text={Text(p.s("name"))},onClick={menu=false;onChange(JSONObject().put("providerId",p.s("id")).apply{if(p.s("baseUrl").startsWith("https://api.deepseek.com")){put("model","deepseek-flash");put("effort","high")}})})}}
    }
    if(provider.isNotBlank()&&(explicitModel||provider!="openai")){
        OutlinedTextField(value?.s("model").orEmpty(),{onChange(JSONObject(value.toString()).put("model",it))},label={BilingualLabel("Provider model ID")},enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth())
        WorkflowText("Uses this provider’s API billing. Model availability is determined by the provider.",fontSize=12.sp,color=Muted)
    }
    if(problem.isNotBlank())WorkflowText(problem,fontSize=12.sp,color=Coral)
    TextButton(onManage,enabled=enabled){BilingualLabel("Manage providers")}
}
