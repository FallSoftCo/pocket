package co.fallsoft.pocket

import org.json.JSONArray
import org.json.JSONObject

/** Public room events remain immutable; operation updates share one display row. */
internal fun roomTranscriptRows(events:List<JSONObject>,prefix:String):List<JSONObject> {
    val rows=mutableListOf<JSONObject>()
    val operations=mutableMapOf<String,Int>()
    events.forEach{event->
        val data=event.optJSONObject("data")
        val author=event.s("author")
        val item=data?.s("itemId").orEmpty()
        val thread=data?.s("thread").orEmpty()
        val native=data?.s("type")=="executionActivity"
        val activity=(event.s("kind")=="artifact"||native)&&item.isNotBlank()&&thread.isNotBlank()&&
            data?.s("nativeType") !in listOf("agentMessage","plan")&&
            data?.s("status") in listOf("inProgress","completed","failed","declined","unknown")
        val identity=JSONArray(if(native)listOf(event.s("authority"),author,data?.s("deliveryId"),
            data?.optLong("generation"),thread,data?.s("turn"),item)else listOf(author,thread,item)).toString()
        val previous=if(activity)operations[identity]else null
        val eventId=event.opt("id")
        val row=JSONObject().put("id",if(activity)"$prefix:operation:$identity" else "$prefix:$eventId")
            .put("roomEventId",eventId).put("roomAuthor",event.s("authorName",author))
            .put("kind",if(activity)"activity" else if(author=="you"||event.optBoolean("isSelf"))"user" else "message")
            .put("text",if(native&&data?.s("nativeType") in listOf("agentMessage","plan"))data?.s("output")else event.s("body"))
        if(activity){
            row.put("type","roomOperation").put("title",if(native)data!!.s("title","Run")else "Run").put("status",data!!.s("status"))
                // This is a presentation boundary, never a native turn or parent.
                .put("groupBoundary",JSONArray(if(native)listOf(event.s("authority"),author,data.s("deliveryId"),
                    data.optLong("generation"),thread,data.s("turn"))else listOf(author,thread,data.s("turn"))).toString())
            if(native){
                val attribution="Agent: $author\nHost: ${data.s("hostId")}\nExecution: ${data.s("deliveryId")}\nThread: $thread\nTurn: ${data.s("turn")}\nItem: $item"
                val changes=data.optJSONArray("changes")?.let{array->(0 until array.length()).map{array.getJSONObject(it)}
                    .joinToString("\n\n"){"${it.s("path")}\n${it.s("diff")}"}}.orEmpty()
                row.put("text",data.s("command",event.s("body")))
                    .put("detail",listOf(data.s("output"),changes,data.s("cwd").takeIf{it.isNotBlank()}?.let{"Directory: $it"}.orEmpty(),attribution).filter{it.isNotBlank()}.joinToString("\n\n"))
                    .put("truncated",data.optBoolean("outputTruncated")||data.optBoolean("detailsTruncated"))
                    .put("agentId",author).put("hostId",data.s("hostId")).put("deliveryId",data.s("deliveryId"))
                    .put("nativeThread",thread).put("nativeTurn",data.s("turn")).put("itemId",item)
                if(!data.isNull("exitCode"))row.put("exitCode",data.optInt("exitCode"))
                if(!data.isNull("durationMs"))row.put("durationMs",data.optLong("durationMs"))
            }
            if(previous==null){operations[identity]=rows.size;rows+=row}else rows[previous]=row
        }else rows+=row
    }
    return rows
}

internal fun roomAuthorStartsBlock(rows:List<JSONObject>,index:Int)=
    rows[index].s("roomAuthor").isNotBlank()&&
        (index==0||rows[index-1].s("roomAuthor")!=rows[index].s("roomAuthor"))
