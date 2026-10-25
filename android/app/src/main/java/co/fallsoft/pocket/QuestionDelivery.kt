package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Source-bound decision receipts are independent of the subsequent transcript read. */
internal object QuestionDelivery {
    private val live=mutableStateMapOf<String,String>()
    fun state(key:String):String=live[key]?:Pocket.prefs.getString("decision:$key","").orEmpty().let{if(it=="sending")"unconfirmed" else it}
    private fun set(key:String,value:String){live[key]=value;Pocket.prefs.edit().putString("decision:$key",value).apply()}
    fun submit(key:String,id:String,body:JSONObject){
        if(state(key) in listOf("sending","submitted","unconfirmed"))return
        val destination=Pocket.captureEnvironment()?:return
        set(key,"sending")
        Pocket.scope.launch{
            try{
                val result=Pocket.apiEnvironment(destination,"/api/requests/$id/answer",body)
                check(result.optBoolean("ok")){"Decision receipt was not confirmed"}
                set(key,"submitted")
                Pocket.prefs.edit().remove(key).apply()
                if(Pocket.captureEnvironment()?.matches(destination)==true){Pocket.refreshDetail();Pocket.refresh()}
            }catch(e:Exception){
                set(key,if(e is PocketApiException&&e.status==409)"submitted" else if(e is PocketApiException&&e.status in 400..499)"failed" else "unconfirmed")
                if(Pocket.captureEnvironment()?.matches(destination)==true&&state(key)=="failed")Pocket.error=PocketNetwork.error(e)
            }
        }
    }
}
