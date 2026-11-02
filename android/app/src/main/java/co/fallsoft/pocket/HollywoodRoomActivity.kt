package co.fallsoft.pocket

import org.json.JSONObject

/** A viewer-scoped observation, never a work timestamp or a host-health signal. */
internal data class RoomActivityCategory(val key:String,val label:String,val count:Long)
internal data class HollywoodRoomActivity(val observedAt:Long,val counts:List<Long>,val native:Boolean=false,
    val idleAgents:Long=0,val unavailableAgents:Long=0,val approvals:Long=0,val inputRequests:Long=0) {
    val idle get()=counts.all{it==0L}&&unavailableAgents==0L
    val executing get()=counts[2]>0
    val scheduled get()=counts.take(4).any{it>0}
    val needsHuman get()=counts[5]>0
    val uncertain get()=counts[4]>0||unavailableAgents>0
    val states get()=if(!native)keys.indices.filter{counts[it]>0}.map{RoomActivityCategory(keys[it],labels[it],counts[it])} else buildList {
        if(approvals>0)add(RoomActivityCategory("approval","Approval needed",approvals))
        if(inputRequests>0)add(RoomActivityCategory("input","Reply needed",inputRequests))
        if(counts[2]>0)add(RoomActivityCategory("running","Running",counts[2]))
        if(idleAgents>0&&counts[2]==0L&&counts[5]==0L)add(RoomActivityCategory("idle","Idle",idleAgents))
        if(unavailableAgents>0)add(RoomActivityCategory("unavailable","Agent unavailable",unavailableAgents))
    }
    fun categories()=states.map{it.label to it.count}
    val summary get()=when {
        !native->categories().joinToString(" · "){(label,count)->"$count $label"}.ifEmpty{"Idle"}
        approvals>0&&inputRequests==0L->"Approval needed"
        inputRequests>0&&approvals==0L->"Reply needed"
        counts[5]>0->"Needs you"
        executing->"Running"
        unavailableAgents>0&&idleAgents>0->"$idleAgents idle · $unavailableAgents unavailable"
        unavailableAgents>0->"Agent status unavailable"
        else->"Idle"
    }
    companion object {
        private const val MAX_SAFE=9007199254740991L
        val keys=listOf("pending","claimed","running","cancelRequested","unknown","waitingForHuman")
        val labels=listOf("Queued","Claimed","Running","Stopping","Unknown","Needs you")
        fun usesNative(room:JSONObject):Boolean {
            if(!room.has("nativeState"))return false
            val raw=room.optJSONObject("nativeState")?:return true
            fun number(key:String):Long? {
                val value=raw.opt(key)
                return if(value is Int||value is Long)(value as Number).toLong()else null
            }
            return !(number("version")==1L&&number("runtimeCount")==0L&&
                number("observedAt")?.let{it>0}==true&&raw.optJSONArray("agents")?.length()==0)
        }
        fun read(room:JSONObject,available:Boolean):HollywoodRoomActivity? {
            if(!available)return null
            val native=room.optJSONObject("nativeState")
            if(room.has("nativeState")&&native==null)return null
            if(native!=null) {
                fun number(value:Any?):Long? = if(value is Int||value is Long)(value as Number).toLong().takeIf{it in 0..MAX_SAFE}else null
                if(number(native.opt("version"))!=1L)return null
                val total=number(native.opt("runtimeCount"))?:return null
                val agents=native.optJSONArray("agents")?:return null
                if(total<agents.length())return null
                if(total>0) {
                    val observed=number(native.opt("observedAt"))?.takeIf{it>0}?:return null
                    val counts=MutableList(6){0L};var unavailable=total-agents.length();var idle=0L;var approvals=0L;var inputs=0L
                    val identities=mutableSetOf<String>()
                    for(i in 0 until agents.length()) {
                        val agent=agents.optJSONObject(i)?:return null
                        val identity=agent.optString("agentId")+"/"+agent.optString("nativeThread")
                        if(!identities.add(identity))return null
                        val expires=number(agent.opt("expiresAt"))?:return null
                        val status=agent.optJSONObject("status")?:return null
                        if(expires<=observed) { unavailable++;continue }
                        when(status.optString("type")) {
                            "idle"->idle++
                            "active"->{
                                val flags=status.optJSONArray("activeFlags")?:return null
                                var waiting=false
                                for(j in 0 until flags.length())when(flags.optString(j)) {
                                    "waitingOnApproval"->{waiting=true;approvals++}
                                    "waitingOnUserInput"->{waiting=true;inputs++}
                                    else->return null
                                }
                                if(waiting)counts[5]++ else counts[2]++
                            }
                            "notLoaded","systemError","unavailable"->unavailable++
                            else->return null
                        }
                    }
                    return HollywoodRoomActivity(observed,counts,native=true,idleAgents=idle,unavailableAgents=unavailable,approvals=approvals,inputRequests=inputs)
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
