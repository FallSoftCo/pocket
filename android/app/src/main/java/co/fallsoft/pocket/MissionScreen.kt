package co.fallsoft.pocket

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay

/** The current work as readable sentences in a quiet spatial field, with no nested card chrome. */
@Composable fun MissionScreen(workingOnly:Boolean=false,refreshRequest:Int=0) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var displayed by remember(Pocket.local,Pocket.token) { mutableStateOf(emptyList<MissionItem>()) }
    LaunchedEffect(Pocket.local,Pocket.token) { while(true){Pocket.refresh();delay(5_000)} }
    LaunchedEffect(Pocket.local,Pocket.token) {
        while(true){
            now=System.currentTimeMillis()
            val known=Pocket.tasks.associateBy{it.id}
            val activities=Pocket.activities.mapNotNull { a -> known[a.s("threadId")]?.let { t ->
                MissionItem(t.id,t.title,t.cwd,a.s("preview"),a.s("previewKind"),
                    a.s("state")=="working"&&t.status=="active",a.optLong("at"),a.s("state")=="failed")
            } }
            val sessions=Pocket.tasks.filterNot{it.archived}.map { t ->
                MissionItem(t.id,t.title,t.cwd,t.preview,t.previewKind,t.status=="active",
                    if(t.updated<100_000_000_000L)t.updated*1_000 else t.updated)
            }
            displayed=missionItems(activities,sessions,now)
            delay(if(displayed.count{it.working}>6)1_600 else 1_000)
        }
    }
    LaunchedEffect(refreshRequest){if(refreshRequest>0)Pocket.refresh()}
    val visible=displayed.filter{(!workingOnly||it.working)&&it.text.isNotBlank()}
    val density=LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal=deviceCornerInset(),vertical=12.dp)) {
        var previous by remember(Pocket.local,Pocket.token){mutableStateOf(emptyList<MissionPlacement>())}
        val placements=remember(previous,visible.map{it.threadId},maxWidth,maxHeight,density.fontScale){
            missionField(previous,visible,maxWidth.value,(maxHeight-38.dp).value,density.fontScale)
        }
        SideEffect{if(previous!=placements)previous=placements}
        val byId=visible.associateBy{it.threadId}
        placements.forEach { p ->
            val item=byId[p.threadId]?:return@forEach
            MissionSentence(item,now,Modifier.offset{IntOffset(p.x.dp.roundToPx(),p.y.dp.roundToPx())}.size(p.width.dp,p.height.dp))
        }
        if(visible.isEmpty())Text("No recent activity",color=Muted,fontSize=15.sp,modifier=Modifier.align(Alignment.Center))
        val overflow=(visible.size-placements.size).coerceAtLeast(0)
        if(overflow>0)TextButton({Pocket.tab=0},modifier=Modifier.align(Alignment.BottomEnd)) {
            Text("+$overflow sessions",color=Muted,fontSize=12.sp)
        }
    }
}

@Composable private fun MissionSentence(item:MissionItem,now:Long,modifier:Modifier) {
    val sourceId="mission:${item.threadId}"
    LaunchedEffect(sourceId,item.text,PocketImmersion.enabled){PocketImmersion.offer(sourceId,item.text,item.kind)}
    val text=PocketImmersion.display(sourceId,item.text)
    val age=(now-item.at).coerceAtLeast(0)
    val alpha by animateFloatAsState(if(item.working||item.failed)1f else
        (1f-age.coerceAtMost(90_000).toFloat()/90_000*.25f),tween(650),label="mission recency")
    val accent=if(item.failed)Coral else sessionAgeColor(item.at)
    Column(modifier.graphicsLayer{this.alpha=alpha}.clickable{Pocket.open(item.threadId);Pocket.tab=1}
        .semantics{contentDescription="Open ${item.title}. $text"}.padding(vertical=4.dp)) {
        Text(item.title,color=if(item.working||item.failed)accent else Muted,fontSize=12.sp,lineHeight=18.sp,
            fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
        Crossfade(targetState=text,animationSpec=tween(220),label="mission sentence",modifier=Modifier.fillMaxWidth().weight(1f)) { sentence ->
            Text(sentence,color=Paper,fontSize=17.sp,lineHeight=22.sp,fontWeight=FontWeight.Normal,
                maxLines=3,overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth())
        }
    }
}
