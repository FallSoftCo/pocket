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
    val state:String="loading",
    val credits:CreditUsage=CreditUsage(),
    val continuationState:String="unverified",
    val continuationLabel:String="Continuation status unavailable"
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
            return WeeklyUsage(remaining,weekly?.optLong("resetsAt")?.takeIf{it>0},json.optLong("updatedAt"),json.optBoolean("stale"),json.optString("state","unavailable"),CreditUsage.fromJson(json.optJSONObject("credits")),json.optJSONObject("continuation")?.optString("state","unverified")?:"unverified",json.optJSONObject("continuation")?.optString("label","Continuation status unavailable")?:"Continuation status unavailable")
        }
    }
}


data class CreditUsage(
    val balance:String?=null,val unlimited:Boolean=false,val updatedAt:Long=0,val stale:Boolean=true,
    val fromAt:Long=0,val toAt:Long=0,val decrease:String?=null,val comparisonState:String="unavailable"
){
    fun isStale(now:Long,connected:Boolean)=stale||!connected||updatedAt<=0||now-updatedAt>120000
    fun label(locale:Locale=Locale.getDefault()):String {
        if(unlimited)return "Unlimited credits"
        val amount=balance?.toBigDecimalOrNull()?:return "Credits unavailable"
        return NumberFormat.getNumberInstance(locale).apply{maximumFractionDigits=12}.format(amount)+" credits"
    }
    fun compact(locale:Locale=Locale.getDefault()):String? {
        val amount=balance?.toBigDecimalOrNull()?:return if(unlimited)"∞ cr" else null
        val divisor=if(amount>=java.math.BigDecimal(1000))java.math.BigDecimal(1000) else java.math.BigDecimal.ONE
        return NumberFormat.getNumberInstance(locale).apply{maximumFractionDigits=1}.format(amount.divide(divisor))+if(divisor>java.math.BigDecimal.ONE)"k cr" else " cr"
    }
    companion object {
        fun fromJson(json:JSONObject?):CreditUsage {
            val observation=json?.optJSONObject("observation")
            fun decimal(value:String?)=value?.takeIf{it.matches(Regex("[0-9]{1,24}(\\.[0-9]{1,12})?"))}
            return CreditUsage(decimal(json?.optString("balance")),json?.optBoolean("unlimited")==true,json?.optLong("updatedAt")?:0,json?.optBoolean("stale",true)?:true,observation?.optLong("fromAt")?:0,observation?.optLong("toAt")?:0,decimal(observation?.optString("balanceDecrease")),json?.optString("comparisonState","unavailable")?:"unavailable")
        }
    }
}
