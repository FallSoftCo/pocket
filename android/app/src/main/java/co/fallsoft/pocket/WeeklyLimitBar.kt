package co.fallsoft.pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable fun WeeklyLimitBar(){
    val usage=Pocket.weeklyUsage
    var now by remember{mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit){while(true){now=System.currentTimeMillis();delay(30000)}}
    val stale=usage.isStale(now,Pocket.connected&&Pocket.codexOnline)
    val color=if((usage.remainingPercent?:100.0)<=20.0)Coral else Mint
    Surface(color=Panel,modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(horizontal=20.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text("Weekly limit",color=Paper,fontSize=14.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                Text(if(Pocket.token.isBlank())"Not connected" else usage.remainingLabel(),color=if(usage.remainingPercent==null)Muted else color,fontSize=21.sp,fontWeight=FontWeight.Bold)
            }
            if(usage.remainingPercent!=null){
                LinearProgressIndicator(progress={usage.remainingPercent.toFloat()/100f},color=color,trackColor=Line,modifier=Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
                Text((if(stale)"Last known · " else "")+usage.resetLabel(),color=Muted,fontSize=11.sp)
            }else{
                Box(Modifier.fillMaxWidth().height(3.dp).background(Line))
                Text(if(Pocket.token.isBlank())"Connect Codex to see your allowance" else if(usage.state=="loading")"Checking your Codex allowance…" else "Codex hasn’t returned a weekly allowance",color=Muted,fontSize=11.sp)
            }
        }
    }
}
