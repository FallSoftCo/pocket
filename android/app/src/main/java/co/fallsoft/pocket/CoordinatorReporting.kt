package co.fallsoft.pocket
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CoordinatorReportingControl(){
 val base=Pocket.base;val token=Pocket.token
 var settings by remember(base,token){mutableStateOf<JSONObject?>(null)}
 var busy by remember(base,token){mutableStateOf(false)}
 var error by remember(base,token){mutableStateOf("")}
 val scope=rememberCoroutineScope()
 suspend fun request(body:JSONObject?=null):JSONObject=withContext(Dispatchers.IO){val b=Request.Builder().url(base+"/api/coordinator/reporting").header("Authorization","Bearer $token");if(body!=null)b.post(body.toString().toRequestBody("application/json".toMediaType()));Pocket.http.newCall(b.build()).execute().use{r->if(!r.isSuccessful)throw Exception("Unavailable");JSONObject(r.body!!.string())}}
 LaunchedEffect(base,token){if(token.isNotBlank())try{settings=request()}catch(_:Exception){error="Check-in settings unavailable"}}
 fun save(enabled:Boolean=settings?.optBoolean("enabled")?:false,interval:Int=settings?.optInt("intervalMinutes",30)?:30,stale:Int=settings?.optInt("staleAfterMinutes",120)?:120){if(busy)return;scope.launch{busy=true;try{val result=request(JSONObject().put("enabled",enabled).put("intervalMinutes",interval).put("staleAfterMinutes",stale));if(Pocket.base==base&&Pocket.token==token){settings=result;error=""}}catch(_:Exception){if(Pocket.base==base&&Pocket.token==token)error="Could not save check-in settings"}finally{if(Pocket.base==base&&Pocket.token==token)busy=false}}}
 Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
 Row(verticalAlignment=Alignment.CenterVertically){BilingualLabel("Coordinator check-ins",modifier=Modifier.weight(1f),centered=false);Switch(settings?.optBoolean("enabled")?:false,{save(enabled=it)},enabled=settings!=null&&!busy)}
 if(settings?.optBoolean("enabled")==true){FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(30,60,120).forEach{m->FilterChip(settings?.optInt("intervalMinutes")==m,{save(interval=m)},label={Text(if(m<60)"30 min" else "${m/60} h")},enabled=!busy)}};FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){BilingualLabel("Stale after",centered=false);listOf(120,240).forEach{m->FilterChip(settings?.optInt("staleAfterMinutes")==m,{save(stale=m)},label={Text("${m/60} h")},enabled=!busy)}}}
 WorkflowText(error.ifBlank{"Status checks only; agent messages are manual"},color=Muted)
 }
}
