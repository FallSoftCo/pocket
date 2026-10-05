export const INTERACTIVE_SOURCES=['cli','vscode','exec','appServer','unknown'];
const timestamp=t=>Number(t.updatedAt||t.createdAt||0);
const archived=t=>t.archived===true||/(?:^|[\\/])archived_sessions(?:[\\/]|$)/.test(t.path||'');
/** Lists metadata only: never resumes a session, reads histories, or runs work. */
export class SessionDiscovery {
  constructor({db,codex,hidden=()=>false,maxPages=3}){Object.assign(this,{db,codex,hidden,maxPages});this.readCache=new Map();this.refreshing=new Map();
    db.exec('CREATE TABLE IF NOT EXISTS pocket_discovered_threads(thread_id TEXT PRIMARY KEY,metadata TEXT NOT NULL,archived INTEGER NOT NULL DEFAULT 0,seen_at INTEGER NOT NULL)');
  }
  remember(thread,{archived:flag=archived(thread)}={}){if(!thread?.id||this.hidden(thread.id))return;const old=this.db.prepare('SELECT metadata,archived FROM pocket_discovered_threads WHERE thread_id=?').get(thread.id);if(old&&!thread.name&&!thread.preview)thread={...thread,name:JSON.parse(old.metadata).name};if(old?.metadata===JSON.stringify(thread)&&old.archived===(flag?1:0))return thread;this.db.prepare('INSERT INTO pocket_discovered_threads VALUES(?,?,?,?) ON CONFLICT(thread_id) DO UPDATE SET metadata=excluded.metadata,archived=excluded.archived,seen_at=excluded.seen_at').run(thread.id,JSON.stringify(thread),flag?1:0,Date.now());return thread;}
  knownIds(){const ids=new Set();
    for(const [table,column,where] of [['voice_sessions','selected','selected IS NOT NULL'],['session_starts','thread_id',"state='started'"],['watches','thread_id','enabled=1']]){
      if(!this.db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").get(table))continue;
      for(const row of this.db.prepare(`SELECT ${column} AS id FROM ${table} WHERE ${where} ORDER BY ${table==='session_starts'?'updated_at':'rowid'} DESC LIMIT 200`).all())if(row.id)ids.add(row.id);
    }for(const row of this.db.prepare('SELECT thread_id FROM pocket_discovered_threads ORDER BY seen_at DESC LIMIT 200').all())ids.add(row.thread_id);return [...ids].filter(id=>!this.hidden(id)).slice(0,200);
  }
  updateStatus(id,status){const saved=this.db.prepare('SELECT metadata,archived FROM pocket_discovered_threads WHERE thread_id=?').get(id);if(!saved)return;this.remember({...JSON.parse(saved.metadata),status,discoveryPending:false,updatedAt:Math.floor(Date.now()/1000)},{archived:!!saved.archived});this.readCache.delete(id);}
  markArchived(id,flag){this.db.prepare('UPDATE pocket_discovered_threads SET archived=? WHERE thread_id=?').run(flag?1:0,id);this.readCache.delete(id);}
  snapshot(flag){return {data:this.db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE archived=? ORDER BY seen_at DESC LIMIT 400').all(flag?1:0).map(row=>JSON.parse(row.metadata)).filter(t=>!this.hidden(t.id)).sort((a,b)=>timestamp(b)-timestamp(a)||a.id.localeCompare(b.id)),nextCursor:null,refreshPending:true};}
  async list({archived:flag=false}={}){
    let work=this.refreshing.get(flag);
    if(!work){work=this.refreshList(flag);this.refreshing.set(flag,work);void work.finally(()=>{if(this.refreshing.get(flag)===work)this.refreshing.delete(flag);}).catch(()=>{});}
    let timer;const timeout=Symbol('discovery deadline');
    try{const result=await Promise.race([work,new Promise(resolve=>{timer=setTimeout(()=>resolve(timeout),1750);})]);return result===timeout?this.snapshot(flag):result;}
    finally{clearTimeout(timer);}
  }
  async refreshList(flag){
    const found=new Map();let cursor=null;
    for(let page=0;page<this.maxPages;page++){
      const r=await this.codex.call('thread/list',{limit:100,sortKey:'updated_at',sortDirection:'desc',archived:flag,useStateDbOnly:true,sourceKinds:INTERACTIVE_SOURCES,...(cursor?{cursor}:{})});
      this.db.exec('BEGIN IMMEDIATE');try{for(const t of r.data||[]){if(this.hidden(t.id))continue;found.set(t.id,this.remember(t,{archived:flag})||t);}this.db.exec('COMMIT');}catch(error){this.db.exec('ROLLBACK');throw error;}
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
          entry.thread=this.remember(thread)||thread;found.set(id,entry.thread);
        }).catch(()=>{}).finally(()=>{entry.work=null;});entry.work=work;fresh.push(work);
      }
      if(fresh.length){let timer;await Promise.race([Promise.all(fresh),new Promise(resolve=>{timer=setTimeout(resolve,750);})]);clearTimeout(timer);}
    }
    return {data:[...found.values()].sort((a,b)=>timestamp(b)-timestamp(a)||a.id.localeCompare(b.id)),nextCursor:cursor};
  }
}
