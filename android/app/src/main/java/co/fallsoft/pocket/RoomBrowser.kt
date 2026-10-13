package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlinx.coroutines.*

/** Identity is supplied by Hollywood. Duplicate titles never collapse rooms. */
internal fun roomForTask(rooms:List<JSONObject>, task:String):JSONObject? =
    rooms.firstOrNull { it.s("task") == task }

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable internal fun RoomBrowser(
    teams:List<JSONObject>, team:String, rooms:List<JSONObject>, error:String,
    onTeam:(String)->Unit, onRoom:(JSONObject)->Unit, onSettings:()->Unit,
    onNew:()->Unit
){
    Column(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text("Rooms",style=MaterialTheme.typography.headlineSmall)
            TextButton(onSettings){BilingualLabel("Settings")}
        }
        TeamChoice(InterfaceTemplate.TEAM_CHOICE_TEAM,teams.map{it.s("id") to it.s("name")},team,Modifier.fillMaxWidth(),onTeam)
        Text("Choose where to work. Each room keeps its own conversation and context.",style=MaterialTheme.typography.bodyMedium)
        if(error.isNotBlank())Text(error,color=Coral)
        if(rooms.isEmpty()&&error.isBlank())Text("Loading rooms…",color=Muted)
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)){
            items(rooms,key={it.s("id")}){room->
                OutlinedCard(onClick={onRoom(room)},modifier=Modifier.fillMaxWidth().testTag("hollywood-room:"+room.s("id"))){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text(room.s("title"),style=MaterialTheme.typography.titleMedium)
                        Text(room.s("purpose").ifBlank{"Purpose has not been set yet."},style=MaterialTheme.typography.bodyMedium)
                        val members=room.optJSONArray("members")?.objects().orEmpty()
                        val working=members.count{it.s("status")=="working"}
                        Text("${members.size} members"+(if(working>0)" · $working working" else ""),style=MaterialTheme.typography.labelMedium,color=Mint)
                        // A legacy key also distinguishes the lobby from similarly named work.
                        Text(room.s("task"),style=MaterialTheme.typography.labelSmall,color=Muted)
                    }
                }
            }
        }
        TextButton(onNew,enabled=team.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("New room")}
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun RoomMembers(client:LosangelexClient,team:String,room:JSONObject?,onDismiss:()->Unit){
    val jobs=rememberCoroutineScope()
    val roomId=room?.s("id").orEmpty()
    var shown by remember(roomId){mutableStateOf(room)}
    var actors by remember(roomId){mutableStateOf(emptyList<JSONObject>())}
    var error by remember(roomId){mutableStateOf("")}
    var busy by remember(roomId){mutableStateOf(false)}
    LaunchedEffect(room){
        if(!busy&&room?.s("id")==roomId){
            shown=room
            val observed=room?.optJSONArray("members")?.objects().orEmpty()
            var reconciled=false
            observed.forEach{member->
                val key="room-membership:${client.owner}:$roomId:${member.s("id")}" 
                if(Pocket.prefs.contains(key)){Pocket.prefs.edit().remove(key).commit();reconciled=true}
            }
            if(reconciled)error=""
        }
    }
    LaunchedEffect(roomId){
        try{
            val found=mutableListOf<JSONObject>();var cursor="";val seen=mutableSetOf<String>()
            do{
                val page=client.call("teams/$team/actors?limit=100"+if(cursor.isBlank())"" else "&cursor=$cursor")
                found+=page.optJSONArray("actors")?.objects().orEmpty();cursor=page.s("next_cursor")
                if(cursor.isNotBlank()&&!seen.add(cursor))throw Exception("Repeated actor cursor")
            }while(cursor.isNotBlank())
            actors=found
        }catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Room members")},text={
        LazyColumn(Modifier.heightIn(max=420.dp).semantics{testTagsAsResourceId=true},verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{Text("Membership does not start work. Each room retains a separate execution context.",style=MaterialTheme.typography.bodySmall)}
            if(error.isNotBlank())item{Text(error,color=Coral)}
            items(shown?.optJSONArray("members")?.objects().orEmpty(),key={it.s("id")}){member->
                Column{
                    Text(member.s("agent"),style=MaterialTheme.typography.titleSmall)
                    Text(member.s("role"),style=MaterialTheme.typography.bodySmall)
                    Text(if(member.s("thread").isBlank())"No execution context started" else if(member.optInt("paused")==1)"Paused" else member.s("status"),style=MaterialTheme.typography.labelSmall,color=Muted)
                }
            }
            val memberIds=shown?.optJSONArray("members")?.objects().orEmpty().map{it.s("id")}.toSet()
            items(actors.filter{it.s("id") !in memberIds},key={"join:"+it.s("id")}){actor->
                TextButton({
                    val path="teams/$team/rooms/$roomId/members/${actor.s("id")}" 
                    val key="room-membership:${client.owner}:$roomId:${actor.s("id")}" 
                    // A separate desired-state journal never overwrites pending message commands.
                    Pocket.prefs.edit().putString(key,path).commit()
                    busy=true
                    jobs.launch{
                        try{
                            shown=client.call(path,JSONObject(),"PUT")
                            Pocket.prefs.edit().remove(key).commit();error=""
                        }catch(e:Exception){
                            if(e is CancellationException)throw e
                            error="Membership unconfirmed. Reopen this room to check; adding the same member is safe to retry."
                        }finally{busy=false}
                    }
                },enabled=!busy&&roomId.isNotBlank(),modifier=Modifier.testTag("room-join:"+actor.s("id"))){Text("Add ${actor.s("agent")} to room")}
            }
        }
    },confirmButton={TextButton(onDismiss){BilingualLabel("Close")}})
}
