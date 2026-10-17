package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Local custody, not another chat feed. Actions always use the recording's frozen origin. */
@Composable internal fun RecordingQueueControls() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var turns by remember { mutableStateOf(emptyList<VoiceTurnStore.Turn>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    val environment=Pocket.environmentId
    LaunchedEffect(environment,PocketVoice.deliveryStatus,PocketVoice.captureBusy,revision) {
        turns=withContext(Dispatchers.IO) {
            DurableVoiceWork.initialize(context)
            VoiceTurnStore(context,DurableVoiceWork.JOURNAL).use { store ->
                store.list().filter { it.origin.environment==environment &&
                    it.delivery !in setOf(VoiceTurnStore.Delivery.COMPLETED,VoiceTurnStore.Delivery.CANCELLED) }
                    .sortedByDescending { it.createdAt }
            }
        }
    }
    if(turns.isEmpty())return
    fun act(turn:VoiceTurnStore.Turn,operation:String) {
        if(busy!=null)return
        busy=turn.key;problem=""
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    VoiceTurnStore(context,DurableVoiceWork.JOURNAL).use { store ->
                        val current=requireNotNull(store.get(turn.key))
                        val requests=VoiceTurnRequests(context)
                        val path="/api/voice/recordings/"+current.id
                        when(operation) {
                            "check"->DurableVoiceWork.enqueue(context,current.key)
                            "retry"->{
                                val receipt=requests.call(current,path+"/retry-audio",JSONObject())
                                store.retryAdmittedAudio(current.key,receipt)
                                DurableVoiceWork.enqueue(context,current.key)
                            }
                            "cancel-request"->store.cancelled(current.key,requests.call(current,path+"/cancel-request",JSONObject()))
                            "cancel"->{
                                if(current.delivery==VoiceTurnStore.Delivery.UNSUBMITTED)store.cancelUnsubmitted(current.key)
                                else store.cancelled(current.key,requests.call(current,path+"/cancel",JSONObject()))
                            }
                        }
                    }
                }
            }catch(e:Exception){problem=e.message?:"Could not confirm the recording action. Audio and receipts are retained."}
            finally{busy=null;revision++}
        }
    }
    Column(Modifier.fillMaxWidth()) {
        TextButton({expanded=!expanded},modifier=Modifier.heightIn(min=48.dp)) {
            BilingualLabel("Recordings");WorkflowText(" · ${turns.size}")
        }
        if(expanded)turns.forEach { turn ->
            val receipt=runCatching { JSONObject(turn.receipt.orEmpty()) }.getOrNull()
            val destination=runCatching { JSONObject(turn.origin.destination) }.getOrNull()
            val room=destination?.optJSONObject("room")
            val label=if(room!=null)room.optString("name").ifBlank{"Room"}
                else if(destination?.optString("threadId").isNullOrBlank())"Coordinator" else "Direct conversation"
            val status=when {
                turn.capture==VoiceTurnStore.Capture.INTERRUPTED->"Interrupted · not sent"
                turn.capture==VoiceTurnStore.Capture.RECORDING->"Recording"
                turn.capture==VoiceTurnStore.Capture.SEALING->"Saving recording"
                turn.delivery==VoiceTurnStore.Delivery.UNSUBMITTED->"Saved · waiting to upload"
                turn.delivery==VoiceTurnStore.Delivery.SUBMITTING->"Checking upload"
                turn.delivery==VoiceTurnStore.Delivery.STORED->"Audio received · transcribing"
                turn.delivery==VoiceTurnStore.Delivery.ACCEPTED->"Request accepted"
                turn.delivery==VoiceTurnStore.Delivery.UNKNOWN->"Checking original delivery"
                else->turn.problem?:"Needs attention"
            }
            Column(Modifier.fillMaxWidth().padding(vertical=4.dp)) {
                WorkflowText(label+" · "+android.text.format.DateUtils.getRelativeTimeSpanString(turn.createdAt),maxLines=2)
                WorkflowText(status,color=if(turn.problem!=null)Coral else Muted,maxLines=3)
                Row(Modifier.fillMaxWidth()) {
                    if(turn.capture==VoiceTurnStore.Capture.SEALED && turn.delivery!=VoiceTurnStore.Delivery.FAILED)
                        TextButton({act(turn,"check")},enabled=busy==null,modifier=Modifier.heightIn(min=48.dp)){BilingualLabel("Check status")}
                    if(turn.delivery==VoiceTurnStore.Delivery.FAILED && receipt?.optBoolean("canRetryAudio")==true)
                        TextButton({act(turn,"retry")},enabled=busy==null,modifier=Modifier.heightIn(min=48.dp)){BilingualLabel("Retry audio")}
                    if(turn.delivery==VoiceTurnStore.Delivery.ACCEPTED && receipt?.optString("state")=="queued")
                        TextButton({act(turn,"cancel-request")},enabled=busy==null,modifier=Modifier.heightIn(min=48.dp)){BilingualLabel("Cancel request")}
                    if(turn.capture!=VoiceTurnStore.Capture.RECORDING && turn.delivery !in setOf(VoiceTurnStore.Delivery.ACCEPTED,VoiceTurnStore.Delivery.COMPLETED))
                        TextButton({act(turn,"cancel")},enabled=busy==null,modifier=Modifier.heightIn(min=48.dp)){BilingualLabel("Cancel recording")}
                }
            }
        }
        if(problem.isNotBlank())WorkflowText(problem,color=Coral,maxLines=4)
    }
}
