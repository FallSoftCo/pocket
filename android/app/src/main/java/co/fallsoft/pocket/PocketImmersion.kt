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
    private var motionState by mutableStateOf(true)
    val motionEnabled get()=motionState
    fun setMotionEnabled(value:Boolean){motionState=value;Pocket.prefs.edit().putBoolean(profileKey()+":motion",value).apply()}
    private var densityState by mutableStateOf("strong")
    val density get()=densityState
    fun setDensity(value:String){densityState=if(value in listOf("starter","balanced","strong"))value else "strong";Pocket.prefs.edit().putString(profileKey()+":density",densityState).apply();translations=emptyMap();byContent=emptyMap();originals=emptySet();clearReadingChoices();pending.clear();job?.cancel();sync()}
    var unavailable by mutableStateOf(false); private set
    private var translations by mutableStateOf<Map<String,JSONObject>>(emptyMap())
    private var byContent by mutableStateOf<Map<String,JSONObject>>(emptyMap())
    private val pending=linkedMapOf<String,JSONObject>()
    private var job:Job?=null
    private var originals by mutableStateOf(setOf<String>())
    private val readingChoices=ImmersionReadingChoices()
    private var readingRevision by mutableIntStateOf(0)
    private fun profileKey()=Pocket.key("immersionItalian")+":"+Pocket.prefs.getString(Pocket.key("deviceId"),"").orEmpty()
    // The credential establishes ownership before optional device/account metadata hydrates.
    private fun readingOwner()=version(Pocket.key("immersionItalian")+"\u0000"+Pocket.base+"\u0000"+Pocket.token)
    fun readingKey(id:String,plan:ImmersionPresentation)=immersionReadingKey(readingOwner(),if(id.startsWith("voice:"))PocketVoice.targetThread?:"voice-coordinator" else Pocket.selected.orEmpty(),id,plan,density=density)
    fun readingSelection(key:ImmersionReadingKey,plan:ImmersionPresentation)=readingRevision.let{readingChoices.selected(key,plan)}
    fun selectReading(key:ImmersionReadingKey,span:ImmersionSpan?){readingChoices.select(key,span);readingRevision++}
    private fun clearReadingChoices(){readingChoices.clear();readingRevision++}
    private fun version(text:String)=immersionSourceHash(text)
    private fun cacheKey()="immersion-display-cache-v4:"+profileKey()
    private fun eligible(row:JSONObject)=row.s("planVersion")=="inline-replacement-v3"&&row.s("density")==density
    private fun cached(id:String,text:String):JSONObject? {val hash=version(text);return translations[id]?.takeIf{it.s("version")==hash&&eligible(it)}?:byContent[hash]?.takeIf{eligible(it)}}
    private var cacheSave:Job?=null
    private fun persistCache(){
        cacheSave?.cancel();val captured=profileKey();val key=cacheKey()
        val rows=translations.values.toList().asReversed().map{it.toString()}
        cacheSave=Pocket.scope.launch {delay(400);if(captured!=profileKey())return@launch
            val encoded=withContext(Dispatchers.IO){val saved=JSONArray();var chars=0;for(row in rows){ensureActive();if(saved.length()>=200||chars+row.length>500000)break;saved.put(JSONObject(row));chars+=row.length};saved.toString()}
            if(captured==profileKey())Pocket.prefs.edit().putString(key,encoded).apply()
        }
    }
    fun restore(){
        motionState=Pocket.prefs.getBoolean(profileKey()+":motion",true)
        val oldDensity=densityState
        val ownerChanged=readingChoices.restore(readingOwner())
        job?.cancel();cacheSave?.cancel();pending.clear();enabledState=Pocket.prefs.getBoolean(profileKey(),false);supportState=Pocket.prefs.getBoolean(profileKey()+":englishSupport",true);densityState=Pocket.prefs.getString(profileKey()+":density","strong")?:"strong"
        val saved=try{JSONArray(Pocket.prefs.getString(cacheKey(),"[]"))}catch(_:Exception){JSONArray()}
        translations=saved.objects().asReversed().associateBy{it.s("id")};byContent=translations.values.associateBy{it.s("version")}
        if(ownerChanged||oldDensity!=densityState){originals=emptySet();clearReadingChoices()};unavailable=false;if(enabled)sync()
    }
    fun offerLabel(text:String){if(text.length<=200)offer("label:"+version(text),text,"interface label")}
    fun label(text:String):String=if(enabled)(ImmersionLexicon.italian(text)?:ImmersionVoiceLexicon.italian(text))?:cached("label:"+version(text),text)?.s("text",text)?:text else text
    fun setEnabled(value:Boolean){enabledState=value;Pocket.prefs.edit().putBoolean(profileKey(),value).apply();pending.clear();job?.cancel();originals=emptySet();clearReadingChoices();sync()}
    private fun sync(){val captured=profileKey();Pocket.scope.launch{try{val result=Pocket.api("/api/immersion",JSONObject().put("enabled",enabled).put("density",density));if(captured==profileKey())accept(result)}catch(_:Exception){if(captured==profileKey())unavailable=enabled}}}
    fun accept(event:JSONObject){if(!enabled)return;unavailable=event.optBoolean("unavailable",false);val list=event.optJSONArray("translations")?.objects()?:return;translations=(translations+list.associateBy{it.s("id")}).entries.toList().takeLast(600).associate{it.toPair()};byContent=translations.values.associateBy{it.s("version")};persistCache();if(list.isNotEmpty())PocketNotifications.refreshImmersion()}
    /** Bound speech preparation; use the existing native Codex audio transport for pronunciation. */
    suspend fun spoken(id:String,text:String,waitMs:Long=8000):String {
        if(!enabled)return text
        val captured=profileKey()
        offer(id,text,"speech")
        val deadline=System.currentTimeMillis()+waitMs
        while(enabled&&captured==profileKey()&&System.currentTimeMillis()<deadline){
            if(hasTranslation(id,text))return target(id,text)
            delay(600)
            try{val result=Pocket.api("/api/immersion");if(captured==profileKey())accept(result)}catch(_:Exception){return text}
        }
        return text
    }
    fun originalShown(id:String)=id in originals
    fun target(id:String,text:String):String {
        if(!enabled)return text
        val offline=ImmersionLexicon.italian(text)?:ImmersionVoiceLexicon.italian(text);if(offline!=null)return offline
        val row=cached(id,text)?:return text
        return if(contextualHybridText(text,spans(row))==row.s("text"))row.s("text") else text
    }
    private fun spans(row:JSONObject)=row.optJSONArray("spans")?.objects()?.map{r->ImmersionSpan(r.optInt("start"),r.optInt("end"),r.s("source"),r.s("target"),r.s("note"),r.s("unit","phrase"),r.optJSONArray("targetSegments")?.objects()?.map{s->ImmersionTargetSegment(s.optInt("start"),s.optInt("end"),s.s("target"),s.s("meaning"),s.s("role","other"),s.optJSONArray("features")?.objects()?.map{ImmersionFeature(it.s("name"),it.s("value"))}?:emptyList(),s.optJSONArray("relations")?.objects()?.map{ImmersionRelation(it.s("kind"),it.optInt("toSegment",-1))}?:emptyList())}?:emptyList())}?.takeIf{list->list.all{it.targetSegments.isEmpty()||alignedTargetValid(it)}}?:emptyList()
    fun presentation(id:String,text:String):ImmersionPresentation {
        if(!enabled)return ImmersionPresentation(text,text,true)
        val offline=ImmersionLexicon.italian(text)?:ImmersionVoiceLexicon.italian(text)
        if(offline!=null)return ImmersionPresentation(offline,text,true,listOf(ImmersionSpan(0,text.length,text,offline)))
        val exact=cached(id,text)
        if(exact!=null){val plan=spans(exact);if(contextualHybridText(text,plan)==exact.s("text"))return ImmersionPresentation(exact.s("text"),text,true,plan)}
        val older=translations[id]?.takeIf{eligible(it)&&it.s("original").isNotBlank()}
        if(older!=null){val source=older.s("original");val plan=spans(older);if(immersionSourceHash(source)==older.s("version")&&contextualHybridText(source,plan)==older.s("text"))return ImmersionPresentation(older.s("text"),source,false,plan)}
        // Source stays readable during first-plan latency; do not invent a translation or alignment.
        return ImmersionPresentation(text,text,false)
    }
    fun revealOriginal(id:String){originals=if(id in originals)originals-id else originals+id}
    fun hasTranslation(id:String,text:String)=enabled&&((ImmersionLexicon.italian(text)?:ImmersionVoiceLexicon.italian(text))!=null||cached(id,text)?.let{contextualHybridText(text,spans(it))==it.s("text")}==true)
    fun display(id:String,text:String):String {if(!enabled||id in originals)return text;return target(id,text)}
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
                    coalesce={delay(if(pending.values.any{it.s("kind").contains("urgent")})150 else 2500)},
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
