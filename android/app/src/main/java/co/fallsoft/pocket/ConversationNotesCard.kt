package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ConversationNotesCard(){
    val notes=Pocket.detail?.optJSONArray("notes")?.objects().orEmpty()
    if(notes.isEmpty())return
    var expanded by remember(Pocket.selected){mutableStateOf(false)}
    val first=notes.first()
    val id="note:"+first.s("id")
    LaunchedEffect(first.s("text"),PocketImmersion.enabled){PocketImmersion.offer(id,first.s("text"),"interim reply")}
    Surface(onClick={expanded=true},color=Panel,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)){
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
                SymbolIcon("Codex",null,Modifier.size(18.dp));Text("Answers & notes",fontSize=12.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f));Text("${notes.size}",fontSize=11.sp,color=Mint)
            }
            Text(PocketImmersion.display(id,first.s("text")),fontSize=13.sp,lineHeight=18.sp,minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
    if(expanded)ModalBottomSheet(onDismissRequest={expanded=false},containerColor=Panel){
        Text("Answers & notes",fontSize=18.sp,fontWeight=FontWeight.Medium,modifier=Modifier.padding(horizontal=20.dp,vertical=12.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=520.dp).padding(horizontal=20.dp),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            items(notes,key={it.s("id")}){n->
                val noteId="note:"+n.s("id")
                LaunchedEffect(n.s("text"),PocketImmersion.enabled){PocketImmersion.offer(noteId,n.s("text"),"interim reply")}
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    if(n.s("question").isNotBlank())Text(n.s("question"),color=Muted,fontSize=12.sp)
                    SelectionContainer{RichText(PocketImmersion.display(noteId,n.s("text")))}
                    Row{
                        if(PocketImmersion.enabled)TextButton({PocketImmersion.revealOriginal(noteId)}){Text("Original / Italiano")}
                        TextButton({val thread=Pocket.selected?:return@TextButton;Pocket.scope.launch{try{val r=Pocket.api("/api/threads/$thread/notes/remove",JSONObject().put("id",n.s("id")));if(Pocket.selected==thread)Pocket.detail=Pocket.detail?.let{JSONObject(it.toString()).put("notes",r.optJSONArray("notes"))}}catch(e:Exception){Pocket.error=PocketNetwork.error(e)}}}){Text("Remove",color=Muted)}
                    }
                }
            }
        }
    }
}
