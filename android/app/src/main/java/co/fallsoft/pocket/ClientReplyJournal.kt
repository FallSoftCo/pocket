package co.fallsoft.pocket

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal val replyReceiptStates=setOf("queued","sending","held","accepted","failed","unknown","cancelled")
internal val admittedReplyStates=setOf("queued","sending","held","accepted")
internal data class ClientReplyIntent(val id:String,val environment:String,val owner:String,val thread:String,val text:String,val mode:String,val draftKey:String?,val phase:String="prepared",val state:String="unconfirmed",val message:String="",val createdAt:Long=System.currentTimeMillis()){
    fun json()=JSONObject().put("id",id).put("environment",environment).put("owner",owner).put("thread",thread).put("text",text).put("mode",mode).put("draftKey",draftKey).put("phase",phase).put("state",state).put("message",message).put("createdAt",createdAt)
}
internal fun checkedReplyReceipt(receipt:JSONObject,intent:ClientReplyIntent,lookup:Boolean=false):String{
    val state=receipt.s("state")
    if(receipt.s("id")!=intent.id||state !in replyReceiptStates||(lookup&&receipt.s("thread_id")!=intent.thread))throw IOException("Delivery was not confirmed for this message. Check its status before sending again.")
    return state
}
/** Atomic private storage before dispatch. Corrupt storage fails closed, never resets identities. */
internal class ClientReplyJournal(private val read:()->String,private val write:(String)->Boolean){
    @Synchronized fun all():List<ClientReplyIntent> = try{
        val array=JSONArray(read());(0 until array.length()).map{n->val j=array.getJSONObject(n);ClientReplyIntent(j.getString("id"),j.getString("environment"),j.getString("owner"),j.getString("thread"),j.getString("text"),j.getString("mode"),j.s("draftKey").takeIf{it.isNotBlank()},j.getString("phase"),j.getString("state"),j.s("message"),j.getLong("createdAt"))}
    }catch(e:Exception){throw IOException("Saved message state could not be read. Your draft is still saved.",e)}
    private fun save(rows:List<ClientReplyIntent>){if(!write(JSONArray(rows.map{it.json()}).toString()))throw IOException("Could not save this message safely. Your draft is still saved.")}
    @Synchronized fun put(intent:ClientReplyIntent){val rows=all();require(rows.none{it.id==intent.id});save(rows+intent)}
    @Synchronized fun get(id:String)=all().firstOrNull{it.id==id}
    @Synchronized fun update(id:String,change:(ClientReplyIntent)->ClientReplyIntent):ClientReplyIntent?{val rows=all();val old=rows.firstOrNull{it.id==id}?:return null;val next=change(old);save(rows.map{if(it.id==id)next else it});return next}
    @Synchronized fun remove(id:String){save(all().filterNot{it.id==id})}
}
