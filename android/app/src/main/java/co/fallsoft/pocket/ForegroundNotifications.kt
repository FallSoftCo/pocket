package co.fallsoft.pocket

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlinx.coroutines.*
import androidx.compose.ui.platform.testTag

/** Presentation policy only: never acknowledges reads, resolves questions, or starts work. */
internal object ForegroundNotificationPolicy {
    fun inApp(visible:Boolean,blackout:Boolean)=visible&&!blackout
    fun material(kind:String)=kind in setOf("complete","completed","message","question","approval","error","failure","update","answer","heads_up","note")
    fun sameConversation(environment:String,thread:String,currentEnvironment:String,currentThread:String?)=
        thread.isNotBlank()&&environment==currentEnvironment&&thread==currentThread
}
internal object ForegroundNotifications {
    private data class Notice(val source:EnvironmentRequest,val value:JSONObject)
    private val pending=linkedMapOf<String,Notice>()
    private var revision by mutableIntStateOf(0)
    var visibleRoom by mutableStateOf<Triple<String,String,String>?>(null)
    private var restored=false
    private val persistence=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(1))
    private var saving:Job?=null
    private fun restore(){
        synchronized(pending){
            if(restored)return
            restored=true
            val saved=runCatching{org.json.JSONArray(Pocket.prefs.getString("foreground-notifications:v1","[]"))}.getOrNull()?:return
            saved.objects().takeLast(64).forEach{entry->
                val n=entry.optJSONObject("notice")?:return@forEach
                val source=Pocket.captureEnvironment(n.s("_environment"))?:return@forEach
                if(backendOwner(source.endpoint,source.token)==entry.s("owner"))pending[entry.s("key")]=Notice(source,n)
            }
        }
    }
    private fun persist(){
        synchronized(pending){
            if(saving?.isActive==true)return
            saving=persistence.launch{
                delay(80)
                val snapshot=synchronized(pending){
                    saving=null
                    org.json.JSONArray(pending.map{(key,n)->JSONObject().put("key",key).put("owner",backendOwner(n.source.endpoint,n.source.token)).put("notice",n.value)})
                }
                Pocket.prefs.edit().putString("foreground-notifications:v1",snapshot.toString()).commit()
            }
        }
    }
    fun active()=ForegroundNotificationPolicy.inApp(PocketVoice.foreground,BlackoutVisibility.active)
    /** Both FCM and the socket enter the same already-deduplicated presentation lane. */
    fun offer(n:JSONObject){
        restore()
        val env=n.s("_environment",Pocket.environmentId)
        val source=Pocket.captureEnvironment(env)?:return
        val attention=NotificationReadPolicy.preserve(n.s("kind"),if(n.has("_needsAttention"))n.optBoolean("_needsAttention")else null)
        val key=env+":"+n.s("_hollywoodKey",n.s("thread_id"))+if(attention)":"+n.optLong("id")else ":routine"
        synchronized(pending){
            val old=pending[key]
            if(old!=null&&old.value.optLong("id")>n.optLong("id"))return
            pending[key]=Notice(source,JSONObject(n.toString()))
            while(pending.size>64)pending.remove(pending.keys.first())
        }
        persist()
        Pocket.scope.launch{revision++}
    }
    private fun available():List<Notice> {
        restore()
        return synchronized(pending){pending.values.toList()}.filter{
        Pocket.captureEnvironment(it.source.environment.id)?.matches(it.source)==true&&
            (if(it.value.has("_hollywoodKey"))HollywoodNotifications.record(it.value.s("_hollywoodKey"))?.let{record->record.s("state") !in listOf("resolved","revoked")&&record.s("action")!="resolve"}==true else !PocketNotificationReads.isRead(it.value))
    }
    }
    fun current():JSONObject?{
        revision
        return available().asReversed().firstOrNull{
            ForegroundNotificationPolicy.material(it.value.s("kind"))&&
                (if(it.value.has("_hollywoodKey"))visibleRoom!=Triple(backendOwner(it.source.endpoint,it.source.token),it.value.s("_binding"),it.value.s("_room"))
                else !ForegroundNotificationPolicy.sameConversation(it.source.environment.id,it.value.s("thread_id"),Pocket.environmentId,Pocket.selected))
        }?.value
    }
    fun offerRoom(key:String,value:JSONObject,generic:Boolean,status:String?){
        val origin=Pocket.captureEnvironment(value.s("environment"))?:return
        if(backendOwner(origin.endpoint,origin.token)!=value.s("owner"))return
        val n=JSONObject().put("_environment",origin.environment.id).put("_hollywoodKey",key).put("_binding",value.s("bindingId")).put("_room",value.s("roomId"))
            .put("id",value.optLong("revision")).put("kind",if(value.has("attention"))"question"else "update")
            .put("title",status?:if(generic)"Room update" else value.optJSONObject("hint")?.s("roomName","Room update"))
            .put("body",if(generic)"Open the room to review"else value.optJSONObject("event")?.s("body").orEmpty())
        offer(n)
    }
    fun open(n:JSONObject){
        n.s("_hollywoodKey").takeIf{it.isNotBlank()}?.let{HollywoodNotifications.openIntent(it);return}
        val env=n.s("_environment");val id=n.s("thread_id");if(id.isBlank())return
        val notice=available().firstOrNull{it.source.environment.id==env&&it.value.optLong("id")==n.optLong("id")}?:return
        PocketCoordinator.close();PocketWorkUpdates.close();PocketVoice.showInPlace()
        Pocket.activateEnvironment(notice.source.environment.id)
        Pocket.open(id)
    }
    /** Leave unseen work discoverable in the system tray when the app ceases to be visible.
     * Fetching or suppressing an alert never marks its conversation read. */
    fun background(){
        val retained=available()
        synchronized(pending){pending.clear()};persist();revision++
        retained.forEach{notice->
            val key=notice.value.s("_hollywoodKey")
            if(key.isNotBlank())HollywoodNotifications.record(key)?.let{HollywoodNotifications.show(key,it,generic=it.s("state")!="verified",quiet=true)}
            else PocketNotifications.show(Pocket.context,notice.value,quiet=true)
        }
    }
}
@Composable internal fun ForegroundWorkNotice(){
    val n=ForegroundNotifications.current()?:return
    if(!ForegroundNotifications.active())return
    TextButton({ForegroundNotifications.open(n)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("foreground-work-notice"),contentPadding=PaddingValues(horizontal=16.dp,vertical=6.dp)){
        Column(Modifier.weight(1f)){
            Text(n.s("title","Work update"),color=Mint,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(n.s("body"),color=Paper,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp));BilingualLabel("Open",color=Mint,fontSize=14.sp)
    }
}
