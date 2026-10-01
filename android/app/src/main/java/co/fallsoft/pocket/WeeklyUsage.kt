package co.fallsoft.pocket

import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WeeklyUsage(
    val remainingPercent:Double?=null,
    val resetsAt:Long?=null,
    val updatedAt:Long=0,
    val stale:Boolean=false,
    val state:String="loading"
){
    fun remainingLabel(locale:Locale=Locale.getDefault()):String {
        val remaining=remainingPercent?:return if(state=="loading")"Loading…" else "Unavailable"
        val number=NumberFormat.getNumberInstance(locale).apply{maximumFractionDigits=1}
        return if(remaining>0&&remaining<0.1)"<0.1% left" else "${number.format(remaining)}% left"
    }
    fun isStale(now:Long,connected:Boolean)=remainingPercent!=null&&(stale||!connected||updatedAt<=0||now-updatedAt>120000||(resetsAt!=null&&now>=resetsAt*1000))
    fun resetLabel():String=resetsAt?.let{"Resets ${SimpleDateFormat("EEE, MMM d · h:mm a",Locale.getDefault()).format(Date(it*1000))}"}?:"Reset time unavailable"
    companion object{
        fun fromJson(json:JSONObject?):WeeklyUsage{
            if(json==null)return WeeklyUsage(state="unavailable")
            val weekly=json.optJSONObject("weekly")
            val remaining=weekly?.optDouble("remainingPercent")?.takeIf{it.isFinite()&&it in 0.0..100.0}
            return WeeklyUsage(remaining,weekly?.optLong("resetsAt")?.takeIf{it>0},json.optLong("updatedAt"),json.optBoolean("stale"),json.optString("state","unavailable"))
        }
    }
}
