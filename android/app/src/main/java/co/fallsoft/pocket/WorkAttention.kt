package co.fallsoft.pocket

import org.json.JSONArray
import org.json.JSONObject

/** Catalog evidence only. Reviewing it never answers a question or resends a message. */
data class WorkAttentionItem(val kind:String,val id:String,val label:String)
data class WorkAttention(val verified:Boolean,val items:List<WorkAttentionItem>)
internal fun workAttention(raw:JSONObject?):WorkAttention? {
    if(raw==null||raw.optInt("version")!=1||raw.opt("verified") !is Boolean)return null
    val values=raw.optJSONArray("items")?:return null
    val kinds=setOf("question","approval","deliveryFailed","deliveryUnknown","needsInput","runtimeError")
    val items=values.objects().mapNotNull { row ->
        val kind=row.s("kind");val id=row.s("id");val label=row.s("label")
        if(kind !in kinds||id.isBlank()||label.isBlank())null else WorkAttentionItem(kind,id,label)
    }.distinctBy{it.id}
    if(items.size!=values.length())return null
    return WorkAttention(raw.optBoolean("verified"),items)
}
internal fun WorkAttention.json()=JSONObject().put("version",1).put("verified",verified)
    .put("items",JSONArray(items.map{JSONObject().put("kind",it.kind).put("id",it.id).put("label",it.label)}))
internal fun Task.needsReview()=attention?.items?.isNotEmpty()==true||needsInput
internal fun Task.attentionLabel():String? {
    val evidence=attention
    val label=evidence?.items?.firstOrNull()?.label?:if(needsInput)"Input requested"else return null
    return (if(evidence?.verified==false)"Last known · "else "")+label+
        (if((evidence?.items?.size?:0)>1)" · +${evidence!!.items.size-1}"else "")
}
