package co.fallsoft.pocket

import org.json.JSONObject

/** Durable missing interval between retained windows. No receipt/read-ack authority. */
internal data class TranscriptGapWindow(val id:String,val cursor:String,val beforeRow:String,val olderAnchor:String){
    fun json()=JSONObject().put("id",id).put("cursor",cursor).put("beforeRow",beforeRow).put("olderAnchor",olderAnchor)
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
internal fun transcriptReadingAnchor(row:String,gaps:List<TranscriptHistoryGap>):String =
    gaps.firstOrNull{it.rowId==row}?.beforeRow?:row
internal fun restoreTranscriptGaps(value:JSONObject,rows:List<JSONObject>):List<TranscriptGapWindow>{
    val typed=value.optJSONArray("historyGaps")
    val raw=if(typed!=null)typed.objects().mapNotNull{v->
        val id=v.s("id");val cursor=v.s("cursor");val before=v.s("beforeRow");val older=v.s("olderAnchor")
        if(id.isBlank()||cursor.isBlank()||cursor.length>16000||before.isBlank()||older.isBlank())null
        else TranscriptGapWindow(id,cursor,before,older)
    } else {
        val cursor=value.s("bridgeBefore");val before=value.s("bridgeBoundary")
        val known=value.optJSONArray("bridgeKnown")
        val older=known?.let{a->(0 until a.length()).map{a.optString(it)}.lastOrNull{it.isNotBlank()}}.orEmpty()
        if(cursor.isBlank()||before.isBlank()||older.isBlank())emptyList()
        else listOf(TranscriptGapWindow(java.util.UUID.randomUUID().toString(),cursor,before,older))
    }
    val ids=rows.mapTo(hashSetOf()){transcriptMessageIdentity(it.s("kind"),it.s("turnId"),it.s("clientId"),it.s("id"))}
    val requested=raw.flatMap{listOf(it.beforeRow,it.olderAnchor)}.toSet()
    val aliases=migrateTranscriptAnchors(rows,requested)
    return raw.map{it.copy(beforeRow=aliases[it.beforeRow]?:it.beforeRow,olderAnchor=aliases[it.olderAnchor]?:it.olderAnchor)}
        .filter{it.beforeRow in ids&&it.olderAnchor in ids}.distinctBy{it.id}
}

/** A bounded current page proves only its own contiguous window. A retained
 * window outside it needs an explicit interval; catalog/cache merges cannot
 * silently claim that the intervening history was loaded. */
internal fun missingTranscriptWindow(
    retained:List<JSONObject>,incoming:List<JSONObject>,page:JSONObject,
    gaps:List<TranscriptGapWindow>,unknownCoverage:Boolean=false
):TranscriptGapWindow?{
    if(!page.optBoolean("hasEarlier"))return null
    val cursor=page.s("before");if(cursor.isBlank())return null
    val content=incoming.filter{transcriptContentAnchor(it.s("kind"))}
    val boundary=content.firstOrNull()?.s("id")?:return null
    val ids=content.mapTo(hashSetOf()){it.s("id")}
    val old=retained.filter{transcriptContentAnchor(it.s("kind"))}
    val outside=old.filter{it.s("id") !in ids}
    if(outside.isEmpty()||(!unknownCoverage&&old.any{it.s("id") in ids}))return null
    if(gaps.any{it.beforeRow==boundary||it.cursor==cursor})return null
    return TranscriptGapWindow(java.util.UUID.randomUUID().toString(),cursor,boundary,outside.last().s("id"))
}
