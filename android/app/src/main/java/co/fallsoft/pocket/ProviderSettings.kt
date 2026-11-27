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

@Composable internal fun ProviderChoice(value:JSONObject?,onChange:(JSONObject?)->Unit,enabled:Boolean,onManage:()->Unit,revision:Int=0,explicitModel:Boolean=false,providerPath:String="/api/model-providers"){
    val destination=remember(Pocket.environmentId){Pocket.captureEnvironment()}
    var view by remember(destination){mutableStateOf<JSONObject?>(null)}
    var problem by remember(destination){mutableStateOf("")}
    var menu by remember{mutableStateOf(false)}
    LaunchedEffect(destination,revision){try{
        val result=Pocket.apiEnvironment(requireNotNull(destination),providerPath)
        if(Pocket.captureEnvironment()?.matches(destination)==true){view=result;if(value==null)providerDefaultChoice(result)?.let{onChange(it)}}
    }catch(e:CancellationException){throw e}catch(e:Exception){problem=e.message?:"Provider list unavailable"}}
    val providers=view?.optJSONArray("providers")?.objects().orEmpty()
    val provider=value?.s("providerId").orEmpty()
    Box{
        OutlinedButton({menu=true},enabled=enabled&&providers.isNotEmpty(),modifier=Modifier.fillMaxWidth()){
            Text("Provider: "+(providers.firstOrNull{it.s("id")==provider}?.s("name")?:"Codex default"))
        }
        DropdownMenu(menu,{menu=false}){providers.forEach{p->DropdownMenuItem(text={Text(p.s("name"))},onClick={menu=false;onChange(providerChoice(p))})}}
    }
    if(provider.isNotBlank()&&(explicitModel||provider!="openai")){
        val selected=providers.firstOrNull{it.s("id")==provider}
        var models by remember(destination,provider,revision){mutableStateOf<List<JSONObject>>(emptyList())}
        var modelProblem by remember(destination,provider,revision){mutableStateOf("")}
        var loadingModels by remember(destination,provider,revision){mutableStateOf(false)}
        var modelMenu by remember(destination,provider){mutableStateOf(false)}
        var manualModel by remember(destination,provider){mutableStateOf(false)}
        val latestValue by rememberUpdatedState(value)
        LaunchedEffect(destination,provider,revision,selected?.optBoolean("canListModels")){
            if(selected?.optBoolean("canListModels")==true){
                loadingModels=true
                try{
                    val result=Pocket.apiEnvironment(requireNotNull(destination),"/api/model-providers?modelsFor="+java.net.URLEncoder.encode(provider,"UTF-8"))
                    if(Pocket.captureEnvironment()?.matches(destination)==true&&latestValue?.s("providerId")==provider){
                        if(result.s("providerId")==provider&&result.s("state")=="available")models=result.optJSONArray("models")?.objects().orEmpty()
                        if(models.isEmpty())modelProblem="Model list unavailable. Enter a model ID manually."
                        else if(latestValue?.s("model").isNullOrBlank()&&!manualModel){
                            val default=result.s("defaultModel").takeIf{id->models.any{it.s("id")==id}}?:models.first().s("id")
                            onChange(JSONObject(latestValue.toString()).put("model",default))
                        }
                    }
                }catch(e:CancellationException){throw e}catch(_:Exception){modelProblem="Model list unavailable. Enter a model ID manually."}
                finally{loadingModels=false}
            }
        }
        if(models.isNotEmpty())Box{
            OutlinedButton({modelMenu=true},enabled=enabled,modifier=Modifier.fillMaxWidth()){
                Text("Model: "+(models.firstOrNull{it.s("id")==value?.s("model")}?.s("name")?:"Enter model ID…"))
            }
            DropdownMenu(modelMenu,{modelMenu=false}){
                models.forEach{m->DropdownMenuItem(text={Text(m.s("name").ifBlank{m.s("id")})},onClick={modelMenu=false;manualModel=false;onChange(JSONObject(value.toString()).put("model",m.s("id")))})}
                DropdownMenuItem(text={Text("Enter model ID…")},onClick={modelMenu=false;manualModel=true;onChange(JSONObject(value.toString()).put("model",""))})
            }
        }
        if(manualModel||models.none{it.s("id")==value?.s("model")}){
            OutlinedTextField(value?.s("model").orEmpty(),{manualModel=true;onChange(JSONObject(value.toString()).put("model",it))},label={BilingualLabel("Provider model ID")},enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth())
        }
        if(loadingModels)WorkflowText("Loading models…",fontSize=12.sp,color=Muted)
        if(modelProblem.isNotBlank())WorkflowText(modelProblem,fontSize=12.sp,color=Muted)
        WorkflowText(if(provider=="openai")"Uses this environment’s Codex authentication. Model availability is determined by the provider." else "Uses this provider’s API billing. Model availability is determined by the provider.",fontSize=12.sp,color=Muted)
    }
    if(problem.isNotBlank())WorkflowText(problem,fontSize=12.sp,color=Coral)
    TextButton(onManage,enabled=enabled){BilingualLabel("Manage providers")}
}

