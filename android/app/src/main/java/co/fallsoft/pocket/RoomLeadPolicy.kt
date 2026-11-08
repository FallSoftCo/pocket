package co.fallsoft.pocket

import org.json.JSONObject

/** Canonical communication role metadata; it never asserts execution health. */
internal data class RoomLeadPolicy(val agent:String,val name:String,val revision:Long,val after:Long)

internal fun roomLeadPolicy(detail:JSONObject,events:List<JSONObject>):RoomLeadPolicy? {
    fun parse(value:JSONObject?):RoomLeadPolicy? {
        value?:return null
        val agent=value.s("leadAgentId");val revision=value.optLong("revision",-1);val after=value.optLong("inputAfterSeq",-1)
        if(value.optInt("version")!=1||!hollywoodUuid(agent)||revision<1||after<0)return null
        return RoomLeadPolicy(agent,value.s("leadName").ifBlank{"Project lead"},revision,after)
    }
    // Current authenticated metadata wins. Canonical cached event metadata can
    // recover the same role before the parallel metadata read completes.
    val current=detail.optJSONObject("room")?.optJSONObject("leadPolicy")
    if(current!=null)return parse(current)
    val latest=events.asReversed().firstOrNull{it.optJSONObject("data")?.s("roomMode")=="lead"}?:return null
    val data=latest.getJSONObject("data")
    return parse(JSONObject().put("version",1).put("leadAgentId",data.s("leadAgentId"))
        .put("revision",data.optLong("leadRevision",-1)).put("inputAfterSeq",data.optLong("inputAfterSeq",-1)))
}

internal fun roomLeadConversation(events:List<JSONObject>,policy:RoomLeadPolicy?):List<JSONObject> {
    if(policy==null)return events
    return events.filter{event->val data=event.optJSONObject("data")
        event.optLong("seq")>policy.after&&((data?.s("channel")=="human"&&data.optLong("leadRevision")==policy.revision)||data?.s("type") in setOf("executionActivity","executorState"))}
}

/** Coordination lifecycle labels report messages/results, never inferred running. */
internal fun roomTeamLabel(event:JSONObject):String {
    val coord=event.optJSONObject("data")?.optJSONObject("coordination")?:return "Team note"
    return when(coord.s("type")) {
        "task"->"Task sent"
        "cancel"->"Cancellation requested"
        "question"->"Question"
        "answer"->"Answer delivered"
        "wake"->"Work ready"
        "result"->when(coord.s("outcome")){"done"->"Result received";"blocked","waiting"->"Dependency";"failed"->"Needs review";"cancelled"->"Cancellation reported";else->"Progress"}
        else->"Team note"
    }
}
