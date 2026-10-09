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

@Composable fun usageDockWidth():androidx.compose.ui.unit.Dp {
    val density=LocalDensity.current
    val measure=androidx.compose.ui.text.rememberTextMeasurer()
    val style=LocalTextStyle.current.copy(fontSize=12.sp,lineHeight=14.sp)
    val width=maxOf(measure.measure(androidx.compose.ui.text.AnnotatedString("Settings"),style).size.width,measure.measure(androidx.compose.ui.text.AnnotatedString(PocketImmersion.label("Settings")),style).size.width)
    return maxOf((72*density.fontScale.coerceAtLeast(1f)).dp,with(density){width.toDp()}+4.dp)
}

@Composable fun UsageDetails(onNavigate:()->Unit={}){
    val context=androidx.compose.ui.platform.LocalContext.current
    TextButton({onNavigate();PocketCoordinator.close();PocketWorkUpdates.close();PocketVoice.showInPlace();Pocket.closeTask();if(Pocket.tab==1){Pocket.requestSessionOrder();Pocket.tab=0}else Pocket.tab=1}){BilingualLabel(if(Pocket.tab==1)"Work" else "Settings")}
    PocketUpdateControl()
    TextButton({onNavigate();context.nextCompActivity()?.enterBlackout()}){BilingualLabel("Blackout")}
    val usage=Pocket.weeklyUsage
    val now=System.currentTimeMillis()
    Text(usage.remainingLabel(),color=NextGreen,fontSize=22.sp,fontWeight=FontWeight.SemiBold)
    usage.resetsAt?.let{Text("Resets "+SimpleDateFormat("EEE, MMM d · HH:mm",if(PocketImmersion.enabled)Locale.ITALIAN else Locale.getDefault()).format(Date(it*1000)),color=Muted,fontSize=13.sp)}
    UsageForecast.deadline(Pocket.usageSamples,usage,now,Pocket.connected&&Pocket.codexOnline)?.let{
        Text("Estimated run-out "+SimpleDateFormat("EEE HH:mm",if(PocketImmersion.enabled)Locale.ITALIAN else Locale.getDefault()).format(Date(it)),color=Muted,fontSize=13.sp)
    }
    val credits=usage.credits
    BilingualLabel("Codex credits",centered=false,color=Muted,fontSize=13.sp)
    Text(credits.label(),color=Paper,fontSize=22.sp,fontWeight=FontWeight.SemiBold)
    BilingualLabel(usage.continuationLabel,centered=false,maxLines=3,color=if(usage.continuationState=="spending-blocked")Coral else Muted,fontSize=13.sp)
    if(credits.updatedAt>0){
        val checked=SimpleDateFormat("MMM d · HH:mm:ss",Locale.getDefault()).format(Date(credits.updatedAt))
        Text((if(credits.isStale(now,Pocket.connected&&Pocket.codexOnline))"Last known · " else "Checked · ")+checked,color=Muted,fontSize=12.sp)
    }else BilingualLabel("Credit data unavailable",centered=false,color=Muted,fontSize=12.sp)
    if(credits.decrease!=null&&credits.fromAt>0&&credits.toAt>credits.fromAt){
        val format=SimpleDateFormat("MMM d HH:mm",Locale.getDefault())
        BilingualLabel("Observed balance decrease",centered=false,color=Muted,fontSize=12.sp)
        Text("${credits.decrease} credits · ${format.format(Date(credits.fromAt))} – ${format.format(Date(credits.toAt))}",color=Paper,fontSize=13.sp)
        BilingualLabel("Across this account; not lifetime or per-session spending",centered=false,maxLines=3,color=Muted,fontSize=12.sp)
    }else BilingualLabel(if(credits.comparisonState=="balance-increased")"Balance increased · comparison restarted" else "Waiting for comparable balance observations",centered=false,maxLines=2,color=Muted,fontSize=12.sp)
    ConnectionPill()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun UsageDock(modifier:Modifier=Modifier,conversationOnly:Boolean=false){
    var open by remember{mutableStateOf(false)}
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){delay(30_000);now=System.currentTimeMillis()}}
    val usage=Pocket.weeklyUsage
    val stale=usage.isStale(now,Pocket.connected&&Pocket.codexOnline)
    val remaining=usage.remainingPercent
    val creditPrimary=remaining==0.0&&usage.credits.compact()!=null
    val color=when{creditPrimary&&!usage.credits.isStale(now,Pocket.connected&&Pocket.codexOnline)&&usage.continuationState=="existing-credits-available"->NextGreen;remaining==null||stale->Muted;remaining<=20->Coral;remaining<=50->Mint;else->NextGreen}
    val day=usage.resetsAt?.let{SimpleDateFormat("EEE",if(PocketImmersion.enabled)Locale.ITALIAN else Locale.getDefault()).format(Date(it*1000))}.orEmpty()
    val time=usage.resetsAt?.let{SimpleDateFormat("HH:mm",if(PocketImmersion.enabled)Locale.ITALIAN else Locale.getDefault()).format(Date(it*1000))}.orEmpty()
    Box(modifier.height(usageDockHeight())) {
    Surface(onClick={open=true},modifier=Modifier.fillMaxSize().semantics{contentDescription="Settings, app updates, usage and activity"},color=Ink,shape=RoundedCornerShape(20.dp)){
        Column(Modifier.fillMaxSize().padding(vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)){if(!creditPrimary)AnimatedMark(20);Text(if(creditPrimary)usage.credits.compact()!! else usage.remainingLabel().replace(" left",""),color=color,fontSize=18.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold,maxLines=1)}
            Text(if(day.isBlank())"↻ —" else "$day $time",color=Muted,fontSize=12.sp,lineHeight=16.sp,maxLines=1)
            Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
                val online=Pocket.connected&&Pocket.codexOnline
                val stateColor=if(online)NextGreen else if(Pocket.connected||PocketLive.outageVisible)Coral else Muted
                Box(Modifier.size(20.dp).background(stateColor.copy(alpha=.35f),RoundedCornerShape(6.dp)),contentAlignment=Alignment.Center){
                    SymbolIcon("Tune","Settings",Modifier.size(20.dp))
                }
                BilingualLabel("Settings",modifier=Modifier.fillMaxWidth(),color=Paper,fontSize=12.sp,lineHeight=14.sp)
            }
        }
    }
    val details:@Composable ()->Unit={
        UsageDetails(onNavigate={open=false})
        if(stale)BilingualLabel("Waiting for fresh usage",color=Muted,centered=false)
        TextButton({Pocket.retryConnection()}){BilingualLabel("Refresh")}
    }
    if(conversationOnly)DropdownMenu(open,{open=false},modifier=Modifier.width(300.dp).heightIn(max=420.dp)){details()}
    else ActivityPopup(open,{open=false},Pocket.selected,controlsFirst=true){details()}
    }
}
