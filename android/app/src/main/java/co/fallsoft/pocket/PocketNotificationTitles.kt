package co.fallsoft.pocket

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest

/** Label-only replacement keeps each alert's body, actions, ID, and unread/attention state. */
object PocketNotificationTitles {
    private fun profile(local:Boolean,environment:String=Pocket.environment(local)):String {
        val raw=Pocket.savedBase(local,environment)+"\u0000"+Pocket.savedToken(local,environment)
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    fun title(thread:String,fallback:String,local:Boolean=Pocket.local,environment:String=Pocket.environment(local))=Pocket.prefs.getString("notification-title:${profile(local,environment)}:$thread",null)?:fallback
    fun threadForId(id:Long,local:Boolean=Pocket.local,environment:String=Pocket.environment(local)):String?=Pocket.prefs.getString("notification-thread:${profile(local,environment)}:$id",null)
    fun forId(id:Long,fallback:String,local:Boolean=Pocket.local,environment:String=Pocket.environment(local)):String {
        val thread=Pocket.prefs.getString("notification-thread:${profile(local,environment)}:$id",null)?:return fallback
        return title(thread,fallback,local,environment)
    }
    fun remember(n:JSONObject){
        val local=n.optBoolean("_local",Pocket.local);val environment=n.s("_environment",Pocket.environment(local));val thread=n.s("thread_id");val id=n.optLong("id")
        if(thread.isBlank()||id<=0)return
        val revision=n.optLong("title_revision")
        if(revision>Pocket.prefs.getLong("notification-title-revision:${profile(local,environment)}:$thread",0))rename(thread,n.s("title"),listOf(id),local,revision,environment)
        n.put("title",title(thread,n.s("title"),local,environment))
        Pocket.prefs.edit().putString("notification-thread:${profile(local,environment)}:$id",thread).apply()
    }
    fun extras(n:JSONObject)=Bundle().apply{putString("nextcompThread",n.s("thread_id"));putString("nextcompProfile",profile(n.optBoolean("_local",Pocket.local),n.s("_environment",Pocket.environment(n.optBoolean("_local",Pocket.local)))))}
    @Synchronized fun rename(thread:String,name:String,ids:List<Long> = emptyList(),local:Boolean=Pocket.local,revision:Long=0,environment:String=Pocket.environment(local)):Boolean{
        if(thread.isBlank()||name.isBlank())return false
        val p=profile(local,environment)
        val previous=Pocket.prefs.getLong("notification-title-revision:$p:$thread",0)
        if(revision>0&&(revision<previous||revision==previous&&Pocket.prefs.getString("notification-title:$p:$thread",null)!=name)||revision==0L&&previous>0)return false
        val matched=ids.toMutableSet();val edit=Pocket.prefs.edit().putString("notification-title:$p:$thread",name)
        if(revision>0)edit.putLong("notification-title-revision:$p:$thread",revision)
        Pocket.prefs.all.forEach{(key,value)->
            if(key.startsWith("notification-thread:$p:")&&value==thread)key.substringAfterLast(':').toLongOrNull()?.let{matched.add(it)}
            if(key.startsWith(Pocket.key("attention:",local,environment))&&value is String){
                val n=runCatching{JSONObject(value)}.getOrNull()
                if(n?.s("thread_id")==thread){n.put("title",name);matched.add(n.optLong("id"));edit.putString(key,n.toString())}
            }
        }
        matched.filter{it>0}.forEach{edit.putString("notification-thread:$p:$it",thread)};edit.apply()
        val manager=Pocket.context.getSystemService(NotificationManager::class.java)
        val displayedIds=matched.map{(it+(if(local)500000 else 1000)).toInt()}.toSet()
        for(alert in manager.activeNotifications){
            val metadata=alert.notification.extras
            if(!metadata?.getString("nextcompProfile").isNullOrBlank()&&metadata.getString("nextcompProfile")!=p)continue
            if(alert.id !in displayedIds&&!(metadata?.getString("nextcompThread")==thread&&metadata.getString("nextcompProfile")==p))continue
            val replacement=Notification.Builder.recoverBuilder(Pocket.context,alert.notification).setContentTitle(name).setOnlyAlertOnce(true).build()
            try{manager.notify(alert.tag,alert.id,replacement)}catch(_:SecurityException){}
        }
        if(environment==Pocket.environmentId){
            Pocket.scope.launch {
                if(title(thread,name,local,environment)!=name)return@launch
                Pocket.notifications=Pocket.notifications.map{if(it.s("thread_id")==thread)JSONObject(it.toString()).put("title",name)else it}
                Pocket.attention=Pocket.attention.map{if(it.s("thread_id")==thread)JSONObject(it.toString()).put("title",name)else it}
                PocketSpeech.queue.messages.replaceAll{if(it.id in matched)it.copy(title=name)else it}
                PocketSpeech.save()
                PocketSpeechCaptions.rename(matched,name)
            }
        }
        return true
    }
}
