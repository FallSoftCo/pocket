package co.fallsoft.pocket

import android.app.NotificationManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

/** Only successful foreground conversation fetches supply a read watermark. */
object PocketNotificationReads {
    private fun key(thread:String,local:Boolean):String {
        val identity=Pocket.savedBase(local)+"\u0000"+Pocket.savedToken(local)
        val hash=MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString(""){"%02x".format(it)}
        return "notification-read:$hash:$thread"
    }
    fun isRead(n:JSONObject,local:Boolean=n.optBoolean("_local",Pocket.local)):Boolean {
        val attention=if(n.has("_needsAttention"))n.optBoolean("_needsAttention") else null
        if(NotificationReadPolicy.preserve(n.s("kind"),attention))return false
        return n.optBoolean("_read")||NotificationReadPolicy.read(n.optLong("id"),Pocket.prefs.getLong(key(n.s("thread_id"),local),0),n.s("kind"),attention)
    }
    fun readVisible(threadId:String,notifications:List<JSONObject>,local:Boolean=Pocket.local){
        val fetched=notifications.filter{it.s("thread_id")==threadId};val through=fetched.maxOfOrNull{it.optLong("id") }?:return
        if(through<=0)return
        val readKey=key(threadId,local);if(through<=Pocket.prefs.getLong(readKey,0))return
        val token=Pocket.savedToken(local);val base=Pocket.savedBase(local)
        Pocket.scope.launch {
            try{
                val result=withContext(Dispatchers.IO){
                    val request=Request.Builder().url(base.trimEnd('/')+"/api/threads/$threadId/notifications/read").header("Authorization","Bearer $token")
                        .post(JSONObject().put("throughId",through).toString().toRequestBody("application/json".toMediaType())).build()
                    Pocket.http.newCall(request).execute().use{r->if(!r.isSuccessful)throw IllegalStateException("Read was not acknowledged");JSONObject(r.body?.string()?:"{}")}
                }
                if(Pocket.savedToken(local)!=token||Pocket.savedBase(local)!=base||!result.optBoolean("ok"))return@launch
                Pocket.prefs.edit().putLong(readKey,maxOf(Pocket.prefs.getLong(readKey,0),result.optLong("throughId",through))).apply()
                val ids=result.optJSONArray("ids")?:return@launch
                val manager=Pocket.context.getSystemService(NotificationManager::class.java)
                for(i in 0 until ids.length()){val id=ids.optLong(i);if(id>0)manager.cancel((id+(if(local)500000 else 1000)).toInt())}
            }catch(_:Exception){/* Keep existing notifications on a failed acknowledgement. */}
        }
    }
}
