package co.fallsoft.pocket

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Revealing is not archiving. Horizontal drag owns only horizontal gestures;
 * vertical list scrolling and the card's existing tap regions retain their jobs. */
@Composable internal fun SessionArchiveReveal(task:Task,onArchive:(()->Unit)?,modifier:Modifier=Modifier,content:@Composable ()->Unit){
    if(task.archived||onArchive==null){Box(modifier){content()};return}
    val width=with(LocalDensity.current){88.dp.toPx()}
    var offset by remember(task.id){mutableFloatStateOf(0f)}
    val archive by rememberUpdatedState(onArchive)
    Box(modifier.clip(RoundedCornerShape(18.dp)).testTag("swipe-session:"+task.id)){
        if(offset<0f)FilledTonalButton(onClick={archive?.invoke()},
            modifier=Modifier.align(Alignment.CenterEnd).width(84.dp).heightIn(min=64.dp).testTag("archive-session:"+task.id),
            shape=RoundedCornerShape(14.dp)){
            Column(horizontalAlignment=Alignment.CenterHorizontally){
                Icon(Icons.Rounded.Archive,null,Modifier.size(24.dp))
                BilingualLabel("Archive")
            }
        }
        Box(Modifier.offset{IntOffset(offset.roundToInt(),0)}.semantics{
            customActions=listOf(CustomAccessibilityAction(if(offset<0)"Hide archive action" else "Show archive action"){
                offset=if(offset<0)0f else -width;true
            })
        }.draggable(rememberDraggableState{delta->offset=(offset+delta).coerceIn(-width,0f)},Orientation.Horizontal,
            onDragStopped={velocity->
                val target=if(velocity < -350f || (velocity <= 350f&&offset < -width/2)) -width else 0f
                animate(offset,target,animationSpec=tween(140)){value,_->offset=value}
            })){content()}
    }
}

@Composable internal fun SessionRestoreAction(task:Task,onRestore:()->Unit){
    if(task.archived)IconButton(onRestore,Modifier.size(48.dp).testTag("restore-session:"+task.id)){
        SymbolIcon("Unarchive","Restore conversation",Modifier.size(24.dp),tint=Mint)
    }
}
