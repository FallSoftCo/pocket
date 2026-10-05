package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlinx.coroutines.launch

fun keepConversationReply(row:JSONObject){val thread=Pocket.selected?:return;Pocket.scope.launch{
    try{val r=Pocket.api("/api/threads/$thread/notes",JSONObject().put("id",row.s("itemId",row.s("id"))).put("turnId",row.s("turnId")).put("text",row.s("text")))
        if(Pocket.selected==thread)Pocket.detail=Pocket.detail?.let{JSONObject(it.toString()).put("notes",r.optJSONArray("notes"))}
    }catch(e:Exception){Pocket.error=PocketNetwork.error(e)}
}}
@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable fun ConversationNotesCard(onReply:(JSONObject)->Unit,onGoTo:(JSONObject)->Unit){
    val thread=Pocket.selected
    val notes=Pocket.detail?.optJSONArray("notes")?.objects().orEmpty()
    var pending by remember(thread){mutableStateOf(setOf<String>())}
    var expanded by remember(thread){mutableStateOf(false)}
    var problem by remember(thread){mutableStateOf<String?>(null)}
    val visible=notes.filter{it.s("id") !in pending}
    val remove:(JSONObject)->Unit={note->
        val id=note.s("id")
        if(thread!=null&&id !in pending){pending=pending+id;problem=null;Pocket.scope.launch{
            try{
                val response=Pocket.api("/api/threads/$thread/notes/remove",JSONObject().put("id",id))
                require(response.optJSONArray("notes")!=null){"Removal was not acknowledged. Try again."}
                if(Pocket.selected==thread)Pocket.detail=Pocket.detail?.let{JSONObject(it.toString()).put("notes",response.optJSONArray("notes"))}
            }catch(e:Exception){problem=PocketNetwork.error(e)}finally{pending=pending-id}
        }}
    }
    if(visible.isEmpty()&&pending.isEmpty()&&problem==null)return
    Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=3.dp)){
        if(visible.isNotEmpty()){
            val first=visible.first()
            LaunchedEffect(first.s("text"),PocketImmersion.enabled){PocketImmersion.offer("note:"+first.s("id"),first.s("text"),"interim reply")}
            Surface(onClick={expanded=true},color=Color(0xff302713),border=BorderStroke(1.dp,Mint.copy(alpha=.7f)),shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth().semantics{contentDescription="Retained answers, ${visible.size}; tap to expand"}){
                Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    SymbolIcon("Codex",null,Modifier.size(20.dp))
                    ImmersionText("note:"+first.s("id"),first.s("text"),rescue=true,fontSize=14.sp,lineHeight=20.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f),kind="interim reply")
                    Text("${visible.size} ›",fontSize=12.sp,color=Mint,modifier=Modifier.semantics{contentDescription="Open ${visible.size} retained answers"})
                }
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                TextButton({onReply(first)}){BilingualLabel("Reply",maxLines=1)}
                TextButton({onGoTo(first)}){BilingualLabel("Go to message",maxLines=1)}
                TextButton({remove(first)}){BilingualLabel("Remove",color=Muted,maxLines=1)}
            }
        }
        if(pending.isNotEmpty())Text("Removing…",color=Muted,fontSize=13.sp)
        problem?.let{Text(it,color=Coral,fontSize=14.sp)}
    }
    if(expanded)ModalBottomSheet(onDismissRequest={expanded=false},containerColor=Panel){
        Text("Answers & notes",fontSize=18.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(horizontal=20.dp,vertical=12.dp))
        problem?.let{Text(it,color=Coral,fontSize=14.sp,modifier=Modifier.padding(horizontal=20.dp))}
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=520.dp).padding(horizontal=20.dp),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            items(visible,key={it.s("id")}){note->
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                    if(note.s("question").isNotBlank())BilingualMessage("note-question:"+note.s("id"),note.s("question"))
                    SelectionContainer{BilingualMessage("note:"+note.s("id"),note.s("text"))}
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                        TextButton({expanded=false;onReply(note)}){BilingualLabel("Reply",maxLines=1)}
                        TextButton({expanded=false;onGoTo(note)}){BilingualLabel("Go to message",maxLines=1)}
                        TextButton({remove(note)}){BilingualLabel("Remove",color=Muted,maxLines=1)}
                    }
                }
            }
        }
    }
}
