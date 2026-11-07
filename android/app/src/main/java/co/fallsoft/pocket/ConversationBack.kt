package co.fallsoft.pocket

import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Conversation navigation takes precedence over the IME's hide-keyboard callback. */
@Composable fun ConversationBack(hideKeyboard:Boolean=true,onBack:()->Unit){
    val activity=LocalActivity.current
    val keyboard=LocalSoftwareKeyboardController.current
    val latest=rememberUpdatedState(onBack)
    val hide=rememberUpdatedState(hideKeyboard)
    val leave:()->Unit={if(hide.value)keyboard?.hide();latest.value()}
    // The native blackout overlay owns Back while it is visible. Keeping an
    // overlay-priority conversation callback would bypass its exit/restoration.
    val blackout=BlackoutVisibility.active
    BackHandler(enabled=!blackout,onBack=leave)
    DisposableEffect(activity,blackout){
        if(Build.VERSION.SDK_INT>=33&&activity!=null&&!blackout){
            val callback=OnBackInvokedCallback{if(hide.value)keyboard?.hide();latest.value()}
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY,callback)
            onDispose{activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)}
        }else onDispose{}
    }
}
