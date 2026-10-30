package co.fallsoft.pocket

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Authentication only: cannot submit agent work, turn input or arbitrary paths. */
internal object FleetTransport {
    private val http by lazy{CodexReadTransport.catalogClient.newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(6,TimeUnit.SECONDS).build()}
    suspend fun session(source:EnvironmentRequest,fleet:FleetDescriptor):FleetSession {
        require(!source.environment.local)
        val value=request(source.endpoint,source.token,"/api/fleet/session",JSONObject())
        return FleetSession.read(value,fleet,System.currentTimeMillis())
    }
    suspend fun enroll(member:FleetMember,fleet:FleetDescriptor,session:FleetSession,operationId:String):FleetEnrollment {
        require(fleet.members.contains(member)&&fleet.enrollmentPath=="/api/fleet/enroll"&&
            session.fleetId==fleet.id&&session.accountId==fleet.accountId)
        UUID.fromString(operationId)
        val value=request(member.origin,session.token,"/api/fleet/enroll",JSONObject().put("operationId",operationId))
        return FleetEnrollment.read(value,fleet,member,session.deviceId)
    }
    suspend fun receipt(member:FleetMember,fleet:FleetDescriptor,session:FleetSession,operationId:String):FleetEnrollment? {
        require(fleet.members.contains(member)&&session.fleetId==fleet.id&&session.accountId==fleet.accountId)
        UUID.fromString(operationId)
        val value=try{request(member.origin,session.token,"/api/fleet/enrollments/"+operationId,null)}
            catch(error:PocketApiException){if(error.status==404)return null else throw error}
        if(value.optString("state")=="pending")throw IOException("Fleet enrollment is still pending")
        return FleetEnrollment.read(value.optJSONObject("enrollment")?:value,fleet,member,session.deviceId)
    }
    private suspend fun request(origin:String,credential:String,path:String,body:JSONObject?):JSONObject {
        require(path in setOf("/api/fleet/session","/api/fleet/enroll")||
            body==null&&path.matches(Regex("/api/fleet/enrollments/[0-9a-f-]{36}")))
        val base=FleetDescriptor.origin(origin)
        val builder=Request.Builder().url(base+path).header("Authorization","Bearer "+credential)
        if(body!=null)builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        val request=builder.build()
        return suspendCancellableCoroutine{continuation->
            val call=http.newCall(request);continuation.invokeOnCancellation{call.cancel()}
            call.enqueue(object:Callback{
                override fun onFailure(call:Call,e:IOException){if(continuation.isActive)
                    continuation.resumeWithException(IOException("Fleet authentication transport unavailable",e))}
                override fun onResponse(call:Call,response:Response){
                    try{
                        val value=response.use{
                            val length=it.body?.contentLength()?:-1
                            require(length<=65_536){"Fleet response too large"}
                            val source=it.body?.source()
                            source?.request(65_537)
                            val bytes=source?.readByteArray(minOf(65_537L,source.buffer.size))?:byteArrayOf()
                            require(bytes.size<=65_536){"Fleet response too large"}
                            if(!it.isSuccessful)throw PocketApiException(it.code,"Fleet authentication unavailable")
                            JSONObject(bytes.toString(Charsets.UTF_8))
                        }
                        if(continuation.isActive)continuation.resume(value)
                    }catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(
                        if(e is PocketApiException)e else IOException("Fleet authentication response unavailable"))}
                }
            })
        }
    }
}
