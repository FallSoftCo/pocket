package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

internal object PocketReplies {
    private const val storeKey="client-replies:v1"
    private val journal by lazy{ClientReplyJournal({Pocket.prefs.getString(storeKey,"[]")?:"[]"},{Pocket.prefs.edit().putString(storeKey,it).commit()})}
    var revision by mutableIntStateOf(0);private set
    var busy by mutableStateOf(setOf<String>());private set
    var dispatching by mutableStateOf(setOf<String>());private set
    val clearedDrafts=mutableStateMapOf<String,ClearedReplyDraft>()
    private val sendingOwners=mutableMapOf<String,EnvironmentRequest>()
    fun profileChanged(){Pocket.sending=Pocket.captureEnvironment()?.let{selected->sendingOwners.values.any{it.matches(selected)}}==true}
    private fun current(intent:ClientReplyIntent):EnvironmentRequest?=Pocket.captureEnvironment(intent.environment)?.takeIf{backendOwner(it.endpoint,it.token)==intent.owner}
    fun entries(thread:String):List<ClientReplyIntent>{revision;return runCatching{journal.all().filter{it.environment==Pocket.environmentId&&it.thread==thread}}.getOrDefault(emptyList())}
    fun matchesCurrent(intent:ClientReplyIntent)=intent.environment==Pocket.environmentId&&Pocket.captureEnvironment()?.let{current(intent)?.matches(it)}==true
    fun visible(thread:String)=entries(thread).filter{it.state !in listOf("accepted","cancelled")&&Pocket.detail?.optJSONArray("outgoing")?.objects()?.none{row->row.s("id")==it.id}!=false}
    private fun clearDraft(intent:ClientReplyIntent):Boolean{
        if(current(intent)==null)return false
        val key=intent.draftKey?:return true
        if(Pocket.prefs.getString(key,null)!=intent.text||replyDraftGeneration(key)!=intent.draftGeneration)return true
        if(!Pocket.prefs.edit().remove(key).commit())return false
        clearedDrafts[key]=ClearedReplyDraft(intent.text,intent.draftGeneration);if(clearedDrafts.size>64)clearedDrafts.remove(clearedDrafts.keys.first())
        return true
    }
    private fun observed(intent:ClientReplyIntent,receipt:JSONObject,lookup:Boolean=false):String{
        val state=checkedReplyReceipt(receipt,intent,lookup)
        sendingOwners.remove(intent.id);profileChanged()
        val next=journal.update(intent.id){old->if(old.state in listOf("accepted","cancelled"))old else old.copy(phase="observed",state=state,message=receipt.s("result",receipt.s("error")))}
        if(next!=null){
            val cleared=if(next.state in admittedReplyStates)clearDraft(next)else false
            if(next.state in admittedReplyStates&&Pocket.captureEnvironment()?.let{current(next)?.matches(it)}==true)Pocket.dismissReplyFailure(next.thread,next.id)
            if(next.state=="cancelled"||(next.state=="accepted"&&cleared))journal.remove(next.id)
            revision++
        }
        return next?.state?:state
    }
    private fun checkMessage(original:String,message:String):String{
        val origin=original.substringBefore("\nDelivery check:")
        return if(origin.isBlank()||origin==message)message.take(1000)else origin.take(500)+"\nDelivery check: "+message.take(500)
    }
    private fun problem(intent:ClientReplyIntent,message:String){
        val next=runCatching{journal.update(intent.id){old->if(old.phase=="observed")old.copy(message=checkMessage(old.message,message)) else old.copy(state="unconfirmed",message=checkMessage(old.message,message))}}.getOrElse{if(Pocket.captureEnvironment()?.let{current(intent)?.matches(it)}==true)Pocket.recordReplyFailure(intent.thread,intent.id,"Could not update saved delivery state. Your message remains saved.");return}
        if(next!=null){revision++;if(current(next)?.matches(Pocket.captureEnvironment()?:return)==true)Pocket.recordReplyFailure(next.thread,next.id,next.message)}
    }
    fun submit(text:String,thread:String,mode:String,draftKey:String?,onDone:()->Unit){
        val destination=Pocket.captureEnvironment()?:return
        val owner=backendOwner(destination.endpoint,destination.token)
        try{
            val old=journal.all().firstOrNull{it.environment==destination.environment.id&&it.thread==thread&&it.text.trim()==text.trim()&&it.mode==mode&&it.state in listOf("unconfirmed","unknown")}
            if(old!=null){Pocket.recordReplyFailure(thread,old.id,if(old.owner==owner)"Check this message’s delivery before sending it again."else "This message was saved under a previous pairing. Review its saved copy before sending again.");check(old);return}
            val intent=ClientReplyIntent(java.util.UUID.randomUUID().toString(),destination.environment.id,owner,thread,text,mode,draftKey,draftGeneration=draftKey?.let{replyDraftGeneration(it)}?:"legacy")
            journal.put(intent);revision++;dispatch(intent,destination,onDone)
        }catch(e:Exception){Pocket.recordReplyFailure(thread,"storage",e.message?:"Could not save message")}
    }
    private fun dispatch(intent:ClientReplyIntent,destination:EnvironmentRequest,onDone:()->Unit={}){
        sendingOwners[intent.id]=destination;profileChanged();busy=busy+intent.id;dispatching=dispatching+intent.id
        Pocket.scope.launch{
            var marked=false
            try{
                if(current(intent)?.matches(destination)!=true)return@launch
                journal.update(intent.id){it.copy(phase="dispatched")}?:return@launch;marked=true;revision++
                val receipt=Pocket.apiEnvironment(destination,"/api/threads/${intent.thread}/reply",JSONObject().put("id",intent.id).put("text",intent.text).put("mode",intent.mode))
                val state=observed(intent,receipt)
                if(state in admittedReplyStates){if(Pocket.captureEnvironment()?.matches(destination)==true){Pocket.rememberSessionInteraction(intent.thread,"reply:"+intent.id);onDone()}}
                else if(state in listOf("failed","unknown")&&Pocket.captureEnvironment()?.matches(destination)==true)Pocket.recordReplyFailure(intent.thread,intent.id,receipt.s("error","Message delivery needs attention"))
                if(Pocket.captureEnvironment()?.matches(destination)==true&&Pocket.selected==intent.thread)Pocket.scheduleRefresh()
            }catch(e:CancellationException){throw e}catch(e:Exception){problem(intent,e.message?:"Message delivery could not be confirmed")}
            finally{busy=busy-intent.id;dispatching=dispatching-intent.id;sendingOwners.remove(intent.id);profileChanged();val pending=runCatching{journal.get(intent.id)}.getOrNull();if(pending?.state=="unconfirmed")Pocket.scope.launch{delay(800);if(current(pending)!=null)check(pending)};if(marked&&!Pocket.sending&&Pocket.selected==intent.thread&&Pocket.captureEnvironment()?.matches(destination)==true)entries(intent.thread).firstOrNull{it.phase=="prepared"&&it.id !in busy&&matchesCurrent(it)}?.let{next->current(next)?.let{dispatch(next,it)}}}
        }
    }
    fun reconcile(thread:String){
        entries(thread).filter{matchesCurrent(it)&&it.id !in busy}.take(16).forEach{intent->
            if(intent.phase=="prepared"){if(!Pocket.sending)current(intent)?.let{dispatch(intent,it)}}else check(intent)
        }
    }
    fun cancelPrepared(thread:String){runCatching{entries(thread).filter{matchesCurrent(it)&&it.phase=="prepared"}.forEach{journal.remove(it.id)};revision++}}

