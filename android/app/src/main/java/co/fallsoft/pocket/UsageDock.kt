package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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

@Composable fun usageDockHeight()=with(LocalDensity.current){
    // Android scales small text more than large text: reserve each line separately.
    18.sp.toDp()+12.sp.toDp()*2+18.sp.toDp()+18.dp
}.coerceAtLeast(88.dp)

@Composable fun usageDockWidth()=(58*LocalDensity.current.fontScale.coerceAtLeast(1f)).dp

@Composable fun UsageDetails(){
    val usage=Pocket.weeklyUsage
    val now=System.currentTimeMillis()
    Text(usage.remainingLabel(),color=NextGreen,fontSize=22.sp,fontWeight=FontWeight.SemiBold)
    usage.resetsAt?.let{Text("Resets "+SimpleDateFormat("EEE, MMM d · HH:mm",if(PocketImmersion.enabled)Locale.ITALIAN else Locale.getDefault()).format(Date(it*1000)),color=Muted,fontSize=13.sp)}
    UsageForecast.deadline(Pocket.usageSamples,usage,now,Pocket.connected&&Pocket.codexOnline)?.let{
        Text("Estimated run-out "+SimpleDateFormat("EEE HH:mm",if(PocketImmersion.enabled)Locale.ITALIAN else Locale.getDefault()).format(Date(it)),color=Muted,fontSize=13.sp)
    }
    ConnectionPill()
}

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
    val forecastLabel=forecast?.let{val minutes=((it-now)/60000).coerceAtLeast(1);"~"+when{minutes<60->"${minutes}m";minutes<2880->"${minutes/60}h";else->"${minutes/1440}d"}}
    val day=usage.resetsAt?.let{SimpleDateFormat("EEE",if(PocketImmersion.enabled)Locale.ITALIAN else Locale.getDefault()).format(Date(it*1000))}.orEmpty()
    val time=usage.resetsAt?.let{SimpleDateFormat("HH:mm",if(PocketImmersion.enabled)Locale.ITALIAN else Locale.getDefault()).format(Date(it*1000))}.orEmpty()
    Surface(onClick={open=true},modifier=modifier.height(usageDockHeight()).semantics{contentDescription="Usage and connection details"},color=Ink,shape=RoundedCornerShape(20.dp)){
        Column(Modifier.fillMaxSize().padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)){AnimatedMark(16);Text(usage.remainingLabel().replace(" left","")+(if(stale)"*" else ""),color=color,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,maxLines=1)}
            Text(if(day.isBlank())"↻ —" else "↻ $day",color=Muted,fontSize=10.sp,lineHeight=12.sp,maxLines=1)
            if(time.isNotBlank())Text(time,color=Muted,fontSize=10.sp,lineHeight=12.sp,maxLines=1)
            Row(Modifier.heightIn(min=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)){
                val online=Pocket.connected&&Pocket.codexOnline
                val stateColor=if(online)NextGreen else if(Pocket.connected||PocketLive.outageVisible)Coral else Muted
                Box(Modifier.size(20.dp).background(stateColor.copy(alpha=.35f),RoundedCornerShape(6.dp)),contentAlignment=Alignment.Center){
                    SymbolIcon(if(Pocket.local)"PhoneAndroid" else "Computer",if(online)"Connected" else if(Pocket.connected)"Codex offline" else if(PocketLive.outageVisible)"Live updates reconnecting" else "Connecting",Modifier.size(16.dp))
                }
                if(forecastLabel!=null){SymbolIcon("ArrowDownward","Estimated usage run-out",Modifier.size(12.dp));Text(forecastLabel,color=color,fontSize=9.sp,lineHeight=12.sp,maxLines=1)}
            }
        }
    }
    if(open)ModalBottomSheet(onDismissRequest={open=false},containerColor=Panel){
        Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Pocket.selected?.let{id->Pocket.tasks.firstOrNull{it.id==id}?.title?.let{Text(it,fontWeight=FontWeight.Medium,fontSize=18.sp)}}
            Text(usage.remainingLabel(),color=color,fontWeight=FontWeight.SemiBold,fontSize=24.sp)
            usage.resetsAt?.let{Text("Resets "+SimpleDateFormat("EEE, MMM d · HH:mm",if(PocketImmersion.enabled)Locale.ITALIAN else Locale.getDefault()).format(Date(it*1000)),color=Muted)}
            UsageForecast.deadline(Pocket.usageSamples,usage,now,Pocket.connected&&Pocket.codexOnline)?.let{Text("At this pace: approximately "+SimpleDateFormat("EEE HH:mm",if(PocketImmersion.enabled)Locale.ITALIAN else Locale.getDefault()).format(Date(it)),color=Muted)}
            if(stale)Text("Waiting for fresh usage",color=Muted)
            ConnectionPill()
            TextButton({Pocket.retryConnection()}){Text("Refresh")}
        }
    }
}
