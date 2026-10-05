package co.fallsoft.pocket

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

fun keepConversationReply(row:JSONObject){val thread=Pocket.selected?:return;Pocket.scope.launch{
    try{val r=Pocket.api("/api/threads/$thread/notes",JSONObject().put("id",row.s("itemId",row.s("id"))).put("turnId",row.s("turnId")).put("text",row.s("text")))
        if(Pocket.selected==thread)Pocket.detail=Pocket.detail?.let{JSONObject(it.toString()).put("notes",r.optJSONArray("notes"))}
    }catch(e:Exception){Pocket.error=PocketNetwork.error(e)}
}}
internal data class ConversationContextEntry(val id:String,val text:String,val label:String,val thread:String?,val sourceId:String="",val note:JSONObject?=null,val owner:String?=null,val captionId:Long?=null)
internal fun uniqueConversationContexts(entries:List<ConversationContextEntry>):List<ConversationContextEntry>{
    val unique=mutableListOf<ConversationContextEntry>()
    entries.filter{it.text.isNotBlank()}.forEach{entry->
        val at=unique.indexOfFirst{canMergeConversationContext(it.thread,entry.thread)&&sameConversationContext(it.text,entry.text)}
        if(at<0)unique.add(entry) else {val old=unique[at];unique[at]=old.copy(id=if(entry.note!=null)entry.id else old.id,label=if(entry.note!=null)entry.label else old.label,note=entry.note?:old.note,sourceId=if(entry.note!=null)entry.sourceId else old.sourceId.ifBlank{entry.sourceId},owner=entry.owner?:old.owner,captionId=entry.captionId?:old.captionId)}
    }
    return unique
}