    fun event(thread:String,id:String,state:String,message:String=""){
        val intent=runCatching{journal.get(id)}.getOrNull()?:return
        if(intent.thread!=thread||intent.environment!=Pocket.environmentId||current(intent)?.matches(Pocket.captureEnvironment()?:return)!=true)return
        runCatching{observed(intent,JSONObject().put("id",id).put("state",state).put("result",message))}
    }
    fun check(intent:ClientReplyIntent){
        val destination=current(intent)?:return;if(intent.id in busy)return;busy=busy+intent.id
        Pocket.scope.launch{
            try{if(!matchesCurrent(intent))return@launch;val receipt=Pocket.apiEnvironment(destination,"/api/replies/${intent.id}");observed(intent,receipt,lookup=true)}
            catch(e:CancellationException){throw e}catch(e:Exception){problem(intent,if(e is PocketApiException&&e.status==404)"No receipt found yet. This does not prove the message wasn’t received." else e.message?:"Could not check delivery")}
            finally{busy=busy-intent.id}
        }
    }
    fun forget(intent:ClientReplyIntent){if(intent.id in busy)return;runCatching{if(matchesCurrent(intent)&&!clearDraft(intent))throw java.io.IOException("Could not remove saved draft");journal.remove(intent.id);revision++;Pocket.dismissReplyFailure(intent.thread,intent.id)}.onFailure{problem(intent,"Could not remove this saved copy. Your message remains saved.")}}
    /** Only called after explicit confirmation. Lookup first; retain original identity. */
    fun retry(intent:ClientReplyIntent){
        val destination=current(intent)?:return;if(intent.id in busy||Pocket.sending)return;busy=busy+intent.id
        Pocket.scope.launch{
            var missing=false;var handedOff=false
            try{
                val receipt=try{Pocket.apiEnvironment(destination,"/api/replies/${intent.id}")}catch(e:PocketApiException){if(e.status!=404)throw e;missing=true;null}
                if(receipt!=null){
                    val state=observed(intent,receipt,lookup=true)
                    if(state in listOf("failed","unknown")){
                        if(current(intent)?.matches(destination)!=true)return@launch
                        val result=Pocket.apiEnvironment(destination,"/api/threads/${intent.thread}/replies/${intent.id}",JSONObject().put("action","retry").put("confirmUnknown",true))
                        observed(intent,result)
                    }
                }else if(missing&&journal.get(intent.id)!=null){if(current(intent)?.matches(destination)!=true||Pocket.sending)return@launch;busy=busy-intent.id;handedOff=true;dispatch(intent,destination)}
            }catch(e:CancellationException){throw e}catch(e:Exception){problem(intent,e.message?:"Could not recover message")}
            finally{if(!handedOff)busy=busy-intent.id;if(Pocket.captureEnvironment()?.matches(destination)==true&&Pocket.selected==intent.thread)Pocket.scheduleRefresh()}
        }
    }
}
