import {createHash,randomUUID} from 'node:crypto';
import {interimExcerpt} from './conversation-notes.mjs';
import {cleanSpeech,spokenText} from './speech.mjs';
const clean=value=>String(value||'').trim().slice(0,6000);
const hash=value=>createHash('sha256').update(value).digest('hex');
const deviceId=device=>typeof device==='string'?device:device?.id;
const invalid=message=>Object.assign(new Error(message),{status:400});
/** Public, material updates only. Discovery and snapshot never constitute a read. */
export class SessionCatchup {
 constructor({db,clock=Date.now,hidden=()=>false,resolvePending=null,maxPerThread=500}={}) {
  Object.assign(this,{db,clock,hidden,resolvePending,maxPerThread});
  db.exec(`CREATE TABLE IF NOT EXISTS session_catchup_events(seq INTEGER PRIMARY KEY AUTOINCREMENT,thread_id TEXT NOT NULL,logical_key TEXT NOT NULL UNIQUE,kind TEXT NOT NULL,text TEXT NOT NULL,turn_id TEXT,request_id TEXT,pending INTEGER NOT NULL DEFAULT 0,resolved INTEGER NOT NULL DEFAULT 0,at INTEGER NOT NULL);
   CREATE INDEX IF NOT EXISTS session_catchup_thread ON session_catchup_events(thread_id,seq);
   CREATE TABLE IF NOT EXISTS session_catchup_reads(device_id TEXT NOT NULL,thread_id TEXT NOT NULL,cursor INTEGER NOT NULL,source TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(device_id,thread_id));
   CREATE TABLE IF NOT EXISTS session_catchup_presented(device_id TEXT NOT NULL,event_seq INTEGER NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(device_id,event_seq));
   CREATE TABLE IF NOT EXISTS session_catchup_gaps(thread_id TEXT PRIMARY KEY,through_seq INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS session_catchup_captures(capture_id INTEGER PRIMARY KEY AUTOINCREMENT,token TEXT UNIQUE NOT NULL,device_id TEXT NOT NULL,thread_id TEXT NOT NULL,cursor INTEGER NOT NULL,payload TEXT NOT NULL,at INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS session_catchup_baselines(device_id TEXT NOT NULL,thread_id TEXT NOT NULL,capture_id INTEGER NOT NULL,cursor INTEGER NOT NULL,payload TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(device_id,thread_id));
   CREATE TABLE IF NOT EXISTS session_catchup_imports(notification_id INTEGER PRIMARY KEY,event_seq INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS session_catchup_interactions(device_id TEXT NOT NULL,thread_id TEXT NOT NULL,cursor INTEGER NOT NULL,working INTEGER NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(device_id,thread_id));
   CREATE TABLE IF NOT EXISTS session_catchup_state(thread_id TEXT PRIMARY KEY,working INTEGER NOT NULL,at INTEGER NOT NULL);`);
 }
 put(threadId,{key,kind,text,turnId=null,requestId=null,pending=false,at=this.clock()}) {
  text=clean(text);if(!threadId||!text||this.hidden(threadId))return null;
  // Content hash versions updated answers/questions while replayed events stay idempotent.
  const logicalKey=threadId+':'+key+':'+hash(text);
  const existing=this.db.prepare('SELECT seq FROM session_catchup_events WHERE logical_key=?').get(logicalKey);
  if(existing)return Number(existing.seq);
  const duplicate=this.db.prepare('SELECT seq FROM session_catchup_events WHERE thread_id=? AND kind=? AND text=? AND turn_id IS ? AND request_id IS ? AND resolved=0 ORDER BY seq DESC LIMIT 1').get(threadId,kind,text,turnId,requestId===null?null:String(requestId));
  if(duplicate)return Number(duplicate.seq);
  if(requestId)this.db.prepare('UPDATE session_catchup_events SET resolved=1 WHERE thread_id=? AND request_id=?').run(threadId,String(requestId));
  const row=this.db.prepare('INSERT INTO session_catchup_events(thread_id,logical_key,kind,text,turn_id,request_id,pending,at) VALUES(?,?,?,?,?,?,?,?)').run(threadId,logicalKey,kind,text,turnId,requestId===null?null:String(requestId),pending?1:0,at);
  const seq=Number(row.lastInsertRowid);this.syncBaselines(threadId,seq);this.prune(threadId);return seq;
 }
 observe(event) {
  const p=event.params||event,method=event.method||'',threadId=p.threadId||p.thread?.id;
  if(!threadId||this.hidden(threadId))return;
  if(method==='turn/started'||method==='turn/completed')this.db.prepare('INSERT INTO session_catchup_state(thread_id,working,at) VALUES(?,?,?) ON CONFLICT(thread_id) DO UPDATE SET working=excluded.working,at=excluded.at').run(threadId,method==='turn/started'?1:0,this.clock());
  const record=item=>{
   if(item?.type!=='agentMessage')return;
   const interim=interimExcerpt(item),final=item.phase!=='commentary'?clean(item.text||item._pocketRow?.text):null;
   const text=interim||final;if(!text)return;
   return this.put(threadId,{key:'assistant:'+String(item.id||hash(text)),kind:interim?'answer':'result',text,turnId:p.turnId||p.turn?.id});
  };
  if(method==='item/completed')record(p.item);
  if(method==='turn/completed'){
   for(const item of p.turn?.items||[])record(item);
   const status=p.turn?.status,turnId=p.turn?.id||p.turnId;
   if(status==='completed')this.put(threadId,{key:'turn:'+turnId,kind:'completed',text:'Work completed.',turnId});
   else if((status==='failed'&&!this.recoverableFailure?.(threadId,turnId))||status==='interrupted')this.put(threadId,{key:'turn:'+turnId,kind:status==='failed'?'failure':'blocked',text:clean(p.turn?.error?.message)|| (status==='failed'?'Work failed.':'Work stopped before completion.'),turnId});
  }
  if(/requestUserInput|requestApproval/.test(method)){
   const requestId=String(event.id??p.requestId??'');
   const text=p.questions?.map(q=>q.question).join('\n')||p.reason||p.message||'Your decision is needed.';
   this.put(threadId,{key:'request:'+requestId,kind:/requestUserInput/.test(method)?'question':'approval',text,requestId,pending:true,turnId:p.turnId});
  }
 }
 recordNotification(n) {
  const threadId=n.threadId||n.thread_id,kind=n.kind;
  if(!['question','approval','error','complete','completed','result','answer'].includes(kind))return null;
  const text=n.text||n.body,turnId=n.turnId||n.source_turn_id,requestId=n.requestId||n.request_id;
  // Normalized source identities keep notification/event delivery idempotent.
  return this.put(threadId,{key:requestId?'request:'+requestId:'notification:'+n.id,kind:kind==='error'?'failure':['complete','completed'].includes(kind)?'result':kind,text,turnId:turnId||null,requestId:requestId||null,pending:['question','approval'].includes(kind),at:n.created_at??n.at??this.clock()});
 }
 recordNote(threadId,n) {return this.put(threadId,{key:'assistant:'+n.id,kind:'answer',text:n.text,turnId:n.turnId});}
 resolve(requestId) {this.db.prepare('UPDATE session_catchup_events SET resolved=1 WHERE request_id=?').run(String(requestId));}
 cursor(threadId) {return Number(this.db.prepare('SELECT MAX(seq) AS seq FROM session_catchup_events WHERE thread_id=?').get(threadId)?.seq||0);}
 acknowledge(device,threadId,cursor,{source='read'}={}) {
  const id=deviceId(device);
  if(!id||!threadId||!Number.isSafeInteger(cursor)||cursor<0||!['read','interaction','coordinator'].includes(source))throw invalid('Invalid catch-up acknowledgement.');
  const belongs=cursor===0||this.db.prepare('SELECT 1 FROM session_catchup_events WHERE thread_id=? AND seq=?').get(threadId,cursor);
  if(!belongs||cursor>this.cursor(threadId))throw invalid('Invalid catch-up acknowledgement.');
  this.db.prepare('INSERT INTO session_catchup_reads(device_id,thread_id,cursor,source,at) VALUES(?,?,?,?,?) ON CONFLICT(device_id,thread_id) DO UPDATE SET cursor=MAX(session_catchup_reads.cursor,excluded.cursor),source=excluded.source,at=excluded.at').run(id,threadId,cursor,source,this.clock());
  return {threadId,cursor};
 }
 presented(device,items) {
  const id=deviceId(device);if(!id||!Array.isArray(items))throw invalid('Invalid presented catch-up items.');
  const rows=items.map(item=>{
   const seq=item?.seq??item?.id;if(!Number.isSafeInteger(seq)||!item.threadId)throw invalid('Invalid catch-up item.');
   const row=this.db.prepare('SELECT seq,thread_id FROM session_catchup_events WHERE seq=?').get(seq);
   if(!row||row.thread_id!==item.threadId)throw invalid('Catch-up item does not belong to this session.');return row;
  });
  for(const row of rows)this.db.prepare('INSERT OR IGNORE INTO session_catchup_presented(device_id,event_seq,at) VALUES(?,?,?)').run(id,row.seq,this.clock());
  return {presented:rows.length};
 }
 async snapshot(device,{threadIds,limit=20}={}) {
  const id=deviceId(device);if(!id)throw invalid('A device is required for catch-up.');
  limit=Math.max(1,Math.min(100,Number(limit)||20));
  const allowed=threadIds?new Set(threadIds):null;
  const threads=this.db.prepare('SELECT thread_id,MAX(seq) AS cursor FROM session_catchup_events GROUP BY thread_id ORDER BY cursor DESC').all();
  const sessions=[],attention=[];
  for(const thread of threads){
   const threadId=thread.thread_id;if(this.hidden(threadId)||allowed&&!allowed.has(threadId))continue;
   const interaction=this.db.prepare('SELECT cursor,working,at FROM session_catchup_interactions WHERE device_id=? AND thread_id=?').get(id,threadId);
   const read=this.db.prepare('SELECT cursor FROM session_catchup_reads WHERE device_id=? AND thread_id=?').get(id,threadId)?.cursor||0;
   const pendingRows=this.db.prepare('SELECT e.*,p.event_seq AS presented FROM session_catchup_events e LEFT JOIN session_catchup_presented p ON p.device_id=? AND p.event_seq=e.seq WHERE e.thread_id=? AND e.pending=1 AND e.resolved=0 ORDER BY e.seq DESC').all(id,threadId);
   for(const row of pendingRows){
    if(this.resolvePending&&await this.resolvePending(row.request_id,threadId)===false){this.resolve(row.request_id);continue;}
    attention.push({id:Number(row.seq),seq:Number(row.seq),threadId,kind:row.kind,text:row.text,requestId:row.request_id,at:row.at,pending:true,needsAttention:true,unseen:row.seq>read&&!row.presented});
   }
   const rows=this.db.prepare(`SELECT e.*,p.event_seq AS presented FROM session_catchup_events e LEFT JOIN session_catchup_presented p ON p.device_id=? AND p.event_seq=e.seq WHERE e.thread_id=? AND e.seq>? AND p.event_seq IS NULL AND e.resolved=0 ORDER BY e.seq DESC`).all(id,threadId,read);
   const items=[];let unseenCount=0;
   for(const row of rows){
    if(row.pending&&this.resolvePending&&await this.resolvePending(row.request_id,threadId)===false){this.resolve(row.request_id);continue;}
    // A final answer explains completion; avoid a second generic line for that same turn.
    if(row.kind==='completed'&&this.db.prepare("SELECT 1 FROM session_catchup_events WHERE thread_id=? AND kind='result' AND turn_id IS ? LIMIT 1").get(threadId,row.turn_id))continue;
    unseenCount++;
    if(items.length<12)items.push({id:Number(row.seq),seq:Number(row.seq),threadId,kind:row.kind,text:row.text,turnId:row.turn_id,requestId:row.request_id,at:row.at,unseen:true,pending:!!row.pending,afterLastInteraction:!!interaction&&row.seq>interaction.cursor});
   }
   const baseline=this.db.prepare('SELECT payload,at FROM session_catchup_baselines WHERE device_id=? AND thread_id=?').get(id,threadId);
   const baselineData=baseline?JSON.parse(baseline.payload):null;
   const gap=this.db.prepare('SELECT through_seq FROM session_catchup_gaps WHERE thread_id=?').get(threadId);
   const missing=!!gap&&Math.max(read,baseline?.cursor||0)<gap.through_seq;
   if(!items.length&&!missing)continue;
   sessions.push({threadId,cursor:Number(thread.cursor),items,unseenCount,working:!!this.db.prepare('SELECT working FROM session_catchup_state WHERE thread_id=?').get(threadId)?.working,gap:missing?'Older updates exceeded retained catch-up history. Open this session for full context.':null,lastInteractionAt:interaction?.at||null,workingWhenInteracted:!!interaction?.working,watchedAt:baseline?.at||null,workingWhenChecked:!!baselineData?.working,firstCheck:!baseline&&!this.db.prepare('SELECT 1 FROM session_catchup_reads WHERE device_id=? AND thread_id=?').get(id,threadId)});
   if(sessions.length>=limit)break;
  }
  return {sessions,attention:attention.slice(0,100),at:this.clock()};
 }
 // Retain only public answers plus turn/request identities; never command output or reasoning.
 material(response) {
  let rows=response?.timeline?.rows||response?.rows;
  if(!rows){rows=[];for(const turn of response?.thread?.turns||response?.turns||[]){
   rows.push({kind:'turn',turnId:turn.id,status:turn.status,startedAt:turn.startedAt});
   for(const item of turn.items||[])if(item.type==='agentMessage')rows.push({kind:'message',type:item.type,itemId:item.id,turnId:turn.id,phase:item.phase,text:item.text||item._pocketRow?.text,status:item.status});
   if(turn.status!=='inProgress')rows.push({kind:'turnEnd',turnId:turn.id,status:turn.status,text:turn.error?.message||'',at:turn.completedAt});
  }}
  const items=[],turns=[];
  for(const row of rows.slice(-500)){
   if(row.kind==='turn'||row.kind==='turnEnd'){const old=turns.find(t=>t.id===row.turnId);const status=row.status||'completed';if(old){old.status=status;if(row.text)old.error=clean(row.text);}else turns.push({id:row.turnId,status,error:clean(row.text),at:row.at||row.completedAt||row.startedAt});continue;}
   if(row.kind==='message'&&row.type==='agentMessage'){
    const text=row.phase==='commentary'?interimExcerpt({type:'agentMessage',phase:row.phase,text:row.text}):clean(row.text);
    if(text&&row.status!=='inProgress')items.push({id:row.itemId||String(row.id||'').split('/').slice(1).join('/'),turnId:row.turnId,kind:row.phase==='commentary'?'answer':'result',text});
   }
   if(row.kind==='request'&&row.request){const m=row.request,p=m.params||{};items.push({id:'request:'+m.id,turnId:row.turnId,kind:/requestUserInput/.test(m.method)?'question':'approval',text:clean(p.questions?.map(q=>q.question).join('\n')||p.reason||p.message||'Your decision is needed.'),requestId:String(m.id),pending:true});}
  }
  for(const note of response?.notes||[])if(note.id&&note.text&&!items.some(i=>i.id===note.id))items.push({id:note.id,turnId:note.turnId,kind:'answer',text:clean(note.text),at:note.at,note:true});
  for(const m of response?.pending||[]){if(items.some(x=>x.requestId===String(m.id)))continue;const p=m.params||{};items.push({id:'request:'+m.id,turnId:p.turnId,kind:/requestUserInput/.test(m.method)?'question':'approval',text:clean(p.questions?.map(q=>q.question).join('\n')||p.reason||p.message||'Your decision is needed.'),requestId:String(m.id),pending:true});}
  return {items:items.slice(-500),turns:turns.slice(-100),working:turns.some(t=>t.status==='inProgress')||(response?.thread?.status||response?.status)?.type==='active',hasEarlier:!!(response?.timeline?.hasEarlier||response?._pocketPage?.hasEarlier)};
 }
 represented(event,payload){
  if(payload.items.some(i=>(i.textHash?i.textHash===hash(event.text):i.text===event.text)&&i.kind===event.kind&&i.turnId===event.turn_id))return true;
  const turn=payload.turns.find(t=>t.id===event.turn_id);
  return !!turn&&(event.kind==='completed'&&turn.status==='completed'||event.kind==='failure'&&turn.status==='failed'&&(!turn.error||turn.error===event.text)||event.kind==='blocked'&&turn.status==='interrupted');
 }
 syncBaselines(threadId,seq){
  const event=this.db.prepare('SELECT * FROM session_catchup_events WHERE seq=?').get(seq);if(!event)return;
  for(const row of this.db.prepare('SELECT device_id,payload FROM session_catchup_baselines WHERE thread_id=?').all(threadId))if(this.represented(event,JSON.parse(row.payload)))this.db.prepare('INSERT OR IGNORE INTO session_catchup_presented(device_id,event_seq,at) VALUES(?,?,?)').run(row.device_id,seq,this.clock());
 }
 capture(device,threadId,response){
  const id=deviceId(device);if(!id||!threadId||this.hidden(threadId))throw invalid('Invalid catch-up capture.');
  const token=randomUUID(),cursor=this.cursor(threadId),at=this.clock(),material=this.material(response);
  const payload={...material,items:material.items.map(({text,...item})=>({...item,textHash:hash(text)}))};
  this.db.prepare('INSERT INTO session_catchup_captures(token,device_id,thread_id,cursor,payload,at) VALUES(?,?,?,?,?,?)').run(token,id,threadId,cursor,JSON.stringify(payload),at);
  this.db.prepare('DELETE FROM session_catchup_captures WHERE at<?').run(at-600000);
  this.db.prepare('DELETE FROM session_catchup_captures WHERE device_id=? AND capture_id NOT IN (SELECT capture_id FROM session_catchup_captures WHERE device_id=? ORDER BY capture_id DESC LIMIT 64)').run(id,id);
  return {cursor,token};
 }
 acknowledgeCapture(device,threadId,token){
  const id=deviceId(device),row=this.db.prepare('SELECT * FROM session_catchup_captures WHERE token=? AND device_id=? AND thread_id=?').get(String(token||''),id,threadId);
  if(!row||row.at<this.clock()-600000)throw invalid('Catch-up capture expired or does not belong to this session.');
  const previous=this.db.prepare('SELECT capture_id FROM session_catchup_baselines WHERE device_id=? AND thread_id=?').get(id,threadId);
  if(previous?.capture_id>row.capture_id)throw Object.assign(Error('A newer conversation view has already been acknowledged.'),{status:409});
  this.db.prepare('INSERT INTO session_catchup_baselines(device_id,thread_id,capture_id,cursor,payload,at) VALUES(?,?,?,?,?,?) ON CONFLICT(device_id,thread_id) DO UPDATE SET capture_id=excluded.capture_id,cursor=excluded.cursor,payload=excluded.payload,at=excluded.at').run(id,threadId,row.capture_id,row.cursor,row.payload,row.at);
  const payload=JSON.parse(row.payload);
  for(const event of this.db.prepare('SELECT * FROM session_catchup_events WHERE thread_id=?').all(threadId))if(this.represented(event,payload))this.db.prepare('INSERT OR IGNORE INTO session_catchup_presented(device_id,event_seq,at) VALUES(?,?,?)').run(id,event.seq,this.clock());
  return {threadId,cursor:row.cursor,watchedAt:row.at,workingWhenChecked:payload.working};
 }
 hydrate(device,threadId,response){
  const id=deviceId(device),current=this.material(response),baseline=this.db.prepare('SELECT payload,at FROM session_catchup_baselines WHERE device_id=? AND thread_id=?').get(id,threadId);
  if(!baseline)return {firstCheck:true,uncertain:true,watchedAt:null,workingWhenChecked:false,imported:0,gap:null,context:current.items.slice(-6)};
  const before=JSON.parse(baseline.payload),prior=new Map(before.items.map(i=>[i.id,i])),priorTurns=new Map(before.turns.map(t=>[t.id,t]));
  const overlap=current.turns.some(t=>priorTurns.has(t.id))||current.items.some(i=>prior.has(i.id));
  let imported=0;
  const lastKnownItem=current.items.reduce((last,item,index)=>prior.has(item.id)?index:last,-1);
  const lastKnownTurn=current.turns.reduce((last,turn,index)=>priorTurns.has(turn.id)?index:last,-1);
  for(const [index,item] of current.items.entries()){const old=prior.get(item.id);if(old&&(old.textHash?old.textHash===hash(item.text):old.text===item.text)&&old.kind===item.kind)continue;
   if(!old&&!item.pending&&index<lastKnownItem&&!item.note)continue;
   if(!old&&item.note&&item.at&&item.at<=baseline.at)continue;
   const seq=this.put(threadId,{key:(item.requestId?'request:':'assistant:')+(item.requestId||item.id),kind:item.kind,text:item.text,turnId:item.turnId,requestId:item.requestId||null,pending:item.pending||false});if(seq)imported++;
  }
  for(const [index,turn] of current.turns.entries()){const old=priorTurns.get(turn.id);if(!old&&index<lastKnownTurn)continue;if(old?.status===turn.status||turn.status==='inProgress')continue;
   if(turn.status==='failed'&&this.recoverableFailure?.(threadId,turn.id))continue;
   if(['completed','failed','interrupted'].includes(turn.status))this.put(threadId,{key:'turn:'+turn.id,kind:turn.status==='failed'?'failure':turn.status==='interrupted'?'blocked':'completed',text:turn.error||(turn.status==='failed'?'Work failed.':turn.status==='interrupted'?'Work stopped before completion.':'Work completed.'),turnId:turn.id});
  }
  this.db.prepare('INSERT INTO session_catchup_state(thread_id,working,at) VALUES(?,?,?) ON CONFLICT(thread_id) DO UPDATE SET working=excluded.working,at=excluded.at').run(threadId,current.working?1:0,this.clock());
  if(!overlap&&current.hasEarlier&&before.turns.length)this.db.prepare('INSERT INTO session_catchup_gaps(thread_id,through_seq) VALUES(?,?) ON CONFLICT(thread_id) DO UPDATE SET through_seq=MAX(through_seq,excluded.through_seq)').run(threadId,Math.max(1,this.cursor(threadId)));
  return {firstCheck:false,watchedAt:baseline.at,workingWhenChecked:before.working,imported,gap:!overlap&&current.hasEarlier?'Recent history does not overlap your last checked view. Some intervening updates may be missing.':null};
 }
 interact(device,threadId){
  const id=deviceId(device);if(!id||!threadId||this.hidden(threadId))throw invalid('Invalid session interaction.');
  const state=this.db.prepare('SELECT working FROM session_catchup_state WHERE thread_id=?').get(threadId);
  const baseline=this.db.prepare('SELECT payload FROM session_catchup_baselines WHERE device_id=? AND thread_id=?').get(id,threadId);
  const discovered=this.db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='pocket_discovered_threads'").get()?this.db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE thread_id=?').get(threadId):null;
  const status=discovered?JSON.parse(discovered.metadata).status?.type:null;
  const working=status==='active'?true:status==='idle'?false:state?!!state.working:!!(baseline&&JSON.parse(baseline.payload).working);
  const cursor=this.cursor(threadId),at=this.clock();
  this.db.prepare('INSERT INTO session_catchup_interactions(device_id,thread_id,cursor,working,at) VALUES(?,?,?,?,?) ON CONFLICT(device_id,thread_id) DO UPDATE SET cursor=excluded.cursor,working=excluded.working,at=excluded.at').run(id,threadId,cursor,working?1:0,at);
  return {threadId,lastInteractionAt:at,workingWhenInteracted:working};
 }
 checkedThreadIds(device,{limit=40}={}){
  const id=deviceId(device);if(!id)throw invalid('A device is required for catch-up.');
  return this.db.prepare('SELECT thread_id FROM session_catchup_baselines WHERE device_id=? ORDER BY at DESC LIMIT ?').all(id,Math.max(1,Math.min(100,Number(limit)||40))).map(row=>row.thread_id).filter(threadId=>!this.hidden(threadId));
 }
 presentedNotification(device,notificationId,threadId){
  const n=this.db.prepare('SELECT * FROM notifications WHERE id=? AND thread_id=?').get(notificationId,threadId);
  if(!n)throw invalid('Notification does not belong to this session.');
  // A completed playback proves only the configured spoken passage was heard.
  // Explicit short summaries must never hide the unspoken result or blocker.
  const normalize=value=>cleanSpeech(value).normalize('NFKC').toLocaleLowerCase('en');
  const body=normalize(n.body),speech=normalize(n.spoken_text||n.spoken_summary||spokenText(n.title,n.body));
  const prefix=speech.endsWith(body)?speech.slice(0,speech.length-body.length):null;
  const omittedDetails=/```|`[^`]+`|https?:\/\/|(?:^|\s)(?:\/?[\w.-]+\/){2,}\S*/m.test(String(n.body||''));
  const full=!omittedDetails&&!!body&&(speech===body||prefix!==null&&prefix.length<=200&&/\s$/.test(prefix));
  if(!full)return {heard:true,presented:0,coverage:'summary'};
  const attention=this.db.prepare('SELECT request_id FROM notification_attention WHERE notification_id=?').get(notificationId);
  const seq=this.db.prepare('SELECT event_seq FROM session_catchup_imports WHERE notification_id=?').get(notificationId)?.event_seq||this.recordNotification({...n,requestId:attention?.request_id});
  if(!seq)return {presented:0};
  return {...this.presented(device,[{threadId,seq:Number(seq)}]),heard:true,coverage:'full'};
 }
 bootstrap(){
  const exists=name=>!!this.db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").get(name);
  if(!exists('notifications'))return {imported:0};
  const hasAttention=exists('notification_attention'),hasReads=exists('notification_reads');let imported=0;
  const rows=this.db.prepare('SELECT * FROM (SELECT * FROM notifications ORDER BY id DESC LIMIT 5000) ORDER BY id').all();
  for(const n of rows){const old=this.db.prepare('SELECT event_seq FROM session_catchup_imports WHERE notification_id=?').get(n.id);if(old)continue;
   const attention=hasAttention?this.db.prepare('SELECT request_id,resolved_at FROM notification_attention WHERE notification_id=?').get(n.id):null;
   const seq=this.recordNotification({...n,requestId:attention?.request_id});if(!seq)continue;imported++;
   this.db.prepare('INSERT INTO session_catchup_imports(notification_id,event_seq) VALUES(?,?)').run(n.id,seq);
   if(attention?.resolved_at!=null)this.db.prepare('UPDATE session_catchup_events SET resolved=1 WHERE seq=? AND pending=1').run(seq);
   if(hasReads)for(const read of this.db.prepare('SELECT device_id FROM notification_reads WHERE thread_id=? AND through_id>=?').all(n.thread_id,n.id))this.db.prepare('INSERT OR IGNORE INTO session_catchup_presented(device_id,event_seq,at) VALUES(?,?,?)').run(read.device_id,seq,n.created_at);
  }
  return {imported,truncated:!!this.db.prepare('SELECT 1 FROM notifications WHERE id<? LIMIT 1').get(rows[0]?.id||0)};
 }
 prune(threadId) {
  const stale=this.db.prepare('SELECT seq FROM session_catchup_events WHERE thread_id=? AND (pending=0 OR resolved=1) ORDER BY seq DESC LIMIT -1 OFFSET ?').all(threadId,this.maxPerThread);
  if(!stale.length)return;
  const through=Math.max(...stale.map(x=>Number(x.seq)));
  this.db.prepare('INSERT INTO session_catchup_gaps(thread_id,through_seq) VALUES(?,?) ON CONFLICT(thread_id) DO UPDATE SET through_seq=MAX(through_seq,excluded.through_seq)').run(threadId,through);
  this.db.prepare('DELETE FROM session_catchup_presented WHERE event_seq IN (SELECT seq FROM session_catchup_events WHERE thread_id=? AND seq<=? AND (pending=0 OR resolved=1))').run(threadId,through);
  this.db.prepare('DELETE FROM session_catchup_events WHERE thread_id=? AND seq<=? AND (pending=0 OR resolved=1)').run(threadId,through);
 }
}
