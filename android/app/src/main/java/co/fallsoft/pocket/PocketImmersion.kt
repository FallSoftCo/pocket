package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Translation is a display layer: submitted text, commands, copy and audit retain originals. */
object PocketImmersion {
    private var enabledState by mutableStateOf(false)
    val enabled get()=enabledState
    var unavailable by mutableStateOf(false); private set
    private var translations by mutableStateOf<Map<String,JSONObject>>(emptyMap())
    private val pending=linkedMapOf<String,JSONObject>()
    private var job:Job?=null
    private var originals by mutableStateOf(setOf<String>())
    private fun profileKey()=Pocket.key("immersionItalian")+":"+Pocket.prefs.getString(Pocket.key("deviceId"),"").orEmpty()
    private fun version(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun restore(){job?.cancel();pending.clear();enabledState=Pocket.prefs.getBoolean(profileKey(),false);translations=emptyMap();originals=emptySet();unavailable=false;if(enabled)sync()}
    fun setEnabled(value:Boolean){enabledState=value;Pocket.prefs.edit().putBoolean(profileKey(),value).apply();pending.clear();job?.cancel();originals=emptySet();sync()}
    private fun sync(){val captured=profileKey();Pocket.scope.launch{try{val result=Pocket.api("/api/immersion",JSONObject().put("enabled",enabled));if(captured==profileKey())accept(result)}catch(_:Exception){if(captured==profileKey())unavailable=enabled}}}
    fun accept(event:JSONObject){if(!enabled)return;unavailable=event.optBoolean("unavailable",false);val list=event.optJSONArray("translations")?.objects()?:return;translations=(translations+list.associateBy{it.s("id")}).entries.toList().takeLast(600).associate{it.toPair()}}
    /** Bound speech preparation; use the existing native Codex audio transport for pronunciation. */
    suspend fun spoken(id:String,text:String,waitMs:Long=8000):String {
        if(!enabled)return text
        val captured=profileKey()
        offer(id,text,"speech")
        val deadline=System.currentTimeMillis()+waitMs
        while(enabled&&captured==profileKey()&&System.currentTimeMillis()<deadline){
            if(hasTranslation(id,text))return translations[id]?.s("text",text)?:text
            delay(600)
            try{val result=Pocket.api("/api/immersion");if(captured==profileKey())accept(result)}catch(_:Exception){return text}
        }
        return text
    }
    fun revealOriginal(id:String){originals=if(id in originals)originals-id else originals+id}
    fun hasTranslation(id:String,text:String)=enabled&&translations[id]?.s("version")==version(text)
    fun display(id:String,text:String):String {if(!enabled||id in originals)return text;val row=translations[id];return if(row?.s("version")==version(text))row.s("text",text) else text}
    /** Call after coalescing visible state; never for every incoming token. */
    fun offer(id:String,text:String,kind:String="message") {if(!enabled||text.isBlank()||text.length>12000||hasTranslation(id,text))return;pending[id]=JSONObject().put("id",id).put("text",text).put("kind",kind);while(pending.size>60)pending.remove(pending.keys.first());if(job?.isActive==true)return;val captured=profileKey();job=Pocket.scope.launch{delay(2500);if(!enabled||captured!=profileKey())return@launch;val batch=JSONArray(pending.values.toList());pending.clear();try{val result=Pocket.api("/api/immersion/translate",JSONObject().put("sources",batch));if(captured==profileKey())accept(result)}catch(_:Exception){if(captured==profileKey())unavailable=true}}}
}
