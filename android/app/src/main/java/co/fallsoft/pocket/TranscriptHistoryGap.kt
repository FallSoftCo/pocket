package co.fallsoft.pocket

import org.json.JSONObject

/** Durable missing interval between retained windows. No receipt/read-ack authority. */
internal data class TranscriptGapWindow(val id:String,val cursor:String,val beforeRow:String,val olderAnchor:String,val exhausted:Boolean=false,val unverified:Set<String> = emptySet()){
    fun json()=JSONObject().put("id",id).put("cursor",cursor).put("beforeRow",beforeRow).put("olderAnchor",olderAnchor).put("exhausted",exhausted).put("unverified",org.json.JSONArray(unverified.toList()))
}
internal data class TranscriptHistoryGap(val id:String,val cursor:String,val beforeRow:String,val loading:Boolean,val problem:String){
    val rowId get()=ROW_PREFIX+id
    companion object{const val ROW_PREFIX="ui:history-gap:"}
}
internal fun transcriptRowsWithGaps(rows:List<JSONObject>,gaps:List<TranscriptHistoryGap>):List<JSONObject>{
    if(gaps.isEmpty())return rows
    val boundaries=gaps.groupBy{it.beforeRow}
    return buildList(rows.size+gaps.size){for(row in rows){
        boundaries[row.s("id")]?.forEach{add(JSONObject().put("id",it.rowId).put("kind","historyGap"))}
        add(row)
    }}
}
/** A disconnected retained island is storage, not the current reading window.
 * Expose only the component attached to the newest page until canonical reads
 * bridge it. This preserves cached bodies without presenting invented adjacency.
 * The gap stays at the reading edge and advances as older pages are hydrated. */
/** A measured viewport position is not a history-reading request while following.
 * Only deliberate historical reading selects an older connected component. */
internal fun transcriptWindowAnchor(position:TranscriptReadPosition?,followingLatest:Boolean):String? =
    position?.rowId?.takeUnless{followingLatest}

internal data class TranscriptWindowRange(val start:Int,val end:Int)
internal fun connectedTranscriptRange(rows:List<JSONObject>,gaps:List<TranscriptHistoryGap>,anchor:String?):TranscriptWindowRange{
    val cuts=gaps.mapNotNull{gap->rows.indexOfFirst{it.s("id")==gap.beforeRow}.takeIf{it>=0}}.distinct().sorted()
    val anchored=anchor?.let{id->rows.indexOfFirst{it.s("id")==id}.takeIf{it>=0}}
    // Structural headings repeat across pages and cannot select a historical
    // island. Resolve them to this turn's newest content. Proven input edges
    // likewise select their attached component, including chained inputs.
    var target=anchored?:rows.lastIndex
    val visited=hashSetOf<Int>()
    while(target in rows.indices&&visited.add(target)){
        val row=rows[target]
        val successor=row.s("_inputSuccessor").takeIf{row.s("kind")=="user"&&row.s("_inputOrder")=="canonical"&&it.isNotBlank()}
        val next=if(successor!=null)rows.indexOfFirst{it.s("id")==successor}.takeIf{it>=0}
            else if(row.s("kind")=="turn"&&row.s("turnId").isNotBlank())
                rows.indices.lastOrNull{rows[it].s("turnId")==row.s("turnId")&&transcriptContentAnchor(rows[it].s("kind"))}
            else null
        if(next==null||next==target)break
        target=next
    }
    var start=cuts.lastOrNull{it<=target}?:0
    val end=cuts.firstOrNull{it>target}?:rows.size
    // A position proof connects its input to this component even while the
    // surrounding raw-item interval is still loading. Keep that gap explicit.
    while(start>0){
        val input=rows[start-1]
        val successor=input.s("_inputSuccessor")
        val connected=input.s("kind")=="user"&&input.s("_inputOrder")=="canonical"&&successor.isNotBlank()&&
            rows.subList(start,end).any{it.s("id")==successor}
        if(!connected)break
        start--
    }
    return TranscriptWindowRange(start,end)
}
internal fun connectedTranscriptWindow(rows:List<JSONObject>,gaps:List<TranscriptHistoryGap>,range:TranscriptWindowRange):List<JSONObject>{
    val content=rows.subList(range.start,range.end)
    val turn=content.firstOrNull()?.s("turnId").orEmpty()
    val header=rows.take(range.start).lastOrNull{it.s("kind")=="turn"&&turn.isNotBlank()&&it.s("turnId")==turn}
    val ids=content.mapTo(hashSetOf()){it.s("id")}
    val next=rows.getOrNull(range.end)?.s("id")
    val trailing=gaps.filter{it.beforeRow==next}.map{
        JSONObject().put("id",it.rowId).put("kind","historyGap").put("historyDirection","newer")
    }
    return listOfNotNull(header)+transcriptRowsWithGaps(content,gaps.filter{it.beforeRow in ids})+trailing
}

/** One viewport demand, not a whole-history downloader or a one-raw-page gate. */
internal class TranscriptViewportBudget(private val began:Long,private val maxPages:Int=8,private val maxMillis:Long=2000){
    private var pages=0
    private val seen=hashSetOf<String>()
    fun next(cursor:String?,now:Long):Boolean{
        if(cursor.isNullOrBlank()||pages>=maxPages||pages>0&&now-began>=maxMillis||!seen.add(cursor))return false
        pages++;return true
    }
}

internal fun transcriptReadingAnchor(row:String,gaps:List<TranscriptHistoryGap>):String =
    gaps.firstOrNull{it.rowId==row}?.beforeRow?:row
