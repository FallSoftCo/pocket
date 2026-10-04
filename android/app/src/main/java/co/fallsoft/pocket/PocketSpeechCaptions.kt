package co.fallsoft.pocket

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Reuses speech notification 998 after playback; lock-screen text remains private by default. */
object PocketSpeechCaptions {
    var state by mutableStateOf(SpeechCaptionState());private set
    internal fun label(s:SpeechCaptionState=state):String=listOf(PocketImmersion.label(s.phase),if(s.parts>1){if(PocketImmersion.enabled)"Parte ${s.part+1} di ${s.parts}" else "Part ${s.part+1} of ${s.parts}"}else "").filter{it.isNotBlank()}.joinToString(" · ")
    private fun profile()="${Pocket.local}:${Pocket.base}:${Pocket.prefs.getString(Pocket.key("deviceId"),"")}"
    private val storage get()=Pocket.context.getSharedPreferences("speech-captions-"+java.security.MessageDigest.getInstance("SHA-256").digest(profile().toByteArray()).joinToString(""){"%02x".format(it)},Context.MODE_PRIVATE)
    fun init(){
        state=try{val s=JSONObject(storage.getString("last","{}")!!);SpeechCaptionState(s.optLong("id"),s.s("title"),s.s("text"),s.optInt("part"),s.optInt("parts"),s.s("phase"),s.optBoolean("dismissed"))}catch(_:Exception){SpeechCaptionState()}
    }
    private fun save(){val s=state;storage.edit().putString("last",JSONObject().put("id",s.id).put("title",s.title).put("text",s.text).put("part",s.part).put("parts",s.parts).put("phase",s.phase).put("dismissed",s.dismissed).toString()).apply()}
    fun update(id:Long,title:String,text:String?,part:Int,parts:Int,sourceProfile:String=profile()){
        if(sourceProfile!=profile())return
        if(text.isNullOrBlank())return
        val next=state.update(id,PocketNotificationTitles.forId(id,title),text,part,parts)
        if(next!=state){state=next;save()}
    }
    fun rename(ids:Set<Long>,title:String){if(state.id !in ids)return;state=state.copy(title=title);save();PocketVoice.service?.refreshCaptionNotification();PocketSpeech.service?.refreshCaptionNotification();if(PocketVoice.service==null&&PocketSpeech.service==null&&state.visible)showRetained()}
    fun pause(sourceProfile:String=profile()){if(sourceProfile!=profile())return;state=state.pause();save()}
    fun complete(sourceProfile:String=profile(),retain:Boolean=true){if(sourceProfile!=profile())return;state=state.complete();save();if(retain)showRetained()}
    fun retain(){showRetained()}
    fun dismiss(){state=state.dismiss();save();PocketVoice.service?.refreshCaptionNotification();PocketSpeech.service?.refreshCaptionNotification();if(PocketSpeech.service==null)Pocket.context.getSystemService(NotificationManager::class.java).cancel(998)}
    private fun dismissIntent()=PendingIntent.getBroadcast(Pocket.context,997,Intent(Pocket.context,AttentionReceiver::class.java).setAction("caption-dismiss"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun decorate(builder:Notification.Builder):Notification.Builder {
        if(!state.visible)return builder
        val public=Notification.Builder(Pocket.context,"spoken-playback").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(PocketImmersion.label("NextComp spoken update")).setContentText(PocketImmersion.label("Unlock to read")).build()
        return builder.setContentText(state.text).setSubText(label()).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public).setStyle(Notification.BigTextStyle().bigText(state.text)).addAction(Notification.Action.Builder(R.drawable.ic_notification,PocketImmersion.label("Dismiss captions"),dismissIntent()).build())
    }
    fun decorate(builder:androidx.core.app.NotificationCompat.Builder):androidx.core.app.NotificationCompat.Builder {
        if(!state.visible)return builder
        val public=androidx.core.app.NotificationCompat.Builder(Pocket.context,"voice-mode").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(PocketImmersion.label("NextComp voice is on")).setContentText(PocketImmersion.label("Unlock to read")).build()
        return builder.setContentText(state.text).setSubText(label()).setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public).setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(state.text)).addAction(R.drawable.ic_notification,PocketImmersion.label("Dismiss captions"),dismissIntent())
    }
    private fun showRetained(){
        if(!state.visible)return
        val c=Pocket.context;val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("spoken-playback",PocketImmersion.label("Spoken message controls"),NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null)})
        val dismiss=PendingIntent.getBroadcast(c,997,Intent(c,AttentionReceiver::class.java).setAction("caption-dismiss"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public=Notification.Builder(c,"spoken-playback").setSmallIcon(R.drawable.ic_notification).setContentTitle(PocketImmersion.label("NextComp spoken update")).setContentText(PocketImmersion.label("Unlock to read")).build()
        val builder=Notification.Builder(c,"spoken-playback").setSmallIcon(R.drawable.ic_notification).setContentTitle(PocketImmersion.target("caption-title:${state.id}",state.title).ifBlank{PocketImmersion.label("NextComp spoken update")})
            .setContentIntent(PocketNotifications.open(c,null,998)).setOnlyAlertOnce(true).setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).setDeleteIntent(dismiss)
            .addAction(Notification.Action.Builder(R.drawable.ic_notification,PocketImmersion.label("Dismiss"),dismiss).build())
        try{manager.notify(998,decorate(builder).build())}catch(_:SecurityException){}
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SpeechCaptionBanner(modifier:Modifier=Modifier){
    val s=PocketSpeechCaptions.state
    if(!s.visible)return
    var expanded by remember { mutableStateOf(false) }
    Box {
    Surface(modifier=modifier.fillMaxWidth(),color=Panel,tonalElevation=0.dp){
        Row(Modifier.padding(start=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Column(Modifier.weight(1f).heightIn(min=48.dp).clickable(role=Role.Button,onClickLabel=PocketImmersion.label("Read full spoken passage")){expanded=true}.padding(vertical=8.dp)){
                Text(PocketSpeechCaptions.label(s)+" · "+PocketImmersion.label("Read"),color=Muted,style=MaterialTheme.typography.labelSmall)
                Text(s.text,color=Paper,style=MaterialTheme.typography.bodyMedium,maxLines=3,overflow=TextOverflow.Ellipsis)
            }
            TextButton(onClick={PocketSpeechCaptions.dismiss()},modifier=Modifier.heightIn(min=48.dp)){Text(PocketImmersion.label("Dismiss"))}
        }
    }
    ActivityPopup(expanded,{expanded=false},PocketNotificationTitles.threadForId(s.id),passage=s.text){
        TextButton({PocketSpeechCaptions.dismiss();expanded=false}){Text(PocketImmersion.label("Dismiss captions"))}
    }
    }
}
