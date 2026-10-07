package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal data class WorkUpdate(val id:String,val response:String,val actions:List<JSONObject>,val createdAt:Long,val unread:Boolean)
/** Stored check-ins have their own inbox; fetching never acknowledges their source material. */
object PocketWorkUpdates {
    var visible by mutableStateOf(false);private set
    internal var reports by mutableStateOf<List<WorkUpdate>>(emptyList());private set
    internal var latest by mutableStateOf<WorkUpdate?>(null);private set
    var unreadCount by mutableIntStateOf(0);private set
    var loading by mutableStateOf(false);private set
    var problem by mutableStateOf("");private set
    var hasEarlier by mutableStateOf(false);private set
    private val cursorState=WorkInboxCursor()
    private val before get()=cursorState.before
    private var owner=""
    private var job:Job?=null
    private val acknowledged=mutableSetOf<String>()
    private val acknowledging=mutableSetOf<String>()
    private data class Profile(val local:Boolean,val base:String,val token:String){val identity get()="$local:$base:$token"}
    private fun capture():Profile {
        val p=Profile(Pocket.local,Pocket.base.trimEnd('/'),Pocket.token)
        if(owner!=p.identity){job?.cancel();owner=p.identity;reports=emptyList();latest=null;unreadCount=0;cursorState.reset();hasEarlier=false;loading=false;problem="";acknowledged.clear();acknowledging.clear()}
        return p
    }
    private fun current(p:Profile)=Pocket.local==p.local&&Pocket.base.trimEnd('/')==p.base&&Pocket.token==p.token&&owner==p.identity
    private suspend fun api(p:Profile,path:String,body:JSONObject?=null):JSONObject=withContext(Dispatchers.IO){
        val b=Request.Builder().url(p.base+path).header("Authorization","Bearer ${p.token}")
        if(body!=null)b.post(body.toString().toRequestBody("application/json".toMediaType()))
        Pocket.http.newCall(b.build()).execute().use{r->if(!r.isSuccessful)throw Exception("Work updates unavailable (${r.code})");JSONObject(r.body!!.string())}
    }
    private fun decode(row:JSONObject)=WorkUpdate(row.s("id"),row.s("response"),row.optJSONArray("actions")?.objects().orEmpty(),row.optLong("createdAt"),row.optBoolean("unread"))
    fun open(){capture();visible=true;refresh()}
    fun close(){visible=false}
    fun refresh(older:Boolean=false){
        val p=capture();if(p.token.isBlank()||loading||older&&(!hasEarlier||before==null))return
        loading=true;problem="";val cursor=if(older)before else null
        job=Pocket.scope.launch {try{
            val result=api(p,"/api/coordinator/reports"+if(cursor!=null)"?before=$cursor" else "")
            if(!current(p))return@launch
            val page=result.optJSONArray("reports")?.objects().orEmpty().map(::decode)
            val overlaps=page.any{row->reports.any{it.id==row.id}}
            cursorState.accept(result.optLong("before").takeIf{it>0},result.optBoolean("hasEarlier"),older,reports.isNotEmpty(),overlaps)
            reports=(if(older)page+reports else reports+page).associateBy{it.id}.values.sortedBy{it.createdAt}
            latest=result.optJSONObject("latest")?.let(::decode);unreadCount=result.optInt("unreadCount");hasEarlier=cursorState.hasEarlier
        }catch(e:Exception){if(e is CancellationException)throw e;if(current(p))problem=PocketNetwork.error(e)}finally{if(current(p))loading=false}}
    }
    internal fun presented(id:String){
        if(!visible||!PocketVoice.foreground||BlackoutVisibility.active||id in acknowledged||!acknowledging.add(id))return
        val p=capture();if(reports.none{it.id==id}){acknowledging.remove(id);return}
        Pocket.scope.launch{try{val result=api(p,"/api/voice/turns/$id/presented",JSONObject());if(current(p)&&result.optBoolean("ok")){
            acknowledged.add(id);if(reports.any{it.id==id&&it.unread})unreadCount=(unreadCount-1).coerceAtLeast(0)
            reports=reports.map{if(it.id==id)it.copy(unread=false)else it};latest=latest?.let{if(it.id==id)it.copy(unread=false)else it}
        }}catch(e:Exception){if(e is CancellationException)throw e}finally{if(current(p))acknowledging.remove(id)}}
    }
}
internal fun scheduledCoordinatorReport(id:String,user:String)=user.isBlank()&&Regex("report-[a-f0-9]{12}-[0-9]+-[0-9]+").matches(id)
