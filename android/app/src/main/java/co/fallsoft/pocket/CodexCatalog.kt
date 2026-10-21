package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

internal enum class CodexCatalogLane { CATALOG, METADATA, ACTIVITY, NOTIFICATIONS, ATTENTION, PAGE }
internal data class CodexCatalogKey(val environment:String,val backend:String,val archived:Boolean)
internal data class CodexCatalogSnapshot(
    val key:CodexCatalogKey?,val incarnation:Long,val tasks:List<Task>,val load:CatalogLoadState,
    val complete:Boolean,val paging:Boolean,val errors:Map<CodexCatalogLane,String>,val reading:Set<CodexCatalogLane>
)

/** Environment-owned read admission. Slow ancillary lanes never serialize catalog freshness.
 * Catalog timestamps remain server work timestamps; observation time is not activity.
 */
internal class CodexCatalogStore(private val destination:EnvironmentRequest,private val archived:Boolean) {
    private data class Session(val key:CodexCatalogKey,val incarnation:Long,val destination:EnvironmentRequest,val scope:CoroutineScope,
        var catalog:Job?=null,var metadata:Job?=null,var activity:Job?=null,var notifications:Job?=null,var attention:Job?=null,
        var page:Job?=null,var discovery:Job?=null,var ticker:Job?=null,var lastDiscovery:Long=0)
    var tasks by mutableStateOf(emptyList<Task>())
    var activities by mutableStateOf(emptyList<JSONObject>())
    var notifications by mutableStateOf(emptyList<JSONObject>())
    var attention by mutableStateOf(emptyList<JSONObject>())
    var load by mutableStateOf<CatalogLoadState>(CatalogLoadState.Loading);private set
    var complete by mutableStateOf(false);private set
    var cursor by mutableStateOf<String?>(null);private set
    var paging by mutableStateOf(false);private set
    private val errors=mutableStateMapOf<CodexCatalogLane,String>()
    private val inFlight=mutableStateMapOf<CodexCatalogLane,Long>()
    private var readSerial=0L
    val error get()=CodexCatalogLane.entries.firstNotNullOfOrNull{errors[it]}.orEmpty()
    private var session by mutableStateOf<Session?>(null,referentialEqualityPolicy())
    private var retainedKey:CodexCatalogKey?=null
    private var serial=0L
    private var pageSerial=0L
    private var pageSearch=""
    private var cursorInitialized=false
    private var foreground=false
    private var listVisible=false
    private fun selected()=Pocket.captureEnvironment()?.matches(destination)==true&&Pocket.showArchived==archived
    private fun current(value:Session)=session===value&&Pocket.captureEnvironment(value.destination.environment.id)?.matches(value.destination)==true
    private var catalogAcceptedAt=0L
    private fun retire(){val old=session;session=null;old?.scope?.cancel();inFlight.clear();paging=false;pageSerial++}
    fun reset(){
        retire();retainedKey=null;tasks=emptyList();activities=emptyList();notifications=emptyList();attention=emptyList()
        load=CatalogLoadState.Loading;complete=false;cursor=null;cursorInitialized=false;errors.clear()
    }
    private fun active():Session?{
        if(Pocket.captureEnvironment(destination.environment.id)?.matches(destination)!=true)return null
        val key=CodexCatalogKey(destination.environment.id,backendOwner(destination.endpoint,destination.token),archived)
        session?.takeIf{it.key==key&&current(it)}?.let{startTicker(it);return it}
        retire()
        if(retainedKey!=key){tasks=emptyList();load=CatalogLoadState.Loading;complete=false;cursor=null;cursorInitialized=false;errors.clear();retainedKey=key}
        val value=Session(key,++serial,destination,CoroutineScope(SupervisorJob(Pocket.scope.coroutineContext[Job])+Dispatchers.Main.immediate+EnvironmentRequestContext(destination)))
        session=value;startTicker(value);return value
    }
    fun onForeground(visible:Boolean){
        foreground=visible
        if(!visible){retire();return}
        refresh()
    }
    fun listVisibility(environment:String,archived:Boolean,visible:Boolean){
        if(destination.environment.id!=environment||this.archived!=archived)return
        listVisible=visible
        if(visible){val value=active()?:return;startTicker(value);refresh()}
        else{session?.ticker?.cancel();session?.ticker=null}
    }
    private fun startTicker(value:Session){
        if(!foreground||!listVisible||value.ticker?.isActive==true)return
        value.ticker=value.scope.launch{while(isActive){delay(5_000);if(current(value)&&foreground&&listVisible)refresh()}}
    }
    private suspend fun read(value:Session,path:String):JSONObject{
        if(!current(value))throw CancellationException("Retired catalog")
        val result=CodexReadTransport.read(value.destination,path,client=CodexReadTransport.catalogClient)
        if(!current(value))throw CancellationException("Retired catalog")
        return result
    }
    private fun launch(value:Session,lane:CodexCatalogLane,block:suspend ()->Unit):Job {
        val ticket=++readSerial;inFlight[lane]=ticket
        return value.scope.launch{
            try{block();if(current(value)&&inFlight[lane]==ticket)errors.remove(lane)}
            catch(error:CancellationException){throw error}
            catch(error:Exception){if(current(value)&&inFlight[lane]==ticket){
                val message=PocketNetwork.error(error);errors[lane]=message
                if(lane==CodexCatalogLane.CATALOG&&load!=CatalogLoadState.Ready)load=CatalogLoadState.Failed(message)
            }}
            finally{if(current(value)&&inFlight[lane]==ticket)inFlight.remove(lane)}
        }
    }
    /** Explicit UI/initialization refresh remains supported without a mounted list. */
    fun refresh(explicit:Boolean=false){
        if(!explicit&&!foreground)return
        val value=active()?:return
        if(!selected()){catalog(value);return}
        metadata(value)
        if(BackendNavigation.nativeSelected())return
        catalog(value)
        if(value.activity?.isActive!=true)value.activity=launch(value,CodexCatalogLane.ACTIVITY){
            activities=try{read(value,"/api/activity").optJSONArray("items")?.objects().orEmpty()}catch(error:PocketApiException){if(error.status==404)emptyList()else throw error}
        }
        if(value.notifications?.isActive!=true)value.notifications=launch(value,CodexCatalogLane.NOTIFICATIONS){
            val baseline=notifications.map{it.s("thread_id") to it.optLong("id")}
            notifications=read(value,"/api/notifications").optJSONArray("notifications")?.objects()?.reversed().orEmpty()
            if(transcriptNotificationAdvanced(Pocket.selected,baseline,notifications.map{it.s("thread_id") to it.optLong("id")}))CodexConversations.invalidate()
            notifications.forEach{it.put("_environment",value.destination.environment.id);CodexSessionRepository.invalidate(it.s("thread_id"),value.destination,it.optLong("id"));PocketNotificationTitles.remember(it.put("_local",value.destination.environment.local));ForegroundNotifications.observe(it)}
        }
        if(value.attention?.isActive!=true)value.attention=launch(value,CodexCatalogLane.ATTENTION){
            val latest=read(value,"/api/attention").optJSONArray("notifications")?.objects().orEmpty()
            val ids=latest.map{it.optLong("id")}.toSet()
            Pocket.prefs.all.keys.filter{it.startsWith(Pocket.key("attention:"))}.mapNotNull{it.substringAfterLast(':').toLongOrNull()}
                .filter{it !in ids}.forEach{PocketAttention.dismiss(it)}
            attention=latest
        }
    }
    private fun metadata(value:Session){
        if(value.metadata?.isActive==true)return
        value.metadata=launch(value,CodexCatalogLane.METADATA){
            val revision=Pocket.statusRevision
            val result=read(value,"/api/status")
            if(!selected())return@launch
            val backendKey="backend:"+backendOwner(value.destination.endpoint,value.destination.token)
            if(result.s("backend")=="lax"&&!Pocket.prefs.contains(backendKey)){
                Pocket.prefs.edit().putString(backendKey,"lax").apply();CodexConversations.suspendReading()
            }
            Pocket.acceptUsage(result.optJSONObject("usage"))
            if(Pocket.statusRevision==revision){Pocket.codexOnline=result.optBoolean("connected");Pocket.codexConnectionMessage=ConnectionMessages.server(result.optJSONObject("problem")?.s("message").orEmpty())}
            Pocket.host=result.s("host");Pocket.defaultCwd=result.s("defaultCwd")
            val changed=Pocket.prefs.getString(Pocket.key("deviceId"),"")!=result.s("deviceId")
            Pocket.prefs.edit().putString(Pocket.key("deviceId"),result.s("deviceId")).apply()
            if(changed){PocketImmersion.restore();PocketSpeechCaptions.init()}
            PocketPush.configure(result.optJSONObject("firebase"),result.optJSONObject("push")?.optBoolean("registered")==true)
        }
    }
    private fun catalog(value:Session){
        if(value.catalog?.isActive==true)return
        if(load is CatalogLoadState.Failed)load=CatalogLoadState.Loading
        value.catalog=launch(value,CodexCatalogLane.CATALOG){
            val baseline=tasks.associateBy{it.id}
            val result=read(value,if(value.key.archived)"/api/threads?archived=true"else "/api/threads")
            val fetched=result.optJSONArray("threads")?.objects()?.map{catalogTask(it)}.orEmpty()
            val previous=tasks.firstOrNull{it.id==Pocket.selected}
            fetched.forEach{next->
                val old=baseline[next.id]
                if(old==null||transcriptPresentationChanged(old.preview,old.previewKind,old.status,next.preview,next.previewKind,next.status)||transcriptLiveActivityAdvanced(old.activityAt,next.activityAt))
                    CodexSessionRepository.invalidate(next.id,value.destination)
            }
            tasks=mergeLiveTaskSnapshot(tasks,fetched,baseline,result.optBoolean("refreshPending")||result.s("nextCursor").isNotBlank())
            tasks.firstOrNull{selected()&&it.id==Pocket.selected}?.let{latest->
                if(transcriptPresentationChanged(previous?.preview,previous?.previewKind,previous?.status,latest.preview,latest.previewKind,latest.status)||
                    transcriptLiveActivityAdvanced(previous?.activityAt,latest.activityAt))CodexConversations.invalidate()
            }
            complete=!result.optBoolean("refreshPending")
            if(!cursorInitialized&&complete){cursor=result.s("nextCursor").takeIf{it.isNotBlank()};cursorInitialized=true}
            load=CatalogLoadState.Ready;catalogAcceptedAt=android.os.SystemClock.elapsedRealtime()
        }
    }
    /** Same canonical store serves the merged list and selected conversation.
     * Joining an admitted read does not create a second catalog HTTP request.
     */
    suspend fun head():List<Task>{
        val value=active()?:throw IllegalStateException("Source pairing changed")
        if(android.os.SystemClock.elapsedRealtime()-catalogAcceptedAt>=2_000||load!=CatalogLoadState.Ready)catalog(value)
        value.catalog?.join()
        if(!current(value))throw CancellationException("Source pairing changed")
        errors[CodexCatalogLane.CATALOG]?.let{throw IllegalStateException(it)}
        return tasks.toList()
    }
    fun markForeground(visible:Boolean){foreground=visible}
    suspend fun readPage(search:String=""){
        page(search)
        session?.page?.join()
        errors[CodexCatalogLane.PAGE]?.let{throw IllegalStateException(it)}
    }
    fun detach(){foreground=false;listVisible=false;retire()}
    fun page(search:String=""){
        val value=active()?:return
        if(value.page?.isActive==true){if(search.isBlank()||pageSearch==search)return;value.page?.cancel()}
        val next=if(search.isBlank())cursor else null
        if(search.isBlank()&&next==null)return
        val ticket=++pageSerial;pageSearch=search;paging=true
        value.page=launch(value,CodexCatalogLane.PAGE){
            try{
                val baseline=tasks.associateBy{it.id}
                val result=read(value,"/api/threads?archived="+value.key.archived+
                    (next?.let{"&cursor="+android.net.Uri.encode(it)}?:"")+
                    (if(search.isNotBlank())"&search="+android.net.Uri.encode(search)else ""))
                if(ticket!=pageSerial)return@launch
                tasks=mergeLiveTaskSnapshot(tasks,result.optJSONArray("threads")?.objects()?.map{catalogTask(it)}.orEmpty(),baseline,true)
                if(search.isBlank())cursor=result.s("nextCursor").takeIf{it.isNotBlank()}
            }finally{if(current(value)&&ticket==pageSerial)paging=false}
        }
    }
    fun discover(id:String){
        if(!foreground||id.isBlank()||Pocket.showArchived||tasks.any{it.id==id})return
        val value=active()?:return
        if(value.discovery?.isActive==true)return
        value.discovery=value.scope.launch{
            val now=android.os.SystemClock.elapsedRealtime();delay(maxOf(400,3000-(now-value.lastDiscovery)))
            if(!current(value)||tasks.any{it.id==id})return@launch
            value.lastDiscovery=android.os.SystemClock.elapsedRealtime();refresh()
        }
    }
    val snapshot get()=CodexCatalogSnapshot(session?.key,session?.incarnation?:serial,tasks,load,complete,paging,errors.toMap(),inFlight.keys.toSet())
}