/** One selected passage and its actions. No second caption, player or notes modal. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ConversationContextHost(entries:List<ConversationContextEntry>,host:String,sourceVisible:(ConversationContextEntry)->Boolean={false},onReply:((JSONObject)->Unit)?=null,onGoTo:((ConversationContextEntry)->Unit)?=null,onRemove:((JSONObject)->Unit)?=null,onControls:(()->Unit)?=null,problem:String?=null){
    val choices=uniqueConversationContexts(entries)
    var selected by remember(host){mutableStateOf<String?>(null)}
    var expanded by remember(host){mutableStateOf(false)}
    val entry=choices.firstOrNull{it.id==selected}?:choices.firstOrNull()
    val owner=entry?.owner?:"$host:${entry?.id.orEmpty()}"
    val plannedText=entry?.let{PocketImmersion.display("context:"+it.id,it.text)}.orEmpty()
    var renderedText by remember(host,entry?.id){mutableStateOf<Pair<String,String>?>(null)}
    val displayedText=renderedText?.takeIf{it.first==plannedText}?.second?:plannedText
    LaunchedEffect(PocketSpeech.displayedOwner,PocketSpeech.displayedRunning){
        if(PocketSpeech.displayedRunning&&PocketSpeech.displayedOwner?.startsWith("$host:")==true){choices.firstOrNull{it.owner==PocketSpeech.displayedOwner}?.let{selected=it.id}}
    }
    DisposableEffect(host){onDispose{PocketSpeech.displayedOwner?.takeIf{it.startsWith("$host:")}?.let{PocketSpeech.stopDisplayed(it)}}}
    val manual=PocketSpeech.displayedRunning&&PocketSpeech.displayedOwner!=null
    val caption=PocketSpeechCaptions.state
    val automatic=PocketSpeech.displayedOwner==null&&PocketSpeech.count>0&&!PocketSpeech.paused
    val speaking=manual||automatic
    val stop:()->Unit={if(manual)PocketSpeech.displayedOwner?.let{PocketSpeech.stopDisplayed(it)}else if(automatic)PocketSpeech.control("pause")}
    Surface(color=if(entry?.note!=null)androidx.compose.ui.graphics.Color(0xff302713)else Panel,border=BorderStroke(1.dp,if(entry?.note!=null)Mint.copy(alpha=.65f)else Line),shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp).semantics{contentDescription="Conversation context"}){
        Column(Modifier.padding(horizontal=8.dp,vertical=4.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                TextButton({if(choices.size>1){stop();selected=choices[(choices.indexOf(entry)+1)%choices.size].id;expanded=false}},modifier=Modifier.weight(1f).heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=4.dp)){
                    BilingualLabel(entry?.let{it.label+if(choices.size>1)" · ${choices.indexOf(it)+1}/${choices.size} ›" else ""}?:"Ready",centered=false,color=if(entry?.note!=null)Mint else Muted,fontSize=13.sp,maxLines=1)
                }
                if(entry!=null)IconButton({expanded=!expanded}){SymbolIcon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Collapse context" else "Read full context",Modifier.size(24.dp))}
                onControls?.let{open->IconButton(open){SymbolIcon(Icons.Rounded.Tune,"Conversation controls",Modifier.size(24.dp))}}
            }
            if(entry!=null){
                val showReference=!expanded&&sourceVisible(entry)
                val preview:@Composable (Modifier)->Unit={modifier->
                    if(showReference)BilingualLabel("Shown in conversation above",modifier,centered=false,color=Muted,fontSize=14.sp)
                    else if(expanded)Column(modifier.heightIn(max=(LocalConfiguration.current.screenHeightDp*.30f).dp).verticalScroll(rememberScrollState())){BilingualMessage("context:"+entry.id,entry.text,onDisplayedText={renderedText=plannedText to it})}
                    else ImmersionText("context:"+entry.id,entry.text,modifier=modifier,fontSize=14.sp,lineHeight=20.sp,maxLines=3,overflow=TextOverflow.Ellipsis,onDisplayedText={renderedText=plannedText to it})
                }
                val actions:@Composable ()->Unit={
                    if(entry.note!=null&&onReply!=null)IconButton({onReply(entry.note)}){SymbolIcon(Icons.Rounded.ArrowUpward,"Reply to this answer",Modifier.size(28.dp))}
                    if(onGoTo!=null&&(entry.sourceId.isNotBlank()||entry.thread!=null&&entry.thread!=Pocket.selected))IconButton({onGoTo(entry)}){SymbolIcon(Icons.Rounded.OpenInNew,"Go to source",Modifier.size(28.dp))}
                    if(expanded&&entry.note!=null&&onRemove!=null)IconButton({onRemove(entry.note)}){Icon(Icons.Rounded.DeleteOutline,"Remove retained answer",Modifier.size(24.dp),tint=Muted)}
                    IconButton({if(speaking)stop()else if(entry.captionId!=null&&PocketSpeech.queue.current?.id==entry.captionId&&PocketSpeech.paused)PocketSpeech.control("resume")else PocketSpeech.speakDisplayed(owner,displayedText.ifBlank{entry.text},entry.label)},modifier=Modifier.size(48.dp)){
                        SymbolIcon(if(speaking)Icons.Rounded.Stop else Icons.Rounded.VolumeUp,if(speaking)"Stop speaking context" else "Speak this context",Modifier.size(30.dp),tint=if(speaking)Coral else Mint)
                    }
                }
                BoxWithConstraints{
                    if(expanded||LocalDensity.current.fontScale>1.4f||maxWidth<350.dp)Column{preview(Modifier.fillMaxWidth());FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){actions()}}
                    else Row(verticalAlignment=Alignment.CenterVertically){preview(Modifier.weight(1f));Row{actions()}}
                }
            }
            problem?.takeIf{it.isNotBlank()}?.let{Text(it,color=Coral,fontSize=13.sp)}
            PocketSpeech.displayedProblem.takeIf{PocketSpeech.displayedOwner?.startsWith("$host:")==true&&it.isNotBlank()}?.let{Text(it,color=Coral,fontSize=13.sp)}
        }
    }
}

@Composable internal fun ConversationNotesCard(active:Boolean,onReply:(JSONObject)->Unit,onGoTo:(JSONObject)->Unit,onControls:()->Unit,sourceVisible:(ConversationContextEntry)->Boolean){
    val thread=Pocket.selected
    val host="context:${Pocket.local}:${Pocket.base}:${Pocket.token.hashCode()}:${thread.orEmpty()}"
    val notes=Pocket.detail?.optJSONArray("notes")?.objects().orEmpty()
    var pending by remember(host){mutableStateOf(setOf<String>())}
    var problem by remember(host){mutableStateOf<String?>(null)}
    val entries=mutableListOf<ConversationContextEntry>()
    val task=Pocket.tasks.firstOrNull{it.id==thread}
    if(active&&task?.previewRole in listOf("activity","assistant")&&!task?.preview.isNullOrBlank())entries.add(ConversationContextEntry("activity:$thread",task!!.preview,"Current action",thread))
    notes.filter{it.s("id") !in pending}.forEach{note->entries.add(ConversationContextEntry("note:"+note.s("id"),note.s("text"),"Saved answer",thread,note.s("id"),note))}
    PocketTranscript.rows.lastOrNull{it.s("kind")=="message"&&it.s("text").isNotBlank()}?.let{row->entries.add(ConversationContextEntry("reply:"+row.s("id"),row.s("text"),"Latest reply",thread,row.s("itemId",row.s("id"))))}
    val manualOwner=PocketSpeech.displayedOwner
    if(manualOwner?.startsWith("$host:")==true&&PocketSpeech.displayedRunning)entries.add(ConversationContextEntry("manual:$manualOwner",PocketSpeech.displayedText,PocketSpeech.displayedTitle,thread,owner=manualOwner))
    val caption=PocketSpeechCaptions.state
    if(caption.visible&&PocketSpeech.queue.current?.id!=caption.id&&!(caption.id<0&&manualOwner?.startsWith("$host:")==true))entries.add(ConversationContextEntry("caption:${caption.id}",caption.text,caption.title,PocketNotificationTitles.threadForId(caption.id),captionId=caption.id))
    if(manualOwner==null)PocketSpeech.queue.current?.takeIf{it.text.isNotBlank()}?.let{speech->entries.add(ConversationContextEntry("caption:${speech.id}",speech.text,speech.title,PocketNotificationTitles.threadForId(speech.id),captionId=speech.id))}
    ConversationContextHost(entries,host,sourceVisible,onReply,onGoTo={entry->if(entry.thread!=null&&entry.thread!=thread)Pocket.open(entry.thread)else onGoTo(entry.note?:JSONObject().put("id",entry.sourceId).put("text",entry.text))},onRemove={note->
        val id=note.s("id");if(thread!=null&&id !in pending){pending=pending+id;problem=null;Pocket.scope.launch{
            try{val response=Pocket.api("/api/threads/$thread/notes/remove",JSONObject().put("id",id));require(response.optJSONArray("notes")!=null){"Removal was not acknowledged. Try again."};if(Pocket.selected==thread)Pocket.detail=Pocket.detail?.let{JSONObject(it.toString()).put("notes",response.optJSONArray("notes"))}}
            catch(e:Exception){if(Pocket.selected==thread)problem=PocketNetwork.error(e)}finally{pending=pending-id}
        }}
    },onControls=onControls,problem=problem)
}

internal fun sameConversationContext(note:String,speech:String):Boolean{
    fun normalize(text:String)=text.replace(Regex("[*_`#]"),"").replace(Regex("\\s+")," ").trim().lowercase(java.util.Locale.ROOT)
    val a=normalize(note);val b=normalize(speech)
    return a.isNotBlank()&&b.isNotBlank()&&a==b
}
internal fun canMergeConversationContext(selectedThread:String?,speechThread:String?):Boolean=selectedThread!=null&&selectedThread==speechThread
