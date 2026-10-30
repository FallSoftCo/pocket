package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Foreground discovery independent of the currently selected conversation/profile.
 * The gateway never receives another environment's credentials.
 */
internal object UnifiedWorkCatalog {
    private val state=WorkCatalogState<WorkCatalogPayload>()
    private var sourceSnapshots by mutableStateOf(emptyList<WorkSourceSnapshot<WorkCatalogPayload>>())
    val snapshots by derivedStateOf{
        sourceSnapshots.map{snapshot->
            val destination=profiles.values.firstOrNull{origin->
                val owner=backendOwner(origin.endpoint,origin.token)
                origin.environment.id==snapshot.key.environment&&(snapshot.key.owner==owner||
                    snapshot.key.backend=="lax"&&snapshot.key.owner.startsWith(owner+":"))
            }
            if(destination==null||
                Pocket.captureEnvironment(destination.environment.id)?.matches(destination)!=true)snapshot
            else if(snapshot.key.backend=="lax")snapshot.copy(entries=snapshot.entries.map{entry->
                entry.copy(value=entry.value.copy(destination=destination))
            })
            else if(snapshot.key.backend=="codex"){
                val tasks=CodexCatalog.source(destination,snapshot.key.query=="archived").tasks
                snapshot.copy(entries=tasks.map{task->WorkEntry(WorkItemKey(snapshot.key,WorkKind.SESSION,"",task.id),
                    workTimeMillis(taskWorkTime(task)),WorkCatalogPayload(destination,task.catalogJson().toString()))})
            }else snapshot
        }
    }
    var discoveryErrors by mutableStateOf(emptyMap<WorkSourceKey,String>());private set
    var discovering by mutableStateOf(emptySet<WorkSourceKey>());private set
    private val poller=WorkCatalogPoller(Pocket.scope,state,{sourceSnapshots=it})
    private var profiles by mutableStateOf(emptyMap<WorkSourceKey,EnvironmentRequest>())
    private val readers=mutableMapOf<WorkSourceKey,List<WorkCatalogReader>>()
    private val probes=mutableMapOf<WorkSourceKey,Job>()
    private val nextProbe=mutableMapOf<WorkSourceKey,Long>()
    private val failures=mutableMapOf<WorkSourceKey,Int>()
    private val probeTickets=mutableMapOf<WorkSourceKey,Long>()
    private var probeSerial=0L
    private var ticker:Job?=null
    private var viewers=0
    private var archived=false
    private fun codexReader(destination:EnvironmentRequest)=WorkCatalogReader(destination,"codex",archived=archived,read={origin,path,_->
        val store=CodexCatalog.source(origin,path.contains("archived=true"))
        JSONObject().put("threads",org.json.JSONArray(store.head().map{it.catalogJson()}))
    })
    private fun catalogStores()=profiles.values.filter{origin->
        readers[key(origin)].orEmpty().any{it.backend=="codex"}
    }.mapNotNull{origin->
        // A pairing replacement invalidates the discovery snapshot before its next
        // refresh. Never render or page a store belonging to the previous owner.
        if(Pocket.captureEnvironment(origin.environment.id)?.matches(origin)==true)
            CodexCatalog.source(origin,archived)
        else null
    }
    val hasEarlier get()=catalogStores().any{it.cursor!=null}
    suspend fun more(){
        coroutineScope{catalogStores().filter{it.cursor!=null}.map{store->launch{
            try{store.readPage()}catch(e:CancellationException){throw e}catch(_:Exception){}
        }}.joinAll()}
    }
    fun search(query:String){
        if(query.isBlank())return
        catalogStores().forEach{store->Pocket.scope.launch{
            try{store.readPage(query)}catch(e:CancellationException){throw e}catch(_:Exception){}
        }}
    }

