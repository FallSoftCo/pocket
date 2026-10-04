package co.fallsoft.pocket

import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Conversation navigation takes precedence over the IME's hide-keyboard callback. */
@Composable fun ConversationBack(onBack:()->Unit){
    val activity=LocalActivity.current
    val keyboard=LocalSoftwareKeyboardController.current
    val latest=rememberUpdatedState(onBack)
    val leave:()->Unit={keyboard?.hide();latest.value()}
    BackHandler(onBack=leave)
    DisposableEffect(activity){
        if(Build.VERSION.SDK_INT>=33&&activity!=null){
            val callback=OnBackInvokedCallback{keyboard?.hide();latest.value()}
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY,callback)
            onDispose{activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)}
        }else onDispose{}
    }
}
