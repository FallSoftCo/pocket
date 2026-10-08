import {compareSessionWork,sessionIdentity} from './session-catalog.mjs';
export const INTERACTIVE_SOURCES=['cli','vscode','exec','appServer','unknown'];
const archived=t=>t.archived===true||/(?:^|[\\/])archived_sessions(?:[\\/]|$)/.test(t.path||'');
/** Lists metadata only: never resumes a session, reads histories, or runs work. */
export class SessionDiscovery {
  constructor({db,codex,hidden=()=>false,maxPages=3}){Object.assign(this,{db,codex,hidden,maxPages});this.readCache=new Map();this.refreshing=new Map();this.live=new Map();this.liveReads=new Map();this.liveReadAt=new Map();this.persistedAt=new Map();
    db.exec('CREATE TABLE IF NOT EXISTS pocket_discovered_threads(thread_id TEXT PRIMARY KEY,metadata TEXT NOT NULL,archived INTEGER NOT NULL DEFAULT 0,seen_at INTEGER NOT NULL)');
  }
  async page(params){const requested={...params,sortKey:this.recencyUnsupported?'created_at':(params.sortKey||'recency_at')};try{return await this.codex.call('thread/list',requested);}catch(error){if(requested.sortKey==='recency_at'&&error.rpc?.code===-32602&&/recency_at|sort.?key|unknown variant/i.test(error.message||error.rpc.message||'')){this.recencyUnsupported=true;return this.codex.call('thread/list',{...requested,sortKey:'created_at'});}throw error;}}
  remember(thread,{archived:flag=archived(thread)}={}){if(!thread?.id||this.hidden(thread.id,thread))return;thread={...thread,...this.live.get(thread.id)};const old=this.db.prepare('SELECT metadata,archived FROM pocket_discovered_threads WHERE thread_id=?').get(thread.id);if(old){const previous=JSON.parse(old.metadata);for(const key of ['recencyAt','createdAt','source','parentThreadId','canAcceptDirectInput','agentNickname','agentRole','historyMode','forkedFromId'])if(thread[key]===undefined&&previous[key]!==undefined)thread[key]=previous[key];thread={...thread,...(previous.activityAt&&!thread.activityAt?{activityAt:previous.activityAt}:{}),...(!thread.name&&!thread.preview?{name:previous.name}:{})};}if(old?.metadata===JSON.stringify(thread)&&old.archived===(flag?1:0))return thread;this.db.prepare('INSERT INTO pocket_discovered_threads VALUES(?,?,?,?) ON CONFLICT(thread_id) DO UPDATE SET metadata=excluded.metadata,archived=excluded.archived,seen_at=excluded.seen_at').run(thread.id,JSON.stringify(thread),flag?1:0,Date.now());return thread;}
  observe(event,clock=Date.now){
    const p=event.params||{},id=p.threadId||p.thread?.id;if(!id||this.hidden(id,p.thread))return null;
    if(event.method==='thread/started'&&p.thread)this.remember(p.thread);
    const status=event.method==='thread/status/changed'?p.status:event.method==='turn/started'?{type:'active'}:event.method==='turn/completed'?{type:'idle'}:null;
    const activity=/^item\/(?:started|completed|agentMessage\/delta|commandExecution\/outputDelta|reasoning\/summaryTextDelta|reasoning\/summaryPartAdded)$/.test(event.method)||['turn/started','turn/completed'].includes(event.method);
    if(!status&&!activity)return null;
    const old=this.live.get(id)||{},change={...(activity?{activityAt:clock()}:{}),...(status?{status,discoveryPending:false}:{})};
    this.live.set(id,{...old,...change});while(this.live.size>512){const oldest=[...this.live].reduce((a,b)=>(a[1].activityAt||0)<=(b[1].activityAt||0)?a:b);this.live.delete(oldest[0]);}
    const saved=this.db.prepare('SELECT metadata,archived FROM pocket_discovered_threads WHERE thread_id=?').get(id);
    if(saved&&(status||clock()-(this.persistedAt.get(id)||0)>=500)){this.remember({...JSON.parse(saved.metadata),...change},{archived:!!saved.archived});this.persistedAt.set(id,clock());while(this.persistedAt.size>512)this.persistedAt.delete(this.persistedAt.keys().next().value);}
    return {threadId:id,...change};
  }
  recordIntent(id,clock=Date.now){const change={activityAt:clock()};this.live.set(id,{...this.live.get(id),...change});const saved=this.db.prepare('SELECT metadata,archived FROM pocket_discovered_threads WHERE thread_id=?').get(id);if(saved)this.remember({...JSON.parse(saved.metadata),...change},{archived:!!saved.archived});return {threadId:id,...change};}
  clearLiveStatus(){this.readCache.clear();for(const [id,value] of this.live){const {status,discoveryPending,...activity}=value;this.live.set(id,activity);}}
  discoverLive(id){
    if(this.hidden(id))return Promise.resolve(null);
    if(this.liveReads.has(id))return this.liveReads.get(id);
    if(Date.now()-(this.liveReadAt.get(id)||0)<2000)return Promise.resolve(null);this.liveReadAt.set(id,Date.now());while(this.liveReadAt.size>512)this.liveReadAt.delete(this.liveReadAt.keys().next().value);
    const work=this.codex.call('thread/read',{threadId:id,includeTurns:false}).then(({thread})=>thread&&!archived(thread)?this.remember(thread):null).catch(()=>null).finally(()=>this.liveReads.delete(id));
    this.liveReads.set(id,work);return work;
  }
  knownIds(){const ids=new Set([...this.live].filter(([,value])=>value.activityAt>0).sort((a,b)=>b[1].activityAt-a[1].activityAt||a[0].localeCompare(b[0])).map(([id])=>id));
    for(const [table,column,where] of [['voice_sessions','selected','selected IS NOT NULL'],['session_starts','thread_id',"state='started'"],['watches','thread_id','enabled=1']]){
      if(!this.db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").get(table))continue;
      for(const row of this.db.prepare(`SELECT ${column} AS id FROM ${table} WHERE ${where} ORDER BY ${table==='session_starts'?'updated_at':'rowid'} DESC LIMIT 200`).all())if(row.id)ids.add(row.id);
    }for(const row of this.db.prepare("SELECT thread_id FROM pocket_discovered_threads WHERE archived=0 ORDER BY MAX(COALESCE(json_extract(metadata,'$.activityAt'),0),COALESCE(json_extract(metadata,'$.recencyAt'),json_extract(metadata,'$.createdAt'),0)*1000) DESC LIMIT 200").all())ids.add(row.thread_id);return [...ids].filter(id=>!this.hidden(id)&&!this.db.prepare('SELECT archived FROM pocket_discovered_threads WHERE thread_id=?').get(id)?.archived).slice(0,200);
  }
  updateStatus(id,status){const saved=this.db.prepare('SELECT metadata,archived FROM pocket_discovered_threads WHERE thread_id=?').get(id);if(!saved)return;this.remember({...JSON.parse(saved.metadata),status,discoveryPending:false},{archived:!!saved.archived});this.readCache.delete(id);}
  markArchived(id,flag){this.db.prepare('UPDATE pocket_discovered_threads SET archived=? WHERE thread_id=?').run(flag?1:0,id);this.readCache.delete(id);}
  snapshot(flag){return {data:this.db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE archived=?').all(flag?1:0).map(row=>JSON.parse(row.metadata)).filter(t=>!this.hidden(t.id,t)).sort(compareSessionWork).slice(0,400),nextCursor:null,refreshPending:true};}
  async list({archived:flag=false,cursor=null,searchTerm=null}={}){
    if(cursor||searchTerm){const page=await this.page({limit:100,sortKey:'recency_at',sortDirection:'desc',archived:flag,useStateDbOnly:true,sourceKinds:searchTerm?[...INTERACTIVE_SOURCES,'subAgentThreadSpawn']:INTERACTIVE_SOURCES,...(cursor?{cursor}:{}),...(searchTerm?{searchTerm}:{})});return {data:(page.data||[]).filter(t=>!this.hidden(t.id,t)).map(t=>this.remember(t,{archived:flag})||t).sort(compareSessionWork),nextCursor:page.nextCursor||null};}
    let work=this.refreshing.get(flag);
    if(!work){work=this.refreshList(flag);this.refreshing.set(flag,work);void work.finally(()=>{if(this.refreshing.get(flag)===work)this.refreshing.delete(flag);}).catch(()=>{});}
    let timer;const timeout=Symbol('discovery deadline');
    try{const result=await Promise.race([work,new Promise(resolve=>{timer=setTimeout(()=>resolve(timeout),1750);})]);return result===timeout?this.snapshot(flag):result;}
    finally{clearTimeout(timer);}
  }
  async refreshList(flag){
    const found=new Map(),freshStatus=new Set();let cursor=null;
    for(let page=0;page<this.maxPages;page++){
      const r=await this.page({limit:100,sortKey:'recency_at',sortDirection:'desc',archived:flag,useStateDbOnly:true,sourceKinds:INTERACTIVE_SOURCES,...(cursor?{cursor}:{})});
      this.db.exec('BEGIN IMMEDIATE');try{for(const t of r.data||[]){if(this.hidden(t.id,t))continue;found.set(t.id,this.remember(t,{archived:flag})||t);freshStatus.add(t.id);}this.db.exec('COMMIT');}catch(error){this.db.exec('ROLLBACK');throw error;}
      const next=r.nextCursor||null;if(!next||next===cursor){cursor=null;break;}cursor=next;
    }
    // Recently created/selected work must be reachable even before Codex's state index catches up.
    if(!flag){const missing=this.knownIds().filter(id=>!found.has(id));const fresh=[];
      for(const id of missing){
        const saved=this.db.prepare('SELECT metadata,archived FROM pocket_discovered_threads WHERE thread_id=?').get(id);if(saved?.archived)continue;
        const remembered=saved?JSON.parse(saved.metadata):null;
        if(remembered&&!archived(remembered))found.set(id,{...remembered,discoveryPending:remembered.discoveryPending!==false});
        const cached=this.readCache.get(id);
        if(cached&&Date.now()-cached.at<30000){if(cached.thread&&!archived(cached.thread))found.set(id,cached.thread);continue;}
        if(cached?.work)continue;
        // At most eight metadata reads per poll, and no wait beyond 750 ms. RPCs may
        // finish later; persisted creation metadata remains immediately available.
        if(fresh.length>=8)continue;
        const entry={at:Date.now(),thread:null,work:null};if(this.readCache.size>=512){const prune=[...this.readCache].find(([,value])=>!value.work);if(prune)this.readCache.delete(prune[0]);}this.readCache.set(id,entry);
        const work=this.codex.call('thread/read',{threadId:id,includeTurns:false}).then(({thread})=>{
          entry.thread=thread;entry.at=Date.now();if(!thread||archived(thread)||this.hidden(id))return;
          entry.thread=this.remember(thread)||thread;found.set(id,entry.thread);freshStatus.add(id);
        }).catch(()=>{}).finally(()=>{entry.work=null;});entry.work=work;fresh.push(work);
      }
      if(fresh.length){let timer;await Promise.race([Promise.all(fresh),new Promise(resolve=>{timer=setTimeout(resolve,750);})]);clearTimeout(timer);}
    }
    // Keep delegated results with loaded primary tasks, even when their own pages
    // fall outside the primary recency window. This reads cached metadata only.
    const cached=new Map(this.db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE archived=?').all(flag?1:0).map(row=>{const t=JSON.parse(row.metadata);return [t.id,t];}));
    for(const t of [...found.values()]){let parent=sessionIdentity(t).parentThreadId;const seen=new Set([t.id]);while(parent&&!seen.has(parent)){seen.add(parent);const entry=cached.get(parent);if(!entry||this.hidden(parent))break;if(!found.has(parent))found.set(parent,entry);parent=sessionIdentity(entry).parentThreadId;}}
    for(const t of cached.values()){if(this.hidden(t.id,t)||!sessionIdentity(t).isChild)continue;let parent=sessionIdentity(t).parentThreadId;const seen=new Set([t.id]);while(parent&&!seen.has(parent)){seen.add(parent);if(found.has(parent)){if(!found.has(t.id))found.set(t.id,{...t,status:{type:'notLoaded'},discoveryPending:true,...this.live.get(t.id)});break;}parent=sessionIdentity(cached.get(parent)||{}).parentThreadId;}}
    for(const [id,t] of found){if(!sessionIdentity(t).isChild)continue;const read=this.readCache.get(id);if(!freshStatus.has(id)&&!this.live.get(id)?.status&&!(read?.thread&&Date.now()-read.at<30000))found.set(id,{...t,status:{type:'notLoaded'},discoveryPending:true});}
    return {data:[...found.values()].map(t=>({...t,...this.live.get(t.id)})).sort(compareSessionWork),nextCursor:cursor};
  }
}
