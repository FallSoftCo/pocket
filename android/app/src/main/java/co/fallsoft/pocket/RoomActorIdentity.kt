package co.fallsoft.pocket

import org.json.JSONObject

/** Host attribution is authenticated canonical metadata, never a UUID/name map. */
internal fun roomActorLabel(actor:JSONObject):String {
    val name=actor.s("name").ifBlank{actor.s("id",actor.s("agentId")).ifBlank{"Unknown agent"}}
    val identity=actor.optJSONObject("identity")?:actor
    val host=identity.optJSONObject("host")
    val bound=identity.optInt("version")==1&&identity.s("agentId")==actor.s("id",actor.s("agentId"))
    return if(bound&&actor.s("kind")=="agent"&&host!=null&&hollywoodUuid(host.s("id"))&&host.s("name").isNotBlank())
        if(name.contains(host.s("name"),ignoreCase=true))name else "$name · ${host.s("name")}" else name
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
    if(state.opt("initializationRequired")==false)return "Room participants"
    if(agents.isEmpty())return "Room agents not initialized"
    val ready=agents.count{it.optBoolean("ready")}
    return "Room agents: $ready/${agents.size} ready"+(if(state.optJSONObject("generation")?.s("phase")=="staging")" · Handoff required" else if(ready<agents.size)" · Initialization incomplete" else "")
}

/** Setup is distinct from idle/business work, and old APIs provide no assertion. */
internal fun roomOriginPendingLabel(detail:JSONObject):String? {
    if((detail.optJSONObject("room")?:detail).s("room_kind")=="global")return null
    val setup=detail.optJSONObject("roomAgents")?:return null
    if(setup.opt("version")!=1)return "Room agent status unavailable"
    val raw=setup.optJSONArray("agents")?:return "Room agent status unavailable"
    val agents=raw.objects()
    val selected=(detail.optJSONObject("room")?:detail).s("id")
    if(selected.isNotBlank()&&setup.s("roomId")!=selected)return "Room agent status unavailable"
    if(agents.size!=raw.length()||agents.any{it.opt("ready") !is Boolean||!hollywoodUuid(it.s("agent"))||!hollywoodUuid(it.s("node"))}||
        agents.map{it.s("agent")}.toSet().size!=agents.size||agents.map{it.s("node")}.toSet().size!=agents.size)return "Room agent status unavailable"
    if(setup.opt("initializationRequired")==false)return null
    if(agents.isEmpty())return "Agents not initialized"
    val phase=setup.optJSONObject("generation")?.s("phase")
    if(phase !in setOf("active","staging"))return "Room agent status unavailable"
    if(agents.any{it.s("readiness",it.s("state")) in setOf("blocked","unknown")})return "Agent setup blocked"
    val ready=agents.count{it.optBoolean("ready")}
    return if(ready<agents.size)"Preparing agents · $ready/${agents.size}"
        else if(phase=="staging")"Agent setup · $ready/${agents.size}" else null
}
