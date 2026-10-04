package co.fallsoft.pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun WeeklyLimitBar(){
    val usage=Pocket.weeklyUsage
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){now=System.currentTimeMillis();delay(30000)}}
    val connected=Pocket.connected&&Pocket.codexOnline
    val stale=usage.isStale(now,connected)
    val remaining=usage.remainingPercent
    val color=when{remaining==null||stale->Muted;remaining<=20->Coral;remaining<=50->Mint;else->NextGreen}
    val dateFormat=SimpleDateFormat("EEE HH:mm",Locale.getDefault())
    val estimate=UsageForecast.deadline(Pocket.usageSamples,usage,now,connected)
    Surface(color=Ink,modifier=Modifier.fillMaxWidth()){
        Row(Modifier.padding(horizontal=16.dp,vertical=6.dp).heightIn(min=32.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)){
            Mark(28)
            Box(Modifier.size(5.dp).background(if(connected)NextGreen else Coral,CircleShape))
            Text(if(Pocket.token.isBlank())"Not connected" else usage.remainingLabel()+(if(stale)" · stale" else ""),color=color,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
            Text((usage.resetsAt?.let{"Reset "+dateFormat.format(Date(it*1000))}?:"")+(estimate?.let{" · ~empty ${((it-now)/3600000.0).let{h->if(h<1)"${((it-now)/60000).coerceAtLeast(1)}m" else "${h.toInt()}h"}}"}?:""),color=Muted,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
        }
    }
}
