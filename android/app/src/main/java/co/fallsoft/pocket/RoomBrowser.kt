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

/** Identity is supplied by Hollywood. Duplicate titles never collapse rooms. */
internal fun roomForTask(rooms:List<JSONObject>, task:String):JSONObject? =
    rooms.firstOrNull { it.s("task") == task }

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable internal fun RoomBrowser(
    teams:List<JSONObject>, team:String, rooms:List<JSONObject>, error:String,
    onTeam:(String)->Unit, onRoom:(JSONObject,Boolean)->Unit, onSettings:()->Unit,
    onSession:(JSONObject,String)->Unit,
    onNew:()->Unit
){
    var toolsOpen by remember(team){mutableStateOf(false)}
    var query by remember(team){mutableStateOf("")}
    Column(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}){
        WorkCatalogList(Modifier.weight(1f)){
            if(error.isNotBlank())item{Text(error,color=Coral)}
            if(rooms.isEmpty()&&error.isBlank())item{Text("Loading rooms…",color=Muted)}
            items(rooms.filter{query.isBlank()||it.s("title").contains(query,true)||it.s("purpose").contains(query,true)},key={it.s("id")}){room->
                val group=roomSessionGroup(room)
                SessionGroupCard(group,System.currentTimeMillis(),onChild={onSession(room,it.agentNickname)},parentTitle=group.task.title){badge->
                    TaskCard(group.task,headerBadge=badge,modifier=Modifier.testTag("hollywood-room:"+room.s("id")),
                        onOpen={keyboard->onRoom(room,keyboard)},onRename=null,onWatch=null)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.SpaceBetween){
            UsageDock(Modifier.width(compactUsageDockWidth()).testTag("hollywood-room-tools"),compact=true,statusLabel="Rooms",onOpen={toolsOpen=true})
        }
    }
    if(toolsOpen)ModalBottomSheet(onDismissRequest={toolsOpen=false},containerColor=Panel){
        Column(Modifier.fillMaxWidth().semantics{testTagsAsResourceId=true}.imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(query,{query=it},placeholder={BilingualLabel("Find rooms",centered=false)},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(teams.size>1)TeamChoice(InterfaceTemplate.TEAM_CHOICE_TEAM,teams.map{it.s("id") to it.s("name")},team,Modifier.fillMaxWidth(),onTeam)
            ProfileSwitcher{toolsOpen=false}
            TextButton({toolsOpen=false;onSettings()}){BilingualLabel("Settings")}
            Button({toolsOpen=false;onNew()},enabled=team.isNotBlank(),modifier=Modifier.fillMaxWidth()){BilingualLabel("New room")}
        }
    }
}
