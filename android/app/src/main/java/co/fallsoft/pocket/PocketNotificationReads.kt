package co.fallsoft.pocket

import android.app.NotificationManager
import org.json.JSONObject
import java.security.MessageDigest

/** A fresh snapshot supplies the watermark; a settled latest viewport presents it. */
object PocketNotificationReads {
    private fun key(thread:String,local:Boolean,environment:String=Pocket.environment(local)):String {
        val identity=Pocket.savedBase(local,environment)+"\u0000"+Pocket.savedToken(local,environment)
        val hash=MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString(""){"%02x".format(it)}
        return "notification-read:$hash:$thread"
    }
    fun isRead(n:JSONObject,local:Boolean=n.optBoolean("_local",Pocket.local),environment:String=n.s("_environment",Pocket.environment(local))):Boolean {
        val attention=if(n.has("_needsAttention"))n.optBoolean("_needsAttention") else null
        if(NotificationReadPolicy.preserve(n.s("kind"),attention))return false
        return n.optBoolean("_read")||NotificationReadPolicy.read(n.optLong("id"),Pocket.prefs.getLong(key(n.s("thread_id"),local,environment),0),n.s("kind"),attention)
    }
    fun readVisible(threadId:String,notifications:List<JSONObject>,local:Boolean=Pocket.local,catchup:JSONObject?=null,environment:String=Pocket.environment(local)){
        if(BlackoutVisibility.active)return
        catchup?.s("token")?.takeIf{it.isNotBlank()}?.let{acknowledgeCatchup(threadId,it,local,environment)}
        val fetched=notifications.filter{it.s("thread_id")==threadId};val through=fetched.maxOfOrNull{it.optLong("id") }?:return
        if(through<=0)return
        val readKey=key(threadId,local,environment);if(through<=Pocket.prefs.getLong(readKey,0))return
        PresentedReads.record(threadId,"notifications",through.toString(),environment)
    }
    internal fun accept(threadId:String,through:Long,result:JSONObject,destination:EnvironmentRequest){
        val environment=destination.environment.id;val local=destination.environment.local
        val readKey=key(threadId,local,environment)
        val acknowledged=result.optLong("throughId",0).coerceIn(0,through)
        Pocket.prefs.edit().putLong(readKey,maxOf(Pocket.prefs.getLong(readKey,0),acknowledged)).commit()
        val ids=result.optJSONArray("ids")?:return
        val manager=Pocket.context.getSystemService(NotificationManager::class.java)
        for(i in 0 until ids.length()){val id=ids.optLong(i);if(id in 1..acknowledged)manager.cancel(Pocket.notificationTag(environment),(id+(if(local)500000 else 1000)).toInt())}
    }
    private fun acknowledgeCatchup(threadId:String,capture:String,local:Boolean,environment:String){
        PresentedReads.record(threadId,"catchup",capture,environment)
    }
}
