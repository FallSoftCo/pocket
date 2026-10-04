package co.fallsoft.pocket


data class MissionItem(
    val threadId: String, val title: String, val project: String, val text: String,
    val kind: String, val working: Boolean, val at: Long,
    val failed: Boolean = false, val actions: Int = 1,
)

fun usefulMissionStatus(kind: String, text: String): Boolean =
    !Regex("^thinking(?:\\.{3}|…)?$", RegexOption.IGNORE_CASE).matches(text.trim())

/** Each conversation gets a place before a second action can crowd another out. */
fun missionItems(activity: List<MissionItem>, sessions: List<MissionItem>, now: Long): List<MissionItem> {
    val live = activity.filter {
        it.text.isNotBlank() && usefulMissionStatus(it.kind, it.text) &&
            (it.working || now - it.at < 90_000)
    }.groupBy { it.threadId }.map { (_, actions) ->
        actions.maxBy { it.at }.copy(working = actions.any { it.working },
            actions = actions.count { it.working }.coerceAtLeast(1))
    }
    val represented = live.map { it.threadId }.toSet()
    val fallback = sessions.filter {
        it.threadId !in represented && (it.working || now - it.at < 900_000)
    }.map { if (usefulMissionStatus(it.kind, it.text)) it else it.copy(text = "", kind = "message") }
    return (live + fallback).distinctBy { it.threadId }
        .sortedWith(compareByDescending<MissionItem> { it.failed }
            .thenByDescending { it.working }.thenByDescending { it.at }.thenBy { it.threadId })
}

/** Stable reserved text bounds, rather than cards or a row/column grid. */
data class MissionPlacement(val threadId:String,val x:Float,val y:Float,val width:Float,val height:Float) {
    val right get()=x+width
    val bottom get()=y+height
}
fun missionOverlap(a:MissionPlacement,b:MissionPlacement,gap:Float=10f):Boolean =
    a.x < b.right+gap && a.right+gap > b.x && a.y < b.bottom+gap && a.bottom+gap > b.y

/** Content changes never change a sentence's reserved bounds or surviving XY position. */
fun missionField(previous:List<MissionPlacement>,items:List<MissionItem>,width:Float,height:Float,fontScale:Float=1f):List<MissionPlacement> {
    if(width<48f||height<48f)return emptyList()
    val scale=fontScale.coerceAtLeast(1f)
    val blockHeight=(22f*3+18f+12f)*scale
    if(height<blockHeight)return emptyList()
    val ids=items.map{it.threadId}.toSet()
    val placed=mutableListOf<MissionPlacement>()
    previous.filter{it.threadId in ids}.forEach { old ->
        if(old.x>=0&&old.y>=0&&old.right<=width+.01f&&old.bottom<=height+.01f&&
            kotlin.math.abs(old.height-blockHeight)<.01f&&placed.none{missionOverlap(it,old)})placed.add(old)
    }
    for(item in items){
        if(placed.any{it.threadId==item.threadId})continue
        val hash=item.threadId.hashCode().toLong() and 0x7fffffffL
        val textWidth=((230f+(hash%71).toFloat())*scale).coerceAtMost(width)
        val preferredX=((hash%997).toFloat()/997f)*(width-textWidth)
        val preferredY=(((hash/997)%991).toFloat()/991f)*(height-blockHeight)*.8f
        val xs=mutableListOf(0f,width-textWidth,preferredX)
        val ys=mutableListOf(0f,height-blockHeight,preferredY)
        placed.forEach { p -> xs.add(p.right+10f);xs.add(p.x-textWidth-10f);ys.add(p.bottom+10f);ys.add(p.y-blockHeight-10f) }
        val candidates=ys.distinct().flatMap { y -> xs.distinct().map { x -> MissionPlacement(item.threadId,x,y,textWidth,blockHeight) }}
        val next=candidates.filter { p -> p.x>=0&&p.y>=0&&p.right<=width+.01f&&p.bottom<=height+.01f&&placed.none{missionOverlap(it,p)} }
            .minByOrNull { p -> (p.x-preferredX)*(p.x-preferredX)+(p.y-preferredY)*(p.y-preferredY) }
        if(next!=null)placed.add(next)
    }
    return placed
}
