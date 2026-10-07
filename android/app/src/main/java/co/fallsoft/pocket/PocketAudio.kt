package co.fallsoft.pocket

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Stable, non-private sound files: Android plays these using its notification audio policy. */
object PocketAudio {
    val labels=linkedMapOf("update" to "Updates","complete" to "Finished","question" to "Questions","approval" to "Approvals","error" to "Problems")
    private val phrases=mapOf("update" to "Codex has an update.","complete" to "Codex finished.","question" to "Codex needs your input.","approval" to "Codex needs your approval.","error" to "Codex hit a problem.")
    var mode by mutableStateOf("tones"); private set
    var status by mutableStateOf(""); private set
    private var engine:TextToSpeech?=null
    private var preparation:Job?=null
    private var preparing=false
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    fun init(){mode=Pocket.prefs.getString("audioMode","tones")?:"tones"}
    fun kind(raw:String)=if(labels.containsKey(raw))raw else "update"
    fun file(c:Context,profile:String,event:String)=File(c.filesDir,"notification-audio/$profile-v1/$event.wav")
    fun uri(profile:String,event:String)=Uri.parse("content://co.fallsoft.pocket.sounds/$profile-v1/$event.wav")
    private fun voiceReady()=labels.keys.all{file(Pocket.context,"voice",it).length()>44}
    @Synchronized fun channel(c:Context,raw:String):String {
        val event=kind(raw)
        val profile=if(mode=="summaries"||(mode=="voice"&&!voiceReady()))"tones" else mode
        if(profile=="tones")ensureTone(c,event)
        val id="audio-$profile-v1-$event"
        val manager=c.getSystemService(NotificationManager::class.java)
        if(manager.getNotificationChannel(id)==null){
            val sound=if(profile=="system")RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else uri(profile,event)
            manager.createNotificationChannel(NotificationChannel(id,"${labels[event]} · ${when(profile){"voice"->"spoken";"tones"->"tones";else->"system sound"}}",NotificationManager.IMPORTANCE_HIGH).apply{
                description=phrases[event];setSound(sound,attributes);enableVibration(true)
            })
        }
        return id
    }
    fun select(value:String){
        if(value !in listOf("tones","voice","system","summaries"))return
        mode=value;Pocket.prefs.edit().putString("audioMode",value).apply();status=""
        if(value!="summaries")PocketSpeech.clear()
        if(value=="voice")prepareVoice()
    }
    fun prepareVoice(){
        if(voiceReady()){status="Spoken labels are ready · generated on this phone";return}
        if(preparing)return
        preparing=true;status="Preparing spoken labels on this phone…"
        preparation=Pocket.scope.launch{delay(30000);finishVoice(false)}
        engine=TextToSpeech(Pocket.context){result->Pocket.scope.launch{
            val tts=engine?:return@launch
            if(result!=TextToSpeech.SUCCESS){finishVoice(false);return@launch}
            val voice=tts.voices?.filter{!it.isNetworkConnectionRequired&&it.locale.language=="en"&&!(it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)?:false)}
                ?.sortedWith(compareByDescending<android.speech.tts.Voice>{it.locale.country=="US"}.thenByDescending{it.quality})?.firstOrNull()
            if(voice==null||tts.setVoice(voice)!=TextToSpeech.SUCCESS){finishVoice(false);return@launch}
            tts.setSpeechRate(1.0f)
            val completed=mutableSetOf<String>()
            tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                override fun onStart(id:String?){}
                override fun onError(id:String?){Pocket.scope.launch{finishVoice(false)}}
                override fun onDone(id:String?){Pocket.scope.launch{
                    if(!preparing||id !in labels.keys)return@launch
                    val dest=file(Pocket.context,"voice",id!!);val temp=File(dest.path+".tmp")
                    if(temp.length()<=44||!temp.renameTo(dest)){finishVoice(false);return@launch}
                    completed.add(id)
                    if(completed.size==labels.size)finishVoice(true)
                }}
            })
            for((event,phrase) in phrases){
                val dest=file(Pocket.context,"voice",event);dest.parentFile!!.mkdirs()
                if(tts.synthesizeToFile(phrase,Bundle(),File(dest.path+".tmp"),event)!=TextToSpeech.SUCCESS){finishVoice(false);break}
            }
        }}
    }
    private fun finishVoice(success:Boolean){
        if(!preparing)return
        preparing=false;preparation?.cancel();preparation=null;engine?.shutdown();engine=null
        status=if(success)"Spoken labels are ready · generated on this phone" else "An installed English voice is needed. Using tones for now."
    }
    fun preview(raw:String){
        val event=kind(raw)
        PocketNotifications.show(Pocket.context,org.json.JSONObject().put("id",900000000L+labels.keys.indexOf(event)).put("kind",event).put("title",phrases[event]).put("body","Sound preview · ${labels[event]}"))
        if(mode=="summaries")PocketSpeech.request(Pocket.context,org.json.JSONObject().put("id",System.currentTimeMillis()).put("_speechPreview",true).put("kind",event).put("spoken_summary",when(event){"complete"->"NextComp: all tests passed. The release is ready.";"question"->"Website: should I publish the preview or keep it private?";else->"Build failed: signing credentials are missing."}))
    }
    private fun ensureTone(c:Context,event:String){
        val dest=file(c,"tones",event);if(dest.length()>44)return
        val notes=when(event){
            "complete"->listOf(523.25,659.25,783.99)
            "question"->listOf(659.25,880.0)
            "approval"->listOf(523.25,783.99,783.99)
            "error"->listOf(440.0,329.63,261.63)
            else->listOf(783.99,1046.50)
        }
        val rate=22050;val spacing=if(event=="approval")0.24 else 0.17;val duration=0.32
        val count=((notes.size-1)*spacing*rate+duration*rate).toInt()
        val pcm=ByteBuffer.allocate(count*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count){i->
            var signal=0.0
            notes.forEachIndexed{n,hz->val t=i.toDouble()/rate-n*spacing
                if(t>=0&&t<duration){val envelope=min(1.0,t/0.012)*exp(-t*11.0)*min(1.0,(duration-t)/0.025)
                    signal+=0.32*envelope*(sin(2*PI*hz*t)+0.15*sin(2*PI*hz*2*t))}
            }
            pcm.putShort((signal.coerceIn(-1.0,1.0)*32767).toInt().toShort())
        }
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply{
            put("RIFF".toByteArray());putInt(36+pcm.capacity());put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(rate);putInt(rate*2);putShort(2);putShort(16);put("data".toByteArray());putInt(pcm.capacity())
        }
        dest.parentFile!!.mkdirs();val temp=File(dest.path+".tmp")
        temp.outputStream().use{it.write(header.array());it.write(pcm.array())};check(temp.renameTo(dest))
    }
}

/** Public read access is limited to these ten generic sounds; no task data or writable paths. */
class PocketSoundProvider:ContentProvider(){
    override fun onCreate()=true
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        val parts=uri.pathSegments
        if(mode!="r"||parts.size!=2||parts[0] !in listOf("tones-v1","voice-v1")||parts[1] !in PocketAudio.labels.keys.map{"$it.wav"})throw FileNotFoundException()
        return ParcelFileDescriptor.open(PocketAudio.file(context!!,parts[0].removeSuffix("-v1"),parts[1].removeSuffix(".wav")),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri:Uri)="audio/wav"
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
