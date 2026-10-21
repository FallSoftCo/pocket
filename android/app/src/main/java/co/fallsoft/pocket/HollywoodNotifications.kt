package co.fallsoft.pocket

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.work.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class HollywoodOpenTarget(val owner:String,val binding:String,val room:String,val event:String,val attention:JSONObject?,val reply:HollywoodScope?,val keyboard:Boolean=false)

/** Notification identity never becomes a stock thread or follows the selected workstation. */
internal object HollywoodNotifications {
    var target by mutableStateOf<HollywoodOpenTarget?>(null);private set
    private fun owner(origin:EnvironmentRequest)=backendOwner(origin.endpoint,origin.token)
    private fun tag(key:String)="hollywood:$key"
    fun record(key:String)=Pocket.prefs.getString("hollywood-notification:$key",null)?.let{JSONObject(it)}
    fun save(key:String,value:JSONObject){check(Pocket.prefs.edit().putString("hollywood-notification:$key",value.toString()).commit()){"Could not save room notification"}}
    private fun replySnapshotKey(key:String,revision:Long)="hollywood-reply-snapshot:$key:$revision"
    fun replySnapshot(key:String,revision:Long)=Pocket.prefs.getString(replySnapshotKey(key,revision),null)?.let{JSONObject(it)}
    private fun captureReplyAction(key:String,value:JSONObject):Long{
        require(value.s("state")=="verified")
        val revision=value.getLong("revision")
        val field=replySnapshotKey(key,revision)
        synchronized(this){
            val prior=Pocket.prefs.getString(field,null)?.let{JSONObject(it)}
            // A published Android action retains one immutable original scope.
            if(prior==null)check(Pocket.prefs.edit().putString(field,value.toString()).commit())
            else {
                require(listOf("owner","bindingId","authorityId","principalId","eventId","roomId").all{prior.s(it)==value.s(it)})
                for(field in listOf("attention","reply")){
                    val a=prior.optJSONObject(field);val b=value.optJSONObject(field)
                    require((a==null)==(b==null))
                    if(a!=null&&b!=null)require(hollywoodJsonEqual(
                        if(field=="attention")a.getJSONObject("scope")else a,
                        if(field=="attention")b.getJSONObject("scope")else b))
                }
            }
        }
        return revision
    }
    fun rememberBinding(origin:EnvironmentRequest,binding:JSONObject){Pocket.prefs.edit().putString("hollywood-binding:"+owner(origin),binding.toString()).apply()}
    fun receive(origin:EnvironmentRequest,data:Map<String,String>,forceRefresh:Boolean=false){
        val binding=data["binding_id"].orEmpty();val event=data["event_id"].orEmpty();val room=data["room_id"].orEmpty()
        val authority=data["authority_id"].orEmpty();val principal=data["principal_id"].orEmpty();val revision=data["revision"]?.toLongOrNull()?:return
        val action=data["action"].orEmpty()
        if(!binding.matches(Regex("[0-9a-f]{64}"))||!listOf(event,room,authority,principal).all(::hollywoodUuid)||revision<1||action !in listOf("notify","resolve"))return
        val key=backendOwner(owner(origin),"$binding:$event")
        synchronized(this){
            val old=record(key)
            if(old!=null&&old.optLong("revision")>revision)return
            if(old!=null&&old.optLong("revision")==revision&&old.s("action")==action&&old.s("state") in listOf("verified","resolved","revoked")){
                // Refresh authorization without downgrading the last verified
                // payload. A metadata hint is not a replacement question.
                if(forceRefresh)HollywoodInboxWorker.enqueue(key)
                return
            }
            val value=JSONObject().put("environment",origin.environment.id).put("owner",owner(origin)).put("bindingId",binding)
                .put("authorityId",authority).put("principalId",principal).put("eventId",event).put("roomId",room).put("revision",revision).put("action",action).put("state","hint")
            old?.optJSONObject("binding")?.let{value.put("binding",it)}
            save(key,value)
            val known=Pocket.prefs.getString("hollywood-binding:"+owner(origin),null)?.let{runCatching{JSONObject(it)}.getOrNull()}
            if(known?.s("bindingId")==binding&&known.s("authorityId")==authority&&known.s("principalId")==principal){
                if(action=="resolve")cancel(key)else show(key,value,generic=true)
            }
            HollywoodInboxWorker.enqueue(key)
        }
    }
    fun socket(origin:EnvironmentRequest,event:JSONObject){
        val b=event.optJSONObject("binding")?:return;val h=event.optJSONObject("hint")?:return
        receive(origin,mapOf("binding_id" to b.s("bindingId"),"authority_id" to b.s("authorityId"),"principal_id" to b.s("principalId"),
            "event_id" to h.s("eventId"),"room_id" to h.s("roomId"),"revision" to h.optLong("revision").toString(),"action" to if(h.optBoolean("notify"))"notify" else "resolve"))
    }
    fun cancel(key:String){ForegroundNotifications.cancelRoom(key);Pocket.context.getSystemService(NotificationManager::class.java).cancel(tag(key),1)}
    private fun open(key:String)=PendingIntent.getActivity(Pocket.context,0,Intent(Pocket.context,MainActivity::class.java)
        .setData(Uri.Builder().scheme("pocket").authority("hollywood").appendPath(key).build())
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("hollywoodNotification",key),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun show(key:String,value:JSONObject,generic:Boolean=false,status:String?=null,quiet:Boolean=false){
        if(ForegroundNotifications.active()){ForegroundNotifications.offerRoom(key,value,generic,status);return}
        val c=Pocket.context;val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("hollywood", "Hollywood rooms",NotificationManager.IMPORTANCE_HIGH))
        val b=NotificationCompat.Builder(c,"hollywood").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(status?:if(generic)"Hollywood update" else value.optJSONObject("hint")?.s("roomName","Hollywood").orEmpty())
            .setContentText(if(generic)"Open the room to review" else value.optJSONObject("event")?.s("body")?.take(2000).orEmpty())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setSilent(quiet).setOnlyAlertOnce(true).setAutoCancel(false).setContentIntent(open(key))
            .addExtras(Bundle().apply{putString("hollywoodEvent",value.s("eventId"));putString("hollywoodBinding",value.s("bindingId"));putString("hollywoodEnvironment",value.s("environment"));putLong("hollywoodRevision",value.optLong("revision"))})
        if(!generic&&status==null){
            val attention=value.optJSONObject("attention")?.optJSONObject("attention")
            val questions=attention?.optJSONObject("request")?.optJSONArray("questions")
            val inline=attention?.s("state")=="pending"&&value.optJSONObject("attention")?.s("deliveryState") in listOf("claimed","running")&&questions?.length()==1&&questions.getJSONObject(0).optBoolean("isOther",true)
            if(value.optJSONObject("reply")!=null||inline){
                val revision=captureReplyAction(key,value)
                val action=PendingIntent.getBroadcast(c,0,Intent(c,HollywoodReplyReceiver::class.java)
                    .setData(Uri.Builder().scheme("pocket").authority("hollywood-reply").appendPath(key).appendPath(revision.toString()).build())
                    .putExtra("key",key).putExtra("revision",revision),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_notification,if(inline)"Answer" else "Reply",action)
                    .addRemoteInput(RemoteInput.Builder("reply").setLabel("Your reply…").build()).setAllowGeneratedReplies(false).build())
            }
            b.addAction(R.drawable.ic_notification,if(attention!=null)"Review request" else "Open room",open(key))
        }
        try{NotificationManagerCompat.from(c).notify(tag(key),1,b.build())}catch(_:SecurityException){}
    }
    suspend fun verify(key:String):JSONObject?{
        val old=record(key)?:return null;val origin=Pocket.captureEnvironment(old.s("environment"))?:return null
        if(owner(origin)!=old.s("owner"))return null
        val binding=Pocket.apiEnvironment(origin,"/api/hollywood/v3/identity")
        if(listOf("bindingId","authorityId","principalId").any{binding.s(it)!=old.s(it)})return null
        val client=HollywoodClient(old.s("owner"),binding,origin.endpoint.trimEnd('/'),origin.token)
        val value=client.call("inbox/events/"+old.s("eventId"));val h=value.getJSONObject("hint");val event=value.getJSONObject("event")
        require(h.s("eventId")==old.s("eventId")&&h.s("roomId")==old.s("roomId")&&event.s("id")==old.s("eventId")&&event.s("room")==old.s("roomId"))
        require(h.s("authorityId")==binding.s("authorityId")&&h.s("principalId")==binding.s("principalId"))
        if(h.optLong("revision")<old.optLong("revision"))return null
        val result=JSONObject(old.toString()).put("revision",h.getLong("revision")).put("binding",binding).put("hint",h).put("event",event)
            .put("action",if(h.optBoolean("notify"))"notify" else "resolve").put("state",if(h.optBoolean("notify"))"verified" else "resolved")
        result.remove("reply");result.remove("attention")
        if(h.s("attentionId").isNotBlank())result.put("attention",client.call("attention/"+h.s("attentionId")))
        else if(h.optBoolean("notify")){
            val room=client.call("rooms/"+h.s("roomId"));val actor=room.getJSONArray("actors").objects().firstOrNull{it.s("id")==event.s("author")}
            if(actor!=null&&room.getJSONObject("permissions").optBoolean(if(actor.s("kind")=="agent")"can_request" else "can_post"))
                result.put("reply",HollywoodScope(h.s("roomId"),event.s("author"),event.s("audience"),event.s("id"),if(actor.s("kind")=="agent")"request" else "message").json())
        }
        synchronized(this){
            val current=record(key)?:return null
            if(current.optLong("revision")>result.optLong("revision"))return null
            save(key,result)
        }
        rememberBinding(origin,binding);return result
    }
    /** Direct catalog navigation shares the existing typed room destination, not a fake alert. */
    fun openRoom(origin:EnvironmentRequest,binding:JSONObject,room:String,keyboard:Boolean=false){
        require(Pocket.captureEnvironment()?.matches(origin)==true){"Select the original environment"}
        require(!PocketVoice.active){"Finish voice before opening another room"}
        require(hollywoodUuid(room)&&binding.s("bindingId").matches(Regex("[0-9a-f]{64}")))
        BackendNavigation.select("lax")
        target=HollywoodOpenTarget(owner(origin),binding.s("bindingId"),room,"",null,null,keyboard)
    }
    fun openIntent(key:String){
        if(PocketVoice.active){Pocket.error="Finish voice before opening another room";return}
        Pocket.scope.launch{try{
            val value=verify(key)?:throw IllegalStateException("This alert retains another environment or pairing")
            Pocket.activateEnvironment(value.s("environment"))
            require(Pocket.environmentId==value.s("environment")&&backendOwner(Pocket.base,Pocket.token)==value.s("owner")){"Select the original environment"}
            BackendNavigation.select("lax")
            target=HollywoodOpenTarget(value.s("owner"),value.s("bindingId"),value.s("roomId"),value.s("eventId"),value.optJSONObject("attention"),value.optJSONObject("reply")?.let(HollywoodScope::from))
            cancel(key)
        }catch(e:Exception){if(e is CancellationException)throw e;Pocket.error=e.message?:"Could not open the original room"}}
    }
    private val syncing=mutableSetOf<String>()
    fun catchUp(origin:EnvironmentRequest,binding:JSONObject){
        val identity=JSONObject(binding.toString());val who=owner(origin);val key="$who:${identity.s("bindingId")}";if(!syncing.add(key))return
        Pocket.scope.launch{try{
            val client=HollywoodClient(who,identity,origin.endpoint.trimEnd('/'),origin.token)
            fun accept(h:JSONObject){receive(origin,mapOf("binding_id" to identity.s("bindingId"),"authority_id" to identity.s("authorityId"),"principal_id" to identity.s("principalId"),
                "event_id" to h.s("eventId"),"room_id" to h.s("roomId"),"revision" to h.optLong("revision").toString(),"action" to if(h.optBoolean("notify"))"notify" else "resolve"),forceRefresh=true)}
            val cursorKey="hollywood-inbox-cursor:$key"
            var cursor=Pocket.prefs.getLong(cursorKey,-1)
            if(cursor<0){cursor=client.call("inbox").getLong("nextCursor");check(Pocket.prefs.edit().putLong(cursorKey,cursor).commit())}
            val pendingKey="hollywood-pending-cursor:$key"
            var pendingCursor=Pocket.prefs.getLong(pendingKey,0)
            for(pageCount in 0 until 5){
                val pending=client.call("inbox/pending?after=$pendingCursor&limit=100")
                pending.getJSONArray("events").objects().filter{it.optBoolean("notify")}.forEach(::accept)
                pendingCursor=if(pending.getJSONArray("events").length()<100)0 else pending.getLong("nextCursor")
                check(Pocket.prefs.edit().putLong(pendingKey,pendingCursor).commit())
                if(pendingCursor==0L)break
            }
            val known=Pocket.prefs.all.filterKeys{it.startsWith("hollywood-notification:")}.values.mapNotNull{raw->runCatching{JSONObject(raw as String)}.getOrNull()}
                .filter{it.s("owner")==who&&it.s("bindingId")==identity.s("bindingId")}.map{it.s("eventId")}.toSet()
            for(pageCount in 0 until 5){
                val page=client.call("inbox?after=$cursor&limit=100")
                for(h in page.getJSONArray("events").objects())if(h.optBoolean("notify")||h.s("eventId") in known)accept(h)
                cursor=page.getLong("nextCursor");check(Pocket.prefs.edit().putLong(cursorKey,cursor).commit())
                if(page.getJSONArray("events").length()<100)break
            }
            val manager=Pocket.context.getSystemService(NotificationManager::class.java)
            for(n in manager.activeNotifications.filter{it.notification.extras.getString("hollywoodBinding")==identity.s("bindingId")&&it.notification.extras.getString("hollywoodEnvironment")==origin.environment.id}){
                val event=n.notification.extras.getString("hollywoodEvent")?:continue
                val fresh=client.call("inbox/events/$event");accept(fresh.getJSONObject("hint"))
            }
        }catch(e:Exception){if(e is CancellationException)throw e}finally{syncing.remove(key)}}
    }
    fun consumed(value:HollywoodOpenTarget){if(target===value)target=null}
}

