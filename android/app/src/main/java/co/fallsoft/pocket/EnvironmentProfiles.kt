package co.fallsoft.pocket

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Metadata only. Pairing credentials remain in existing private preference namespaces. */
class EnvironmentProfiles(private val prefs:SharedPreferences) {
    fun all():List<EnvironmentIdentity> {
        val saved=runCatching{JSONArray(prefs.getString("environments:v1","[]"))}.getOrElse{JSONArray()}
        val profiles=saved.objects().mapNotNull{row->runCatching{EnvironmentIdentity(row.getString("id"),row.getString("name"),row.optBoolean("local"),row.optJSONObject("fleet")?.let{runCatching{FleetMembership.read(it)}.getOrNull()})}.getOrNull()}.distinctBy{it.id}.toMutableList()
        if(prefs.getString("token","").orEmpty().isNotBlank()&&profiles.none{it.id=="workstation"})profiles.add(0,EnvironmentIdentity("workstation","Workstation"))
        if(prefs.getString("local:token","").orEmpty().isNotBlank()&&profiles.none{it.id=="phone"})profiles.add(EnvironmentIdentity("phone","This phone",true))
        return profiles
    }
    fun lastRemote():EnvironmentIdentity=all().firstOrNull{it.id==prefs.getString("lastRemoteEnvironment",null)&&!it.local&&capture(it.id)!=null}
        ?:all().firstOrNull{!it.local&&capture(it.id)!=null}?:EnvironmentIdentity("workstation","Workstation")
    fun selected():EnvironmentIdentity=all().firstOrNull{it.id==prefs.getString("activeEnvironment",null)}
        ?:EnvironmentIdentity(if(prefs.getBoolean("activeLocal",false))"phone" else "workstation",if(prefs.getBoolean("activeLocal",false))"This phone" else "Workstation",prefs.getBoolean("activeLocal",false))
    fun capture(id:String=selected().id):EnvironmentRequest? {
        val profile=all().firstOrNull{it.id==id}?:return null
        val base=prefs.getString(profile.key("server"),"").orEmpty();val token=prefs.getString(profile.key("token"),"").orEmpty()
        return if(base.isBlank()||token.isBlank())null else EnvironmentRequest(profile,base,token)
    }
    /** A push belongs to exactly one still-paired remote; selection is irrelevant. */
    fun capturePush(deviceId:String):EnvironmentRequest? {
        if(deviceId.isBlank())return null
        val profile=all().filter{!it.local&&prefs.getString(it.key("deviceId"),null)==deviceId}.singleOrNull()?:return null
        return capture(profile.id)
    }
    fun select(id:String):Boolean {
        val destination=capture(id)?:return false
        val edit=prefs.edit().putString("activeEnvironment",id).putBoolean("activeLocal",destination.environment.local)
        if(!destination.environment.local)edit.putString("lastRemoteEnvironment",id)
        return edit.commit()
    }
    /** Called only after successful candidate pairing; a new remote never replaces the legacy remote. */
    fun add(name:String,base:String,token:String,deviceId:String,local:Boolean=false):EnvironmentIdentity = synchronized(prefs) {
        require(name.isNotBlank()&&token.isNotBlank())
        require(base.startsWith("https://")||local&&base in listOf("http://127.0.0.1:18880","http://localhost:18880"))
        val profiles=all().toMutableList()
        val existing=profiles.firstOrNull{prefs.getString(it.key("server"),null)==base&&it.local==local}
        val profile=existing?:EnvironmentIdentity(if(local)"phone" else "remote_"+UUID.randomUUID().toString().replace("-",""),name.trim(),local)
        if(existing==null)profiles.add(profile)
        val encoded=encode(profiles)
        check(prefs.edit().putString("environments:v1",encoded).putString(profile.key("server"),base).putString(profile.key("token"),token).putString(profile.key("deviceId"),deviceId).commit()){ "Could not save environment" }
        profile
    }

    private fun encode(profiles:List<EnvironmentIdentity>)=JSONArray(profiles.map{profile->
        JSONObject().put("id",profile.id).put("name",profile.name).put("local",profile.local).apply{
            profile.fleet?.let{put("fleet",it.json())}
        }
    }).toString()

    /** Metadata adoption never rotates a pairing or changes selection. */
    internal fun associateFleet(id:String,membership:FleetMembership) = synchronized(prefs) {
        val profiles=all().toMutableList();val old=profiles.single{it.id==id&&!it.local}
        old.fleet?.let{require(it==membership){"Existing fleet owner changed"}}
        if(old.fleet==membership)return@synchronized
        profiles[profiles.indexOf(old)]=old.copy(fleet=membership)
        check(prefs.edit().putString("environments:v1",encode(profiles)).commit()){"Could not save fleet identity"}
    }

    /** Saves one verified member; discovery never replaces an existing pairing. */
    internal fun enroll(fleet:FleetDescriptor,member:FleetMember,access:FleetEnrollment):EnvironmentIdentity = synchronized(prefs) {
        require(access.membership.fleetId==fleet.id&&access.membership.accountId==fleet.accountId&&
            access.membership.nodeId==member.id&&fleet.members.any{it==member})
        val profiles=all().toMutableList()
        val existing=profiles.firstOrNull{!it.local&&
            prefs.getString(it.key("server"),null)?.trimEnd('/')==member.origin}
        existing?.fleet?.let{require(it==access.membership){"Existing fleet pairing owner changed"}}
        val id=existing?.id?:"fleet_"+member.id.replace("-","")
        require(existing!=null||profiles.none{it.id==id}){"Fleet identity conflicts with a saved profile"}
        val profile=EnvironmentIdentity(id,existing?.name?:member.name,false,access.membership)
        if(existing==null)profiles.add(profile)else profiles[profiles.indexOf(existing)]=profile
        val edit=prefs.edit().putString("environments:v1",encode(profiles))
        if(existing==null)edit.putString(profile.key("server"),member.origin).putString(profile.key("token"),access.token)
            .putString(profile.key("deviceId"),access.deviceId)
        check(edit.commit()){"Could not save fleet connection"}
        profile
    }
}