internal fun providerDefaultChoice(view:JSONObject)=if(view.s("state")=="available")view.optJSONArray("providers")?.objects()?.firstOrNull{it.s("id")==view.s("defaultProviderId")}?.let(::providerChoice)else null

internal fun providerChoice(provider:JSONObject)=JSONObject().put("providerId",provider.s("id")).apply{
    if(provider.s("baseUrl").startsWith("https://api.deepseek.com")){put("model","deepseek-flash");put("effort","high")}
}

/** Uses native provider credentials; the display cache and conversations remain intact. */
@Composable internal fun ImmersionProviderControl(){
    var destination by remember{mutableStateOf(Pocket.captureEnvironment())}
    val scope=rememberCoroutineScope()
    var open by remember{mutableStateOf(false)}
    var manage by remember{mutableStateOf(false)}
    var selection by remember{mutableStateOf<JSONObject?>(null)}
    var loaded by remember{mutableStateOf(false)}
    var problem by remember{mutableStateOf("")}
    var saving by remember{mutableStateOf(false)}
    LaunchedEffect(Pocket.environmentId){if(destination?.let{Pocket.captureEnvironment()?.matches(it)}!=true)open=false}
    OutlinedButton({destination=Pocket.captureEnvironment();loaded=false;problem="";open=true},modifier=Modifier.fillMaxWidth()){BilingualLabel("Immersion provider & model")}
    if(manage)ProviderSettingsDialog{manage=false}
    if(open){
        LaunchedEffect(destination){try{
            val captured=requireNotNull(destination)
            val state=Pocket.apiEnvironment(captured,"/api/immersion/v2")
            if(Pocket.captureEnvironment()?.matches(captured)==true){selection=state.optJSONObject("selection");loaded=true}
        }catch(e:CancellationException){throw e}catch(e:Exception){problem=e.message?:"Immersion settings unavailable"}}
        SettingsDrawer(true,{if(!saving)open=false},"Immersion model"){
            WorkflowText("Cached phrases stay available. New translations use this provider’s billing; no automatic fallback.",color=Muted,fontSize=13.sp)
            if(loaded)ProviderChoice(selection,{selection=it},!saving,{manage=true},explicitModel=true,providerPath="/api/immersion/v2?settings=1")
            if(problem.isNotBlank())WorkflowText(problem,color=Coral)
            Button({saving=true;scope.launch{try{
                val captured=requireNotNull(destination)
                check(Pocket.captureEnvironment()?.matches(captured)==true){"Environment changed"}
                Pocket.apiEnvironment(captured,"/api/immersion/v2",JSONObject().put("enabled",PocketImmersion.enabled).put("density",PocketImmersion.density).put("selection",selection?:JSONObject.NULL))
                open=false
            }catch(e:CancellationException){throw e}catch(e:Exception){problem=e.message?:"Provider setting was not saved"}finally{saving=false}}},enabled=loaded&&!saving,modifier=Modifier.fillMaxWidth()){BilingualLabel("Save immersion model")}
        }
    }
}
