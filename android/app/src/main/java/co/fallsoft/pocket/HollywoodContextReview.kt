package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Human-selected context sources; rooms never become agent identities. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun HollywoodContextReviewDialog(client:HollywoodClient,actors:List<JSONObject>,initialAgent:String,dismiss:()->Unit){
    val agents=actors.filter{it.s("kind")=="agent"&&hollywoodUuid(it.s("id"))}
    var selected by remember(client.owner,client.bindingId){mutableStateOf(initialAgent.takeIf{id->agents.any{it.s("id")==id}}?:agents.singleOrNull()?.s("id").orEmpty())}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.9f).semantics{testTagsAsResourceId=true}.testTag("native-context-review"),shape=MaterialTheme.shapes.large){
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("Context windows",style=MaterialTheme.typography.titleLarge)
                if(agents.size>1||selected.isBlank())Row{
                    agents.forEach{agent->TextButton({selected=agent.s("id")},Modifier.testTag("native-context-agent:"+agent.s("id"))){Text(agent.s("name"))}}
                }
                if(selected.isBlank()){
                    Text("Choose an agent whose context you want to review.",color=Muted)
                    TextButton(dismiss){BilingualLabel("Close")}
                }else key(client.owner,client.bindingId,selected){
                    val session=remember{HollywoodContextReviewSession(client,selected)}
                    val name=agents.firstOrNull{it.s("id")==selected}?.s("name").orEmpty().ifBlank{"Agent"}
                    ContextReviewBody(session,name,dismiss)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ColumnScope.ContextReviewBody(session:HollywoodContextReviewSession,name:String,dismiss:()->Unit){
    var view by remember{mutableStateOf<JSONObject?>(null)}
    var sources by remember{mutableStateOf(emptyList<JSONObject>())}
    var cursor by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    var verified by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var choosing by remember{mutableStateOf(false)}
    var inspected by remember{mutableStateOf<JSONObject?>(null)}
    var epoch by remember{mutableIntStateOf(0)}
    val jobs=rememberCoroutineScope()
    suspend fun refresh(){
        try{view=session.refresh();verified=true;error=""}
        catch(e:Exception){if(e is CancellationException)throw e;verified=false;error=e.message.orEmpty()}
        epoch++
    }
    fun act(work:suspend ()->Unit){
        if(busy)return
        busy=true;jobs.launch{
            try{work();refresh()}
            catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty();epoch++}
            finally{busy=false}
        }
    }
    LaunchedEffect(session){refresh()}
    val pending=remember(epoch){session.pending}
    val preview=view?.optJSONObject("preview")
    val status=view?.optJSONObject("status")
    val control=status?.optJSONObject("control")
    val state=control?.s("state").orEmpty()
    val availability=view?.optJSONObject("availability")
    val sourceError=view?.s("sourceError").orEmpty()
    val editable=verified&&!busy&&pending==null&&control==null&&session.draftId!=null&&preview!=null&&sourceError.isBlank()
    val authorized=availability?.optBoolean("humanControlAuthorized")==true
    val configured=availability?.optBoolean("nativeReviewConfigured")==true&&availability.optInt("sourceIndexVersion")==1
    val terminal=state in listOf("installed","cancelled","superseded")
    val snapshot=preview?.optJSONObject("snapshot")?:view?.optJSONObject("content")?.optJSONObject("snapshot")
    val records=remember(snapshot?.toString()){runCatching{reviewSourceContent(snapshot)}.getOrDefault(emptyList())}
    val marks=preview?.optJSONObject("draft")?.optJSONObject("marks")
    val title=when {
        pending!=null->"Original action unconfirmed"
        !verified->if(error.isBlank())"Loading context status…" else "Context status unavailable"
        else->when(state){
        "requested"->"Preparing context"
        "prepared"->"Ready for your review"
        "approved","applying"->"Applying approved context"
        "installed"->"New context installed"
        "cancel_requested"->"Cancellation requested"
        "cancelled"->"Review cancelled"
        "refused"->"Preparation refused"
        "superseded"->"Review replaced"
        "revise_requested"->"Updating review"
        else->if(session.parentId!=null)"Editing replacement · original review stays held" else if(session.draftId==null)"No review selected" else "Choose what the next window keeps"
        }
    }
    LaunchedEffect(state,pending?.s("operationId")){
        while(state in listOf("requested","approved","applying","cancel_requested","revise_requested")&&pending==null){
            delay(1500);if(!busy)refresh()
        }
    }
    Text(name+" · Writer context",style=MaterialTheme.typography.titleMedium)
    availability?.let{Text("Window "+(it.optLong("windowGeneration")+1),color=Muted,modifier=Modifier.testTag("native-context-window"))}
    Text(title,modifier=Modifier.testTag("native-context-state"))
    if(error.isNotBlank())Text(error,color=Coral,modifier=Modifier.testTag("native-context-error"))
    if(sourceError.isNotBlank())Text(sourceError,color=Coral)
    if(pending!=null){
        Text("Original action unconfirmed. Check it before making another change.",color=Coral)
        TextButton({act{session.recover()}},enabled=!busy,modifier=Modifier.testTag("native-context-check-original")){Text("Check original action")}
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("native-context-content"),verticalArrangement=Arrangement.spacedBy(8.dp)){
        item{
            if(!verified)Text(if(error.isBlank())"Reading this agent’s context configuration…" else "Context status could not be verified.",color=Muted)
            else if(!configured)Text("Reviewed native windows are not enabled for this agent.",color=Muted)
            else if(!authorized)Text("Creating or applying a review is unavailable for this context.",color=Muted)
            else if(availability?.optBoolean("inferenceBusy")==true)Text("The Writer is busy. Review preparation waits for settled execution.",color=Muted)
            if(session.draftId==null&&authorized&&configured&&availability?.optBoolean("reviewHeld")!=true)
                Button({act{session.create()}},enabled=!busy&&pending==null,modifier=Modifier.testTag("native-context-create")){Text("Create review")}
            if(availability?.optBoolean("reviewHeld")==true&&availability.optJSONObject("ownReview")==null)
                Text("Another review is holding this Writer. Its private selection is not shown.",color=Muted)
        }
        if(records.isNotEmpty()){
            item{Text("Sources kept for the next window",style=MaterialTheme.typography.titleMedium)}
            items(records,key={it.s("reference")}){record->
                val content=record.optJSONObject("content")?:JSONObject()
                val role=content.optJSONObject("role")
                val value=content.optJSONObject("value")?:JSONObject()
                val label=if(record.s("reference")=="role")"Role · always kept" else value.s("objective").ifBlank{"Saved work context"}
                Card(Modifier.fillMaxWidth().testTag("native-context-kept:"+record.s("reference"))){
                    Column(Modifier.padding(10.dp)){
                        Text(label,maxLines=3)
                        if(role!=null)Text(role.s("purpose"),color=Muted)
                        else{
                            val progress=value.optJSONArray("progress")
                            if(progress!=null)for(n in 0 until progress.length())Text(progress.optString(n),color=Muted)
                        }
                        TextButton({inspected=record}){Text("View full source")}
                        if(editable&&record.s("reference")!="role"&&marks?.has(record.s("reference"))==true)
                            TextButton({act{session.mark(record.s("reference"),false,preview!!)}},Modifier.testTag("native-context-unpin:"+record.s("reference"))){Text("Remove selection")}
                    }
                }
            }
        }
        item{
            val bytes=snapshot?.optLong("totalBytes")?:0
            if(bytes>0)Text("Selected source data · "+bytes+" bytes",color=Muted)
            control?.optJSONObject("preflight")?.optLong("replacementWireBytes")?.takeIf{it>0}?.let{Text("Prepared LAX context · "+it+" bytes",color=Muted)}
            if(snapshot!=null)Text("Provider token count is not available in this preview. Tools and instructions add context.",color=Muted)
            if(editable)TextButton({
                act{val result=session.sources();sources=result.optJSONArray("sources")?.objects().orEmpty();cursor=result.s("nextCursor").takeIf{it.isNotBlank()};choosing=true}
            },Modifier.testTag("native-context-sources")){Text("Choose work sources")}
        }
        if(choosing&&editable){
            if(sources.isEmpty())item{Text("No saved work sources on this page.",color=Muted)}
            items(sources,key={it.s("reference")}){source->
                val chosen=marks?.has(source.s("reference"))==true
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Checkbox(chosen,{keep->act{session.mark(source.s("reference"),keep,preview!!)}},
                        modifier=Modifier.testTag("native-context-source:"+source.s("reference")))
                    Column(Modifier.weight(1f)){Text(source.s("title"),maxLines=4);Text(source.s("state"),color=Muted)}
                }
            }
            if(cursor!=null)item{TextButton({act{val result=session.sources(cursor);sources=(sources+result.optJSONArray("sources")?.objects().orEmpty()).distinctBy{it.s("reference")};cursor=result.s("nextCursor").takeIf{it.isNotBlank()}}}){Text("More saved work")}}
        }
        item{
            if(session.parentId!=null&&editable)TextButton({act{session.returnToHeld()}},Modifier.testTag("native-context-return-held")){Text("Return to held review")}
            if(control!=null&&state in listOf("prepared","refused")&&pending==null)
                TextButton({act{session.create(replacing=true);choosing=false;sources=emptyList()}},enabled=!busy,modifier=Modifier.testTag("native-context-edit")){Text("Change selected sources")}
        }
    }
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
        TextButton({act{}},enabled=!busy,modifier=Modifier.testTag("native-context-refresh")){Text("Refresh")}
        if(editable&&preview!=null&&sourceError.isBlank()&&authorized&&configured&&availability?.optBoolean("inferenceBusy")!=true)
            Button({act{session.prepare(preview)}},modifier=Modifier.testTag("native-context-prepare")){Text(if(session.parentId==null)"Prepare window" else "Prepare replacement")}
        if(state=="prepared"&&verified&&authorized&&sourceError.isBlank()&&pending==null)
            Button({act{session.control("approve",control!!)}},enabled=!busy,modifier=Modifier.testTag("native-context-approve")){Text("Approve fresh window")}
        if(state in listOf("requested","prepared","approved","refused")&&verified&&pending==null)
            TextButton({act{session.control("cancel",control!!)}},enabled=!busy,modifier=Modifier.testTag("native-context-cancel")){Text("Cancel review")}
        if(terminal&&pending==null&&authorized&&configured)
            TextButton({act{session.create();choosing=false;sources=emptyList()}},enabled=!busy,modifier=Modifier.testTag("native-context-new")){Text("New review")}
    }
    TextButton(dismiss,enabled=!busy,modifier=Modifier.testTag("native-context-close")){Text("Close")}
    inspected?.let{record->
        AlertDialog(onDismissRequest={inspected=null},title={Text("Source retained verbatim")},text={
            val scroll=androidx.compose.foundation.rememberScrollState()
            Text(record.getJSONObject("content").toString(2),modifier=Modifier.heightIn(max=480.dp).verticalScroll(scroll))
        },confirmButton={TextButton({inspected=null}){Text("Close source")}})
    }
}
