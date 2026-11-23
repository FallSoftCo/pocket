// One translation worker owns one native thread and its original RPC outcomes.
// UI timeouts must never turn into replacement threads or duplicate model turns.
export class ImmersionRuntime {
  constructor(codex,{registerThread=()=>{},wanted=()=>true,ownsTransport=false,loadOwnership=()=>null,saveOwnership=()=>{}}={}) {
    Object.assign(this,{codex,registerThread,wanted,ownsTransport,saveOwnership});
    const retained=loadOwnership();this.thread=retained?.threadId||null;this.cleanupPhase=retained?.phase||null;this.starting=null;this.releasing=null;this.active=null;
    this.retireRequested=!!this.thread;this.startupUncertain=false;this.turns=0;this.stopped=false;this.generation=0;
    this.onEvent=e=>this.observe(e);
    this.onDisconnect=()=>{
      // A lost transport is not a terminal turn receipt. Keep its identity;
      // subsequent work cannot silently replace the uncertain model operation.
      if(this.active){clearTimeout(this.active.timer);this.active.reject(Error('Translation connection lost; cleanup precedes further work.'));this.active=null;this.retireRequested=true;}
    };
    codex.on('event',this.onEvent);codex.on('disconnected',this.onDisconnect);
    if(this.thread)queueMicrotask(()=>{void this.release().catch(()=>{});});
  }
  owns(id){return !!id&&id===this.thread;}
  call(method,params){return this.codex.callWorker?this.codex.callWorker(method,params):this.codex.call(method,params);}
  async ensure(params){
    await this.codex.connect();
    if(this.releasing)await this.releasing;
    if(this.startupUncertain)throw Error('Original translation startup is unresolved.');
    if(this.active)throw Error('The previous translation is still settling.');
    if(this.thread&&(this.turns>=20||this.retireRequested))await this.release();
    if(this.thread)return this.thread;
    if(this.starting)return this.starting;
    const work=(async()=>{
      await this.codex.connect();
      // Effective config may contain credentials: use names only, never persist
      // or log the result. Empty overrides do not clear inherited MCP servers.
      const view=await this.codex.call('config/read',{includeLayers:false});
      if(!view?.config||typeof view.config!=='object')throw Error('Translation tool isolation is unavailable.');
      const config={...params.config,'thread_unload_delay_secs':0,'apps._default.enabled':false};
      config.mcp_servers=Object.fromEntries(Object.keys(view.config.mcp_servers||{}).map(name=>[name,{enabled:false}]));
      if(!this.wanted()||this.stopped)throw Error('Immersion is disabled.');
      let response;
      try{response=await this.call('thread/start',{...params,ephemeral:false,config});}
      catch(error){if(!error.rpc)this.startupUncertain=true;throw error;}
      const {thread}=response;
      if(!thread?.id)throw Error('Translation startup returned no identity.');
      this.thread=thread.id;this.turns=0;this.retireRequested=true;this.cleanupPhase='owned';this.saveOwnership({threadId:thread.id,phase:'owned'});this.registerThread(thread.id);
      if(params.modelProvider&&(response.modelProvider!==params.modelProvider||response.model!==params.model))throw Error('Native immersion provider selection did not match.');
      this.retireRequested=false;
      return thread.id;
    })();
    this.starting=work;
    try{return await work;}finally{if(this.starting===work)this.starting=null;if(this.thread&&this.retireRequested)void this.release().catch(()=>{});}
  }
  async run(params,turn,timeout){
    const generation=this.generation;
    const id=await this.ensure(params);
    if(generation!==this.generation||!this.wanted()||this.stopped){await this.release();throw Error('Immersion is disabled.');}
    if(this.active)throw Error('The previous translation is still settling.');
    return new Promise((resolve,reject)=>{
      const a={threadId:id,turnId:null,text:'',cancelled:false,interruptSent:false,resolve,reject};
      this.active=a;this.turns++;
      a.timer=setTimeout(()=>{a.cancelled=true;reject(Error('Italian translation timed out'));void this.interrupt(a);},timeout);
      // A terminal event can precede the RPC reply. Bind callbacks to this exact
      // turn object so a late reply cannot mutate a newer translation.
      void this.call('turn/start',{...turn,threadId:id}).then(result=>{
        if(this.active!==a)return;
        a.turnId=result.turn?.id;
        if(a.cancelled)void this.interrupt(a);
      }).catch(error=>{
        if(this.active!==a)return;
        clearTimeout(a.timer);reject(error);
        if(error.rpc){this.active=null;void this.release().catch(()=>{});}
        // Nonterminal/transport failure retains the original identity and blocks
        // new inference until a native terminal event or explicit cleanup.
      });
    });
  }
  async interrupt(a){
    if(this.active!==a||!a.turnId||a.interruptSent)return;
    a.interruptSent=true;
    try{
      await this.call('turn/interrupt',{threadId:a.threadId,turnId:a.turnId});
      if(this.active===a&&a.cancelled){clearTimeout(a.timer);this.active=null;void this.release().catch(()=>{});}
    }
    catch{ /* Never repeat an uncertain interrupt or start replacement work. */ }
  }
  observe(event){
    const p=event.params||{};if(!this.owns(p.threadId))return false;
    if(event.method==='thread/closed')return true; // Ownership ends only after native deletion is acknowledged.
    if(event.id!==undefined){try{this.codex.reject(event.id,'Translation has no tools.');}catch{}return true;}
    const a=this.active;if(!a||a.threadId!==p.threadId)return true;
    if(event.method==='turn/started'&&!a.turnId){a.turnId=p.turn?.id;if(a.cancelled)void this.interrupt(a);}
    if(a.turnId&&p.turnId&&a.turnId!==p.turnId)return true;
    if(event.method==='item/agentMessage/delta')a.text+=p.delta||'';
    if(event.method==='item/completed'&&p.item?.type==='agentMessage')a.text=p.item.text||a.text;
    if(event.method==='turn/completed'){
      if(a.turnId&&p.turn?.id&&a.turnId!==p.turn.id)return true;
      clearTimeout(a.timer);this.active=null;
      if(a.cancelled||p.turn?.status==='failed'||p.turn?.status==='interrupted')a.reject(Error('Translation did not complete'));
      else try{a.resolve(JSON.parse(a.text.replace(/^```(?:json)?\s*|\s*```$/g,'')));}catch{a.reject(Error('Invalid translation response'));}
      if(this.stopped)void this.close().catch(()=>{});
      else void this.release().catch(()=>{}); // Every batch is stateless: never accumulate prior translated prose.
    }
    return true;
  }
  release(){
    this.retireRequested=true;
    if(this.releasing)return this.releasing;
    const work=(async()=>{
      if(this.starting)try{await this.starting;}catch{}
      if(this.active)return; // Cancellation awaits its original terminal event.
      const id=this.thread;if(!id)return;
      await this.codex.connect();
      // Only exact internally owned scratch threads enter this method. Archive
      // stops/unloads them on both native 0.163 and 0.171. Delete removes the
      // scratch transcript; accepted display plans live in the bounded cache.
      if(this.cleanupPhase!=='archived'){
        try{await this.call('thread/archive',{threadId:id});}
        catch(error){if(!error.rpc)throw error;/* An interrupted cleanup may already have archived this exact scratch thread. */}
        this.cleanupPhase='archived';this.saveOwnership({threadId:id,phase:'archived'});
      }
      try{await this.call('thread/delete',{threadId:id});}
      catch(error){
        if(!error.rpc)throw error;
        // A lost delete acknowledgement can be reconciled without replaying any
        // inference. Missing history alone is insufficient: residency is checked below.
        let missing=false;
        try{await this.codex.call('thread/read',{threadId:id,includeTurns:false});}
        catch(readError){missing=!!readError.rpc&&/(?:thread not found|no rollout found)/i.test(readError.message);}
        if(!missing)throw error;
      }
      let cursor=null,checked=false;
      for(let i=0;i<50;i++){
        const page=await this.codex.call('thread/loaded/list',{limit:100,...(cursor?{cursor}:{})});
        if(!Array.isArray(page.data)||page.data.includes(id))throw Error('Translation cleanup residency is unavailable.');
        if(!page.nextCursor){checked=true;break;}if(page.nextCursor===cursor)break;cursor=page.nextCursor;
      }
      if(!checked)throw Error('Translation cleanup residency is unavailable.');
      if(this.thread===id){this.thread=null;this.cleanupPhase=null;this.retireRequested=false;this.saveOwnership(null);}

    })();
    this.releasing=work;
    void work.finally(()=>{if(this.releasing===work)this.releasing=null;}).catch(()=>{});
    return work;
  }
  cancel(){
    this.generation++;
    if(this.active){this.active.cancelled=true;this.active.reject(Error('Immersion disabled.'));void this.interrupt(this.active);}
    else void this.release().catch(()=>{});
  }
  async close(){
    this.stopped=true;this.cancel();
    // Active work retains its listener until the terminal event, so stop cannot
    // lose an outstanding start/turn identity. Closing an idle connection is safe.
    if(this.active)return;
    try{await this.release();}finally{
      this.codex.off('event',this.onEvent);this.codex.off('disconnected',this.onDisconnect);
      if(this.ownsTransport)this.codex.closeTransport();
    }
  }
}
