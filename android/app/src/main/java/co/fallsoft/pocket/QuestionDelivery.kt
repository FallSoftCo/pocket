package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Source-bound decision receipts are independent of the subsequent transcript read. */
internal object QuestionDelivery {
    private val live=mutableStateMapOf<String,String>()
    fun state(key:String):String=live[key]?:Pocket.prefs.getString("decision:$key","").orEmpty().let{if(it=="sending")"unconfirmed" else it}
    fun problem(key:String)=Pocket.prefs.getString("decisionProblem:$key","").orEmpty()
    private fun set(key:String,value:String){live[key]=value;Pocket.prefs.edit().putString("decision:$key",value).apply()}
    fun submit(key:String,id:String,body:JSONObject,destination:EnvironmentRequest,threadId:String?){
        fun current()=Pocket.selected==threadId&&Pocket.captureEnvironment()?.matches(destination)==true
        if(!current())return // A delayed callback from an old card has no authority in the new view.
        if(state(key) in listOf("sending","submitted","unconfirmed","expired"))return
        set(key,"sending")
        Pocket.scope.launch{
            try{
                val result=Pocket.apiEnvironment(destination,"/api/requests/$id/answer",body)
                check(result.optBoolean("ok")){"Decision receipt was not confirmed"}
                set(key,"submitted")
                Pocket.prefs.edit().remove(key).apply()
                if(current()){Pocket.refreshDetail();Pocket.refresh()}
            }catch(e:Exception){
                Pocket.prefs.edit().putString("decisionProblem:$key",PocketNetwork.error(e).take(500)).apply()
                set(key,if(e is PocketApiException&&e.status==409)"expired" else if(e is PocketApiException&&e.status in 400..499)"failed" else "unconfirmed")
                if(current()&&state(key)=="failed")Pocket.error=PocketNetwork.error(e)
            }
        }
    }
}
