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

/** Filters the existing transcript; never displays another copy of its content. */
@Composable internal fun ConversationNotesCard(important:Boolean,onFilter:()->Unit,onControls:()->Unit,speechText:String,status:ConversationRunState){
    val host="filter:${Pocket.local}:${Pocket.base}:${Pocket.token.hashCode()}:${Pocket.selected}"
    DisposableEffect(host){onDispose{PocketSpeech.displayedOwner?.takeIf{it==host}?.let{PocketSpeech.stopDisplayed(it)}}}
    val speaking=PocketSpeech.displayedRunning||PocketSpeech.count>0&&!PocketSpeech.paused
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
        TextButton(onControls,modifier=Modifier.weight(1f).heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=4.dp)){
            SymbolIcon(status.icon,status.label+"; conversation controls",Modifier.size(32.dp),tint=if(status==ConversationRunState.NEEDS_YOU)Coral else Mint,spinning=status.spinning)
            Spacer(Modifier.width(6.dp));BilingualLabel(status.label,centered=false,color=if(status==ConversationRunState.NEEDS_YOU)Coral else Mint,fontSize=13.sp,maxLines=1)
        }
        FilterChip(selected=important,onClick=onFilter,label={BilingualLabel("Important",fontSize=13.sp)},modifier=Modifier.heightIn(min=48.dp))
        IconButton({if(PocketSpeech.displayedRunning)PocketSpeech.displayedOwner?.let{PocketSpeech.stopDisplayed(it)}else if(speaking)PocketSpeech.control("pause")else PocketSpeech.speakDisplayed(host,speechText,"Conversation")},enabled=speaking||speechText.isNotBlank()){
            SymbolIcon(if(speaking)Icons.Rounded.Stop else Icons.Rounded.VolumeUp,if(speaking)"Stop speaking conversation" else "Speak visible conversation",Modifier.size(30.dp),tint=if(speaking)Coral else Mint)
        }
    }
}
internal fun importantConversationKind(kind:String,type:String,status:String,retained:Boolean,phase:String="",latest:Boolean=false)=retained||kind=="request"||kind=="message"&&(phase=="final_answer"||phase.isBlank()&&latest)||kind=="turnEnd"&&status=="failed"||kind=="activity"&&type!="reasoning"&&status in listOf("failed","declined")

internal fun sameConversationContext(note:String,speech:String):Boolean{
    fun normalize(text:String)=text.replace(Regex("[*_`#]"),"").replace(Regex("\\s+")," ").trim().lowercase(java.util.Locale.ROOT)
    val a=normalize(note);val b=normalize(speech)
    return a.isNotBlank()&&b.isNotBlank()&&a==b
}
internal fun canMergeConversationContext(selectedThread:String?,speechThread:String?):Boolean=selectedThread!=null&&selectedThread==speechThread

internal fun conversationSpeechRows(rows:List<JSONObject>,selectedThread:String?,speechThread:String?,speechId:Long,speechText:String,speechTitle:String):List<JSONObject>{
    if(!shouldAppendConversationSpeech(selectedThread,speechThread,speechText,rows.map{it.s("text")}))return rows
    return rows+JSONObject().put("id","speech:$speechId").put("kind","message").put("text",speechText).put("speechThread",speechThread).put("speechTitle",speechTitle)
}

internal fun shouldAppendConversationSpeech(selectedThread:String?,speechThread:String?,speechText:String,rowTexts:List<String>):Boolean=canMergeConversationContext(selectedThread,speechThread)&&speechText.isNotBlank()&&rowTexts.none{sameConversationContext(it,speechText)}
