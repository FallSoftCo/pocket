package co.fallsoft.pocket

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat

/** Keeps the loopback WebSocket alive so local Codex events reach Pocket while its UI is closed. */
class LocalMonitorService:Service(){
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Codex running on this phone",NotificationManager.IMPORTANCE_LOW).apply{
            description="Keeps Pocket connected to the local Codex runtime";setSound(null,null)
        })
        val open=PendingIntent.getActivity(this,991,Intent(this,MainActivity::class.java).putExtra("local",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Pocket · Codex on this phone")
            .setContentText("Listening for local tasks, questions and results").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID,notification)
        PocketLive.start()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!Pocket.local||Pocket.token.isBlank()){stopSelf();return START_NOT_STICKY}
        PocketLive.start();return START_STICKY
    }
    override fun onDestroy(){if(Pocket.local)PocketLive.stop();super.onDestroy()}
    companion object{
        private const val CHANNEL="local-codex"
        private const val ID=991
        fun start(c:Context){try{ContextCompat.startForegroundService(c,Intent(c,LocalMonitorService::class.java))}catch(_:RuntimeException){}}
        fun stop(c:Context){c.stopService(Intent(c,LocalMonitorService::class.java));c.getSystemService(NotificationManager::class.java).cancel(ID)}
    }
}
