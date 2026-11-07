package co.fallsoft.pocket

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

internal data class MessageTime(val label:String,val full:String)
internal fun messageTime(startedAtMs:Long?,completedAtMs:Long?=null,zone:ZoneId=ZoneId.systemDefault(),locale:Locale=Locale.getDefault()):MessageTime? {
    val epoch=listOf(startedAtMs,completedAtMs).firstOrNull{it!=null&&it>0&&it<=8640000000000000L}?:return null
    val date=Instant.ofEpochMilli(epoch).atZone(zone)
    return MessageTime(DateTimeFormatter.ofPattern("MMM d · HH:mm",locale).format(date),DateTimeFormatter.ofPattern("MMMM d, uuuu HH:mm:ss z",locale).format(date))
}
internal fun JSONObject.messageStartedAt()=optLong("startedAtMs").takeIf{it>0}
internal fun JSONObject.messageCompletedAt()=optLong("completedAtMs").takeIf{it>0}
@Composable internal fun MessageTimestamp(startedAtMs:Long?,completedAtMs:Long?=null){
    val time=messageTime(startedAtMs,completedAtMs)?:return
    Text(time.label,color=Muted,fontSize=10.sp,lineHeight=14.sp,modifier=Modifier.semantics{contentDescription=time.full})
}
internal fun JSONObject.coordinatorResponseAt():Long? {
    val timings=optJSONObject("timings")?:return null
    return listOf("first_response","first_final_response","response_completed").firstNotNullOfOrNull{timings.optLong(it).takeIf{time->time>0}}
}