/** Network work is authenticated GET only; FCM never carries a native command or answer. */
class HollywoodInboxWorker(c:Context,p:WorkerParameters):Worker(c,p){
    override fun doWork():Result {
        val key=inputData.getString("key")?:return Result.success()
        return try{val value=runBlocking{HollywoodNotifications.verify(key)}
            if(value==null)Result.failure()else{if(value.s("action")=="resolve")HollywoodNotifications.cancel(key)else HollywoodNotifications.show(key,value);Result.success()}
        }catch(e:Exception){
            if(e is HollywoodFailure&&e.status in listOf(403,404)||e is PocketApiException&&e.status in listOf(401,403,404)){
                HollywoodNotifications.cancel(key);HollywoodNotifications.record(key)?.let{it.put("state","revoked");HollywoodNotifications.save(key,it)};Result.failure()
            }else if(runAttemptCount<8)Result.retry()else Result.failure()
        }
    }
    companion object{fun enqueue(key:String){
        val request=OneTimeWorkRequestBuilder<HollywoodInboxWorker>().setInputData(workDataOf("key" to key)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(Pocket.context).enqueueUniqueWork("hollywood-inbox-$key",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }}
}

class HollywoodReplyReceiver:BroadcastReceiver(){
    override fun onReceive(c:Context,i:Intent){
        val text=RemoteInput.getResultsFromIntent(i)?.getCharSequence("reply")?.toString()?.trim()?.takeIf{it.isNotBlank()}?:return
        val key=i.getStringExtra("key")?:return
        val revision=i.getLongExtra("revision",-1)
        // New actions retain their verified payload even if a refresh or
        // resolution replaces the current notification. Older installed actions
        // may use their still-verified current record; no scope is guessed.
        val record=if(revision>=1)HollywoodNotifications.replySnapshot(key,revision)
            else HollywoodNotifications.record(key)?.takeIf{it.s("state")=="verified"}
        if(record==null)return
        val origin=Pocket.captureEnvironment(record.s("environment"))?:return
        if(backendOwner(origin.endpoint,origin.token)!=record.s("owner"))return
        val binding=record.optJSONObject("binding")?:return;val client=HollywoodClient(record.s("owner"),binding,origin.endpoint.trimEnd('/'),origin.token)
        val id=backendOwner(key,record.optLong("revision").toString())
        val existing=Pocket.prefs.getString("hollywood-reply:$id",null)?.let{JSONObject(it)}
        if(existing!=null){
            if(existing.s("text")==text&&existing.s("state")!="accepted")HollywoodReplyWorker.enqueue(c,id)
            return
        }
        try{
            val attention=record.optJSONObject("attention")
            if(attention!=null)require(attention.getJSONObject("attention").s("state")=="pending"&&attention.s("deliveryState") in listOf("claimed","running")){"This native question is no longer accepting answers"}
            val body=JSONObject(record.toString()).put("state","saved").put("text",text)
            val intent=if(attention!=null){
                val questions=attention.getJSONObject("attention").getJSONObject("request").getJSONArray("questions");require(questions.length()==1)
                val question=questions.getJSONObject(0);require(question.optBoolean("isOther",true))
                client.captureAttention(attention,JSONObject().put("answers",JSONObject().put(question.getString("id"),JSONObject().put("answers",org.json.JSONArray(listOf(text))))))
            }else client.capture(HollywoodScope.from(record.getJSONObject("reply")),text)
            body.put("intent",intent)
            check(Pocket.prefs.edit().putString("hollywood-reply:$id",body.toString()).commit())
            HollywoodReplyWorker.enqueue(c,id);HollywoodNotifications.show(key,record,status="Reply saved · sending")
        }catch(_:Exception){HollywoodNotifications.show(key,record,status="Reply needs checking · open the room")}
    }
}

/** After a send may have started, recovery reads only its original operation receipt. */
class HollywoodReplyWorker(c:Context,p:WorkerParameters):Worker(c,p){
    override fun doWork():Result {
        val id=inputData.getString("id")?:return Result.success();val raw=Pocket.prefs.getString("hollywood-reply:$id",null)?:return Result.success()
        val saved=JSONObject(raw);if(saved.s("state")=="accepted")return Result.success()
        if(saved.s("state")=="rejected")return Result.failure()
        val origin=Pocket.captureEnvironment(saved.s("environment"))?:return Result.failure()
        if(backendOwner(origin.endpoint,origin.token)!=saved.s("owner"))return Result.failure()
        return try{runBlocking{
            val binding=Pocket.apiEnvironment(origin,"/api/hollywood/v3/identity");require(hollywoodBindingMatches(saved.getJSONObject("binding"),binding))
            val client=HollywoodClient(saved.s("owner"),binding,origin.endpoint.trimEnd('/'),origin.token);val intent=saved.getJSONObject("intent")
            val inspect=saved.s("state")!="saved"
            if(!inspect){
                client.call("inbox/events/"+saved.s("eventId"))
                saved.put("state","sending");check(Pocket.prefs.edit().putString("hollywood-reply:$id",saved.toString()).commit())
            }
            val attention=saved.optJSONObject("attention")
            if(attention!=null)client.respond(attention.getJSONObject("attention").s("id"),intent,inspect)
            else{val scope=HollywoodScope.from(saved.getJSONObject("reply"));if(inspect)client.inspect(scope,intent)else client.submit(scope,intent)}
            saved.put("state","accepted");check(Pocket.prefs.edit().putString("hollywood-reply:$id",saved.toString()).commit())
            HollywoodNotifications.cancel(backendOwner(saved.s("owner"),saved.s("bindingId")+":"+saved.s("eventId")))
        };Result.success()}catch(error:Exception){
            val attention=saved.optJSONObject("attention")
            val shared=attention?.let{a->Pocket.prefs.getString("attention:"+saved.s("owner")+":"+saved.s("bindingId")+":"+a.getJSONObject("attention").s("id"),null)?.let(::JSONObject)}
            val rejected=shared?.s("state")=="rejected"||(saved.s("state")=="saved"&&error is HollywoodFailure&&error.code=="HOLLYWOOD_REQUEST_REJECTED")
            saved.put("state",if(rejected)"rejected" else if(saved.s("state")=="saved")"saved" else "unknown")
                .put("error",error.message.orEmpty().take(500))
            Pocket.prefs.edit().putString("hollywood-reply:$id",saved.toString()).commit()
            HollywoodNotifications.record(backendOwner(saved.s("owner"),saved.s("bindingId")+":"+saved.s("eventId")))?.let{record->HollywoodNotifications.show(backendOwner(saved.s("owner"),saved.s("bindingId")+":"+saved.s("eventId")),record,status="Reply needs checking · open the room")}
            // Unknown delivery retries GET of the original receipt, never the POST.
            if(saved.s("state") in listOf("saved","unknown")&&runAttemptCount<8)Result.retry()else Result.failure()
        }
    }
    companion object{fun enqueue(context:Context,id:String){
        val request=OneTimeWorkRequestBuilder<HollywoodReplyWorker>().setInputData(workDataOf("id" to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("hollywood-reply-$id",ExistingWorkPolicy.KEEP,request)
    }}
}
