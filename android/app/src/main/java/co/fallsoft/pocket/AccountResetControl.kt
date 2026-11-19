package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** One source-bound durable intent. Drawer dismissal never cancels redemption. */
@Composable internal fun AccountResetControl(){
    val destination=Pocket.captureEnvironment()?:return
    val identity=destination.environment.id
    var data by remember(identity){mutableStateOf<JSONObject?>(null)}
    var busy by remember(identity){mutableStateOf(false)}
    var message by remember(identity){mutableStateOf<String?>(null)}
    var confirm by remember(identity){mutableStateOf(false)}
    val storageKey=Pocket.key("manualResetRequest",environmentId=identity)
    fun saved():JSONObject?=try{Pocket.prefs.getString(storageKey,null)?.let{JSONObject(it)}}catch(_:Exception){null}
    fun inspect(){
        busy=true
        Pocket.scope.launch{
            try{data=Pocket.apiEnvironment(destination,"/api/account/reset");message=data?.optJSONObject("lastResult")?.optString("message")}
            catch(_:Exception){message="Reset controls unavailable. Refresh to check this account."}
            finally{busy=false}
        }
    }
    fun submit(){
        val details=data?:return
        val original=saved()
        if(original!=null&&original.optString("accountToken")!=details.optString("accountToken")){
            message="The account changed. Return to the original account to check its reset request.";return
        }
        val request=original?:JSONObject().put("id",details.optString("pendingId").takeIf{it.isNotBlank()&&it!="null"}?:UUID.randomUUID().toString()).put("accountToken",details.optString("accountToken"))
        // commit before POST: a lost HTTP acknowledgement retains this exact ID.
        if(!Pocket.prefs.edit().putString(storageKey,request.toString()).commit()){message="Could not save reset request. Nothing was sent.";return}
        busy=true
        Pocket.scope.launch{
            try{
                val result=Pocket.apiEnvironment(destination,"/api/account/reset",request)
                message=result.optString("message")
                if(result.optString("state")!="pending")Pocket.prefs.edit().remove(storageKey).commit()
                Pocket.acceptUsage(result.optJSONObject("usage"),destination)
                try{data=Pocket.apiEnvironment(destination,"/api/account/reset")}catch(_:Exception){}
            }catch(_:Exception){message="Reset result not confirmed. Check the same request; no replacement will be sent."}
            finally{busy=false}
        }
    }
    LaunchedEffect(identity){inspect()}
    val pending=saved()!=null||data?.optString("pendingId")?.let{it.isNotBlank()&&it!="null"}==true
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Button(onClick={if(data?.optBoolean("available")==true){if(pending)submit() else confirm=true}else inspect()},enabled=!busy,modifier=Modifier.fillMaxWidth()){
            InterfaceText(if(busy)"Checking reset…" else if(pending)"Check reset result" else if(data?.optBoolean("available")==true)"Use a banked reset" else "Refresh reset controls")
        }
        if(data?.optBoolean("available")==false)InterfaceText(data?.optString("message").orEmpty(),color=Muted)
        message?.takeIf{it.isNotBlank()}?.let{InterfaceText(it,color=Muted)}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={InterfaceText("Use one banked reset?")},text={
        InterfaceText("This uses an existing reset for this account now, even before the allowance runs out. It does not buy credits or turn automatic resets on.")
    },confirmButton={TextButton(onClick={confirm=false;submit()}){InterfaceText("Use reset now")}},dismissButton={TextButton(onClick={confirm=false}){InterfaceText("Cancel")}})
}
