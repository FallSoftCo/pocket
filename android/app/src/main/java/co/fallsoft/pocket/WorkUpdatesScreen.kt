package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable internal fun WorkUpdatesEntry(){
    LaunchedEffect(Pocket.local,Pocket.token){while(true){PocketWorkUpdates.refresh();delay(60000)}}
    TextButton({PocketWorkUpdates.open()},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),contentPadding=PaddingValues(horizontal=20.dp)){
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
            BilingualLabel("Work updates"+(if(PocketWorkUpdates.unreadCount>0)" · ${PocketWorkUpdates.unreadCount} unread" else ""),centered=false)
            val preview=PocketWorkUpdates.latest?.response?.lineSequence()?.filter{it.isNotBlank()&&it.trim()!="Check-in"}?.take(2)?.joinToString(" · ").orEmpty()
            if(preview.isNotBlank())WorkflowText(preview,color=Muted,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable internal fun WorkUpdatesScreen(){
    val list=rememberLazyListState();val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val coverage=remember(Pocket.local,Pocket.token,BlackoutVisibility.active){CoordinatorReadingCoverage()}
    var initialized by remember(Pocket.local,Pocket.token){mutableStateOf(false)}
    ConversationBack{PocketWorkUpdates.close()}
    LaunchedEffect(Unit){PocketWorkUpdates.refresh()}
    LaunchedEffect(list,lifecycle,coverage){lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){snapshotFlow{
        if(BlackoutVisibility.active)emptyList() else list.layoutInfo.let{l->l.visibleItemsInfo.filter{it.key.toString().startsWith("report:")}.map{CoordinatorItemExposure(it.key.toString().substringAfter(":"),it.offset,it.size,l.viewportStartOffset,l.viewportEndOffset)}}
    }.collect{items->if(initialized&&PocketVoice.foreground&&!BlackoutVisibility.active)items.forEach{if(coverage.expose(it.id,it.offset,it.size,it.start,it.end))PocketWorkUpdates.presented(it.id)}}}}
    LaunchedEffect(list,PocketWorkUpdates.hasEarlier){snapshotFlow{list.firstVisibleItemIndex}.collect{if(initialized&&it<3&&PocketWorkUpdates.hasEarlier)PocketWorkUpdates.refresh(true)}}
    LaunchedEffect(list){snapshotFlow{list.layoutInfo.totalItemsCount}.collect{count->if(!initialized&&count>0&&PocketWorkUpdates.reports.isNotEmpty()){list.scrollToItem(count-1);initialized=true}}}
    Column(Modifier.fillMaxSize()){
        BilingualLabel("Work updates",modifier=Modifier.padding(horizontal=20.dp,vertical=12.dp),centered=false)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
            if(PocketWorkUpdates.problem.isNotBlank())item{TextButton({PocketWorkUpdates.refresh()}){WorkflowText(PocketWorkUpdates.problem,color=Coral)}}
            if(PocketWorkUpdates.reports.isEmpty()&&!PocketWorkUpdates.loading)item{WorkflowText("No work updates yet",color=Muted)}
            items(PocketWorkUpdates.reports,key={"report:${it.id}"}){report->
                val speechOwner="work-update:${report.id}"
                var displayedText by remember(report.id,report.response){mutableStateOf(report.response)}
                Column{
                Row(verticalAlignment=Alignment.CenterVertically){WorkflowText(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(report.createdAt)),modifier=Modifier.weight(1f),color=Muted,fontSize=14.sp)
                    TextButton({PocketSpeech.speakDisplayed(speechOwner,displayedText,"Work updates")}){BilingualLabel("Listen")}}
                BilingualMessage(speechOwner,report.response,onDisplayedText={displayedText=it})
                routeReceipts(report.actions,report.id).forEach{route->CoordinatorRouteReceipt(route,{PocketWorkUpdates.close();if(PocketVoice.active)PocketVoice.stop();Pocket.open(route.threadId)},{})}
            }}
        }
        InlineCaptureStatus()
        ConversationSpeechDock()
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){VoiceLaunchButton(modifier=Modifier.weight(1f),threadId=null,bar=true);InlineSpeechVolume(Modifier.weight(1f))}
        Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            ChatActionButton("Back",Icons.Rounded.ArrowBack,Modifier.weight(1f),{PocketWorkUpdates.close()});UsageDock(Modifier.width(usageDockWidth()),conversationOnly=true)
        }
    }
}
