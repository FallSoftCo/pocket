package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URLEncoder

private fun executionQuery(value:String)=URLEncoder.encode(value,"UTF-8")
private fun executionCurrent(destination:EnvironmentRequest?,thread:String?)=destination!=null&&thread!=null&&Pocket.captureEnvironment()?.matches(destination)==true&&Pocket.selected==thread
private const val EXECUTION_SOURCE_CHANGED="The selected session changed. Open this view again in the original environment."
private data class TerminalConfirmation(val destination:EnvironmentRequest,val thread:String,val process:String,val command:String,val opening:Int)

@Composable internal fun NativeCommandOutput(row:JSONObject){
    val thread=Pocket.selected;val environment=Pocket.captureEnvironment();val scope=rememberCoroutineScope()
    var opened by remember(thread,environment,row.s("id")){mutableStateOf(false)}
    var page by remember(thread,environment,row.s("id")){mutableStateOf<JSONObject?>(null)}
    var problem by remember(thread,environment,row.s("id")){mutableStateOf("")};var busy by remember(thread,environment,row.s("id")){mutableStateOf(false)}
    var generation by remember(thread,environment,row.s("id")){mutableIntStateOf(0)}
    val offsets=remember(thread,environment,row.s("id")){mutableStateListOf(0L)}
    var position by remember(thread,environment,row.s("id")){mutableIntStateOf(0)}
    var qualified by remember(thread,environment,row.s("id")){mutableStateOf(false)}
    fun current(request:Int)=opened&&generation==request&&executionCurrent(environment,thread)
    fun dismiss(){opened=false;generation++;busy=false}
    fun read(offset:Long,index:Int,reset:Boolean=false){
        if(busy||environment==null||thread==null)return
        if(!executionCurrent(environment,thread)){problem=EXECUTION_SOURCE_CHANGED;return}
        val request=generation
        busy=true;problem=""
        scope.launch(Dispatchers.Main.immediate){try{
            if(!qualified){
                val feature=Pocket.apiEnvironment(environment,"/api/native/capabilities?threadId=${executionQuery(thread)}").optJSONObject("features")?.optJSONObject("executionOutput")
                if(!current(request))return@launch
                if(feature?.s("state")!="available"){problem=feature?.s("reason")?.ifBlank{"Retained command output is unavailable on this runtime."}?:"Retained command output is unavailable on this runtime.";return@launch}
                qualified=true
            }
            val revision=if(reset)"" else page?.s("revision").orEmpty()
            val query="turnId=${executionQuery(row.s("turnId"))}&itemId=${executionQuery(row.s("itemId"))}&offset=$offset"+(if(revision.isBlank())"" else "&revision=$revision")
            val result=Pocket.apiEnvironment(environment,"/api/threads/$thread/command-output?$query")
            if(!current(request))return@launch
            page=result;position=index
            if(reset){offsets.clear();offsets.add(0L)}
        }catch(e:CancellationException){throw e}
        catch(e:Exception){if(current(request))problem=PocketNetwork.error(e)}
        finally{
            // Completion owns this generation's loading state even if its source
            // retired. A newer request still owns its own loading reservation.
            if(generation==request)busy=false
        }}
    }
    TextButton({if(!busy){opened=true;generation++;page=null;qualified=false;read(0,0,true)}},enabled=!busy){BilingualLabel("Read command output")}
    if(opened)AlertDialog(onDismissRequest={dismiss()},title={BilingualLabel("Command output")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        WorkflowText("Codex retained command output",fontSize=12.sp,color=Muted)
        if(busy)WorkflowText("Reading output…",fontSize=12.sp)
        if(problem.isNotBlank()){WorkflowText(problem,color=Coral,fontSize=13.sp);TextButton({qualified=false;read(0,0,true)},enabled=!busy){BilingualLabel("Read from beginning")}}
        page?.let{value->
            WorkflowText("${value.optLong("offset")}–${value.optLong("offset")+value.s("text").length} of ${value.optLong("totalCharacters")} characters",fontSize=11.sp,color=Muted)
            key(generation,value.s("revision"),value.optLong("offset")){
                SelectionContainer{Text(value.s("text"),fontFamily=FontFamily.Monospace,fontSize=11.sp,modifier=Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()).testTag("native-command-output"))}
            }
            Row{if(position>0)TextButton({read(offsets[position-1],position-1)},enabled=!busy){BilingualLabel("Previous page")}
                if(!value.isNull("nextOffset"))TextButton({val next=value.optLong("nextOffset");if(offsets.size<=position+1)offsets.add(next)else offsets[position+1]=next;read(next,position+1)},enabled=!busy){BilingualLabel("Next page")}}
        }
    }},confirmButton={TextButton({dismiss()}){BilingualLabel("Close")}})
}

