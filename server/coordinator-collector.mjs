import {createHash} from 'node:crypto';
import {sessionWorkTime} from './session-catalog.mjs';
import {INTERACTIVE_SOURCES} from './session-discovery.mjs';
const digest=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
/** Reconciles observations, never work. No turn, message, permissions, or agent-start API. */
export class CoordinatorCollector {
 constructor({db,discovery,memory,catchup,read,ready,hidden=()=>false,subscribe=()=>{},clock=Date.now,budget=8}) {
  Object.assign(this,{db,discovery,memory,catchup,read,ready,hidden,subscribe,clock,budget});this.running=null;
  db.exec(`CREATE TABLE IF NOT EXISTS coordinator_collector_state(id INTEGER PRIMARY KEY CHECK(id=1),cursor TEXT,catalog_at INTEGER,scan_at INTEGER,error TEXT);
   INSERT OR IGNORE INTO coordinator_collector_state(id) VALUES(1);
   CREATE TABLE IF NOT EXISTS coordinator_observations(thread_id TEXT PRIMARY KEY,work_at INTEGER NOT NULL,status TEXT NOT NULL,observed_at INTEGER,captured_at INTEGER,requested INTEGER NOT NULL DEFAULT 0,retry_at INTEGER NOT NULL DEFAULT 0,failures INTEGER NOT NULL DEFAULT 0,error TEXT);
   CREATE TABLE IF NOT EXISTS coordinator_observed_items(thread_id TEXT NOT NULL,item_key TEXT NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(thread_id,item_key));`);
 }
 status(){const s=this.db.prepare('SELECT * FROM coordinator_collector_state WHERE id=1').get();return {catalogObservedAt:s.catalog_at||null,lastCatalogScanEndAt:s.scan_at||null,pending:this.db.prepare('SELECT count(*) AS n FROM coordinator_observations WHERE requested=1').get().n,available:!!this.ready(),problem:s.error||null,modelPolling:false};}
 tick(){if(this.running)return this.running;if(!this.ready())return Promise.resolve(this.status());this.running=this.collect().finally(()=>{this.running=null});return this.running;}
 async catalog(cursor=null) {
  const page=await this.discovery.page({limit:100,sortKey:'recency_at',sortDirection:'desc',archived:false,useStateDbOnly:true,sourceKinds:[...INTERACTIVE_SOURCES,'subAgentThreadSpawn'],...(cursor?{cursor}:{})});
  const at=this.clock();
  for(const source of page.data||[]){
   if(this.hidden(source.id,source)||source.archived)continue;
   const thread=this.discovery.remember(source)||source,workAt=sessionWorkTime(thread),status=thread.status?.type||'unknown';
   const old=this.db.prepare('SELECT * FROM coordinator_observations WHERE thread_id=?').get(thread.id);
   const recent=workAt>at-15*60000,changed=old&&(old.work_at!==workAt||old.status!==status);
   const needed=status==='active'&&(!old?.captured_at||at-old.captured_at>=15000)||changed||!old&&recent;
   this.db.prepare(`INSERT INTO coordinator_observations(thread_id,work_at,status,observed_at,requested) VALUES(?,?,?,?,?)
    ON CONFLICT(thread_id) DO UPDATE SET work_at=excluded.work_at,status=excluded.status,observed_at=excluded.observed_at,requested=MAX(requested,excluded.requested)`).run(thread.id,workAt,status,at,needed?1:0);
   if(status==='active')void Promise.resolve(this.subscribe(thread)).catch(()=>{});
  }
  return page.nextCursor||null;
 }
 async collect(){
  const at=this.clock(),state=this.db.prepare('SELECT * FROM coordinator_collector_state WHERE id=1').get();
  try{
   const headNext=await this.catalog();
   const next=state.cursor?await this.catalog(state.cursor):headNext;
   this.db.prepare('UPDATE coordinator_collector_state SET cursor=?,catalog_at=?,scan_at=CASE WHEN ? IS NULL THEN ? ELSE scan_at END,error=NULL WHERE id=1').run(next,at,next,at);
  }catch(error){this.db.prepare('UPDATE coordinator_collector_state SET error=? WHERE id=1').run('Native catalog temporarily unavailable');return this.status();}
  const rows=this.db.prepare('SELECT * FROM coordinator_observations WHERE requested=1 AND retry_at<=? ORDER BY captured_at IS NULL DESC,work_at DESC LIMIT ?').all(at,this.budget);
  let next=0;
  await Promise.all(Array.from({length:Math.min(2,rows.length)},async()=>{
   while(next<rows.length){
    const row=rows[next++];if(this.hidden(row.thread_id)){this.db.prepare('UPDATE coordinator_observations SET requested=0 WHERE thread_id=?').run(row.thread_id);continue;}
    try{
     const response=await this.read('/api/threads/'+encodeURIComponent(row.thread_id)+'/native-timeline');
     if(response.thread?.id!==row.thread_id)throw Error('Native identity changed');
     this.memory.capture(row.thread_id,response);const material=this.catchup.material(response),seed=row.captured_at==null;
     this.db.exec('BEGIN IMMEDIATE');
     try{
      for(const item of material.items){
       const key=item.kind+':'+item.id,hash=digest([item.text,item.turnId,item.requestId,item.pending]);
       const old=this.db.prepare('SELECT hash FROM coordinator_observed_items WHERE thread_id=? AND item_key=?').get(row.thread_id,key);
       if(old?.hash!==hash){
        this.db.prepare('INSERT INTO coordinator_observed_items VALUES(?,?,?) ON CONFLICT(thread_id,item_key) DO UPDATE SET hash=excluded.hash').run(row.thread_id,key,hash);
        // Initial hydration is a baseline, not newly arrived speech. Existing
        // pending questions remain actionable; reads never mark anything heard.
        if(!seed||item.pending)this.catchup.put(row.thread_id,{key:item.requestId?'request:'+item.requestId:'assistant:'+item.id,...item});
       }
      }
      this.db.prepare('DELETE FROM coordinator_observed_items WHERE thread_id=? AND rowid NOT IN (SELECT rowid FROM coordinator_observed_items WHERE thread_id=? ORDER BY rowid DESC LIMIT 256)').run(row.thread_id,row.thread_id);
      this.db.prepare('INSERT INTO session_catchup_state VALUES(?,?,?) ON CONFLICT(thread_id) DO UPDATE SET working=excluded.working,at=excluded.at').run(row.thread_id,material.working?1:0,this.clock());
      this.db.prepare('UPDATE coordinator_observations SET captured_at=?,requested=0,retry_at=0,failures=0,error=NULL WHERE thread_id=? AND work_at=? AND status=?').run(this.clock(),row.thread_id,row.work_at,row.status);
      this.db.exec('COMMIT');
     }catch(error){this.db.exec('ROLLBACK');throw error;}
    }catch(error){
     const failures=row.failures+1;
     this.db.prepare('UPDATE coordinator_observations SET failures=?,retry_at=?,error=? WHERE thread_id=?').run(failures,this.clock()+Math.min(300000,15000*2**Math.min(failures,5)),'Native recent evidence unavailable',row.thread_id);
    }
   }
  }));
  return this.status();
 }
}

