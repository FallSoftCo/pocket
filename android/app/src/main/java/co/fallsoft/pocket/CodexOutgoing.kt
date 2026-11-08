package co.fallsoft.pocket

import org.json.JSONObject

/** Small delivery snapshots survive viewport changes independently of transcript reads. */
internal class CodexOutgoing {
    private val snapshots=object:LinkedHashMap<CodexConversationKey,JSONObject>(16,.75f,true){}
    private val weights=HashMap<CodexConversationKey,Int>()
    private var bytes=0L
    @Synchronized fun accept(key:CodexConversationKey,incoming:JSONObject?):Boolean {
        if(incoming?.optInt("version")!=1)return false
        val revision=incoming.optLong("revision",-1)
        if(revision<0||revision>9007199254740991L)return false
        val old=snapshots[key]
        if(old!=null&&old.optLong("revision")>=revision)return false
        val items=incoming.optJSONArray("items")?:return false
        val ids=HashSet<String>()
        for(i in 0 until items.length()){
            val row=items.optJSONObject(i)?:return false
            if(row.s("id").isBlank()||!ids.add(row.s("id"))||row.opt("text") !is String||row.s("state") !in setOf("queued","held","sending","failed","unknown"))return false
        }
        val encoded=incoming.toString()
        snapshots[key]=JSONObject(encoded)
        bytes-=weights.put(key,encoded.length*2)?:0;bytes+=encoded.length*2
        while(snapshots.size>1&&(snapshots.size>128||bytes>8*1024*1024)){
            val oldest=snapshots.keys.first();snapshots.remove(oldest);bytes-=weights.remove(oldest)?:0
        }
        return true
    }
    @Synchronized fun overlay(key:CodexConversationKey,value:JSONObject?):JSONObject? {
        val snapshot=snapshots[key]?:return value
        val original=value?:JSONObject().put("thread",JSONObject().put("id",key.thread))
        // Copy metadata only; preserve transcript object references and rendering caches.
        return JSONObject(original,original.keys().asSequence().toList().toTypedArray())
            .put("outbox",snapshot).put("outgoing",snapshot.getJSONArray("items"))
    }
}
