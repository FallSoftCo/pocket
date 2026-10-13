package co.fallsoft.pocket

import org.json.JSONObject

/** Presentation only. Provider rows, ordering, send receipts and cache stay intact. */
internal data class CommandRunProjection(
 val rows:List<JSONObject>,
 val groups:Map<String,List<JSONObject>>,
 val displayIds:Map<String,String>
) {
 fun displayId(sourceId:String)=displayIds[sourceId]?:sourceId
 fun canonicalId(displayId:String)=groups[displayId]?.firstOrNull()?.s("id")
     ?:displayId.removePrefix("ui:run-group:")
 fun headerFor(sourceId:String)=groups.entries.firstOrNull{(_,rows)->rows.any{it.s("id")==sourceId}}?.key
}
internal fun isCommandRun(row:JSONObject)=row.s("kind")=="activity"&&
 row.s("type") in setOf("commandExecution","mcpToolCall","dynamicToolCall","fileChange","webSearch")

/** Outputs, questions, user inputs and turn boundaries never disappear into a run group. */
internal fun commandRunProjection(source:List<JSONObject>,expanded:Set<String>):CommandRunProjection {
 val display=ArrayList<JSONObject>(source.size)
 val groups=linkedMapOf<String,List<JSONObject>>()
 val ids=HashMap<String,String>(source.size)
 var index=0
 while(index<source.size) {
  val first=source[index]
  if(!isCommandRun(first)){display+=first;ids[first.s("id")]=first.s("id");index++;continue}
  val start=index
  val turn=first.s("turnId")
  do{index++}while(index<source.size&&isCommandRun(source[index])&&source[index].s("turnId")==turn)
  val group=source.subList(start,index).toList()
  val key="ui:run-group:"+first.s("id")
  val open=group.any{it.s("id") in expanded}
  groups[key]=group
  // Headers are presentation identities. Every expanded provider row keeps
  // its own unchanged lazy key, including when an older member is prepended.
  display+=JSONObject(first.toString()).put("id",key).put("kind","commandRuns")
  group.forEach{row->
   ids[row.s("id")]=if(open)row.s("id")else key
   if(open)display+=row
  }
 }
 return CommandRunProjection(display,groups,ids)
}
