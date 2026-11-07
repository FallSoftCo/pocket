package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

private fun executionQuery(value:String)=URLEncoder.encode(value,"UTF-8")
private data class TerminalConfirmation(val destination:EnvironmentRequest,val thread:String,val process:String,val command:String)

@Composable internal fun NativeCommandOutput(row:JSONObject){
    val thread=Pocket.selected;val environment=Pocket.captureEnvironment();val scope=rememberCoroutineScope()
    var opened by remember(thread,environment?.environment?.id,row.s("id")){mutableStateOf(false)}
    var page by remember(thread,environment?.environment?.id,row.s("id")){mutableStateOf<JSONObject?>(null)}
    var problem by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    val offsets=remember(thread,environment?.environment?.id,row.s("id")){mutableStateListOf(0L)}
    var position by remember{mutableIntStateOf(0)}
    fun read(offset:Long,index:Int,reset:Boolean=false){
        if(busy||environment==null)return
        busy=true;problem=""
        scope.launch{try{
            val revision=if(reset)"" else page?.s("revision").orEmpty()
            val query="turnId=${executionQuery(row.s("turnId"))}&itemId=${executionQuery(row.s("itemId"))}&offset=$offset"+(if(revision.isBlank())"" else "&revision=$revision")
            page=Pocket.apiEnvironment(environment,"/api/threads/$thread/command-output?$query");position=index
            if(reset){offsets.clear();offsets.add(0L)}
        }catch(e:Exception){problem=PocketNetwork.error(e)}finally{busy=false}}
    }
    TextButton({opened=true;read(0,0,true)}){BilingualLabel("Read command output")}
    if(opened)AlertDialog(onDismissRequest={opened=false},title={BilingualLabel("Command output")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        WorkflowText("Codex retained command output",fontSize=12.sp,color=Muted)
        if(busy)WorkflowText("Reading output…",fontSize=12.sp)
        if(problem.isNotBlank()){WorkflowText(problem,color=Coral,fontSize=13.sp);TextButton({read(0,0,true)},enabled=!busy){BilingualLabel("Read from beginning")}}
        page?.let{value->
            WorkflowText("${value.optLong("offset")}–${value.optLong("offset")+value.s("text").length} of ${value.optLong("totalCharacters")} characters",fontSize=11.sp,color=Muted)
            SelectionContainer{Text(value.s("text"),fontFamily=FontFamily.Monospace,fontSize=11.sp,modifier=Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()))}
            Row{if(position>0)TextButton({read(offsets[position-1],position-1)},enabled=!busy){BilingualLabel("Previous page")}
                if(!value.isNull("nextOffset"))TextButton({val next=value.optLong("nextOffset");if(offsets.size<=position+1)offsets.add(next)else offsets[position+1]=next;read(next,position+1)},enabled=!busy){BilingualLabel("Next page")}}
        }
    }},confirmButton={TextButton({opened=false}){BilingualLabel("Close")}})
}

@Composable internal fun NativeTerminalsControl(){
    val thread=Pocket.selected;val environment=Pocket.captureEnvironment();val scope=rememberCoroutineScope()
    var opened by remember(thread,environment?.environment?.id){mutableStateOf(false)}
    var data by remember(thread,environment?.environment?.id){mutableStateOf<JSONObject?>(null)}
    var problem by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    var confirmation by remember(thread,environment?.environment?.id){mutableStateOf<TerminalConfirmation?>(null)}
    val receipts=remember(thread,environment?.environment?.id){mutableStateMapOf<String,String>()}
    fun read(cursor:String=""){
        if(busy||environment==null)return
        busy=true;problem=""
        scope.launch{try{data=Pocket.apiEnvironment(environment,"/api/threads/$thread/terminals"+(if(cursor.isBlank())"" else "?cursor="+executionQuery(cursor)))}catch(e:Exception){problem=PocketNetwork.error(e)}finally{busy=false}}
    }
    TextButton({opened=true;read()}){BilingualLabel("Background terminals")}
    if(opened)AlertDialog(onDismissRequest={opened=false},title={BilingualLabel("Background terminals")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        if(busy)WorkflowText("Reading terminals…",fontSize=12.sp)
        if(problem.isNotBlank())WorkflowText(problem,color=Coral,fontSize=13.sp)
        if(data?.optJSONArray("data")?.length()==0)WorkflowText("No background terminals reported.",fontSize=13.sp)
        data?.optJSONArray("data")?.objects()?.forEach{terminal->
            val process=terminal.s("processId");val receipt=receipts[process].orEmpty()
            SelectionContainer{Text(terminal.s("command"),fontFamily=FontFamily.Monospace,fontSize=12.sp)}
            WorkflowText(terminal.s("cwd")+" · process "+process,fontSize=11.sp,color=Muted)
            if(!terminal.isNull("cpuPercent"))WorkflowText("CPU ${terminal.optDouble("cpuPercent")}%",fontSize=11.sp,color=Muted)
            if(receipt.isNotBlank())WorkflowText(receipt,fontSize=12.sp,color=Coral)
            TextButton({val destination=environment;val source=thread;if(destination!=null&&source!=null)confirmation=TerminalConfirmation(destination,source,process,terminal.s("command"))},enabled=!busy&&receipt.isBlank()){BilingualLabel("Terminate",color=Coral)}
        }
        data?.s("nextCursor")?.takeIf{it.isNotBlank()}?.let{cursor->TextButton({read(cursor)},enabled=!busy){BilingualLabel("Next terminals")}}
    }},confirmButton={TextButton({read()},enabled=!busy){BilingualLabel("Refresh terminals")}},dismissButton={TextButton({opened=false}){BilingualLabel("Close")}})
    confirmation?.let{terminal->AlertDialog(onDismissRequest={confirmation=null},title={BilingualLabel("Terminate this process?")},text={Text(terminal.command,fontSize=13.sp)},confirmButton={TextButton({
        confirmation=null
        if(Pocket.selected!=terminal.thread||Pocket.captureEnvironment()?.matches(terminal.destination)!=true){problem="The selected session changed. Open its terminals again.";return@TextButton}
        val destination=terminal.destination;val process=terminal.process;receipts[process]="Submitting termination…"
        scope.launch{try{
            val result=Pocket.apiEnvironment(destination,"/api/threads/${terminal.thread}/terminals/terminate",JSONObject().put("processId",process));check(result.optBoolean("ok")){"Termination receipt missing"};receipts[process]="Termination accepted. Refresh to inspect current state."
        }catch(e:Exception){receipts[process]=if(e is PocketApiException&&e.status in 400..499)PocketNetwork.error(e) else "Termination unconfirmed. Refresh before another attempt."}}
    }){BilingualLabel("Confirm terminate",color=Coral)}},dismissButton={TextButton({confirmation=null}){BilingualLabel("Keep running")}})}
}
