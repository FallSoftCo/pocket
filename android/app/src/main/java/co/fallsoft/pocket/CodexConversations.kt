package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Canonical source identity contains no credential value. An incarnation also separates A→B→A. */
internal data class CodexConversationKey(val environment:String,val backend:String,val thread:String)
internal enum class CodexReadPhase { EMPTY, CACHED, UPDATING, CURRENT, FAILED, PAUSED }
internal data class CodexFreshness(val phase:CodexReadPhase,val current:Boolean,val loading:Boolean,val error:String)
internal data class CodexCoverage(val hasEarlier:Boolean,val before:String?,val browsingEarlier:Boolean)
internal data class CodexConversationSnapshot(
    val key:CodexConversationKey?,val incarnation:Long,
    val rows:List<JSONObject>,val detail:JSONObject?,
    val freshness:CodexFreshness,val coverage:CodexCoverage,
    val execution:ConversationRunState,val workVerified:Boolean,
    val pending:Int,val deliveries:List<ClientReplyIntent>
)

/**
 * The stock Codex conversation lifecycle owner.
 *
 * UI/socket/catalog/notification code supplies intents, never read jobs. Each selected
 * source owns one cancellable scope for current/older/gap/position/cache-restore work,
 * coalesced invalidation and foreground head repair. Retiring it cancels those jobs AND
 * their HTTP calls. Disk presentation writes and durable commands have separate lifetimes.
 * PocketTranscript is the retained merge/cache reducer; it cannot own a free-floating retry.
 */
internal object CodexConversations {
    private data class Session(
        val key:CodexConversationKey,val incarnation:Long,val destination:EnvironmentRequest,
        val scope:CoroutineScope,var refresh:Job?=null,var probe:Job?=null,
        val readerBusy:MutableStateFlow<Boolean> = MutableStateFlow(false)
    )
    var selected by mutableStateOf<String?>(null);private set
    var detail by mutableStateOf<JSONObject?>(null)
    private var session:Session?=null
    private var serial=0L
    private var foreground=false
    private var viewport=true
    internal val storageScope by lazy{CoroutineScope(SupervisorJob(Pocket.scope.coroutineContext[Job])+Dispatchers.IO)}
    private val probeClient by lazy{Pocket.http.newBuilder().callTimeout(5,TimeUnit.SECONDS).build()}
    private fun key(destination:EnvironmentRequest,id:String)=CodexConversationKey(destination.environment.id,backendOwner(destination.endpoint,destination.token),id)
    private fun current(value:Session)=session===value&&selected==value.key.thread&&Pocket.captureEnvironment()?.matches(value.destination)==true
    internal val incarnation get()=session?.incarnation?:serial
    internal val currentKey get()=session?.key

