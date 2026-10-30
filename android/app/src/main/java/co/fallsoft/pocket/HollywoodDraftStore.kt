package co.fallsoft.pocket

import android.content.SharedPreferences
import org.json.JSONObject

/** Drafts belong to a human audience and room, not the gateway used to reach it.
 * Pending commands deliberately keep their ORIGINAL physical route and receipt.
 */
internal class HollywoodDraftStore(
    private val prefs:SharedPreferences,private val owner:String,private val binding:JSONObject,
    private val origin:EnvironmentRequest?
) {
    private fun physical(scope:HollywoodScope)="hollywood:$owner:${binding.s("bindingId")}:${scope.room}:${scope.recipient}:${scope.audience}:${scope.sourceEvent?:"root"}:${scope.kind}"
    private fun logical(scope:HollywoodScope):String? {
        val membership=origin?.environment?.fleet?:return null
        if(backendOwner(origin.endpoint,origin.token)!=owner)return null
        val viewer=binding.s("principalId");val authority=binding.s("authorityId")
        if(!hollywoodUuid(viewer)||!hollywoodUuid(authority))return null
        return "fleet-room:"+FleetWorkIdentity(membership.fleetId,membership.accountId,viewer,
            WorkKind.ROOM,authority,scope.room).key+":${scope.recipient}:${scope.audience}:${scope.sourceEvent?:"root"}:${scope.kind}"
    }
    fun draft(scope:HollywoodScope):String {
        val shared=logical(scope)?.let{"draft:$it"}
        // contains distinguishes an intentionally cleared draft from a missing migration.
        if(shared!=null&&prefs.contains(shared))return prefs.getString(shared,"").orEmpty()
        val old=prefs.getString("draft:"+physical(scope),"").orEmpty()
        if(shared!=null&&old.isNotEmpty())prefs.edit().putString(shared,old).putString("scope:"+logical(scope),scope.json().toString()).apply()
        return old
    }
    fun save(scope:HollywoodScope,text:String){
        val legacy=physical(scope)
        val edit=prefs.edit().putString("draft:$legacy",text).putString("scope:$legacy",scope.json().toString())
        logical(scope)?.let{edit.putString("draft:$it",text).putString("scope:$it",scope.json().toString())}
        edit.apply()
    }
    fun scopes(room:String):List<HollywoodScope> {
        val realm=origin?.environment?.fleet
        val prefix=if(realm!=null&&hollywoodUuid(binding.s("principalId"))&&hollywoodUuid(binding.s("authorityId")))
            "scope:fleet-room:"+FleetWorkIdentity(realm.fleetId,realm.accountId,binding.s("principalId"),WorkKind.ROOM,binding.s("authorityId"),room).key+":" else null
        return prefs.all.filterKeys{it.startsWith("scope:hollywood:$owner:${binding.s("bindingId")}:")||prefix!=null&&it.startsWith(prefix)}
            .values.mapNotNull{runCatching{HollywoodScope.from(JSONObject(it as String))}.getOrNull()}.filter{it.room==room}.distinct()
    }
}

internal class HollywoodSavedSend(val origin:EnvironmentRequest,val binding:JSONObject,val scope:HollywoodScope) {
    override fun toString()="HollywoodSavedSend(original route, private intent)"
}

/** Read-only discovery of retained sends on another gateway. Opening one never
 * resends it: the original client's existing command-lookup UI owns recovery.
 */
internal fun hollywoodOtherSends(prefs:SharedPreferences,current:EnvironmentRequest,binding:JSONObject,
    room:String,sources:List<EnvironmentRequest>):List<HollywoodSavedSend> {
    val realm=current.environment.fleet?:return emptyList()
    val own=backendOwner(current.endpoint,current.token)
    return prefs.all.filterKeys{it.startsWith("pending:hollywood:")}.mapNotNull{(key,value)->runCatching{
        val intent=JSONObject(value as String);val old=intent.getJSONObject("binding")
        val scope=HollywoodScope.from(intent.getJSONObject("scope"))
        if(scope.room!=room||old.s("principalId")!=binding.s("principalId")||old.s("authorityId")!=binding.s("authorityId"))return@runCatching null
        val original=sources.firstOrNull{source->
            val membership=source.environment.fleet
            membership?.fleetId==realm.fleetId&&membership.accountId==realm.accountId&&
                key.startsWith("pending:hollywood:"+backendOwner(source.endpoint,source.token)+":"+old.s("bindingId")+":")
        }?:return@runCatching null
        if(backendOwner(original.endpoint,original.token)==own&&old.s("bindingId")==binding.s("bindingId"))null
        else HollywoodSavedSend(original,old,scope)
    }.getOrNull()}.distinctBy{listOf(it.origin.environment.id,it.binding.s("bindingId"),it.scope)}
}
