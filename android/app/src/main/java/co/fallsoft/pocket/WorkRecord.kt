package co.fallsoft.pocket

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Pairing is captured so refresh never follows a profile switch. */
private class WorkRecordClient(val base:String=Pocket.base.trimEnd('/'),val token:String=Pocket.token){
    suspend fun read(before:String?=null):JSONObject=withContext(Dispatchers.IO){
        val url=okhttp3.HttpUrl.Builder().apply {
            val origin=base.toHttpUrl()
            scheme(origin.scheme);host(origin.host);port(origin.port)
            encodedPath(origin.encodedPath.trimEnd('/')+"/api/work-record")
            if(before!=null)addQueryParameter("before",before)
        }.build()
        Pocket.http.newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(Request.Builder().url(url).header("Authorization","Bearer $token").build()).execute().use{response->
            val value=JSONObject(response.body?.string()?:"{}")
            if(!response.isSuccessful)throw PocketApiException(response.code,value.s("error","Work record unavailable"))
            value
        }
    }
}

@Composable fun WorkRecordScreen(onBack:()->Unit){
    val owner=backendOwner(Pocket.base,Pocket.token)
    val client=remember(owner){WorkRecordClient()}
    var rows by remember(owner){mutableStateOf(listOf<JSONObject>())}
    var team by remember(owner){mutableStateOf(listOf<JSONObject>())}
    var voice by remember(owner){mutableStateOf(listOf<JSONObject>())}
    var before by remember(owner){mutableStateOf<String?>(null)}
    var coverage by remember(owner){mutableStateOf("")}
    var error by remember(owner){mutableStateOf("")}
    var busy by remember(owner){mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    suspend fun load(older:Boolean=false){
        if(busy)return
        busy=true;error=""
        try{
            val page=client.read(if(older)before else null)
            val batch=page.optJSONArray("entries")?.objects().orEmpty()
            rows=if(older)(rows+batch).distinctBy{it.s("session")+"/"+it.s("id")}else batch
            if(!older){voice=page.optJSONArray("voice")?.objects().orEmpty();team=page.optJSONArray("team")?.objects().orEmpty()}
            before=if(page.isNull("before"))null else page.s("before").takeIf{it.isNotBlank()}
            val c=page.optJSONObject("coverage")?:JSONObject()
            coverage="${c.optInt("sessions")} conversations · ${c.optInt("backfilling")} filling earlier history"
        }catch(e:Exception){if(e is CancellationException)throw e;error=e.message?:"Work record unavailable"}finally{busy=false}
    }
    LaunchedEffect(owner){load()}
    BackHandler{onBack()}
    Column(Modifier.fillMaxSize().padding(16.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onBack){Text("Back")};TextButton({scope.launch{load()}},enabled=!busy){Text("Refresh")}}
        Text("Work record",style=MaterialTheme.typography.headlineSmall)
        Text("Collected automatically from your requests and the resulting work. Follow-ups stay linked to their conversation.",style=MaterialTheme.typography.bodyMedium)
        Text(coverage,style=MaterialTheme.typography.labelMedium)
        Text("A completed turn records what the agent returned; it does not prove delivery or satisfaction.",style=MaterialTheme.typography.bodySmall)
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)){
            if(rows.isEmpty()&&!busy)item{Text("Your work will appear here automatically as you use NextComp. Earlier followed conversations are filled in gradually.")}
            items(rows,key={it.s("session")+"/"+it.s("id")}){row->
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(row.s("name", "Conversation"),style=MaterialTheme.typography.titleMedium)
                        val at=row.optLong("source_at").takeIf{it>0}?:row.optLong("observed_at")
                        Text((if(row.optLong("source_at")>0)"" else "Observed ")+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(at))+" · "+row.s("status","Observed"),style=MaterialTheme.typography.labelMedium)
                        if(row.s("cwd").isNotBlank())Text(row.s("cwd"),style=MaterialTheme.typography.bodySmall)
                        if(row.optBoolean("continuation"))Text("Follow-up in the same conversation",style=MaterialTheme.typography.labelMedium)
                        val entries=row.optJSONArray("items")?.objects().orEmpty()
                        entries.filter{it.s("kind")=="request"}.forEach{Text("You asked",style=MaterialTheme.typography.labelLarge);Text(it.s("text"))}
                        val answers=entries.filter{it.s("kind")=="output"}
                        val final=answers.filter{it.s("phase")=="final_answer"}.ifEmpty{answers.takeLast(1)}
                        final.forEach{Text(if(it.optBoolean("truncated"))"Agent output · excerpt" else "Agent output",style=MaterialTheme.typography.labelLarge);BilingualMessage("work:${row.s("session")}:${row.s("id")}:${it.s("id")}",it.s("text"))}
                        entries.filter{it.s("kind")=="files"}.forEach{Text("Recorded file changes · ${it.s("status")}",style=MaterialTheme.typography.labelLarge);it.optJSONArray("paths")?.let{paths->for(i in 0 until paths.length())Text(paths.optString(i),style=MaterialTheme.typography.bodySmall)}}
                        val checks=entries.filter{it.s("kind")=="check"}
                        if(checks.isNotEmpty())Text("${checks.size} command results · ${checks.count{!it.isNull("exitCode")&&it.optInt("exitCode")==0}} exited successfully",style=MaterialTheme.typography.bodySmall)
                        entries.filter{it.s("kind")=="artifact"}.forEach{Text("Generated artifact: ${it.s("path")}")}
                        TextButton({Pocket.open(row.s("session"));onBack()}){Text("Open source conversation")}
                    }
                }
            }
            if(before!=null)item{TextButton({scope.launch{load(true)}},enabled=!busy){Text("Earlier work")}}
            if(team.isNotEmpty())item{Text("Recent team work",style=MaterialTheme.typography.titleMedium)}
            items(team,key={"team:"+it.optJSONObject("source")?.s("environment")+":"+it.s("team")+":"+it.s("id")}){row->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("${row.s("team")} · ${row.s("task")} · ${row.s("author")}",style=MaterialTheme.typography.titleMedium)
                Text("Recorded message · ${row.s("visibility")}",style=MaterialTheme.typography.labelMedium)
                BilingualMessage("work-team:${row.optJSONObject("source")?.s("environment")}:${row.s("team")}:${row.s("id")}",row.s("body"))
                Text("Source message ${row.s("id")}",style=MaterialTheme.typography.bodySmall)
            }}}
            if(voice.isNotEmpty())item{Text("Recent voice work",style=MaterialTheme.typography.titleMedium)}
            items(voice,key={"voice:"+it.s("id")}){row->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(row.s("request"));Text("Agent output · "+row.s("status"),style=MaterialTheme.typography.labelMedium);Text(row.s("output"))}}}
        }
    }
}
