package co.fallsoft.pocket

import org.json.JSONArray
import org.json.JSONObject

/** Public room events remain immutable; operation updates share one display row. */
internal fun roomTranscriptRows(events:List<JSONObject>,prefix:String,principalId:String="",agentNames:Map<String,String> = emptyMap()):List<JSONObject> {
    val rows=mutableListOf<JSONObject>()
    val operations=mutableMapOf<String,Int>()
    events.forEach{event->
        val data=event.optJSONObject("data")
        val author=event.s("author")
        val item=data?.s("itemId").orEmpty()
        val thread=data?.s("thread").orEmpty()
        val native=data?.s("type")=="executionActivity"
        val structured=native&&data?.s("nativeType") in listOf("planUpdate","goalState")
        val activity=(event.s("kind")=="artifact"||native)&&item.isNotBlank()&&thread.isNotBlank()&&
            data?.s("nativeType") !in listOf("agentMessage","plan","planUpdate","goalState")&&
            data?.s("status") in listOf("inProgress","completed","failed","declined","unknown")
        val identity=JSONArray(if(structured)listOf(event.s("authority"),author,data?.s("deliveryId"),data?.optLong("generation"),thread,data?.s("turn"),data?.s("nativeType"),data?.optJSONObject("nativeGoal")?.s("goalId"))else if(native)listOf(event.s("authority"),author,data?.s("deliveryId"),
            data?.optLong("generation"),thread,data?.s("turn"),item)else listOf(author,thread,item)).toString()
        val previous=if(activity||structured)operations[identity]else null
        val eventId=event.opt("id")
        val row=JSONObject().put("id",if(activity||structured)"$prefix:operation:$identity" else "$prefix:$eventId")
            .put("roomEventId",eventId).put("roomAuthor",if(author==principalId)"You"else event.s("authorName",agentNames[author]?:author))
            .put("kind",if(activity)"activity" else if(author=="you"||event.optBoolean("isSelf")||author==principalId)"user" else "message")
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
        }else {
            if(data!=null&&(data.s("type")=="workflowState"||data.s("workflowAction").isNotBlank())){
                val receipt=data.optJSONObject("workflowReceipt")
                val action=data.s("workflowAction")
                val state=receipt?.s("state")?:data.s("state")
                val stateLabel=state.replace("cancel_requested","stop requested")
                val title=when(action){
                    "goal.create"->"Goal created"
                    "goal.work"->"Work planned"
                    "work.assign"->"Work assigned"
                    else->(if(action.startsWith("work."))"Work" else "Goal")+" · "+stateLabel
                }
                row.put("kind","activity").put("type","workflowState").put("roomAuthor","Hollywood")
                    .put("title",title).put("status","completed").put("text",event.s("body"))
                    .put("detail","Initiated by: $author\nGoal: ${data.s("goalId")}\nWork: ${data.s("workId")}\nEvent: $eventId\nControl: ${data.s("causalControlEventId",receipt?.s("eventId").orEmpty())}")
            }
            if(native&&data?.s("nativeType") in listOf("planUpdate","goalState")){
                val plan=data?.optJSONArray("plan")?.objects().orEmpty()
                val goal=data?.optJSONObject("nativeGoal")
                val text=if(goal!=null)"${goal.s("status")} · ${goal.optLong("tokensUsed")} tokens · ${goal.optLong("timeUsedSeconds")}s"
                    else plan.joinToString("\n"){step->"${when(step.s("status")){"completed"->"✓";"inProgress"->"→";else->"○"}} ${step.s("step")}"}
                row.put("kind","activity").put("type",data!!.s("nativeType"))
                    .put("title",if(goal!=null)"Native goal · ${goal.s("status")}" else "Plan")
                    .put("status","completed").put("text",text)
                    .put("detail","Agent: $author\nHost: ${data.s("hostId")}\nExecution: ${data.s("deliveryId")}\nThread: $thread\nTurn: ${data.s("turn")}"+
                        (goal?.let{"\nNative goal: ${it.s("goalId")}"}.orEmpty()))
                    .put("truncated",data.optBoolean("detailsTruncated"))
            }
            if(structured){if(previous==null){operations[identity]=rows.size;rows+=row}else rows[previous]=row}else rows+=row
        }
    }
    return rows
}

internal fun roomAuthorStartsBlock(rows:List<JSONObject>,index:Int)=
    rows[index].s("roomAuthor").isNotBlank()&&
        (index==0||rows[index-1].s("roomAuthor")!=rows[index].s("roomAuthor"))