@Composable internal fun NativeTerminalsControl(){
    val thread=Pocket.selected;val environment=Pocket.captureEnvironment();val scope=rememberCoroutineScope()
    var opened by remember(thread,environment){mutableStateOf(false)}
    var data by remember(thread,environment){mutableStateOf<JSONObject?>(null)}
    var problem by remember(thread,environment){mutableStateOf("")};var busy by remember(thread,environment){mutableStateOf(false)}
    var generation by remember(thread,environment){mutableIntStateOf(0)}
    var confirmation by remember(thread,environment){mutableStateOf<TerminalConfirmation?>(null)}
    val receipts=remember(thread,environment){mutableStateMapOf<String,String>()}
    fun current(request:Int)=opened&&generation==request&&executionCurrent(environment,thread)
    fun dismiss(){opened=false;confirmation=null;generation++;busy=false}
    fun read(cursor:String=""){
        if(busy||environment==null||thread==null)return
        if(!executionCurrent(environment,thread)){problem=EXECUTION_SOURCE_CHANGED;return}
        val request=++generation
        busy=true;problem="";data=null;confirmation=null
        scope.launch{try{
            val result=Pocket.apiEnvironment(environment,"/api/threads/$thread/terminals?terminalPresentation=1"+(if(cursor.isBlank())"" else "&cursor="+executionQuery(cursor)))
            if(current(request))data=result
        }catch(e:Exception){if(current(request))problem=PocketNetwork.error(e)}finally{if(current(request))busy=false}}
    }
    TextButton({opened=true;read()}){BilingualLabel("Background terminals")}
    if(opened)AlertDialog(onDismissRequest={dismiss()},title={BilingualLabel("Background terminals")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        if(busy)WorkflowText("Reading terminals…",fontSize=12.sp)
        if(problem.isNotBlank())WorkflowText(problem,color=Coral,fontSize=13.sp)
        val mutation=data?.optJSONObject("features")?.optJSONObject("backgroundTerminals")?.optJSONObject("mutation")
        if(data!=null&&mutation?.s("state")!="available")WorkflowText(mutation?.s("reason")?.ifBlank{"Read-only inspection. Termination is unavailable on this runtime."}?:"Read-only inspection. Termination is unavailable on this runtime.",fontSize=12.sp,color=Muted)
        if(data?.optJSONArray("data")?.length()==0)WorkflowText("No background terminals reported.",fontSize=13.sp)
        data?.optJSONArray("data")?.objects()?.forEach{terminal->
            val process=terminal.s("processId");val receipt=receipts[process].orEmpty()
            SelectionContainer{Text(terminal.s("command"),fontFamily=FontFamily.Monospace,fontSize=12.sp)}
            WorkflowText(terminal.s("cwd")+" · process "+process,fontSize=11.sp,color=Muted)
            if(!terminal.isNull("cpuPercent"))WorkflowText("CPU ${terminal.optDouble("cpuPercent")}%",fontSize=11.sp,color=Muted)
            if(receipt.isNotBlank())WorkflowText(receipt,fontSize=12.sp,color=Coral)
            if(mutation?.s("state")=="available")TextButton({val destination=environment;val source=thread;if(destination!=null&&source!=null&&current(generation))confirmation=TerminalConfirmation(destination,source,process,terminal.s("command"),generation)},enabled=!busy&&receipt.isBlank()){BilingualLabel("Terminate",color=Coral)}
        }
        data?.s("nextCursor")?.takeIf{it.isNotBlank()}?.let{cursor->TextButton({read(cursor)},enabled=!busy){BilingualLabel("Next terminals")}}
    }},confirmButton={TextButton({read()},enabled=!busy){BilingualLabel("Refresh terminals")}},dismissButton={TextButton({dismiss()}){BilingualLabel("Close")}})
    confirmation?.let{terminal->AlertDialog(onDismissRequest={confirmation=null},title={BilingualLabel("Terminate this process?")},text={Text(terminal.command,fontSize=13.sp)},confirmButton={TextButton({
        confirmation=null
        if(!current(terminal.opening)||Pocket.captureEnvironment()?.matches(terminal.destination)!=true){problem=EXECUTION_SOURCE_CHANGED;return@TextButton}
        if(data?.optJSONObject("features")?.optJSONObject("backgroundTerminals")?.optJSONObject("mutation")?.s("state")!="available"){problem="Termination is unavailable on this runtime.";return@TextButton}
        val destination=terminal.destination;val process=terminal.process;receipts[process]="Submitting termination…"
        scope.launch{try{
            val result=Pocket.apiEnvironment(destination,"/api/threads/${terminal.thread}/terminals/terminate",JSONObject().put("processId",process));if(!current(terminal.opening))return@launch;check(result.optBoolean("ok")&&result.opt("terminated") is Boolean){"Termination receipt missing"};receipts[process]=if(result.optBoolean("terminated"))"Termination accepted. Refresh to inspect current state." else "No matching background process was terminated. Refresh to inspect current state."
        }catch(e:Exception){if(current(terminal.opening))receipts[process]=if(e is PocketApiException&&e.status in 400..499)PocketNetwork.error(e) else "Termination unconfirmed. Refresh before another attempt."}}
    }){BilingualLabel("Confirm terminate",color=Coral)}},dismissButton={TextButton({confirmation=null}){BilingualLabel("Keep running")}})}
}
