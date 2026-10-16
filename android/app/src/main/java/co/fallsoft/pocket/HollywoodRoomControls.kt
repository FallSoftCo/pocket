package co.fallsoft.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Current room work is a separate projection from history and transport health. */
internal data class HollywoodRoomWork(val verified:Boolean,val deliveries:List<JSONObject>,val questions:List<JSONObject>) {
    private fun count(state:String)=deliveries.count{it.s("state")==state}
    val label get()=when {
        !verified->"Status unavailable"
        questions.isNotEmpty()->"Needs you"
        deliveries.any{it.s("state")=="running"&&!it.optBoolean("cancellation_requested")}->"Running"
        count("claimed")>0->"Starting"
        count("pending")>0->"Queued"
        deliveries.any{it.s("state")=="cancel_requested"||it.s("state")=="running"&&it.optBoolean("cancellation_requested")}->"Stop requested"
        count("blocked")>0->"Blocked"
        count("unknown")>0->"Outcome unknown"
        else->"Idle"
    }
    val spinning get()=label=="Running"
    val icon get()=when(label){"Running"->"Codex";"Queued","Starting"->"MoreHoriz";"Idle"->"Pause";"Stop requested"->"Stop";else->"ErrorOutline"}
}

internal fun hollywoodRoomWork(detail:JSONObject,error:String,attention:List<JSONObject>,accepted:List<JSONObject> = emptyList()):HollywoodRoomWork {
    val raw=detail.optJSONArray("deliveries")
    val canonical=raw?.objects().orEmpty()
    val deliveries=(accepted+canonical).associateBy{it.s("id")}.values.toList()
    val known=setOf("pending","claimed","running","cancel_requested","blocked","unknown","completed","failed","cancelled")
    val verified=error.isBlank()&&hollywoodUuid(detail.optJSONObject("room")?.s("id").orEmpty())&&raw!=null&&raw.length()==canonical.size&&deliveries.all{it.s("state") in known}
    val ordered=deliveries.sortedWith(compareBy<JSONObject>{when(it.s("state")){"running"->0;"claimed"->1;"pending"->2;else->3}}.thenBy{it.s("id")})
    val questions=attention.filter{it.optJSONObject("attention")?.s("state")=="pending"&&it.s("deliveryState") in listOf("claimed","running")}
    return HollywoodRoomWork(verified,ordered,questions)
}

/** Work stays closest to the anchor; keyed rows cannot retarget a press during live updates. */
@Composable internal fun RoomWorkControls(work:HollywoodRoomWork,names:Map<String,String>,client:HollywoodClient,room:String,
    stopping:Set<String>,detailError:String,attentionError:String,onAttention:(JSONObject)->Unit,onGuidance:(JSONObject)->Unit,onStop:(JSONObject,Boolean)->Unit){
    BilingualLabel(work.label,centered=false,color=if(work.label in listOf("Needs you","Outcome unknown","Blocked"))Coral else Muted)
    if(detailError.isNotBlank())Text(detailError.take(180),color=Coral,maxLines=3)
    if(attentionError.isNotBlank())BilingualLabel("Questions could not refresh",centered=false,color=Coral)
    val shown=work.deliveries.map{it.s("id")}.toSet()
    client.unresolvedStops(room).filter{it.getJSONObject("scope").s("deliveryId") !in shown}.forEach{saved->
        val scope=saved.getJSONObject("scope");val id=scope.s("deliveryId")
        androidx.compose.runtime.key("stop:"+id){
            TextButton({onStop(JSONObject().put("id",id).put("agent",scope.s("agentId")).put("event",scope.s("requestId")),true)},
                enabled=id !in stopping,modifier=Modifier.testTag("native-check-stop:"+id)){BilingualLabel("Check stop")}
        }
    }
    // Presentation cache only, disposed with the popup. State changes don't
    // shuffle actions under a thumb; reopening chooses current priority again.
    val order=androidx.compose.runtime.remember(client.bindingId,room){mutableListOf<String>()}
    order.retainAll(shown)
    work.deliveries.asReversed().forEach{if(it.s("id") !in order)order.add(it.s("id"))}
    val byId=work.deliveries.associateBy{it.s("id")}
    order.mapNotNull{byId[it]}.forEach{delivery->
        val id=delivery.s("id");val saved=client.stopRecord(id)
        androidx.compose.runtime.key("delivery:"+id){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Column(Modifier.weight(1f)){
                    names[delivery.s("agent")]?.let{Text(it,maxLines=1)}?:BilingualLabel("Agent",centered=false)
                    BilingualLabel(roomDeliveryLabel(delivery).substringBefore(" · "),centered=false,color=Muted)
                    if(!delivery.has("can_cancel"))BilingualLabel("Checking controls",centered=false,color=Muted)
                    delivery.s("reason",delivery.s("error")).takeIf{it.isNotBlank()}?.let{ImmersionText("room-work-reason:"+client.owner+":"+client.bindingId+":"+id,it.take(180),color=Muted,maxLines=3)}
                }
                if(delivery.optBoolean("can_steer"))TextButton({onGuidance(delivery.getJSONObject("control_scope"))},Modifier.testTag("native-guide:"+id)){BilingualLabel("Guide")}
                if(saved?.s("state") in listOf("saved","sending","unknown"))TextButton({onStop(delivery,true)},enabled=id !in stopping,modifier=Modifier.testTag("native-check-stop:"+id)){BilingualLabel("Check stop")}
                else if(delivery.optBoolean("can_cancel")&&!delivery.optBoolean("cancellation_requested")&&delivery.s("state")!="cancel_requested"&&saved==null)TextButton({onStop(delivery,false)},enabled=id !in stopping,modifier=Modifier.testTag("native-stop:"+id)){BilingualLabel("Stop")}
            }
        }
    }
    work.questions.forEach{question->
        val id=question.getJSONObject("attention").s("id")
        androidx.compose.runtime.key("question:"+id){
            TextButton({onAttention(question)},Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("native-room-question:"+id)){
                BilingualLabel("Needs you",centered=false)
            }
        }
    }
}
