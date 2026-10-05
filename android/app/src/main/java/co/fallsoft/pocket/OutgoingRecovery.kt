package co.fallsoft.pocket

import org.json.JSONArray
import org.json.JSONObject

internal data class OutgoingRowState(val id:String,val state:String,val text:String,val result:String?)
internal fun applyOutgoingAcknowledgement(row:OutgoingRowState,id:String,action:String,text:String,state:String):OutgoingRowState? {
    if(row.id!=id||row.state in listOf("accepted","sending"))return row
    if(action=="remove")return null
    return row.copy(state=state,text=if(action=="edit")text.trim()else row.text,result=if(action in listOf("retry","send"))null else row.result)
}

/** Apply an acknowledged outbox action without requiring Codex history to load. */
internal fun acknowledgedOutgoing(rows:JSONArray?,id:String,action:String,text:String,state:String):JSONArray {
    val result=JSONArray()
    rows?.objects()?.forEach { row ->
        val original=OutgoingRowState(row.s("id"),row.s("state"),row.s("text"),row.s("result").takeIf{it.isNotEmpty()})
        val updated=applyOutgoingAcknowledgement(original,id,action,text,state)
        if(updated==original)result.put(row)
        else if(updated!=null)result.put(JSONObject(row.toString()).apply {
            put("state",updated.state);put("text",updated.text)
            if(action in listOf("retry","send"))remove("result")
        })
    }
    return result
}
