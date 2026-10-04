package co.fallsoft.pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp

@Composable fun CardInteractionRegion(label:String,modifier:Modifier=Modifier,onClick:()->Unit){
    val interaction=remember{MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val haptic=LocalHapticFeedback.current
    Box(modifier.semantics{contentDescription="Open $label in this conversation"}
        .background(if(pressed)Mint.copy(alpha=.25f)else Color.Transparent)
        .clickable(interactionSource=interaction,indication=null,role=Role.Button){haptic.performHapticFeedback(HapticFeedbackType.LongPress);onClick()},contentAlignment=Alignment.Center){
        if(pressed)Text(label,fontSize=9.sp,color=Paper,maxLines=1)
    }
}