    private fun retire(){
        if(session!=null)PocketTranscript.pauseState()
        val old=session;session=null;old?.scope?.cancel()
    }
    /** Selection is owned here even through legacy/test adapters; no read is implied. */
    fun select(id:String?,force:Boolean=false){
        if(!force&&selected==id&&session!=null)return
        retire();selected=id;detail=null;viewport=id!=null
        if(id==null){serial++;PocketTranscript.clearState();return}
        attach(id)
    }
    private fun attach(id:String):Session? {
        val destination=Pocket.captureEnvironment()?:return null
        val scope=CoroutineScope(SupervisorJob(Pocket.scope.coroutineContext[Job])+Dispatchers.Main.immediate+EnvironmentRequestContext(destination))
        val value=Session(key(destination,id),++serial,destination,scope)
        session=value
        PocketTranscript.resetState(id,scope)
        startProbe(value)
        return value
    }
    private fun active():Session?{
        if(BackendNavigation.teamSelected())return null
        val id=selected?:return null
        val value=session
        if(value!=null&&current(value))return value
        retire()
        return attach(id)
    }
    fun close(){select(null)}
    fun suspendReading(){retire()}
    fun onForeground(visible:Boolean){
        if(foreground==visible)return
        foreground=visible
        if(!visible){retire();return}
        if(viewport)active()?.let{invalidate(immediate=true)}
    }
    fun invalidate(immediate:Boolean=false){
        if(BackendNavigation.teamSelected()||selected==null||!viewport)return
        // Background invalidations do not recreate a retired viewport.
        if(!immediate&&!foreground&&!PocketVoice.foreground)return
        val value=active()?:return
        PocketTranscript.markRefreshPending()
        if(value.refresh?.isActive==true)return
        value.refresh=value.scope.launch{
            if(!immediate)delay(400)
            if(current(value))PocketTranscript.readPage()
        }
    }
    @OptIn(FlowPreview::class)
    suspend fun read(older:Boolean=false,bridgeCursor:String?=null,retryAttempt:Int=0,
        beforeApply:suspend ()->Unit={},beforeCacheRead:suspend ()->Unit={}){
        val value=active()?:return
        // The viewport waits for its intent, but does not own/cancel repository work.
        value.scope.async{
            if(older)value.readerBusy.debounce(180).first{!it}
            if(current(value))PocketTranscript.readPage(older,bridgeCursor,retryAttempt,beforeApply,beforeCacheRead)
        }.await()
    }
    fun viewportMounted(environment:String,id:String){
        if(Pocket.environmentId!=environment||selected!=id)return
        viewport=true
        if(session==null)invalidate(immediate=true)
    }
    fun viewportDisposed(key:CodexConversationKey?,incarnation:Long){
        if(key==null||key.environment!=Pocket.environmentId||selected!=key.thread||this.incarnation!=incarnation)return
        viewport=false;retire()
    }
    fun readerMotion(key:CodexConversationKey?,incarnation:Long,moving:Boolean){
        val value=session?:return
        if(value.key==key&&value.incarnation==incarnation)value.readerBusy.value=moving
    }
    private fun owns(key:CodexConversationKey?,incarnation:Long):Boolean{
        val value=session?:return false
        return viewport&&value.key==key&&value.incarnation==incarnation&&current(value)
    }
    suspend fun readFrom(key:CodexConversationKey?,incarnation:Long,older:Boolean=false){
        if(owns(key,incarnation))read(older=older)
    }
    fun readerInteraction(key:CodexConversationKey?,incarnation:Long){
        if(owns(key,incarnation))PocketTranscript.readerInteraction()
    }
    fun presented(key:CodexConversationKey?,incarnation:Long){
        if(owns(key,incarnation))PocketTranscript.presentedLatest()
    }
    /** Preserve the original journal; only a still-current source may admit UI input. */
    fun submit(key:CodexConversationKey?,incarnation:Long,text:String,mode:String,draftKey:String,onDone:()->Unit):Boolean{
        if(!owns(key,incarnation)){Pocket.error="Conversation changed. Your draft is preserved.";return false}
        Pocket.reply(text,threadId=key!!.thread,mode=mode,draftKey=draftKey,onDone=onDone)
        return true
    }
    fun interrupt(key:CodexConversationKey?,incarnation:Long){
        if(owns(key,incarnation))Pocket.interrupt()
    }
    fun retry(){PocketTranscript.prepareRetry();invalidate(immediate=true)}
    fun reconnected(){invalidate()}
    private fun startProbe(value:Session){
        if(!foreground||value.probe?.isActive==true)return
        value.probe=value.scope.launch{while(isActive){delay(5_000);probe(value)}}
    }
    private suspend fun probe(value:Session){
        if(!current(value)||!foreground||Pocket.newTask||PocketCoordinator.visible||BackendNavigation.teamSelected())return
        val baseline=PocketTranscript.freshnessBaseline()
        try{
            val result=CodexReadTransport.read(value.destination,"/api/status?threadId="+android.net.Uri.encode(value.key.thread),client=probeClient)
            if(current(value)&&foreground)PocketTranscript.checkFreshness(value.key.thread,result.optJSONObject("historyHead"),baseline)
        }catch(error:CancellationException){throw error}
        catch(_:Exception){/* Unavailable read evidence changes neither commands nor verified work. */}
    }
    fun timeline(update:JSONObject){
        if(!viewport||(!foreground&&!PocketVoice.foreground))return
        val value=active()?:return
        if(!current(value)||update.s("threadId")!=value.key.thread)return
        val input=transcriptInputEvent(PocketTranscript.rows,update.optJSONObject("row"))
        PocketTranscript.apply(update)
        if(input.needsPosition)input.row?.let{PocketTranscript.reconcileInputPosition(it)}
        if(update.has("turn")||input.newInput)invalidate()
    }
    val snapshot:CodexConversationSnapshot get(){
        val rows=PocketTranscript.rows;val d=detail
        val pending=d?.optJSONArray("pending")?.length()?:rows.count{it.s("kind")=="request"}
        val task=Pocket.tasks.firstOrNull{it.id==selected}
        val turn=rows.lastOrNull{it.s("kind")=="turn"}
        val verified=PocketTranscript.currentWorkVerified
        val current=session!=null&&PocketTranscript.currentHistoryVerified
        val error=PocketTranscript.readError
        val phase=when{
            session==null&&selected!=null->CodexReadPhase.PAUSED
            error.isNotBlank()->CodexReadPhase.FAILED
            PocketTranscript.updating||PocketTranscript.loading->CodexReadPhase.UPDATING
            current->CodexReadPhase.CURRENT
            rows.isNotEmpty()->CodexReadPhase.CACHED
            else->CodexReadPhase.EMPTY
        }
        return CodexConversationSnapshot(session?.key,incarnation,rows,d,
            CodexFreshness(phase,current,PocketTranscript.loading,error),
            CodexCoverage(PocketTranscript.earlier,PocketTranscript.before,PocketTranscript.browsingEarlier),
            conversationRunState(pending,task?.status.orEmpty(),d?.optJSONObject("thread")?.optJSONObject("status")?.s("type").orEmpty(),
                if(verified)turn?.s("status").orEmpty()else "",d!=null&&verified,PocketTranscript.loading),
            verified,pending,PocketReplies.visible(selected.orEmpty()))
    }
}
