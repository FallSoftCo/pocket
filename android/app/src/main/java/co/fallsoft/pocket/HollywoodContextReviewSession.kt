package co.fallsoft.pocket

import org.json.JSONObject
import java.util.UUID

private object ContextReviewIntents

/** Device journal for human review operations. It never admits LAX work. */
internal class HollywoodContextReviewSession(val client:HollywoodClient,val agent:String) {
    private val prefix="context-review:${client.owner}:${client.bindingId}:$agent"
    val base="agents/$agent/contexts/writer"
    init{require(hollywoodUuid(agent))}
    val draftId get()=Pocket.prefs.getString("$prefix:current",null)
    val parentId get()=Pocket.prefs.getString("$prefix:parent",null)
    val pending get()=Pocket.prefs.getString("$prefix:pending",null)?.let(::JSONObject)
    private fun save(key:String,value:JSONObject?){
        val edit=Pocket.prefs.edit()
        if(value==null)edit.remove(key)else edit.putString(key,value.toString())
        check(edit.commit()){ "Could not save the original review operation" }
    }
    private fun point(id:String,parent:String?=null){
        require(hollywoodUuid(id))
        val edit=Pocket.prefs.edit().putString("$prefix:current",id)
        if(parent==null)edit.remove("$prefix:parent")else edit.putString("$prefix:parent",parent)
        check(edit.commit()){ "Could not save this review" }
    }
    private fun checkScope(intent:JSONObject){
        require(intent.s("owner")==client.owner&&intent.s("bindingId")==client.bindingId&&
            intent.s("authorityId")==client.authorityId&&intent.s("principalId")==client.principalId&&
            intent.s("agentId")==agent&&intent.s("purpose")=="writer"){"Review belongs to another environment or agent"}
    }
    private fun verify(intent:JSONObject,receipt:JSONObject){
        checkScope(intent)
        val draft=receipt.optJSONObject("draft")
        val id=receipt.s("draftId").ifBlank{draft?.s("id").orEmpty()}
        require(id==intent.s("receiptDraftId")){"Original review receipt is unconfirmed"}
        val actor=receipt.s("reviewerId").ifBlank{draft?.s("reviewer").orEmpty()}
        require(actor==client.principalId){"Review belongs to another human"}
        val actual=receipt.s("agentId").ifBlank{draft?.s("agent").orEmpty()}
        require(actual==agent){"Review belongs to another agent"}
    }
    private fun settle(intent:JSONObject,receipt:JSONObject){
        verify(intent,receipt)
        synchronized(ContextReviewIntents){
            require(pending?.s("operationId")==intent.s("operationId")){"Original pending operation changed"}
            val edit=Pocket.prefs.edit().putString("$prefix:receipt:${intent.s("operationId")}",receipt.toString())
                .remove("$prefix:pending")
            check(edit.commit()){ "Accepted review receipt could not be saved; check the original operation" }
        }
    }
    private suspend fun write(path:String,method:String,body:JSONObject,receiptDraft:String){
        val intent=JSONObject().put("owner",client.owner).put("bindingId",client.bindingId)
            .put("authorityId",client.authorityId).put("principalId",client.principalId)
            .put("agentId",agent).put("purpose","writer").put("receiptDraftId",receiptDraft)
            .put("operationId",body.s("operationId")).put("path",path).put("method",method)
            .put("body",JSONObject(body.toString())).put("state","sending")
        synchronized(ContextReviewIntents){require(pending==null){"Check the original pending review first"};save("$prefix:pending",intent)}
        try{settle(intent,client.call(path,body,method))}
        catch(e:Exception){
            if(e is HollywoodFailure&&e.status in listOf(400,403,404,409,422)){
                save("$prefix:rejected",JSONObject(intent.toString()).put("error",e.message))
                save("$prefix:pending",null)
            }else save("$prefix:pending",JSONObject(intent.toString()).put("state","unknown"))
            throw e
        }
    }
    private fun operation()="phone-context:"+UUID.randomUUID()
    suspend fun create(replacing:Boolean=false){
        require(pending==null){"Check the original pending review first"}
        val old=draftId;val id=UUID.randomUUID().toString()
        point(id,if(replacing)old else null)
        write("$base/seed-drafts","POST",JSONObject().put("id",id).put("operationId",operation()),id)
    }
    suspend fun recover(){
        val intent=pending?:return
        checkScope(intent)
        val receipt=client.call("$base/seed-drafts/${intent.s("receiptDraftId")}/operations/${intent.s("operationId")}")
        settle(intent,receipt)
    }
    suspend fun mark(reference:String,keep:Boolean,preview:JSONObject){
        val id=draftId?:error("Create a review first")
        val record=if(keep)client.call("$base/seed-sources?reference="+android.net.Uri.encode(reference)).getJSONObject("record")
        else preview.getJSONObject("draft").getJSONObject("marks").getJSONObject(reference).let{
            JSONObject().put("reference",reference).put("revision",it.getLong("revision")).put("sha256",it.s("sha256"))
        }
        require(record.s("reference")==reference)
        write("$base/seed-drafts/$id/marks","PUT",JSONObject().put("operationId",operation())
            .put("revision",preview.getJSONObject("draft").getLong("revision")).put("reference",reference)
            .put("sourceRevision",record.getLong("revision")).put("sourceSha256",record.s("sha256"))
            .put("keep",keep).put("reason",if(keep)"Selected by the human for the next context window" else "Removed from the next context window"),id)
    }
    suspend fun prepare(preview:JSONObject){
        val id=draftId?:error("Create a review first")
        val parent=parentId
        if(parent!=null){
            val old=client.call("$base/seed-drafts/$parent/control").getJSONObject("control")
            write("$base/seed-drafts/$parent/control","POST",JSONObject().put("operationId",operation())
                .put("action","revise").put("revision",old.getLong("revision")).put("replacementDraftId",id)
                .put("canonicalSha256",preview.s("canonicalContentSha256")),parent)
        }else write("$base/seed-drafts/$id/control","POST",JSONObject().put("operationId",operation())
            .put("action","prepare").put("revision",preview.getJSONObject("draft").getLong("revision"))
            .put("canonicalSha256",preview.s("canonicalContentSha256")),id)
    }
    suspend fun control(action:String,status:JSONObject){
        val id=draftId?:error("Create a review first")
        val body=JSONObject().put("operationId",operation()).put("action",action)
            .put("revision",status.getLong("revision"))
        if(action=="approve")body.put("approvalDigest",status.s("approvalDigest"))
        require(action in listOf("approve","cancel"))
        write("$base/seed-drafts/$id/control","POST",body,id)
    }
    fun returnToHeld(){require(pending==null);point(parentId?:error("No held review"))}
    suspend fun refresh():JSONObject {
        val configuration=client.call("agents/$agent/context-system")
        require(configuration.getJSONObject("system").s("agentId")==agent)
        val binding=configuration.getJSONArray("bindings").objects().single{it.s("purpose")=="writer"}
        val availability=binding.optJSONObject("contextReview")?:error("Context review is not available in this environment")
        require(availability.s("schema")=="lax-human-context-review-availability/v1")
        if(draftId==null)availability.optJSONObject("ownReview")?.s("draftId")?.takeIf(::hollywoodUuid)?.let{point(it)}
        val result=JSONObject().put("availability",availability).put("nativeThread",binding.s("nativeThread"))
        draftId?.let{id->
            val status=client.call("$base/seed-drafts/$id/control")
            require(status.s("draftId")==id&&status.s("agentId")==agent&&status.s("reviewerId")==client.principalId)
            result.put("status",status)
            val control=status.optJSONObject("control")
            try{
                if(control==null)result.put("preview",client.call("$base/seed-drafts/$id"))
                else{
                    result.put("content",client.call("$base/seed-drafts/$id/control/content"))
                    if(parentId!=null)point(id)
                }
            }catch(e:HollywoodFailure){
                if(e.status !in listOf(400,403,404,409,422))throw e
                result.put("sourceError",e.message)
            }
        }
        return result
    }
    suspend fun sources(after:String?=null):JSONObject=client.call("$base/seed-sources?view=history"+
        (after?.let{"&after="+android.net.Uri.encode(it)}?:""))
}

internal fun reviewSourceContent(snapshot:JSONObject?):List<JSONObject> {
    if(snapshot==null)return emptyList()
    val chunks=snapshot.optJSONArray("chunks")?.objects().orEmpty()
    if(chunks.isEmpty())return emptyList()
    return JSONObject(chunks.joinToString(""){it.s("content")}).optJSONArray("records")?.objects().orEmpty()
}
