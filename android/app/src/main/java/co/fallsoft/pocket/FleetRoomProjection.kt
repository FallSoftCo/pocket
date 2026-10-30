package co.fallsoft.pocket

import org.json.JSONObject

/** Canonical room identity is separate from the authenticated route used to open it. */
internal data class FleetRoomProjection(
    val identity:String,
    val content:WorkEntry<WorkCatalogPayload>,
    val route:WorkEntry<WorkCatalogPayload>,
    val source:WorkSourceSnapshot<WorkCatalogPayload>
) {
    fun displayRoom():JSONObject=JSONObject(content.value.body).apply {
        // Current delivery state comes from the live route, not an old retained preview.
        val live=JSONObject(route.value.body)
        if(source.available&&live.has("activity"))put("activity",live.get("activity"))else remove("activity")
    }
}

internal fun fleetRoomIdentity(entry:WorkEntry<WorkCatalogPayload>):String {
    if(entry.key.kind!=WorkKind.ROOM)return entry.key.saveableKey()
    val member=entry.value.destination.environment.fleet?:return entry.key.saveableKey()
    val binding=runCatching{JSONObject(entry.value.binding?:return entry.key.saveableKey())}.getOrNull()
        ?:return entry.key.saveableKey()
    val viewer=binding.optString("principalId")
    if(!hollywoodUuid(viewer)||binding.optString("authorityId")!=entry.key.authority)return entry.key.saveableKey()
    return "fleet-room:"+FleetWorkIdentity(member.fleetId,member.accountId,viewer,WorkKind.ROOM,
        entry.key.authority,entry.key.id).key
}

/** A stale gateway cannot replace newer canonical content. A reachable route can
 * still open that content without pretending its binding came from another host.
 * No mutations or receipt retries occur when the chosen route changes.
 */
internal fun fleetRoomProjections(snapshots:List<WorkSourceSnapshot<WorkCatalogPayload>>):List<FleetRoomProjection> {
    data class Candidate(val entry:WorkEntry<WorkCatalogPayload>,val source:WorkSourceSnapshot<WorkCatalogPayload>,val seq:Long)
    val candidates=snapshots.flatMap{source->source.entries.filter{it.key.kind==WorkKind.ROOM}.map{entry->
        val room=JSONObject(entry.value.body)
        val seq=if(HollywoodRoomFeedSummary.read(room)!=null)
            room.optJSONObject("feedSummary")?.optJSONObject("recency")?.optLong("seq",0)?:0 else 0
        Candidate(entry,source,seq)
    }}
    return candidates.groupBy{fleetRoomIdentity(it.entry)}.map{(identity,options)->
        val content=options.sortedWith(compareByDescending<Candidate>{it.seq}.thenBy{it.entry.key.saveableKey()}).first()
        val route=options.sortedWith(compareByDescending<Candidate>{it.source.available}.thenBy{it.entry.key.saveableKey()}).first()
        FleetRoomProjection(identity,content.entry,route.entry,route.source)
    }
}
