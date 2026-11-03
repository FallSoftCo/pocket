package co.fallsoft.pocket

import org.json.JSONObject

/** Host attribution is authenticated canonical metadata, never a UUID/name map. */
internal fun roomActorLabel(actor:JSONObject):String {
    val name=actor.s("name").ifBlank{actor.s("id",actor.s("agentId")).ifBlank{"Unknown agent"}}
    val identity=actor.optJSONObject("identity")?:actor
    val host=identity.optJSONObject("host")
    val bound=identity.optInt("version")==1&&identity.s("agentId")==actor.s("id",actor.s("agentId"))
    return if(bound&&actor.s("kind")=="agent"&&host!=null&&hollywoodUuid(host.s("id"))&&host.s("name").isNotBlank())
        "$name · ${host.s("name")}" else name
}

internal fun roomNotificationTitle(value:JSONObject):String {
    val room=value.optJSONObject("hint")?.s("roomName","Hollywood").orEmpty()
    val event=value.optJSONObject("event")
    val author=value.optJSONObject("author")?.takeIf{it.s("agentId")==event?.s("author")}
    val who=author?.let(::roomActorLabel).orEmpty()
    return (if(event?.s("audience")=="direct")"Private · " else "")+room+if(who.isNotBlank())" · $who" else ""
}

internal fun roomOriginSummary(detail:JSONObject,available:Boolean=true):String {
    if(!available)return "Room agent status unavailable"
    val state=detail.optJSONObject("roomAgents")?:return "Room participants"
    val agents=state.optJSONArray("agents")?.objects()?:return "Room participants"
    if(state.optInt("version")!=1)return "Room participants"
    if(agents.isEmpty())return "Room agents not initialized"
    val ready=agents.count{it.optBoolean("ready")}
    return "Room agents: $ready/${agents.size} ready"+(if(state.optJSONObject("generation")?.s("phase")=="staging")" · Handoff required" else if(ready<agents.size)" · Initialization incomplete" else "")
}