/** Registry owns one canonical catalog per pairing and archive query. The existing
 * selected-profile API is a projection, not a second cache or read scheduler.
 */
internal object CodexCatalog {
    private val stores=mutableMapOf<CodexCatalogKey,CodexCatalogStore>()
    private var foreground=false
    private fun prune(){
        stores.keys.filter{key->Pocket.captureEnvironment(key.environment)?.let{
            backendOwner(it.endpoint,it.token)==key.backend
        }!=true}.toList().forEach{stores.remove(it)?.detach()}
    }
    fun source(destination:EnvironmentRequest,archived:Boolean=false):CodexCatalogStore {
        prune()
        require(Pocket.captureEnvironment(destination.environment.id)?.matches(destination)==true){"Source pairing changed"}
        val key=CodexCatalogKey(destination.environment.id,backendOwner(destination.endpoint,destination.token),archived)
        return stores.getOrPut(key){CodexCatalogStore(destination,archived)}
    }
    private fun selected()=Pocket.captureEnvironment()?.let{source(it,Pocket.showArchived)}
    var tasks:List<Task>
        get()=selected()?.tasks.orEmpty()
        set(value){selected()?.tasks=value}
    var activities:List<JSONObject>
        get()=selected()?.activities.orEmpty()
        set(value){selected()?.activities=value}
    var notifications:List<JSONObject>
        get()=selected()?.notifications.orEmpty()
        set(value){selected()?.notifications=value}
    var attention:List<JSONObject>
        get()=selected()?.attention.orEmpty()
        set(value){selected()?.attention=value}
    val error get()=selected()?.error.orEmpty()
    val load get()=selected()?.load?:CatalogLoadState.Loading
    val complete get()=selected()?.complete?:false
    val cursor get()=selected()?.cursor
    val paging get()=selected()?.paging?:false
    val snapshot get()=selected()?.snapshot?:CodexCatalogSnapshot(null,0,emptyList(),CatalogLoadState.Loading,false,false,emptyMap(),emptySet())
    // Switching leaves the old source's canonical content intact. Its transport
    // admission is retired; a later late response cannot overwrite another source.
    fun reset(){selected()?.detach();prune()}
    fun onForeground(visible:Boolean){
        foreground=visible
        if(visible)selected()?.onForeground(true)else stores.values.forEach{it.detach()}
    }
    fun listVisibility(environment:String,archived:Boolean,visible:Boolean){
        Pocket.captureEnvironment(environment)?.let{destination->
            val store=source(destination,archived)
            if(visible&&foreground)store.onForeground(true)
            store.listVisibility(environment,archived,visible)
        }
    }
    fun refresh(explicit:Boolean=false){
        selected()?.let{it.markForeground(foreground);it.refresh(explicit)}
    }
    fun page(search:String="")=selected()?.page(search)?:Unit
    fun discover(id:String)=selected()?.discover(id)?:Unit
}
