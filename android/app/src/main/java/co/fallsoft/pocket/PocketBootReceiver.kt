package co.fallsoft.pocket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PocketBootReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action==Intent.ACTION_BOOT_COMPLETED&&Pocket.local&&Pocket.token.isNotBlank())LocalMonitorService.start(context)
    }
}
