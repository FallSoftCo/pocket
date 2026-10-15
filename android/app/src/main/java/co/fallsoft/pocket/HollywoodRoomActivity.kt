package co.fallsoft.pocket

import org.json.JSONObject

/** A viewer-scoped observation, never a work timestamp or a host-health signal. */
internal data class HollywoodRoomActivity(val observedAt:Long,val counts:List<Long>) {
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
