package co.fallsoft.pocket

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Keeps local notifications alive independently of the selected development environment. */
class LocalMonitorService:Service(){
    private val handler=Handler(Looper.getMainLooper())
    private val refresh=object:Runnable{override fun run(){updateNotification();handler.postDelayed(this,15000)}}
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Codex running on this phone",NotificationManager.IMPORTANCE_LOW).apply{
            description="Keeps NextComp connected to the local Codex runtime";setSound(null,null)
        })
        val notification=notification()
        if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID,notification)
        handler.post(refresh)
        PhoneEventMonitor.start()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(Pocket.savedToken(true).isBlank()){stopSelf();return START_NOT_STICKY}
        PhoneEventMonitor.start();return START_STICKY
    }
    private fun notification():Notification{
        val repair=PocketAutomation.allowed&&!PocketAutomation.systemEnabled(this)
        val intent=if(repair)Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) else Intent(this,MainActivity::class.java).putExtra("local",true)
        val open=PendingIntent.getActivity(this,if(repair)992 else 991,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if(repair)"NextComp phone control needs attention" else "NextComp · Codex on this phone")
            .setContentText(if(repair)"Tap to re-enable NextComp in Android Accessibility" else if(PhoneEventMonitor.connected)"Listening for local tasks, questions and results" else "Waiting for local Codex")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    private fun updateNotification(){getSystemService(NotificationManager::class.java).notify(ID,notification())}
    override fun onDestroy(){handler.removeCallbacks(refresh);PhoneEventMonitor.stop();super.onDestroy()}
    companion object{
        private const val CHANNEL="local-codex"
        private const val ID=991
        fun start(c:Context){try{ContextCompat.startForegroundService(c,Intent(c,LocalMonitorService::class.java))}catch(_:RuntimeException){}}
        fun stop(c:Context){c.stopService(Intent(c,LocalMonitorService::class.java));c.getSystemService(NotificationManager::class.java).cancel(ID)}
    }
}
