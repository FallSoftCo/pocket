package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Read-only projection of Hollywood business state. Reading never admits work. */
@Composable internal fun HollywoodWorkDialog(client:HollywoodClient,room:String,dismiss:()->Unit){
    val jobs=rememberCoroutineScope()
    var goals by remember(client.bindingId,room){mutableStateOf(emptyList<JSONObject>())}
    var work by remember(client.bindingId,room){mutableStateOf(emptyList<JSONObject>())}
    var goalCursor by remember(client.bindingId,room){mutableStateOf<String?>(null)}
    var workCursor by remember(client.bindingId,room){mutableStateOf<String?>(null)}
    var selected by remember(client.bindingId,room){mutableStateOf<Pair<String,String>?>(null)}
    var detail by remember(client.bindingId,room){mutableStateOf<JSONObject?>(null)}
    var plan by remember(client.bindingId,room){mutableStateOf(emptyList<JSONObject>())}
    var planCursor by remember(client.bindingId,room){mutableStateOf<String?>(null)}
    var fullObjective by remember(selected){mutableStateOf(false)}
    var error by remember(client.bindingId,room){mutableStateOf("")}
    var loading by remember(client.bindingId,room){mutableStateOf(true)}
    suspend fun page(kind:String,after:String?=null){
        val value=client.call("rooms/$room/$kind?limit=50"+(after?.let{"&after=$it"}.orEmpty()))
        val rows=value.getJSONArray(kind).objects()
        val cursor=value.s("nextCursor").takeIf(::hollywoodUuid)
        if(kind=="goals"){goals=(if(after==null)rows else goals+rows).distinctBy{it.s("id")};goalCursor=cursor}
        else {work=(if(after==null)rows else work+rows).distinctBy{it.s("id")};workCursor=cursor}
    }
    suspend fun show(target:Pair<String,String>){
        val value=client.call("${target.first}/${target.second}")
        val planPage=if(target.first=="goals")client.call("goals/${target.second}/work?limit=50")else null
        if(selected==target){detail=value;plan=planPage?.getJSONArray("work")?.objects().orEmpty();planCursor=planPage?.s("nextCursor")?.takeIf(::hollywoodUuid)}
    }
    fun select(kind:String,id:String){selected=kind to id;detail=null;error=""}
    LaunchedEffect(client.bindingId,room,selected){
        loading=true
        try{val target=selected;if(target==null){page("goals");page("work")}else show(target);error=""}
        catch(e:Exception){if(e is CancellationException)throw e;detail=null;goals=emptyList();work=emptyList();plan=emptyList();error=e.message.orEmpty()}
        finally{loading=false}
    }
    fun more(kind:String,cursor:String){jobs.launch{try{page(kind,cursor);error=""}catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}}}
    AlertDialog(onDismissRequest=dismiss,title={Text(if(selected==null)"Goals & work" else if(selected?.first=="goals")"Goal & plan" else "Work")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()).testTag("native-room-work-body"),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(loading)Text("Loading…")
            if(error.isNotBlank())Text(error,color=Coral)
            if(selected==null){
                Text("Goals",style=MaterialTheme.typography.titleSmall)
                if(goals.isEmpty()&&!loading&&error.isBlank())Text("No goals in this room",color=Muted)
                goals.forEach{g->TextButton({select("goals",g.s("id"))},modifier=Modifier.testTag("native-goal:"+g.s("id"))){Text("${g.s("title")} · ${g.s("state")}")}}
                goalCursor?.let{cursor->TextButton({more("goals",cursor)}){Text("More goals")}}
                Text("Assigned work",style=MaterialTheme.typography.titleSmall)
                if(work.isEmpty()&&!loading&&error.isBlank())Text("No assigned work in this room",color=Muted)
                work.forEach{w->TextButton({select("work",w.s("id"))},modifier=Modifier.testTag("native-work:"+w.s("id"))){Text("${w.s("specification").take(120)} · ${w.s("state")}")}}
                workCursor?.let{cursor->TextButton({more("work",cursor)}){Text("More work")}}
            }else detail?.let{value->
                if(selected?.first=="goals"){
                    val goal=value.getJSONObject("goal")
                    Text(goal.s("title"),style=MaterialTheme.typography.titleSmall)
                    Text("Outcome · ${goal.s("state")} · revision ${goal.optLong("revision")}",color=Mint)
                    value.optJSONObject("cooperation")?.let{team->
                        Text(if(team.s("policy")=="kanban_pull")"Work allocation · team claims ready work" else "Work allocation · assigned work",color=Muted)
                        Text(if(team.isNull("reviewerId"))"Review · goal manager" else "Review · independent verifier",color=Muted)
                    }
                    HollywoodCriteria(goal)
                    Text(goal.s("objective"),maxLines=if(fullObjective)Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis)
                    TextButton({fullObjective=!fullObjective}){Text(if(fullObjective)"Condense objective" else "Full objective")}
                    Text("Plan · accountable work",style=MaterialTheme.typography.titleSmall)
                    plan.forEach{w->TextButton({select("work",w.s("id"))}){Text("${w.s("specification").take(120)} · ${w.s("state")}")}}
                    planCursor?.let{cursor->TextButton({val target=selected!!;jobs.launch{try{val next=client.call("goals/${target.second}/work?limit=50&after=$cursor");if(selected==target){plan=(plan+next.getJSONArray("work").objects()).distinctBy{it.s("id")};planCursor=next.s("nextCursor").takeIf(::hollywoodUuid)}}catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}}}){Text("More plan work")}}
                }else {
                    val item=value.getJSONObject("work");val execution=value.optJSONObject("execution")
                    Text("Acceptance · ${item.s("state")} · revision ${item.optLong("revision")}",color=Mint)
                    Text("Execution · ${execution?.s("state")?:"not admitted"}",color=Muted)
                    Text(item.s("specification"),maxLines=if(fullObjective)Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis)
                    TextButton({fullObjective=!fullObjective}){Text(if(fullObjective)"Condense assignment" else "Full assignment")}
                    HollywoodCriteria(item)
                    if(item.s("note").isNotBlank())Text(item.s("note"))
                    item.optJSONArray("evidence")?.objects().orEmpty().forEach{e->
                        Text("${e.s("relativePath")} · ${e.optLong("size")} bytes")
                        Text("Host: ${e.s("hostId")}\nWorkspace: ${e.s("workspaceId")}\nSHA-256: ${e.s("sha256")}",color=Muted,style=MaterialTheme.typography.bodySmall)
                    }
                    Text("Owner: ${if(item.isNull("owner"))"Unclaimed" else item.s("owner")}\nGoal: ${item.s("goal")}",color=Muted,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    },confirmButton={TextButton(dismiss){BilingualLabel("Close")}},dismissButton={
        if(selected!=null)TextButton({selected=null;detail=null;plan=emptyList()}){BilingualLabel("Back")}
        else TextButton({loading=true;jobs.launch{try{page("goals");page("work");error=""}catch(e:Exception){if(e is CancellationException)throw e;goals=emptyList();work=emptyList();error=e.message.orEmpty()}finally{loading=false}}}){BilingualLabel("Refresh")}
    })
}

@Composable private fun HollywoodCriteria(value:JSONObject){
    if(value.s("acceptance").isNotBlank()){
        Text("Acceptance criteria",style=MaterialTheme.typography.labelLarge)
        Text(value.s("acceptance"))
    }
}