    fun attach():()->Unit {
        viewers++
        if(ticker?.isActive!=true)ticker=Pocket.scope.launch{
            while(isActive){refresh();delay(5_000)}
        }
        var attached=true
        return {if(attached){attached=false;viewers--;if(viewers==0){
            ticker?.cancel();ticker=null
            probes.values.forEach{it.cancel()};probes.clear();discovering=emptySet()
            poller.stop()
        }}}
    }
    private fun key(destination:EnvironmentRequest)=WorkSourceKey(destination.environment.id,backendOwner(destination.endpoint,destination.token),"discovery")
    fun refresh() {
        if(archived!=Pocket.showArchived){
            archived=Pocket.showArchived
            readers.keys.toList().forEach{source->
                val destination=profiles[source]?:return@forEach
                readers[source]=readers[source].orEmpty().mapNotNull{reader->
                    if(reader.backend=="codex")codexReader(destination)else if(!archived)reader else null
                }
            }
        }
        CodexCatalog.refresh()
        val next=Pocket.environmentProfiles.all().mapNotNull{Pocket.captureEnvironment(it.id)}.associateBy(::key)
        val removed=profiles.keys-next.keys
        removed.forEach{source->probes.remove(source)?.cancel();readers.remove(source);nextProbe.remove(source);failures.remove(source);probeTickets.remove(source)}
        profiles=next
        discoveryErrors=discoveryErrors.filterKeys{it in next}
        discovering=discovering.filter{it in next}.toSet()
        reconcileReaders()
        val now=System.currentTimeMillis()
        next.forEach{(source,destination)->
            if(probes[source]?.isActive!=true&&now>=(nextProbe[source]?:0))probe(source,destination)
        }
        poller.refresh()
    }
    private fun current(source:WorkSourceKey,destination:EnvironmentRequest)=profiles[source]?.matches(destination)==true&&
        Pocket.captureEnvironment(destination.environment.id)?.matches(destination)==true
    private fun reconcileReaders() {
        val active=readers.filterKeys{it in profiles}.values.flatten()
        poller.reconcile(active.associate{reader->reader.key to suspend {
            if(Pocket.captureEnvironment(reader.destination.environment.id)?.matches(reader.destination)!=true)
                throw CancellationException("Source pairing changed")
            val rows=reader.head()
            if(Pocket.captureEnvironment(reader.destination.environment.id)?.matches(reader.destination)!=true)
                throw CancellationException("Source pairing changed")
            rows
        }})
    }
    private fun probe(source:WorkSourceKey,destination:EnvironmentRequest) {
        val ticket=++probeSerial;probeTickets[source]=ticket
        fun ownsProbe()=probeTickets[source]==ticket&&current(source,destination)
        discovering=discovering+source
        // Single flight is set by the owning job; this function does not change selection.
        probes[source]=Pocket.scope.launch{
            try{
                val status=discoverWorkBackends(
                    {WorkCatalogTransport.get(destination,"/api/backends")},
                    {codexReader(destination).head();Unit}
                )
                if(!ownsProbe())return@launch
                FleetConnections.observe(destination,status.optJSONObject("fleet")){refresh()}
                val available=status.getJSONArray("backends").objects()
                require(available.map{it.s("id")}.distinct().size==available.size){"Backend discovery is unconfirmed"}
                require(available.any{it.s("id") in setOf("codex","lax")&&it.optBoolean("configured")}){"No supported work catalog is confirmed"}
                val old=readers[source].orEmpty()
                val discovered=available.flatMap{backend->
                    when{
                        backend.s("id")=="codex"&&backend.optBoolean("configured")->listOf(codexReader(destination))
                        backend.s("id")=="lax"&&!archived&&backend.optBoolean("available")->listOf(WorkCatalogReader(destination,"lax",backend))
                        backend.s("id")=="lax"&&!archived&&backend.optBoolean("configured")->old.filter{it.backend=="lax"}
                        else->emptyList()
                    }
                }
                readers[source]=discovered
                failures.remove(source);nextProbe[source]=System.currentTimeMillis()+60_000
                discoveryErrors=discoveryErrors-source
                reconcileReaders();poller.refresh()
                // Publish catalog immediately; ancillary capability discovery cannot
                // delay visible work or grant unsafe legacy history attachment.
                if(Pocket.captureEnvironment()?.matches(destination)!=true){
                    try{
                        val metadata=WorkCatalogTransport.get(destination,"/api/status")
                        if(ownsProbe())CodexSessionRepository.observeCapabilities(destination,metadata.optJSONObject("capabilities"))
                    }catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){if(ownsProbe())CodexSessionRepository.observeCapabilities(destination,null)}
                }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){
                if(ownsProbe()){
                    val attempt=((failures[source]?:0)+1).coerceAtMost(5);failures[source]=attempt
                    nextProbe[source]=System.currentTimeMillis()+(5_000L*(1L shl (attempt-1))).coerceAtMost(60_000)
                    discoveryErrors=discoveryErrors+(source to PocketNetwork.error(error))
                }
            }finally{if(ownsProbe())discovering=discovering-source}
        }
    }
    fun entries(previous:List<WorkItemKey>)=state.ordered(previous)
}
