package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun usageDockHeight()=with(LocalDensity.current){74.sp.toDp()}.coerceAtLeast(88.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun UsageDock(modifier:Modifier=Modifier){
    var open by remember{mutableStateOf(false)}
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){delay(30_000);now=System.currentTimeMillis()}}
    val usage=Pocket.weeklyUsage
    val stale=usage.isStale(now,Pocket.connected&&Pocket.codexOnline)
    val remaining=usage.remainingPercent
    val color=when{remaining==null||stale->Muted;remaining<=20->Coral;remaining<=50->Mint;else->NextGreen}
    val forecast=UsageForecast.deadline(Pocket.usageSamples,usage,now,Pocket.connected&&Pocket.codexOnline)
    val day=usage.resetsAt?.let{SimpleDateFormat("EEE",Locale.getDefault()).format(Date(it*1000))}.orEmpty()
    val time=usage.resetsAt?.let{SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(it*1000))}.orEmpty()
    Surface(onClick={open=true},modifier=modifier.height(usageDockHeight()).semantics{contentDescription="Usage and connection details"},color=Ink,shape=RoundedCornerShape(20.dp)){
        Column(Modifier.fillMaxSize().padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)){AnimatedMark(16);Text(usage.remainingLabel().replace(" left","")+(if(stale)"*" else ""),color=color,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,maxLines=1)}
            Text(if(day.isBlank())"Reset —" else "↻ $day",color=Muted,fontSize=10.sp,lineHeight=12.sp,maxLines=1)
            if(time.isNotBlank())Text(time,color=Muted,fontSize=10.sp,lineHeight=12.sp,maxLines=1)
            Text(forecast?.let{"~ "+SimpleDateFormat("EEE HH:mm",Locale.getDefault()).format(Date(it))}?:"Pace —",color=if(forecast!=null)color else Muted,fontSize=10.sp,lineHeight=12.sp,maxLines=1)
            Text(if(Pocket.connected&&Pocket.codexOnline)"Connected" else if(Pocket.connected)"Codex offline" else "Reconnecting",color=if(Pocket.connected&&Pocket.codexOnline)NextGreen else Coral,fontSize=9.sp,lineHeight=11.sp,maxLines=1)
        }
    }
    if(open)ModalBottomSheet(onDismissRequest={open=false},containerColor=Panel){
        Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Pocket.selected?.let{id->Pocket.tasks.firstOrNull{it.id==id}?.title?.let{Text(it,fontWeight=FontWeight.Medium,fontSize=18.sp)}}
            Text(usage.remainingLabel(),color=color,fontWeight=FontWeight.SemiBold,fontSize=24.sp)
            usage.resetsAt?.let{Text("Resets "+SimpleDateFormat("EEE, MMM d · HH:mm",Locale.getDefault()).format(Date(it*1000)),color=Muted)}
            UsageForecast.deadline(Pocket.usageSamples,usage,now,Pocket.connected&&Pocket.codexOnline)?.let{Text("At this pace: approximately "+SimpleDateFormat("EEE HH:mm",Locale.getDefault()).format(Date(it)),color=Muted)}
            if(stale)Text("Waiting for fresh usage",color=Muted)
            ConnectionPill()
            TextButton({Pocket.retryConnection()}){Text("Refresh")}
        }
    }
}
