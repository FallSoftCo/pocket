package co.fallsoft.pocket

import androidx.compose.runtime.*

/** Window focus excludes focusable overlays without treating the IME as background. */
internal object AppWindowVisibility {
    private var owner:Any?=null
    var focused by mutableStateOf(false);private set
    fun focus(activity:Any,value:Boolean){
        if(value){owner=activity;focused=true}
        else if(owner===activity){owner=null;focused=false}
    }
}
