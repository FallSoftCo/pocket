package co.fallsoft.pocket

import org.json.JSONObject

/** A viewer-scoped observation, never a work timestamp or a host-health signal. */
internal data class HollywoodRoomActivity(val observedAt:Long,val counts:List<Long>,val native:Boolean=false) {
    val idle get()=counts.all{it==0L}
    val executing get()=counts[2]>0
    val scheduled get()=counts.take(4).any{it>0}
    val needsHuman get()=counts[5]>0
    val uncertain get()=counts[4]>0
    fun categories()=keys.indices.filter{counts[it]>0}.map{labels[it] to counts[it]}
    companion object {
        private const val MAX_SAFE=9007199254740991L
        val keys=listOf("pending","claimed","running","cancelRequested","unknown","waitingForHuman")
        val labels=listOf("Queued","Claimed","Running","Stopping","Unknown","Needs you")
        fun read(room:JSONObject,available:Boolean):HollywoodRoomActivity? {
            if(!available)return null
            val native=room.optJSONObject("nativeState")
            if(native!=null) {
                fun number(value:Any?):Long? = if(value is Int||value is Long)(value as Number).toLong().takeIf{it in 0..MAX_SAFE}else null
                if(number(native.opt("version"))!=1L)return null
                val total=number(native.opt("runtimeCount"))?:return null
                val agents=native.optJSONArray("agents")?:return null
                if(total<agents.length())return null
                if(total>0) {
                    val observed=number(native.opt("observedAt"))?.takeIf{it>0}?:return null
                    val counts=MutableList(6){0L};counts[4]=total-agents.length()
                    val identities=mutableSetOf<String>()
                    for(i in 0 until agents.length()) {
                        val agent=agents.optJSONObject(i)?:return null
                        val identity=agent.optString("agentId")+"/"+agent.optString("nativeThread")
                        if(!identities.add(identity))return null
                        val expires=number(agent.opt("expiresAt"))?:return null
                        val status=agent.optJSONObject("status")?:return null
                        if(expires<=observed) { counts[4]++;continue }
                        when(status.optString("type")) {
                            "idle"->Unit
                            "active"->{
                                counts[2]++
                                val flags=status.optJSONArray("activeFlags")?:return null
                                var waiting=false
                                for(j in 0 until flags.length())when(flags.optString(j)) {
                                    "waitingOnApproval","waitingOnUserInput"->waiting=true
                                    else->return null
                                }
                                if(waiting)counts[5]++
                            }
                            "notLoaded","systemError","unavailable"->counts[4]++
                            else->return null
                        }
                    }
                    return HollywoodRoomActivity(observed,counts,native=true)
                }
            }
            val raw=room.optJSONObject("activity")?:return null
            fun integer(key:String):Long? {
                val v=raw.opt(key)
                if(v !is Int&&v !is Long)return null
                return (v as Number).toLong().takeIf{it in 0..MAX_SAFE}
            }
            if(integer("version")!=1L)return null
            val observed=integer("observedAt")?.takeIf{it>0}?:return null
            val counts=keys.map{integer(it)?:return null}
            return HollywoodRoomActivity(observed,counts)
        }
    }
}
