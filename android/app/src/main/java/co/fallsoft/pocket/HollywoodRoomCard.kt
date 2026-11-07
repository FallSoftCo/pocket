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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** No generic session Idle fallback; only the canonical catalog can assert quiet work. */
@Composable internal fun HollywoodRoomCard(room:JSONObject,available:Boolean,identityScope:String=Pocket.environmentId,headerBadge:(@Composable ()->Unit)?=null,onKeyboard:(()->Unit)?=null,onOpen:()->Unit) {
    val activity=HollywoodRoomActivity.read(room,available)
    val preview=hollywoodRoomPreview(room,available)
    val setupLabel=if(available)roomOriginPendingLabel(room)else null
    val showSetup=setupLabel!=null&&activity?.executing!=true&&activity?.needsHuman!=true
    val density=LocalDensity.current
    val lineHeight=with(density){20.sp.toDp()}.coerceAtLeast(24.dp)
    val previewHeight=with(density){18.sp.toDp()}.coerceAtLeast(18.dp)

    val spoken=when {
        showSetup->setupLabel.orEmpty()
        activity==null->"Status unavailable"
        activity.idle->if(activity.native)"Native agents idle at observation" else "Idle; no visible unsettled work at observation"
        else->activity.categories().joinToString("; "){(label,count)->"$count $label"}
    }
    WorkCardSurface(activity?.scheduled==true||showSetup,activity?.needsHuman==true||setupLabel=="Agent setup blocked",
        if(activity?.needsHuman==true||activity?.counts?.get(4)?.let{it>0}==true)Coral else Mint,modifier=Modifier
            .testTag("native-room:"+room.s("id")).then(if(onKeyboard==null)Modifier.clickable(onClick=onOpen)else Modifier),knownIdle=activity?.idle==true&&!showSetup) {
        if(onKeyboard!=null)Row(Modifier.matchParentSize()){
            CardInteractionRegion("Chat",Modifier.weight(.78f).fillMaxHeight(),room.s("name"),onOpen)
            CardInteractionRegion("Keyboard",Modifier.weight(.22f).fillMaxHeight(),room.s("name"),onKeyboard)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                BilingualLabel(room.s("name"),Modifier.weight(1f),color=Paper,fontSize=16.sp,fontWeight=FontWeight.Medium,centered=false)
                headerBadge?.invoke()
                Box(Modifier.size(24.dp)) {
                    if(activity?.executing==true)SymbolIcon("Codex",spoken,Modifier.fillMaxSize(),spinning=true)
                    else SymbolIcon(when {
                        showSetup&&setupLabel!="Agent setup blocked"&&setupLabel!="Room agent status unavailable"->Icons.Rounded.MoreHoriz
                        activity==null||activity.uncertain->Icons.Rounded.ErrorOutline
                        activity.needsHuman->Icons.Rounded.ErrorOutline
                        activity.scheduled->Icons.Rounded.MoreHoriz
                        else->Icons.Rounded.Pause
                    },spoken,tint=if(activity?.needsHuman==true||activity?.counts?.get(4)?.let{it>0}==true)Coral else Muted,modifier=Modifier.size(20.dp))
                }
            }
            Row(Modifier.fillMaxWidth().height(lineHeight).testTag("native-room-activity:"+room.s("id")).semantics{contentDescription=spoken},
                horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                if(showSetup)BilingualLabel(setupLabel.orEmpty(),color=if(setupLabel=="Agent setup blocked")Coral else Paper,centered=false)
                else if(activity==null)BilingualLabel("Status unavailable",color=Muted)
                else if(activity.idle)BilingualLabel("Idle",color=Muted)
                else {
                    // Surface attention before ordinary execution. These categories overlap;
                    // never add them into a misleading total or resort the catalog here.
                    val order=listOf("approval","input","waitingForHuman","running","pending","cancelRequested","claimed","idle","unavailable","unknown")
                    val states=activity.states.sortedBy{order.indexOf(it.key)}
                    val shown=states.take(if(density.fontScale>1.3f)2 else 3)
                    shown.forEachIndexed { position,state ->
                        val count=state.count
                        val tint=if(state.key in listOf("approval","input","waitingForHuman","unknown"))Coral else Paper
                        Row((if(position==0)Modifier.weight(1f) else Modifier).testTag("native-room-state:"+room.s("id")+":"+state.key),
                            horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                            if(position==0||shown.size<=2)BilingualLabel(state.label,Modifier.weight(1f,fill=false),color=tint,centered=false)
                            else {
                                val name=when(state.key){"pending"->"Inbox";"claimed"->"Computer";"running"->"Codex";"cancelRequested"->"Stop";"idle"->"Pause";"unavailable","unknown"->"ErrorOutline";else->"User"}
                                SymbolIcon(name,null,Modifier.size(18.dp),tint=tint)
                            }
                            Text(if(count>999)"999+" else count.toString(),color=tint,fontSize=14.sp,maxLines=1)
                        }
                    }
                    if(states.size>shown.size)
                        SymbolIcon(Icons.Rounded.MoreHoriz,null,Modifier.size(18.dp),tint=Muted)
                }
            }
            // Reserve exactly one context line even when a room has no purpose yet.
            // Activity bursts and missing context must not move the next touch target.
            Box(Modifier.fillMaxWidth().height(previewHeight)) {
                if(preview.isNotBlank())ImmersionText("room-preview:"+identityScope+":"+room.s("id"),preview,color=Muted,fontSize=13.sp,lineHeight=18.sp,maxLines=1,overflow=TextOverflow.Ellipsis,rescue=false,phraseRescue=false,links=false)
            }
        }
    }
}
