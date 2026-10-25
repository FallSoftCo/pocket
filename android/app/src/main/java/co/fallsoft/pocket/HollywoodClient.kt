package co.fallsoft.pocket

import kotlinx.coroutines.ensureActive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

private object HollywoodAttentionIntents
internal class HollywoodStopReceiptNotSaved(val receipt:JSONObject,val confirmation:String):java.io.IOException("Stop confirmed by Hollywood; the device receipt could not be saved.")
internal class HollywoodAcceptedReceiptNotSaved(val receipt:JSONObject):java.io.IOException("Accepted by Hollywood; the device receipt could not be saved. Check the original delivery.")

/** Captured source profile and human binding. A pending action never follows navigation. */
internal class HollywoodClient(val owner:String,source:JSONObject,
    private val base:String=Pocket.base.trimEnd('/'),private val token:String=Pocket.token) {
    private val identity=JSONObject(source.toString())
    private val http=Pocket.http.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    val bindingId=identity.s("bindingId")
    val authorityId=identity.s("authorityId")
    val principalId=identity.s("principalId")
    val defaultRoomId=identity.s("defaultRoomId")
    val contextReviewEnabled=identity.optBoolean("contextReview",false)
    init { require(bindingId.matches(Regex("[0-9a-f]{64}")));require(listOf(authorityId,principalId,defaultRoomId).all(::hollywoodUuid)) }
    suspend fun call(path:String,body:JSONObject?=null,method:String=if(body==null)"GET" else "POST"):JSONObject=withContext(Dispatchers.IO){
        val request=Request.Builder().url("$base/api/hollywood/v3/$path")
            .header("Authorization","Bearer $token").header("X-Hollywood-Binding",bindingId)
        require(method in listOf("GET","POST","PUT")&&(body!=null||method=="GET"))
        if(body!=null)request.method(method,body.toString().toRequestBody("application/json".toMediaType()))
        val built=request.build()
        // A lost write acknowledgement can leave its pooled socket stale.
        // Retry only GET, once, on an isolated fresh connection. Never replay a write
        // or evict the shared pool used by live transport/audio.
        var attempt=0
        while(true){
            val transport=if(attempt==0)http else http.newBuilder()
                .connectionPool(okhttp3.ConnectionPool(0,1,java.util.concurrent.TimeUnit.MILLISECONDS)).build()
            try{
                return@withContext transport.newCall(built).execute().use{response->
                    val raw=response.body?.string().orEmpty()
                    val value=runCatching{JSONObject(raw)}.getOrElse{
                        throw HollywoodFailure(response.code,"Hollywood reply is unconfirmed.","HOLLYWOOD_OUTCOME_UNCONFIRMED")}
                    if(!response.isSuccessful)throw HollywoodFailure(response.code,value.s("error","Hollywood is unavailable."),value.s("code"))
                    val binding=value.optJSONObject("binding")?:throw HollywoodFailure(502,"Hollywood binding is unconfirmed.","HOLLYWOOD_OUTCOME_UNCONFIRMED")
                    require(hollywoodBindingMatches(identity,binding)){"Reply belongs to another environment or human identity"}
                    value
                }
            }catch(e:java.io.IOException){
                if(body!=null||attempt++>0)throw e
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Read attempt ended without a result")
    }
    fun key(scope:HollywoodScope)="hollywood:$owner:$bindingId:${scope.room}:${scope.recipient}:${scope.audience}:${scope.sourceEvent?:"root"}:${scope.kind}"
    fun draft(scope:HollywoodScope)=Pocket.prefs.getString("draft:"+key(scope),"").orEmpty()
    fun saveDraft(scope:HollywoodScope,text:String){Pocket.prefs.edit().putString("draft:"+key(scope),text).putString("scope:"+key(scope),scope.json().toString()).apply()}
    fun savedScopes(room:String):List<HollywoodScope> = Pocket.prefs.all.filterKeys{it.startsWith("scope:hollywood:$owner:$bindingId:")}.values
        .mapNotNull{value->runCatching{HollywoodScope.from(JSONObject(value as String))}.getOrNull()}
        .distinct().filter{it.room==room&&(draft(it).isNotBlank()||pending(it)!=null)}
    fun pending(scope:HollywoodScope)=Pocket.prefs.getString("pending:"+key(scope),null)?.let{JSONObject(it)}
    private fun persist(key:String,value:JSONObject?){
        val edit=Pocket.prefs.edit();if(value==null)edit.remove(key)else edit.putString(key,value.toString())
        if(!edit.commit())throw java.io.IOException("Could not save the original delivery state. Your draft is retained.")
    }
    fun capture(scope:HollywoodScope,text:String):JSONObject {
        require(text.isNotBlank()&&text.toByteArray(Charsets.UTF_8).size<=6000){"Message is too long. Shorten it before sending; your draft is kept."}
        require(pending(scope)==null){"Inspect the original pending delivery first"}
        val body=scope.message("phone-room:"+UUID.randomUUID(),text)
        val intent=JSONObject().put("binding",JSONObject(identity.toString())).put("scope",scope.json()).put("body",body).put("state","saved")
        persist("pending:"+key(scope),intent);return intent
    }
    fun messageReceipt(scope:HollywoodScope,operation:String)=Pocket.prefs.getString("receipt:"+key(scope)+":"+operation,null)?.let(::JSONObject)
    private fun settleMessage(scope:HollywoodScope,intent:JSONObject,receipt:JSONObject):JSONObject {
        val operation=intent.getJSONObject("body").s("operationId")
        synchronized(HollywoodAttentionIntents){
            val edit=Pocket.prefs.edit().putString("receipt:"+key(scope)+":"+operation,
                JSONObject().put("binding",identity).put("scope",scope.json()).put("operationId",operation).put("receipt",receipt).toString())
            if(pending(scope)?.optJSONObject("body")?.s("operationId")==operation)edit.remove("pending:"+key(scope))
            if(!edit.commit())throw HollywoodAcceptedReceiptNotSaved(receipt)
        }
        return receipt
    }
    suspend fun submit(scope:HollywoodScope,intent:JSONObject):JSONObject {
        require(hollywoodBindingMatches(identity,intent.getJSONObject("binding")))
        val receipt=call("rooms/${scope.room}/messages",intent.getJSONObject("body"))
        require(hollywoodMessageMatches(identity,scope,intent.getJSONObject("body"),receipt)){"Original message receipt is unconfirmed"}
        return settleMessage(scope,intent,receipt)
    }
    suspend fun inspect(scope:HollywoodScope,intent:JSONObject):JSONObject {
        require(hollywoodBindingMatches(identity,intent.getJSONObject("binding")))
        val receipt=call("commands/"+intent.getJSONObject("body").s("operationId"))
        require(hollywoodMessageMatches(identity,scope,intent.getJSONObject("body"),receipt)){"Original message receipt is unconfirmed"}
        return settleMessage(scope,intent,receipt)
    }
    private fun stopKey(id:String)="stop:$owner:$bindingId:$id"
    fun stopRecord(id:String)=Pocket.prefs.getString(stopKey(id),null)?.let(::JSONObject)
    fun unresolvedStops(room:String)=Pocket.prefs.all.filterKeys{it.startsWith("stop:$owner:$bindingId:")}.values
        .mapNotNull{runCatching{JSONObject(it as String)}.getOrNull()}
        .filter{it.optJSONObject("scope")?.s("roomId")==room&&it.s("state") in listOf("saved","sending","unknown")}

    /** One durable stop for the original delivery. Lost acknowledgements only read it back. */
    suspend fun stopDelivery(room:String,target:JSONObject,inspect:Boolean=false):JSONObject {
        val id=target.s("id")
        require(hollywoodUuid(room)&&hollywoodUuid(id)&&hollywoodUuid(target.s("agent"))&&hollywoodUuid(target.s("event")))
        val scope=JSONObject().put("authorityId",authorityId).put("roomId",room).put("deliveryId",id)
            .put("agentId",target.s("agent")).put("requestId",target.s("event"))
        val dispatch=synchronized(HollywoodAttentionIntents){
            val current=stopRecord(id)?:JSONObject().put("binding",JSONObject(identity.toString())).put("scope",scope).put("state","saved")
            require(hollywoodBindingMatches(identity,current.getJSONObject("binding"))&&hollywoodJsonEqual(scope,current.getJSONObject("scope"))){"Stop belongs to another original delivery"}
            val send=!inspect&&current.s("state")=="saved"
            if(send)current.put("state","sending")
            persist(stopKey(id),current)
            send
        }
        var submissionError:Exception?=null
        if(dispatch)try{call("deliveries/$id/cancel",JSONObject())}
        catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){submissionError=e}
        try {
            // The authoritative read is required even after a successful HTTP
            // response. It also recovers a lost reply without a second stop.
            val observed=call("deliveries/$id")
            val delivery=observed.getJSONObject("delivery");val request=observed.getJSONObject("request")
            require(delivery.s("id")==id&&delivery.s("authority")==authorityId&&delivery.s("agent")==scope.s("agentId")&&
                delivery.s("event")==scope.s("requestId")&&request.s("id")==scope.s("requestId")&&
                request.s("authority")==authorityId&&request.s("room")==room){"Original stop identity could not be verified"}
            val confirmed=when {
                delivery.s("state")=="cancelled"->"cancelled"
                delivery.optBoolean("cancellation_requested")||delivery.s("state")=="cancel_requested"->"requested"
                delivery.s("state") in listOf("completed","failed")->"finished"
                else->"unknown"
            }
            synchronized(HollywoodAttentionIntents){
                val current=requireNotNull(stopRecord(id));current.put("state",confirmed).put("receipt",observed)
                try{persist(stopKey(id),current)}catch(e:java.io.IOException){
                    if(confirmed!="unknown")throw HollywoodStopReceiptNotSaved(observed,confirmed)
                    throw e
                }
            }
            if(confirmed=="unknown")throw java.io.IOException("Stop is not confirmed. "+submissionError?.message.orEmpty().take(160))
            return observed.put("stopConfirmation",confirmed)
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:HollywoodStopReceiptNotSaved){throw e}
        catch(e:Exception){
            synchronized(HollywoodAttentionIntents){stopRecord(id)?.let{current->
                current.put("state","unknown").put("error",e.message.orEmpty().take(300));persist(stopKey(id),current)
            }}
            throw e
        }
    }

    private fun guidanceKey(scope:JSONObject)="guidance:"+owner+":"+bindingId+":"+scope.s("deliveryId")+":"+scope.s("nativeTurn")
    fun guidancePending(scope:JSONObject)=Pocket.prefs.getString(guidanceKey(scope),null)?.let{JSONObject(it)}
    fun savedGuidance(room:String):List<JSONObject> = Pocket.prefs.all.filterKeys{it.startsWith("guidance:"+owner+":"+bindingId+":")}.values
        .mapNotNull{runCatching{JSONObject(it as String)}.getOrNull()}
        .filter{it.optJSONObject("body")?.optJSONObject("scope")?.s("roomId")==room}
        .distinctBy{it.getJSONObject("body").s("id")}
    fun guidanceDraft(scope:JSONObject)=Pocket.prefs.getString("draft:"+guidanceKey(scope),"").orEmpty()
    fun saveGuidanceDraft(scope:JSONObject,text:String){Pocket.prefs.edit().putString("draft:"+guidanceKey(scope),text).putString("guidance-scope:"+guidanceKey(scope),scope.toString()).apply()}
    fun savedGuidanceScopes(room:String):List<JSONObject> {
        val records=savedGuidance(room).map{it.getJSONObject("body").getJSONObject("scope")}
            .filter{scope->guidancePending(scope)?.s("state")?.let{it !in listOf("native_recorded","rejected")}==true}
        val drafts=Pocket.prefs.all.filterKeys{it.startsWith("guidance-scope:guidance:"+owner+":"+bindingId+":")}.values
            .mapNotNull{runCatching{JSONObject(it as String)}.getOrNull()}.filter{it.s("roomId")==room&&guidanceDraft(it).isNotBlank()}
        return (records+drafts).distinctBy{it.s("deliveryId")+":"+it.s("nativeTurn")}
    }
    fun captureGuidance(scope:JSONObject,text:String):JSONObject = synchronized(HollywoodAttentionIntents) {
        require(scope.s("authorityId")==authorityId&&text.isNotBlank())
        val previous=guidancePending(scope)
        require(previous==null||previous.s("state") in listOf("native_admitted","native_recorded","rejected")){"Inspect the original guidance first"}
        previous?.let{persist(guidanceKey(scope)+":"+it.getJSONObject("body").s("id"),it)}
        val id=UUID.randomUUID().toString()
        val body=JSONObject().put("id",id).put("operationId","phone-guidance:"+id).put("scope",JSONObject(scope.toString())).put("body",text)
        val intent=JSONObject().put("binding",JSONObject(identity.toString())).put("body",body).put("state","saved")
        persist(guidanceKey(scope),intent);intent
    }
    suspend fun guide(intent:JSONObject,inspect:Boolean=false):JSONObject {
        require(hollywoodBindingMatches(identity,intent.getJSONObject("binding")))
        val body=intent.getJSONObject("body");val scope=body.getJSONObject("scope");val key=guidanceKey(scope)
        val reading=synchronized(HollywoodAttentionIntents){
            val current=guidancePending(scope)?:throw IllegalStateException("Original guidance is missing")
            require(hollywoodJsonEqual(current.getJSONObject("body"),body))
            val read=inspect||current.s("state")!="saved"
            if(!read){current.put("state","sending");persist(key,current)}
            read
        }
        try {
            val receipt=if(reading)call("native-controls/"+body.s("id"))else call("deliveries/"+scope.s("deliveryId")+"/guidance",body)
            val control=receipt.getJSONObject("control")
            require(hollywoodJsonEqual(receipt.getJSONObject("scope"),scope)&&
                hollywoodJsonEqual(control.getJSONObject("captured_scope"),scope)&&
                control.s("id")==body.s("id")&&control.s("operation")==body.s("operationId")&&
                control.s("human")==principalId&&control.s("body")==body.s("body")){"Original guidance receipt is unconfirmed"}
            synchronized(HollywoodAttentionIntents){
                val current=guidancePending(scope)?:throw IllegalStateException("Original guidance is missing")
                require(hollywoodJsonEqual(current.getJSONObject("body"),body))
                current.put("state",control.s("state")).put("receipt",receipt);persist(key,current)
            }
            return receipt
        } catch(error:Exception) {
            synchronized(HollywoodAttentionIntents){
                val current=guidancePending(scope)
                if(current!=null&&hollywoodJsonEqual(current.getJSONObject("body"),body)){
                    val rejected=!reading&&error is HollywoodFailure&&error.code=="HOLLYWOOD_REQUEST_REJECTED"
                    current.put("state",if(rejected)"rejected" else "unknown").put("error",error.message.orEmpty().take(500))
                    persist(key,current)
                }
            }
            throw error
        }
    }
    fun attentionPending(id:String)=Pocket.prefs.getString("attention:$owner:$bindingId:$id",null)?.let{JSONObject(it)}
    fun captureAttention(value:JSONObject,response:JSONObject):JSONObject = synchronized(HollywoodAttentionIntents) {
        val attention=value.getJSONObject("attention");val scope=value.getJSONObject("scope");val id=attention.s("id")
        require(attentionPending(id)==null){"Inspect the original pending response first"}
        require(scope.s("authorityId")==authorityId&&scope.s("attentionId")==id)
        require(attention.s("state")=="pending"&&value.s("deliveryState") in listOf("claimed","running")){"This native request is no longer accepting answers"}
        val body=JSONObject().put("operationId","phone-attention:"+UUID.randomUUID()).put("scope",JSONObject(scope.toString())).put("response",JSONObject(response.toString()))
        val intent=JSONObject().put("binding",JSONObject(identity.toString())).put("body",body).put("state","saved")
        persist("attention:$owner:$bindingId:$id",intent);intent
    }
    fun editRejectedAttention(id:String):JSONObject = synchronized(HollywoodAttentionIntents) {
        val current=attentionPending(id)?:throw IllegalStateException("No rejected answer is saved")
        require(current.s("state")=="rejected"){"Unknown delivery must be inspected first"}
        val archive="attention-rejected:$owner:$bindingId:$id:"+current.getJSONObject("body").s("operationId")
        persist(archive,current)
        persist("attention:$owner:$bindingId:$id",null)
        current.getJSONObject("body").getJSONObject("response")
    }
    suspend fun respond(id:String,intent:JSONObject,inspect:Boolean=false):JSONObject {
        require(hollywoodBindingMatches(identity,intent.getJSONObject("binding")))
        val body=intent.getJSONObject("body")
        val inspecting=synchronized(HollywoodAttentionIntents){
            val current=attentionPending(id)
            if(current!=null)require(hollywoodJsonEqual(current.getJSONObject("body"),body)){"Another saved response owns this question"}
            if(current?.s("state")=="rejected")throw HollywoodFailure(current.optInt("errorStatus",409),current.s("error","The original response was rejected."),"HOLLYWOOD_REQUEST_REJECTED")
            val readOnly=inspect||current==null||current.s("state")!="saved"
            if(!readOnly){
                // Persist before dispatch. A process loss can only inspect this exact operation.
                current!!.put("state","sending")
                persist("attention:$owner:$bindingId:$id",current)
            }
            readOnly
        }
        try{
            val receipt=if(inspecting)call("attention/$id/commands/"+body.s("operationId"))else call("attention/$id/response",body)
            require(hollywoodJsonEqual(receipt.getJSONObject("scope"),body.getJSONObject("scope")))
            val attention=receipt.getJSONObject("attention")
            require(attention.s("response_author")==principalId&&hollywoodJsonEqual(attention.getJSONObject("response"),body.getJSONObject("response")))
            synchronized(HollywoodAttentionIntents){
                val current=attentionPending(id)
                require(current==null||hollywoodJsonEqual(current.getJSONObject("body"),body))
                persist("attention:$owner:$bindingId:$id",null)
            }
            return receipt
        }catch(error:Exception){
            synchronized(HollywoodAttentionIntents){
                val current=attentionPending(id)
                if(current!=null&&hollywoodJsonEqual(current.getJSONObject("body"),body)){
                    val rejected=!inspecting&&error is HollywoodFailure&&error.code=="HOLLYWOOD_REQUEST_REJECTED"
                    current.put("state",if(rejected)"rejected" else "unknown")
                        .put("error",error.message.orEmpty().take(500))
                    if(error is HollywoodFailure)current.put("errorStatus",error.status)
                    persist("attention:$owner:$bindingId:$id",current)
                }
            }
            throw error
        }
    }
}

internal class HollywoodFailure(val status:Int,message:String,val code:String):Exception(message)
internal fun hollywoodUuid(value:String)=value.matches(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
internal fun hollywoodBindingMatches(expected:JSONObject,actual:JSONObject)=
    listOf("bindingId","installationId","authorityId","principalId").all{expected.s(it).isNotBlank()&&expected.s(it)==actual.s(it)}
internal fun hollywoodJsonEqual(a:JSONObject,b:JSONObject):Boolean {
    val keys=a.keys().asSequence().toSet()
    if(keys!=b.keys().asSequence().toSet())return false
    return keys.all{key->val x=a.opt(key);val y=b.opt(key)
        when {x is JSONObject&&y is JSONObject->hollywoodJsonEqual(x,y)
              x is org.json.JSONArray&&y is org.json.JSONArray->x.toString()==y.toString()
              else->x==y}}
}
internal data class HollywoodScope(val room:String,val recipient:String,val audience:String="room",val sourceEvent:String?=null,val kind:String="request") {
    companion object { fun from(value:JSONObject)=HollywoodScope(value.s("room"),value.s("recipient"),value.s("audience"),value.s("sourceEvent").takeIf{it.isNotBlank()},value.s("kind")) }
    init {require(hollywoodUuid(room));require(hollywoodUuid(recipient));require(audience in listOf("room","direct"));require(sourceEvent==null||hollywoodUuid(sourceEvent));require(kind in listOf("message","request"))}
    fun json()=JSONObject().put("room",room).put("recipient",recipient).put("audience",audience).put("sourceEvent",sourceEvent).put("kind",kind)
    fun message(id:String,text:String)=JSONObject().put("operationId",id).put("kind",kind).put("body",text).put("audience",audience).put("recipientId",recipient).apply{sourceEvent?.let{put("sourceEventId",it)}}
}
internal fun hollywoodMessageMatches(identity:JSONObject,scope:HollywoodScope,body:JSONObject,receipt:JSONObject):Boolean {
    val event=receipt.optJSONObject("event")?:return false
    if(!receipt.optBoolean("accepted")||event.s("authority")!=identity.s("authorityId")||event.s("author")!=identity.s("principalId")||
        event.s("room")!=scope.room||event.s("kind")!=scope.kind||event.s("body")!=body.s("body")||event.s("audience")!=scope.audience||
        event.s("recipient")!=scope.recipient||event.s("source")!=scope.sourceEvent.orEmpty())return false
    val deliveries=receipt.optJSONArray("deliveries")?.objects()?:return false
    return if(scope.kind=="message")deliveries.isEmpty()else deliveries.size==1&&deliveries.single().s("agent")==scope.recipient
}
