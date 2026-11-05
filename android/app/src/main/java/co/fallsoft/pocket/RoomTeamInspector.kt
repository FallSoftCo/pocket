package co.fallsoft.pocket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

/** Read-only team communication, separate from human input and model admission. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RoomTeamInspector(client:HollywoodClient,room:String,names:Map<String,String>,dismiss:()->Unit){
    var archive by remember(room){mutableStateOf(false)}
    val view=if(archive)"all" else "coordination"
    val feed=remember(client,room,view){HollywoodRoomFeed(room,view,client.historyKey(room,view)){client.call(it)}}
    val jobs=rememberCoroutineScope()
    var expanded by remember(room,view){mutableStateOf(emptySet<String>())}
    LaunchedEffect(feed){feed.refreshHistory(preferTail=true);while(isActive){delay(3000);feed.refreshHistory()}}
    val rows=remember(feed.events,archive){
        if(archive)feed.events else feed.events.filter{it.optJSONObject("data")?.optJSONObject("coordination")?.s("type")!="wake"}
    }
    ModalBottomSheet(onDismissRequest=dismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),modifier=Modifier.testTag("native-team-inspector")){
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal=16.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton({archive=false},Modifier.weight(1f)){BilingualLabel("Team")}
                TextButton({archive=true},Modifier.weight(1f)){BilingualLabel("Earlier room")}
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)){
                if(feed.loading&&rows.isEmpty())item{BilingualLabel("Loading…",centered=false)}
                if(!feed.loading&&rows.isEmpty())item{BilingualLabel("No team updates",centered=false)}
                items(rows,key={it.s("id")}){event->
                    val id=event.s("id");val open=id in expanded
                    Column(Modifier.fillMaxWidth().clickable{expanded=if(open)expanded-id else expanded+id}
                        .testTag("native-team-event:$id").padding(vertical=4.dp)){
                        Text(names[event.s("author")]?:"Agent",color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                        BilingualLabel(roomTeamLabel(event),color=Muted,centered=false)
                        if(open)TranscriptRow(JSONObject().put("id","team:"+id).put("kind","message").put("text",event.s("body")),showSpeaker=false)
                        else Text(event.s("body"),maxLines=4,overflow=TextOverflow.Ellipsis)
                        if(open)event.optJSONObject("data")?.optJSONObject("coordination")?.optJSONArray("evidence")?.let{refs->
                            repeat(refs.length()){Text(refs.optString(it),color=Muted)}
                        }
                    }
                }
            }
            InlineCaptureStatus(modifier=Modifier.testTag("native-team-capture-status"))
            if(feed.historyError.isNotBlank())Text(feed.historyError,color=Coral)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(feed.older!=null)TextButton({jobs.launch{feed.loadOlder()}},Modifier.weight(1f),enabled=!feed.loadingOlder){BilingualLabel("Earlier updates")}
                TextButton(dismiss,Modifier.weight(1f).heightIn(min=48.dp).testTag("native-team-close")){BilingualLabel("Close")}
            }
        }
    }
}
