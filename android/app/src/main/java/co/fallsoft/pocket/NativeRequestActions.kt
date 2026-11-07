package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.json.JSONArray

/** Decisions are indexes into the original live native request, never invented grants. */
@Composable internal fun NativeRequestActions(request:JSONObject,answers:Map<String,String>,save:(String,String)->Unit,editable:Boolean,submit:(JSONObject)->Unit){
    val p=request.optJSONObject("params")?:return
    val ui=request.optJSONObject("_pocketUI")?:if(request.s("method") in listOf("item/commandExecution/requestApproval","item/fileChange/requestApproval")){
        val offered=p.optJSONArray("availableDecisions")?:JSONArray(listOf("accept","decline","cancel"));val actions=JSONArray()
        for(index in 0 until offered.length()){val decision=offered.optString(index);if(decision in listOf("accept","decline","cancel"))actions.put(JSONObject().put("id",decision).put("label",when(decision){"accept"->"Allow once";"decline"->"Decline";else->"Cancel request"}))}
        JSONObject().put("kind","approval").put("legacy",true).put("actions",actions)
    }else null
    val uriHandler=LocalUriHandler.current
    if(ui==null||ui.s("kind")=="unsupported"){WorkflowText("This runtime request must be handled in the terminal.",color=Coral);return}
    ImmersionText("native-request:"+request.s("id"),p.s("reason",p.s("message",p.s("title","Review this request."))),fontSize=15.sp)
    if(ui.s("kind")=="approval")SelectionContainer{Text(p.s("command").ifBlank{p.optJSONObject("fileChanges")?.toString(2)?:p.toString(2)},fontSize=12.sp,color=Paper)}
    if(ui.s("kind")=="permissions")SelectionContainer{Text(p.optJSONObject("permissions")?.toString(2).orEmpty(),fontSize=12.sp,color=Paper)}
    if(p.s("cwd").isNotBlank())WorkflowText("Working directory: "+p.s("cwd"),fontSize=12.sp,color=Muted)
    val mode=p.s("mode")
    if(mode=="url"){
        val url=p.s("url");val valid=runCatching{java.net.URI(url).let{it.scheme in listOf("https","http")&&!it.host.isNullOrBlank()}}.getOrDefault(false)
        if(valid)OutlinedButton({runCatching{uriHandler.openUri(url)}.onFailure{Pocket.error="Could not open this link."}}){BilingualLabel("Open verification link")}
        WorkflowText(if(valid)"Complete the external step, then continue here." else "Unsupported link. Open this request in the terminal.",fontSize=12.sp,color=Muted)
    }
    if(mode=="openai/userVerification")WorkflowText("Complete identity verification in the terminal. You can decline or cancel here.",color=Coral,fontSize=13.sp)
    val schema=p.optJSONObject("requestedSchema")
    val properties=schema?.optJSONObject("properties")
    val fields=properties?.keys()?.asSequence()?.toList().orEmpty()
    val simple=schema?.s("type")=="object"&&fields.all{val field=properties?.optJSONObject(it);field?.s("type") in listOf("string","number","integer","boolean")||(field?.s("type")=="array"&&(field.optJSONObject("items")?.has("enum")==true||field.optJSONObject("items")?.has("anyOf")==true))}
    if(mode in listOf("form","openai/form","openaiForm")){
        if(simple)fields.forEach{key->
            val field=properties?.optJSONObject(key)?:JSONObject();val draft="form:$key"
            val multiple=field.s("type")=="array";val enumField=if(multiple)field.optJSONObject("items")?:JSONObject()else field
            val choices=enumField.optJSONArray("enum")?.let{a->(0 until a.length()).map{a.get(it).toString() to (enumField.optJSONArray("enumNames")?.optString(it)?.takeIf{it.isNotBlank()}?:a.get(it).toString())}}
                ?:(enumField.optJSONArray("oneOf")?:enumField.optJSONArray("anyOf"))?.objects()?.map{it.s("const") to it.s("title",it.s("const"))}
                ?:if(field.s("type")=="boolean")listOf("true" to "Yes","false" to "No")else emptyList()
            val current=answers[draft]?:field.opt("default")?.toString().orEmpty()
            val selected=if(multiple)runCatching{JSONArray(current.ifBlank{"[]"}).let{a->(0 until a.length()).map{a.getString(it)}}}.getOrDefault(emptyList())else emptyList()

            WorkflowText(field.s("title",key)+(if(schema?.optJSONArray("required")?.let{a->(0 until a.length()).any{a.optString(it)==key}}==true)" (required)" else ""),fontSize=14.sp)
            if(field.s("description").isNotBlank())WorkflowText(field.s("description"),fontSize=12.sp,color=Muted)
            choices.forEach{(value,label)->OutlinedButton({save(draft,if(multiple)JSONArray(if(value in selected)selected-value else selected+value).toString()else value)},enabled=editable){BilingualLabel((if(multiple&&value in selected)"✓ " else "")+label)}}
            if(!multiple)OutlinedTextField(answers[draft]?:field.opt("default")?.toString().orEmpty(),{save(draft,it)},readOnly=!editable,label={BilingualLabel(field.s("title",key))},modifier=Modifier.fillMaxWidth())
        }else{
            SelectionContainer{Text(schema?.toString(2).orEmpty(),fontSize=12.sp,color=Muted)}
            OutlinedTextField(answers["form:json"]?:"{}",{save("form:json",it)},readOnly=!editable,label={BilingualLabel("Form values (JSON)")},modifier=Modifier.fillMaxWidth())
        }
    }
    ui.optJSONArray("actions")?.objects()?.forEach{action->
        if(action.s("detail").isNotBlank())SelectionContainer{Text(action.s("detail"),fontSize=12.sp,color=Muted)}
        OutlinedButton({
            val body=JSONObject().put(if(ui.optBoolean("legacy"))"decision" else "actionId",action.s("id"))
            if(action.s("id")=="accept"&&mode in listOf("form","openai/form","openaiForm")){
                if(simple){val content=JSONObject();fields.forEach{key->val field=properties?.optJSONObject(key)?:JSONObject();val value=answers["form:$key"]?:field.opt("default")?.toString().orEmpty();if(value.isNotEmpty())content.put(key,when(field.s("type")){"array"->runCatching{JSONArray(value)}.getOrDefault(JSONArray());"boolean"->if(value=="true")true else if(value=="false")false else value;"integer","number"->value.toDoubleOrNull()?:value;else->value})};body.put("content",content)}
                else body.put("content",answers["form:json"]?:"{}")
            }
            submit(body)
        },enabled=editable,modifier=Modifier.fillMaxWidth()){BilingualLabel(action.s("label"))}
    }
}