internal fun restoreTranscriptGaps(value:JSONObject,rows:List<JSONObject>):List<TranscriptGapWindow>{
    val typed=value.optJSONArray("historyGaps")
    val raw=if(typed!=null)typed.objects().mapNotNull{v->
        val id=v.s("id");val cursor=v.s("cursor");val before=v.s("beforeRow");val older=v.s("olderAnchor")
        if(id.isBlank()||cursor.isBlank()&&!v.optBoolean("exhausted")||cursor.length>16000||before.isBlank()||older.isBlank())null
        else TranscriptGapWindow(id,cursor,before,older,v.optBoolean("exhausted"),v.optJSONArray("unverified")?.let{a->(0 until a.length()).map{a.optString(it)}.filter{it.isNotBlank()}.toSet()}.orEmpty())
    } else {
        val cursor=value.s("bridgeBefore");val before=value.s("bridgeBoundary")
        val known=value.optJSONArray("bridgeKnown")
        val older=known?.let{a->(0 until a.length()).map{a.optString(it)}.lastOrNull{it.isNotBlank()}}.orEmpty()
        if(cursor.isBlank()||before.isBlank()||older.isBlank())emptyList()
        else listOf(TranscriptGapWindow(java.util.UUID.randomUUID().toString(),cursor,before,older))
    }
    val ids=rows.mapTo(hashSetOf()){transcriptMessageIdentity(it.s("kind"),it.s("turnId"),it.s("clientId"),it.s("id"))}
    val requested=raw.flatMap{listOf(it.beforeRow,it.olderAnchor)+it.unverified}.toSet()
    val aliases=migrateTranscriptAnchors(rows,requested)
    return raw.map{it.copy(beforeRow=aliases[it.beforeRow]?:it.beforeRow,olderAnchor=aliases[it.olderAnchor]?:it.olderAnchor,unverified=it.unverified.map{ref->aliases[ref]?:ref}.toSet())}
        .filter{it.beforeRow in ids&&it.olderAnchor in ids}.distinctBy{it.id}
}

/** A bounded current page proves only its own contiguous window. A retained
 * window outside it needs an explicit interval; catalog/cache merges cannot
 * silently claim that the intervening history was loaded. */
internal fun canonicalTranscriptIds(page:JSONObject,rows:List<JSONObject>):Set<String>{
    val content=rows.filter{transcriptContentAnchor(it.s("kind"))}
    val ids=content.mapTo(hashSetOf()){it.s("id")}
    val raw=page.optJSONArray("canonicalIds")?:return ids
    val references=(0 until raw.length()).map{raw.optString(it)}.toSet()
    val aliases=migrateTranscriptAnchors(rows,references)
    return references.map{aliases[it]?:it}.filterTo(linkedSetOf()){it in ids}
}
internal fun missingTranscriptWindow(
    retained:List<JSONObject>,incoming:List<JSONObject>,page:JSONObject,
    gaps:List<TranscriptGapWindow>,unknownCoverage:Boolean=false,confirmed:Set<String>?=null
):TranscriptGapWindow?{
    val ids=canonicalTranscriptIds(page,incoming)
    val content=incoming.filter{it.s("id") in ids}
    val boundary=content.firstOrNull()?.s("id")?:return null
    val old=retained.filter{transcriptContentAnchor(it.s("kind"))}
        .filter{unknownCoverage||confirmed==null||it.s("id") in confirmed}
    val outside=old.filter{it.s("id") !in ids}
    if(outside.isEmpty())return null
    val earlier=page.optBoolean("hasEarlier")
    if(earlier&&!unknownCoverage&&old.any{it.s("id") in ids})return null
    val cursor=page.s("before")
    if(earlier&&cursor.isBlank())return null
    if(gaps.any{it.beforeRow==boundary||cursor.isNotBlank()&&it.cursor==cursor})return null
    return TranscriptGapWindow(java.util.UUID.randomUUID().toString(),cursor,boundary,
        (if(unknownCoverage)outside.first()else outside.last()).s("id"),exhausted=!earlier,
        unverified=if(unknownCoverage||!earlier)outside.mapTo(linkedSetOf()){it.s("id")}else emptySet())
}

/** Version1 cached rows may already join disconnected islands without recording
 * a gap. Their old coverage flag is not proof; retain the rows and re-establish
 * coverage of every retained content identity on bounded reader demand. */
internal fun verifiedTranscriptCoverage(value:JSONObject)=value.optInt("schema")==2&&value.optBoolean("coverageVerified")&&value.optJSONArray("confirmedIds")!=null

internal fun advanceTranscriptGap(gap:TranscriptGapWindow,incoming:List<JSONObject>,page:JSONObject):TranscriptGapWindow?{
    val content=incoming.filter{transcriptContentAnchor(it.s("kind"))}
    val ids=content.mapTo(hashSetOf()){it.s("id")}
    val pending=gap.unverified-ids
    if(if(gap.unverified.isNotEmpty())pending.isEmpty() else gap.olderAnchor in ids)return null
    val boundary=content.firstOrNull()?.s("id")?:gap.beforeRow
    // EOF without the retained anchor is a disconnected/unavailable interval,
    // not evidence that all history between the two islands was displayed.
    if(!page.optBoolean("hasEarlier"))return gap.copy(beforeRow=boundary,exhausted=true,unverified=pending)
    val cursor=page.s("before")
    check(cursor.isNotBlank()&&cursor!=gap.cursor){"History page did not advance; cached messages are retained."}
    return gap.copy(cursor=cursor,beforeRow=boundary,exhausted=false,unverified=pending)
}
