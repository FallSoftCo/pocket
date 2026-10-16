package co.fallsoft.pocket

import android.content.Context
import androidx.compose.runtime.*
import org.json.JSONObject

/** Immutable input intent. Permission callbacks never reread a later room/profile. */
internal data class CaptureDestination(
    val source:EnvironmentRequest,
    val threadId:String?,
    val roomJson:String?,
    val sourceLabel:String?,
    val inPlace:Boolean,
    val checkSaved:Boolean=false
){
    fun matchesCurrent():Boolean=Pocket.captureEnvironment(Pocket.environmentId)?.let(source::matches)==true
    fun start(context:Context){
        if(!PocketVoice.foreground||!matchesCurrent()){
            PocketVoice.captureFailure("The recording environment changed. Tap the microphone again; your draft is kept.",inPlace)
            return
        }
        PocketVoice.start(context,threadId,inPlace=inPlace,checkSaved=checkSaved,
            roomDestination=roomJson?.let(::JSONObject),sourceLabel=sourceLabel)
    }
    companion object{
        fun capture(threadId:String?,roomJson:String?=null,sourceLabel:String?=null,inPlace:Boolean=true,checkSaved:Boolean=false):CaptureDestination?=
            Pocket.captureEnvironment(Pocket.environmentId)?.let{CaptureDestination(it,threadId,roomJson,captureSourceLabel(threadId,roomJson,sourceLabel),inPlace,checkSaved)}
    }
    override fun toString()="CaptureDestination("+source.environment.id+", "+if(roomJson==null)"session)" else "room)"
}

/** Only the currently composed, authorized native room registers a hardware target. */
internal object ForegroundRoomInput {
    private data class Entry(val identity:Any,val target:CaptureDestination)
    private var entry by mutableStateOf<Entry?>(null)
    fun attach(target:CaptureDestination):()->Unit{
        require(target.roomJson!=null)
        val saved=Entry(Any(),target);entry=saved
        return {if(entry?.identity===saved.identity)entry=null}
    }
    fun current():ForegroundCaptureTarget?{
        val target=entry?.target?:return null
        if(!BackendNavigation.nativeSelected()||!target.matchesCurrent())return null
        return ForegroundCaptureTarget(null,true,target.roomJson,target.sourceLabel)
    }
}

/** Resolve a readable label once at input, never from another profile during playback. */
internal fun captureSourceLabel(threadId:String?,roomJson:String?,explicit:String?):String{
    explicit?.takeIf{it.isNotBlank()}?.let{return it}
    if(roomJson!=null)return "Room"
    if(threadId==null)return "Coordinator"
    return Pocket.tasks.firstOrNull{it.id==threadId}?.title?.takeIf{it.isNotBlank()}?:"Conversation"
}
