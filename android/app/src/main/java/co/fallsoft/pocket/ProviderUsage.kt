package co.fallsoft.pocket

import org.json.JSONObject
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Account-wide provider balance. This is not a ChatGPT allowance or thread spend. */
data class ProviderUsage(
    val providerId:String="",
    val configured:Boolean=false,
    val available:Boolean=false,
    val currency:String?=null,
    val totalBalance:String?=null,
    val updatedAt:Long=0,
    val stale:Boolean=true,
    val state:String="unavailable"
){
    fun isDeepSeek()=providerId=="deepseek"
    fun isStale(now:Long,connected:Boolean)=stale||!connected||updatedAt<=0||now-updatedAt>180000
    fun balanceLabel():String {
        if(totalBalance==null)return if(state=="loading")"Loading…" else "Balance unavailable"
        val amount=totalBalance.toBigDecimalOrNull()?:return "Balance unavailable"
        return if(currency=="USD")NumberFormat.getCurrencyInstance(Locale.US).apply{maximumFractionDigits=2}.format(amount)
            else (currency?.takeIf{it.isNotBlank()}?.plus(" ") ?: "")+NumberFormat.getNumberInstance(Locale.US).apply{maximumFractionDigits=2}.format(amount)
    }
    fun compactBalanceLabel()=balanceLabel()
    companion object{
        fun fromJson(json:JSONObject?):ProviderUsage? {
            if(json==null)return null
            val provider=json.optString("providerId").takeIf{it.matches(Regex("[a-zA-Z0-9_-]{1,80}"))}?:return null
            val currency=json.optString("currency").takeIf{it.matches(Regex("[A-Z]{3,8}"))}
            val balance=json.optString("totalBalance").takeIf{it.matches(Regex("-?(?:0|[1-9][0-9]{0,23})(?:\\.[0-9]{1,12})?"))}
            return ProviderUsage(provider,json.optBoolean("configured"),json.optBoolean("available"),currency,balance,json.optLong("updatedAt"),json.optBoolean("stale",true),json.optString("state","unavailable"))
        }
    }
}

data class ConversationUsage(val lastTokens:Long?,val totalTokens:Long?,val contextWindow:Long?,val observedAt:Long=0){
    fun footerLabel():String? {
        val last=lastTokens?.let{"${compactTokenCount(it)} ctx"}
        val total=totalTokens?.let{"${compactTokenCount(it)} total"}
        return listOfNotNull(last,total).takeIf{it.isNotEmpty()}?.joinToString(" · ")
    }
    fun contextPercent():Int? {
        val used=lastTokens?:return null
        val window=contextWindow?:return null
        if(window<=0)return null
        return (used*100/window).coerceIn(0,100).toInt()
    }
    companion object{
        fun fromJson(json:JSONObject?):ConversationUsage? {
            if(json==null)return null
            fun tokens(value:JSONObject?)=value?.optLong("totalTokens")?.takeIf{it>=0}
            val last=tokens(json.optJSONObject("last"))
            val total=tokens(json.optJSONObject("total"))
            val window=json.optLong("modelContextWindow").takeIf{it>0}
            if(last==null&&total==null&&window==null)return null
            return ConversationUsage(last,total,window,json.optLong("observedAt"))
        }
    }
}

internal fun compactTokenCount(tokens:Long):String=when{
    tokens>=1_000_000->trimToken(tokens,1_000_000.0)+"m"
    tokens>=1_000->trimToken(tokens,1_000.0)+"k"
    else->tokens.toString()
}

private fun trimToken(tokens:Long,divisor:Double):String{
    val value=tokens/divisor
    return if(value>=100)String.format(Locale.US,"%.0f",value)
        else if(value>=10)String.format(Locale.US,"%.1f",value).trimEnd('0').trimEnd('.')
        else String.format(Locale.US,"%.2f",value).trimEnd('0').trimEnd('.')
}

internal fun providerFooterUsage(usage:ProviderUsage?,providerId:String?,accountSourceLabel:String?=null):ProviderUsage?=
    usage?.takeIf{accountSourceLabel==null&&it.isDeepSeek()&&(providerId.isNullOrBlank()||providerId==it.providerId)}
