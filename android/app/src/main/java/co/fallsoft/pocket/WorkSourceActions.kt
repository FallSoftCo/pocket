package co.fallsoft.pocket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A list row retains its original authority even after another source becomes selected.
 * Validation is repeated at admission, never derived from the currently open conversation.
 */
internal fun validateWorkDestination(entry:WorkEntry<WorkCatalogPayload>,current:EnvironmentRequest?):EnvironmentRequest {
    val captured=entry.value.destination
    require(current?.matches(captured)==true){"This source pairing changed. Refresh the work list."}
    require(entry.key.source.environment==captured.environment.id){"Work source is unconfirmed"}
    val owner=backendOwner(captured.endpoint,captured.token)
    when(entry.key.kind){
        WorkKind.SESSION->{
            require(entry.key.source.backend=="codex"&&entry.key.source.owner==owner&&entry.key.authority.isEmpty())
            require(entry.key.id.isNotBlank()&&JSONObject(entry.value.body).s("id")==entry.key.id)
        }
        WorkKind.ROOM->{
            val binding=JSONObject(requireNotNull(entry.value.binding))
            require(entry.key.source.backend=="lax"&&entry.key.source.owner==owner+":"+binding.s("bindingId"))
            require(binding.s("bindingId").matches(Regex("[0-9a-f]{64}")))
            require(hollywoodUuid(entry.key.id)&&hollywoodUuid(entry.key.authority)&&entry.key.authority==binding.s("authorityId"))
            val room=JSONObject(entry.value.body)
            require(room.s("id")==entry.key.id&&room.s("authority")==entry.key.authority)
        }
    }
    return captured
}

/** Reversible list metadata operations only. No turn/start, retry, steer or outbox replay. */
internal class WorkSourceCommands(
    private val capture:(String)->EnvironmentRequest?,
    private val request:suspend (EnvironmentRequest,String,JSONObject)->JSONObject
) {
    suspend fun metadata(entry:WorkEntry<WorkCatalogPayload>,operation:String,body:JSONObject):JSONObject {
        val destination=validateWorkDestination(entry,capture(entry.key.source.environment))
        require(entry.key.kind==WorkKind.SESSION)
        require(operation in setOf("rename","watch","unarchive"))
        // IDs are escaped as one path segment, never interpreted as a resource/query.
        val id=java.net.URLEncoder.encode(entry.key.id,"UTF-8").replace("+","%20")
        return request(destination,"/api/threads/$id/$operation",body)
    }
}

internal object WorkSourceActions {
    private val commands=WorkSourceCommands(Pocket::captureEnvironment){destination,path,body->
        Pocket.apiEnvironment(destination,path,body)
    }
    fun open(entry:WorkEntry<WorkCatalogPayload>,keyboard:Boolean){
        try{
            val destination=validateWorkDestination(entry,Pocket.captureEnvironment(entry.key.source.environment))
            require(!PocketVoice.active&&PocketVoice.state !in setOf("Listening","Starting microphone","Finishing recording")){
                "Finish voice before opening another source"
            }
            Pocket.activateEnvironment(destination.environment.id)
            require(Pocket.captureEnvironment()?.matches(destination)==true){"Could not select the original source"}
            when(entry.key.kind){
                WorkKind.SESSION->{BackendNavigation.select("codex");Pocket.open(entry.key.id,keyboard)}
                WorkKind.ROOM->HollywoodNotifications.openRoom(destination,JSONObject(entry.value.binding!!),entry.key.id,keyboard)
            }
        }catch(e:Exception){Pocket.error=e.message?:"Could not open the original work"}
    }
    private fun change(entry:WorkEntry<WorkCatalogPayload>,operation:String,body:JSONObject,done:()->Unit={}){
        Pocket.scope.launch{
            try{
                val result=commands.metadata(entry,operation,body)
                if(operation=="rename"){
                    val origin=entry.value.destination
                    PocketNotificationTitles.rename(entry.key.id,body.getString("name"),
                        result.optJSONArray("notificationIds")?.let{a->(0 until a.length()).map{a.optLong(it)}}?:emptyList(),
                        origin.environment.local,result.optLong("revision"),origin.environment.id)
                }
                // The HTTP receipt is authoritative. A later refresh failure cannot undo it.
                done()
                UnifiedWorkCatalog.refresh()
                if(Pocket.captureEnvironment()?.matches(entry.value.destination)==true)Pocket.refresh()
            }catch(e:CancellationException){throw e}
            catch(e:Exception){Pocket.error=PocketNetwork.error(e)}
        }
    }
    val actions=UnifiedWorkActions(
        open=::open,
        rename={entry,name,done->change(entry,"rename",JSONObject().put("name",name),done)},
        watch={entry,enabled->change(entry,"watch",JSONObject().put("enabled",enabled))},
        restore={entry->change(entry,"unarchive",JSONObject())}
    )
}
