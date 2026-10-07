package co.fallsoft.pocket

import org.json.JSONObject

internal fun catalogTask(t:JSONObject)=Task(t.s("id"),t.s("name"),t.s("cwd"),t.optJSONObject("status")?.s("type")?:"idle",t.optLong("updatedAt"),t.optBoolean("watched"),t.optBoolean("archived"),t.s("preview"),t.s("previewRole","context"),t.s("previewKind","message"),t.optLong("activityAt"),t.optLong("recencyAt",t.optLong("createdAt")),t.s("parentThreadId").takeIf{it.isNotBlank()},t.optBoolean("isChild",t.s("parentThreadId").isNotBlank()),t.s("agentNickname"),t.s("agentRole"),t.optBoolean("canAcceptDirectInput",!t.optBoolean("isChild",t.s("parentThreadId").isNotBlank())),t.optInt("unreadCount"))
internal fun taskWorkTime(t:Task)=maxOf(t.recencyAt,t.activityAt)
data class SessionGroup(val task:Task,val children:List<Task>)
/** Parent identity comes from the runtime, never a title/name heuristic. */
internal fun sessionGroups(tasks:List<Task>):List<SessionGroup>{
    val byId=tasks.associateBy{it.id}
    fun owner(t:Task):String?{var parent=t.parentThreadId;val seen=mutableSetOf(t.id)
        while(parent!=null&&seen.add(parent)){val entry=byId[parent]?:return null;if(!entry.isChild)return entry.id;parent=entry.parentThreadId};return null}
    val children=tasks.filter{it.isChild}.groupBy{owner(it)}
    return tasks.filter{!it.isChild}.map{SessionGroup(it,children[it.id].orEmpty())}
}
internal fun ungroupedChildren(tasks:List<Task>):List<Task>{val grouped=sessionGroups(tasks).flatMap{it.children}.map{it.id}.toSet();return tasks.filter{it.isChild&&it.id !in grouped}}
