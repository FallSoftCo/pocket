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
    private var supportState by mutableStateOf(true)
    val supportEnabled get()=supportState
    fun setSupportEnabled(value:Boolean){supportState=value;Pocket.prefs.edit().putBoolean(profileKey()+":englishSupport",value).apply()}
    var unavailable by mutableStateOf(false); private set
    private var translations by mutableStateOf<Map<String,JSONObject>>(emptyMap())
    private var byContent by mutableStateOf<Map<String,JSONObject>>(emptyMap())
    private val pending=linkedMapOf<String,JSONObject>()
    private var job:Job?=null
    private var originals by mutableStateOf(setOf<String>())
    private fun profileKey()=Pocket.key("immersionItalian")+":"+Pocket.prefs.getString(Pocket.key("deviceId"),"").orEmpty()
    private fun version(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun cacheKey()="immersion-display-cache-v2:"+profileKey()
    private fun cached(id:String,text:String):JSONObject? {val hash=version(text);return translations[id]?.takeIf{it.s("version")==hash}?:byContent[hash]}
    private data class CacheRow(val id:String,val version:String,val text:String)
    private var cacheSave:Job?=null
    private fun persistCache(){
        cacheSave?.cancel()
        val captured=profileKey();val key=cacheKey()
        cacheSave=Pocket.scope.launch {
            delay(400)
            if(captured!=profileKey())return@launch
            // Snapshot only immutable strings. JSON construction and its size bound run off the UI thread.
            val rows=translations.values.toList().asReversed().map{CacheRow(it.s("id"),it.s("version"),it.s("text"))}
            val encoded=withContext(Dispatchers.IO){
                val saved=JSONArray();var chars=0
                for(row in rows){
                    ensureActive()
                    val compact=JSONObject().put("id",row.id).put("version",row.version).put("text",row.text).put("language","it")
                    val length=compact.toString().length;if(saved.length()>=200||chars+length>500000)break
                    saved.put(compact);chars+=length
                }
                saved.toString()
            }
            if(captured==profileKey())Pocket.prefs.edit().putString(key,encoded).apply()
        }
    }
    fun restore(){
        job?.cancel();cacheSave?.cancel();pending.clear();enabledState=Pocket.prefs.getBoolean(profileKey(),false);supportState=Pocket.prefs.getBoolean(profileKey()+":englishSupport",true)
        val saved=try{JSONArray(Pocket.prefs.getString(cacheKey(),"[]"))}catch(_:Exception){JSONArray()}
        translations=saved.objects().asReversed().associateBy{it.s("id")};byContent=translations.values.associateBy{it.s("version")}
        originals=emptySet();unavailable=false;if(enabled)sync()
    }
    fun offerLabel(text:String){if(text.length<=200)offer("label:"+version(text),text,"interface label")}
    fun label(text:String):String=if(enabled)ImmersionLexicon.italian(text)?:cached("label:"+version(text),text)?.s("text",text)?:text else text
    fun setEnabled(value:Boolean){enabledState=value;Pocket.prefs.edit().putBoolean(profileKey(),value).apply();pending.clear();job?.cancel();originals=emptySet();sync()}
    private fun sync(){val captured=profileKey();Pocket.scope.launch{try{val result=Pocket.api("/api/immersion",JSONObject().put("enabled",enabled));if(captured==profileKey())accept(result)}catch(_:Exception){if(captured==profileKey())unavailable=enabled}}}
    fun accept(event:JSONObject){if(!enabled)return;unavailable=event.optBoolean("unavailable",false);val list=event.optJSONArray("translations")?.objects()?:return;translations=(translations+list.associateBy{it.s("id")}).entries.toList().takeLast(600).associate{it.toPair()};byContent=translations.values.associateBy{it.s("version")};persistCache()}
    /** Bound speech preparation; use the existing native Codex audio transport for pronunciation. */
    suspend fun spoken(id:String,text:String,waitMs:Long=8000):String {
        if(!enabled)return text
        val captured=profileKey()
        offer(id,text,"speech")
        val deadline=System.currentTimeMillis()+waitMs
        while(enabled&&captured==profileKey()&&System.currentTimeMillis()<deadline){
            if(hasTranslation(id,text))return display(id,text)
            delay(600)
            try{val result=Pocket.api("/api/immersion");if(captured==profileKey())accept(result)}catch(_:Exception){return text}
        }
        return text
    }
    fun revealOriginal(id:String){originals=if(id in originals)originals-id else originals+id}
    fun hasTranslation(id:String,text:String)=enabled&&(ImmersionLexicon.italian(text)!=null||cached(id,text)!=null)
    fun display(id:String,text:String):String {if(!enabled||id in originals)return text;return ImmersionLexicon.italian(text)?:cached(id,text)?.s("text",text)?:text}
    /** Call after coalescing visible state; never for every incoming token. */
    fun offer(id:String,text:String,kind:String="message") {
        if(!enabled||text.isBlank()||text.length>12000||hasTranslation(id,text))return
        pending[id]=JSONObject().put("id",id).put("text",text).put("kind",kind)
        while(pending.size>60)pending.remove(pending.keys.first())
        if(job?.isActive==true)return
        val captured=profileKey()
        job=Pocket.scope.launch {
            try {
                drainTranslationBatches(
                    isCurrent={enabled&&captured==profileKey()},
                    coalesce={delay(2500)},
                    nextBatch={pending.values.toList().also{pending.clear()}},
                    submit={batch->
                        try {
                            val result=Pocket.api("/api/immersion/translate",JSONObject().put("sources",JSONArray(batch)))
                            if(captured==profileKey())accept(result)
                        } catch(e:Exception) {
                            if(e is CancellationException)throw e
                            if(captured==profileKey()){
                                // Preserve a newer version offered while this request was in flight.
                                batch.forEach{pending.putIfAbsent(it.s("id"),it)}
                                while(pending.size>60)pending.remove(pending.keys.first())
                                unavailable=true
                            }
                            throw e // Retry on the next visible offer, never in a failing loop.
                        }
                    }
                )
            } catch(e:Exception) {if(e is CancellationException)throw e}
        }
    }
}
