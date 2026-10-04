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
    private val storage get()=Pocket.context.getSharedPreferences("speech-captions",Context.MODE_PRIVATE)
    fun init(){
        state=try{val s=JSONObject(storage.getString("last","{}")!!);SpeechCaptionState(s.optLong("id"),s.s("title"),s.s("text"),s.optInt("part"),s.optInt("parts"),s.s("phase"),s.optBoolean("dismissed"))}catch(_:Exception){SpeechCaptionState()}
    }
    private fun save(){val s=state;storage.edit().putString("last",JSONObject().put("id",s.id).put("title",s.title).put("text",s.text).put("part",s.part).put("parts",s.parts).put("phase",s.phase).put("dismissed",s.dismissed).toString()).apply()}
    fun update(id:Long,title:String,text:String?,part:Int,parts:Int){
        if(text.isNullOrBlank())return
        val next=state.update(id,title,text,part,parts)
        if(next!=state){state=next;save()}
    }
    fun pause(){state=state.pause();save()}
    fun complete(){state=state.complete();save();showRetained()}
    fun dismiss(){state=state.dismiss();save();if(PocketSpeech.service==null)Pocket.context.getSystemService(NotificationManager::class.java).cancel(998)}
    fun decorate(builder:Notification.Builder):Notification.Builder {
        if(!state.visible)return builder
        val public=Notification.Builder(Pocket.context,"spoken-playback").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NextComp spoken update").setContentText("Unlock to read").build()
        return builder.setContentText(state.text).setSubText(state.label).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public).setStyle(Notification.BigTextStyle().bigText(state.text))
    }
    private fun showRetained(){
        if(!state.visible)return
        val c=Pocket.context;val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("spoken-playback","Spoken message controls",NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null)})
        val dismiss=PendingIntent.getBroadcast(c,997,Intent(c,AttentionReceiver::class.java).setAction("caption-dismiss"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public=Notification.Builder(c,"spoken-playback").setSmallIcon(R.drawable.ic_notification).setContentTitle("NextComp spoken update").setContentText("Unlock to read").build()
        val builder=Notification.Builder(c,"spoken-playback").setSmallIcon(R.drawable.ic_notification).setContentTitle(state.title.ifBlank{"NextComp spoken update"})
            .setContentIntent(PocketNotifications.open(c,null,998)).setOnlyAlertOnce(true).setAutoCancel(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).setDeleteIntent(dismiss)
            .addAction(Notification.Action.Builder(R.drawable.ic_notification,"Dismiss",dismiss).build())
        try{manager.notify(998,decorate(builder).build())}catch(_:SecurityException){}
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SpeechCaptionBanner(modifier:Modifier=Modifier){
    val s=PocketSpeechCaptions.state
    if(!s.visible)return
    var expanded by remember { mutableStateOf(false) }
    Surface(modifier=modifier.fillMaxWidth(),color=Panel,tonalElevation=0.dp){
        Row(Modifier.padding(start=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Column(Modifier.weight(1f).heightIn(min=48.dp).clickable(role=Role.Button,onClickLabel="Read full spoken passage"){expanded=true}.padding(vertical=8.dp)){
                Text(s.label+" · Read",color=Muted,style=MaterialTheme.typography.labelSmall)
                Text(s.text,color=Paper,style=MaterialTheme.typography.bodyMedium,maxLines=3,overflow=TextOverflow.Ellipsis)
            }
            TextButton(onClick={PocketSpeechCaptions.dismiss()},modifier=Modifier.heightIn(min=48.dp)){Text("Dismiss")}
        }
    }
    if(expanded){
        ModalBottomSheet(onDismissRequest={expanded=false},containerColor=Panel){
            Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal=16.dp)){
                Text(s.title.ifBlank{"Spoken update"},color=Paper,style=MaterialTheme.typography.titleMedium)
                Text(s.label,color=Muted,style=MaterialTheme.typography.labelSmall)
                val scroll=rememberScrollState()
                LaunchedEffect(s.id,s.part){scroll.scrollTo(0)}
                Text(s.text,color=Paper,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(vertical=16.dp))
                Row(Modifier.fillMaxWidth().navigationBarsPadding(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    TextButton(onClick={expanded=false},modifier=Modifier.weight(1f).heightIn(min=64.dp)){Text("Close")}
                    TextButton(onClick={PocketSpeechCaptions.dismiss();expanded=false},modifier=Modifier.weight(1f).heightIn(min=64.dp)){Text("Dismiss captions")}
                }
            }
        }
    }
}
