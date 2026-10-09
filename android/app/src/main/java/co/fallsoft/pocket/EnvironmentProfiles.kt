package co.fallsoft.pocket

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Metadata only. Pairing credentials remain in existing private preference namespaces. */
class EnvironmentProfiles(private val prefs:SharedPreferences) {
    fun all():List<EnvironmentIdentity> {
        val saved=runCatching{JSONArray(prefs.getString("environments:v1","[]"))}.getOrElse{JSONArray()}
        val profiles=saved.objects().mapNotNull{row->runCatching{EnvironmentIdentity(row.getString("id"),row.getString("name"),row.optBoolean("local"))}.getOrNull()}.distinctBy{it.id}.toMutableList()
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
    fun select(id:String):Boolean {
        val destination=capture(id)?:return false
        val edit=prefs.edit().putString("activeEnvironment",id).putBoolean("activeLocal",destination.environment.local)
        if(!destination.environment.local)edit.putString("lastRemoteEnvironment",id)
        return edit.commit()
    }
    /** Called only after successful candidate pairing; a new remote never replaces the legacy remote. */
    fun add(name:String,base:String,token:String,deviceId:String,local:Boolean=false):EnvironmentIdentity {
        require(name.isNotBlank()&&token.isNotBlank())
        require(base.startsWith("https://")||local&&base in listOf("http://127.0.0.1:18880","http://localhost:18880"))
        val profiles=all().toMutableList()
        val existing=profiles.firstOrNull{prefs.getString(it.key("server"),null)==base&&it.local==local}
        val profile=existing?:EnvironmentIdentity(if(local)"phone" else "remote_"+UUID.randomUUID().toString().replace("-",""),name.trim(),local)
        if(existing==null)profiles.add(profile)
        val encoded=JSONArray(profiles.map{JSONObject().put("id",it.id).put("name",it.name).put("local",it.local)}).toString()
        check(prefs.edit().putString("environments:v1",encoded).putString(profile.key("server"),base).putString(profile.key("token"),token).putString(profile.key("deviceId"),deviceId).commit()){ "Could not save environment" }
        return profile
    }
}
