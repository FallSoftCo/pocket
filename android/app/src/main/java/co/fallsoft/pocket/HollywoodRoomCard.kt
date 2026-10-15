package co.fallsoft.pocket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** No generic session Idle fallback; only the canonical catalog can assert quiet work. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun HollywoodRoomCard(room:JSONObject,available:Boolean,onOpen:()->Unit) {
    val activity=HollywoodRoomActivity.read(room,available)
    val spoken=when {
        activity==null->"Status unavailable"
        activity.idle->"Idle; no visible unsettled work at observation"
        else->activity.categories().joinToString("; "){(label,count)->"$count $label"}
    }
    WorkCardSurface(activity?.scheduled==true,activity?.needsHuman==true,
        if(activity?.uncertain==true)Coral else Mint,modifier=Modifier
            .testTag("native-room:"+room.s("id")).clickable(onClick=onOpen)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                BilingualLabel(room.s("name"),Modifier.weight(1f),color=Paper,fontSize=16.sp,fontWeight=FontWeight.Medium,centered=false)
                Box(Modifier.size(24.dp)) {
                    if(activity?.executing==true)SymbolIcon("Codex",spoken,Modifier.fillMaxSize(),spinning=true)
                    else SymbolIcon(when {
                        activity==null||activity.uncertain->Icons.Rounded.HelpOutline
                        activity.needsHuman->Icons.Rounded.ErrorOutline
                        activity.scheduled->Icons.Rounded.HourglassTop
                        else->Icons.Rounded.Pause
                    },spoken,tint=if(activity?.uncertain==true||activity?.needsHuman==true)Coral else Muted,modifier=Modifier.size(20.dp))
                }
            }
            FlowRow(Modifier.fillMaxWidth().heightIn(min=24.dp).testTag("native-room-activity:"+room.s("id")).semantics{contentDescription=spoken},
                horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                if(activity==null)BilingualLabel("Status unavailable",color=Muted)
                else if(activity.idle)BilingualLabel("Idle",color=Muted)
                else activity.categories().forEach{(label,count)->
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(count.toString(),color=if(label=="Unknown"||label=="Needs you")Coral else Paper,fontSize=14.sp)
                        BilingualLabel(label,color=if(label=="Unknown"||label=="Needs you")Coral else Muted)
                    }
                }
            }
            if(room.s("purpose").isNotBlank())ImmersionText("room-purpose:"+Pocket.environmentId+":"+room.s("id"),room.s("purpose"),color=Muted,fontSize=13.sp,lineHeight=18.sp,maxLines=1,overflow=TextOverflow.Ellipsis,rescue=false,phraseRescue=false,links=false)
        }
    }
}
