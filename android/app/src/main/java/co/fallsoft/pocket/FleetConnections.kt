package co.fallsoft.pocket

import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import androidx.annotation.MainThread
import androidx.compose.runtime.mutableStateMapOf

/** Adds authenticated fleet routes in the background; never changes selection or sends work. */
internal object FleetConnections {
    val unavailable=mutableStateMapOf<String,String>()
    private val jobs=mutableMapOf<String,Job>()
    private val retryAt=ConcurrentHashMap<String,Long>()
    private val failures=ConcurrentHashMap<String,Int>()
    private fun current(source:EnvironmentRequest)=Pocket.captureEnvironment(source.environment.id)?.matches(source)==true

    private fun backoff(attempts:Int)=minOf(600_000L,15_000L*(1L shl attempts.coerceAtMost(5)))

    @MainThread
    fun observe(source:EnvironmentRequest,raw:JSONObject?,changed:()->Unit) {
        if(raw==null||source.environment.local||!current(source))return
        val fleet=runCatching{FleetDescriptor.read(raw)}.getOrNull()?:return
        if(fleet.enrollmentPath==null)return // A partial/old gateway cannot silently enroll clients.
        val realm=fleet.id+":"+fleet.accountId
        val prefix="fleetConnection:$realm:"
        val owner=Pocket.prefs.getString(prefix+"ownerDeviceId",null)
        val anchor=Pocket.prefs.getString(prefix+"anchor",null)
        val metadataOnly=owner!=null&&anchor!=source.environment.id&&source.environment.fleet?.ownerDeviceId!=owner
        val membership=source.environment.fleet
        if(metadataOnly&&membership?.fleetId==fleet.id&&membership.accountId==fleet.accountId&&membership.nodeId==fleet.nodeId)return
        val key=if(metadataOnly)realm+":profile:"+source.environment.id else realm
        if(jobs[key]?.isActive==true||System.currentTimeMillis()<(retryAt[key]?:0))return
        val job=Pocket.scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY){
            var added=false
            try{
                if(!current(source))return@launch
                val session=FleetTransport.session(source,fleet)
                check(metadataOnly||owner==null||owner==session.deviceId){"Fleet login changed"}
                if(!current(source))return@launch
                if(metadataOnly){
                    Pocket.environmentProfiles.associateFleet(source.environment.id,
                        FleetMembership(fleet.id,fleet.accountId,fleet.nodeId,session.deviceId))
                    added=true;failures.remove(key);retryAt.remove(key)
                    withContext(Dispatchers.Main){unavailable.remove(key)}
                    return@launch
                }
                check(Pocket.prefs.edit().putString(prefix+"ownerDeviceId",session.deviceId)
                    .putString(prefix+"anchor",source.environment.id).commit())
                val enrolled=FleetMembership(fleet.id,fleet.accountId,fleet.nodeId,session.deviceId)
                Pocket.environmentProfiles.associateFleet(source.environment.id,enrolled)
                added=source.environment.fleet!=enrolled
                if(added)withContext(Dispatchers.Main){changed();added=false}
                for(member in fleet.members){
                    if(!current(source))return@launch
                    val memberKey=key+":"+member.id
                    // A saved pairing remains authoritative. Do not replace its token/device ID.
                    val known=Pocket.environmentProfiles.all().any{profile->
                        !profile.local&&Pocket.captureEnvironment(profile.id)?.endpoint?.trimEnd('/')==member.origin
                    }
                    if(known){
                        failures.remove(memberKey);retryAt.remove(memberKey)
                        withContext(Dispatchers.Main){unavailable.remove(memberKey)}
                        continue
                    }
                    if(System.currentTimeMillis()<(retryAt[memberKey]?:0))continue
                    try {
                    val opKey=prefix+"enroll:"+member.id
                    var operation=Pocket.prefs.getString(opKey,null)
                    val pending=operation!=null
                    if(operation==null){
                        operation=UUID.randomUUID().toString()
                        check(Pocket.prefs.edit().putString(opKey,operation).commit())
                    }
                    // Unknown authentication admission is reconciled under the same operation.
                    // The enrollment API is idempotent; no task or message endpoint is used here.
                    val receipt=if(pending)FleetTransport.receipt(member,fleet,session,operation)else null
                    val access=receipt?:FleetTransport.enroll(member,fleet,session,operation)
                    if(!current(source))return@launch
                    Pocket.environmentProfiles.enroll(fleet,member,access)
                    check(Pocket.prefs.edit().remove(opKey).commit())
                    added=true
                    failures.remove(memberKey);retryAt.remove(memberKey)
                    withContext(Dispatchers.Main){unavailable.remove(memberKey);changed();added=false}
                    }catch(error:CancellationException){throw error}
                    catch(_:Exception){
                        val attempts=(failures[memberKey]?:0)+1;failures[memberKey]=attempts
                        retryAt[memberKey]=System.currentTimeMillis()+backoff(attempts)
                        withContext(Dispatchers.Main){unavailable[memberKey]=member.name+" · Connection unavailable; retrying automatically"}
                    }
                }
                withContext(Dispatchers.Main){unavailable.remove(key)}
                failures.remove(key);retryAt[key]=System.currentTimeMillis()+60_000
            }catch(error:CancellationException){throw error}
            catch(_:Exception){
                val attempts=(failures[key]?:0)+1;failures[key]=attempts
                retryAt[key]=System.currentTimeMillis()+backoff(attempts)
                withContext(Dispatchers.Main){unavailable[key]="Fleet discovery unavailable; saved connections are unchanged"}
            }finally{
                withContext(NonCancellable+Dispatchers.Main){jobs.remove(key);if(added)changed()}
            }
        }
        jobs[key]=job;job.start()
    }
}
