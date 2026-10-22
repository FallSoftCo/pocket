import {textPrefix} from './text-boundary.mjs';
import {coordinatorEvidence} from './coordinator-discovery.mjs';
const valid=id=>typeof id==='string'&&/^[a-zA-Z0-9_-]{5,100}$/.test(id);
const clip=(text,n=1800)=>textPrefix(String(text||'').replace(/\bBearer\s+\S+/gi,'Bearer [redacted]').replace(/\bsk-[\w-]{12,}/g,'[redacted]').replace(/\b(password|token|secret|api[_ -]?key)\s*[=:]\s*[^\s,;]+/gi,'$1=[redacted]'),n);
const terms=query=>[...new Set(String(query||'').normalize('NFKC').match(/[\p{L}\p{N}]{3,}/gu)||[])].slice(0,16);
/** Indexed public evidence, not another transcript or a source of execution authority.
 * Event ingestion is deterministic. Queries never start/resume a worker. */
export class CoordinatorMemory {
 constructor({db,hidden=()=>false,clock=Date.now,read}){
  Object.assign(this,{db,hidden,clock,read});this.reading=new Map();
  db.exec(`CREATE TABLE IF NOT EXISTS coordinator_memory(id INTEGER PRIMARY KEY,thread_id TEXT NOT NULL,item_id TEXT NOT NULL,turn_id TEXT,role TEXT NOT NULL,phase TEXT,text TEXT NOT NULL,observed_at INTEGER NOT NULL,UNIQUE(thread_id,item_id));
   CREATE INDEX IF NOT EXISTS coordinator_memory_thread ON coordinator_memory(thread_id,id DESC);
   CREATE VIRTUAL TABLE IF NOT EXISTS coordinator_memory_search USING fts5(thread_id UNINDEXED,text,tokenize='unicode61 remove_diacritics 2');
   CREATE TRIGGER IF NOT EXISTS coordinator_memory_insert AFTER INSERT ON coordinator_memory BEGIN INSERT INTO coordinator_memory_search(rowid,thread_id,text) VALUES(new.id,new.thread_id,new.text); END;
   CREATE TRIGGER IF NOT EXISTS coordinator_memory_delete AFTER DELETE ON coordinator_memory BEGIN DELETE FROM coordinator_memory_search WHERE rowid=old.id; END;
   CREATE TRIGGER IF NOT EXISTS coordinator_memory_update AFTER UPDATE ON coordinator_memory BEGIN DELETE FROM coordinator_memory_search WHERE rowid=old.id; INSERT INTO coordinator_memory_search(rowid,thread_id,text) VALUES(new.id,new.thread_id,new.text); END;
   CREATE TABLE IF NOT EXISTS coordinator_memory_heads(thread_id TEXT PRIMARY KEY,payload TEXT NOT NULL,observed_at INTEGER NOT NULL);`);
 }
 put(threadId,item){
  if(!valid(threadId)||this.hidden(threadId)||!item?.id||!['userMessage','agentMessage'].includes(item.type))return;
  const text=item.type==='userMessage'?(item.content||[]).filter(c=>c.type==='text').map(c=>c.text).join('\n'):item.text;
  if(!String(text||'').trim())return;
  this.db.prepare(`INSERT INTO coordinator_memory(thread_id,item_id,turn_id,role,phase,text,observed_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(thread_id,item_id) DO UPDATE SET text=excluded.text,phase=excluded.phase,observed_at=excluded.observed_at WHERE text!=excluded.text OR phase IS NOT excluded.phase`).run(threadId,String(item.id),item.turnId||null,item.type==='userMessage'?'user':'assistant',item.phase||null,clip(text,6000),this.clock());
  this.db.prepare('DELETE FROM coordinator_memory WHERE thread_id=? AND id NOT IN (SELECT id FROM coordinator_memory WHERE thread_id=? ORDER BY id DESC LIMIT 128)').run(threadId,threadId);
 }
 observe(event){const p=event.params||{},threadId=p.threadId||p.thread?.id;if(event.method==='item/completed')this.put(threadId,{...p.item,turnId:p.turnId});}
 capture(threadId,response){
  if(!valid(threadId)||response?.thread?.id!==threadId||this.hidden(threadId))return;
  const value=coordinatorEvidence(response,{},threadId);
  value.messages=value.messages.slice(-4).map(m=>({...m,text:clip(m.text,400)}));value.latestUser=clip(value.latestUser,700);value.latestAssistant=clip(value.latestAssistant,700);value.notes=value.notes.slice(0,2).map(n=>({...n,text:clip(n.text,240)}));
  for(const row of response.timeline?.rows||[])if(['userMessage','agentMessage'].includes(row.type))this.put(threadId,{id:row.itemId||row.id,type:row.type,phase:row.phase,text:row.text,content:[{type:'text',text:row.text}],turnId:row.turnId});
  this.db.prepare('INSERT INTO coordinator_memory_heads VALUES(?,?,?) ON CONFLICT(thread_id) DO UPDATE SET payload=excluded.payload,observed_at=excluded.observed_at').run(threadId,JSON.stringify(value),this.clock());return value;
 }
 catalog(){
  return this.db.prepare(`SELECT metadata,seen_at FROM pocket_discovered_threads WHERE archived=0 ORDER BY MAX(COALESCE(json_extract(metadata,'$.activityAt'),0),COALESCE(json_extract(metadata,'$.recencyAt'),json_extract(metadata,'$.createdAt'),0)*CASE WHEN COALESCE(json_extract(metadata,'$.recencyAt'),json_extract(metadata,'$.createdAt'),0)<100000000000 THEN 1000 ELSE 1 END) DESC LIMIT 1200`).all().map(row=>({...JSON.parse(row.metadata),observedAt:row.seen_at})).filter(t=>valid(t.id)&&!this.hidden(t.id));
 }
 initialSnapshot({threadIds=[]}={}){
  const all=this.catalog(),refs=new Set(threadIds);const catalog=[...all.filter(t=>refs.has(t.id)),...all.filter(t=>!refs.has(t.id))].slice(0,8).map(t=>this.meta(t));
  return {collection:this.collection?.()||null,deferred:true,sessions:[],catalog,catalogCount:catalog.length,listedCount:all.length,checkedAt:this.clock(),scope:{complete:false,kind:'stored-public-evidence'},instruction:'Indexed observations, not a complete live roster. Discover retrieves public requests/results; read verifies a target before mutation.',errors:[]};
 }
 meta(t){return {id:t.id,name:clip(t.name,120),cwd:clip(t.cwd,140),status:t.status?.type||t.status||'unknown',observedAt:t.observedAt||null,canAcceptDirectInput:t.canAcceptDirectInput!==false&&!t.parentThreadId,parentThreadId:t.parentThreadId||null,preview:clip(t.preview,200)};}
 async discover({query='',threadIds=[],limit=3,hydrate=true,cursor=null}={}){
  limit=Math.max(1,Math.min(6,Number(limit)||3));let all=this.catalog();const byId=new Map(all.map(t=>[t.id,t])),chosen=[...new Set(threadIds.filter(valid))].slice(0,limit);
  const words=terms(query),ranked=new Map();let nextCursor=null;
  // Search native metadata when the stored index has no topical evidence, and
  // follow explicit native cursors. This never reads legacy turn history.
  const hasStored=words.some(w=>all.some(t=>[t.name,t.cwd,t.preview].join(' ').toLowerCase().includes(w.toLowerCase())));
  if(hydrate&&(cursor||words.length&&!hasStored)){try{
   const page=await this.read('/api/threads?search='+encodeURIComponent(query.slice(0,200))+(cursor?'&cursor='+encodeURIComponent(String(cursor).slice(0,4096)):''));
   nextCursor=page.nextCursor||null;for(const t of page.threads||[])if(valid(t.id)&&!this.hidden(t.id)){byId.set(t.id,t);}
   all=[...byId.values()];
  }catch{/* Stored evidence remains explicitly partial. */}}

  if(words.length){const match=words.map(w=>'"'+w.replaceAll('"','""')+'"').join(' OR ');
   for(const row of this.db.prepare('SELECT thread_id,bm25(coordinator_memory_search) AS rank FROM coordinator_memory_search WHERE coordinator_memory_search MATCH ? ORDER BY rank LIMIT 40').all(match))if(!ranked.has(row.thread_id))ranked.set(row.thread_id,100-row.rank);
   for(const t of all){const hay=[t.name,t.cwd,t.preview].join(' ').toLowerCase();const score=words.filter(w=>hay.includes(w.toLowerCase())).length;if(score)ranked.set(t.id,(ranked.get(t.id)||0)+score);}
  }
  for(const [id]of [...ranked].sort((a,b)=>b[1]-a[1]))if(chosen.length<limit&&!chosen.includes(id)&&!this.hidden(id))chosen.push(id);
  for(const t of all)if(chosen.length<limit&&!chosen.includes(t.id))chosen.push(t.id);
  const sessions=new Array(chosen.length);let next=0;
  const retrieve=async id=>{
   const cached=this.db.prepare('SELECT payload,observed_at FROM coordinator_memory_heads WHERE thread_id=?').get(id);
   if(!hydrate){if(cached)return {...JSON.parse(cached.payload),observedAt:cached.observed_at,evidence:'stored-native-head'};return {...this.meta(byId.get(id)||{id}),messages:this.db.prepare('SELECT role,phase,text,turn_id AS turnId FROM coordinator_memory WHERE thread_id=? ORDER BY id DESC LIMIT 4').all(id).reverse().map(m=>({...m,text:clip(m.text,500)})),availability:'stored-public-evidence',history:{scope:'stored',truncated:true}};}
   if(cached&&this.clock()-cached.observed_at<5000)return {...JSON.parse(cached.payload),observedAt:cached.observed_at,evidence:'native-head-cache'};
   try{return {...await this.readHead(id),observedAt:this.clock(),evidence:'native-head'};}
   catch(error){const messages=this.db.prepare('SELECT role,phase,text,turn_id FROM coordinator_memory WHERE thread_id=? ORDER BY id DESC LIMIT 8').all(id).reverse().map(r=>({role:r.role,text:clip(r.text,1200),phase:r.phase,turnId:r.turn_id}));return {...this.meta(byId.get(id)||{id}),messages,availability:'unavailable',readError:'native-head-unavailable',history:{scope:'stored-public-evidence',truncated:true}};}
  };
  await Promise.all(Array.from({length:Math.min(3,chosen.length)},async()=>{while(next<chosen.length){const n=next++;sessions[n]=await retrieve(chosen[n]);}}));
  return {collection:this.collection?.()||null,sessions,catalog:all.slice(0,12).map(t=>this.meta(t)),listedCount:all.length,checkedAt:this.clock(),scope:{complete:false,kind:'indexed-public-evidence',nextCursor},errors:sessions.filter(s=>s.availability==='unavailable').map(s=>({operation:'read',threadId:s.id,code:s.readError}))};
 }
 async readHead(id){
  if(!valid(id)||this.hidden(id))throw Error('Choose an available work session.');
  if(!this.reading.has(id)){const promise=(async()=>this.capture(id,await this.read('/api/threads/'+encodeURIComponent(id)+'/native-timeline')))();this.reading.set(id,promise);promise.finally(()=>{if(this.reading.get(id)===promise)this.reading.delete(id)}).catch(()=>{});}
  return this.reading.get(id);
 }
}
