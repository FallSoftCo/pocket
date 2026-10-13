package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** Room creation is metadata, never a task assignment or model admission. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun RoomCreateDialog(client:LosangelexClient,team:String,dismiss:()->Unit,created:(String)->Unit){
    val key="room-create:${client.owner}:$team"
    val saved=remember(key){Pocket.prefs.getString(key,null)?.let{runCatching{JSONObject(it)}.getOrNull()}}
    var command by remember(key){mutableStateOf(saved)}
    var title by remember(key){mutableStateOf(saved?.s("title")?:Pocket.prefs.getString("$key:title","").orEmpty())}
    var purpose by remember(key){mutableStateOf(saved?.s("purpose")?:Pocket.prefs.getString("$key:purpose","").orEmpty())}
    var busy by remember(key){mutableStateOf(false)}
    var error by remember(key){mutableStateOf("")}
    val jobs=rememberCoroutineScope()
    suspend fun resolve(value:JSONObject):JSONObject?{
        val result=client.call("teams/$team/rooms?task=${value.getString("task")}")
        val room=result.optJSONArray("rooms")?.objects()?.firstOrNull{it.s("task")==value.s("task")}?:return null
        check(room.s("title")==value.s("title")&&room.s("purpose")==value.s("purpose")){"This room exists with changed details. Review it before continuing."}
        return room
    }
    suspend fun finish(room:JSONObject){
        check(withContext(Dispatchers.IO){Pocket.prefs.edit().remove(key).remove("$key:title").remove("$key:purpose").commit()}){"Room created; local confirmation could not be saved. Check the room list."}
        created(room.getString("task"))
    }
    LaunchedEffect(key){
        command?.let{value->
            busy=true
            try{resolve(value)?.let{finish(it)}}
            catch(e:Exception){if(e is CancellationException)throw e;error=e.message.orEmpty()}
            finally{busy=false}
        }
    }
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text("New room")},text={
        Column(Modifier.semantics{testTagsAsResourceId=true},verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Describe its purpose. Creating a room does not start agent work.")
            OutlinedTextField(title,{title=it;Pocket.prefs.edit().putString("$key:title",it).apply()},enabled=command==null&&!busy,label={Text("Room name")},modifier=Modifier.testTag("room-create-title"))
            OutlinedTextField(purpose,{purpose=it;Pocket.prefs.edit().putString("$key:purpose",it).apply()},enabled=command==null&&!busy,label={Text("Purpose")},maxLines=6,modifier=Modifier.testTag("room-create-purpose"))
            if(error.isNotBlank())Text(error,color=Coral)
        }
    },confirmButton={
        TextButton({
            busy=true;error=""
            jobs.launch{
                val value=command?:JSONObject().put("task","room-"+UUID.randomUUID().toString()).put("title",title.trim()).put("purpose",purpose.trim())
                try{
                    check(withContext(Dispatchers.IO){Pocket.prefs.edit().putString(key,value.toString()).commit()}){"Could not save this request on the phone; nothing submitted."}
                    command=value
                    val result=resolve(value)?:client.call("teams/$team/rooms",value)
                    finish(result)
                }catch(e:Exception){
                    if(e is CancellationException)throw e
                    if(e is PocketApiException&&e.status in listOf(400,404,422)){
                        withContext(Dispatchers.IO){Pocket.prefs.edit().remove(key).commit()};command=null;error=e.message.orEmpty()
                    }else{
                        try{
                            val existing=resolve(value)
                            if(existing!=null)finish(existing)else error="Unconfirmed. Check or retry this saved room request. ${e.message.orEmpty()}"
                        }catch(checkError:Exception){if(checkError is CancellationException)throw checkError;error="Unconfirmed. Keep this saved request. ${checkError.message.orEmpty()}"}
                    }
                }finally{busy=false}
            }
        },enabled=!busy&&(command!=null||title.isNotBlank()&&title.length<=200&&purpose.isNotBlank()&&purpose.length<=4000),modifier=Modifier.testTag("room-create-submit").semantics{testTagsAsResourceId=true}){Text(if(command==null)"Create room" else "Check saved room")}
    },dismissButton={TextButton(dismiss,enabled=!busy){Text("Close")}})
}
