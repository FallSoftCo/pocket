import {createHash} from 'node:crypto';
const minute=60000,hour=60*minute;
const idOf=device=>typeof device==='string'?device:device?.id;
const fail=message=>Object.assign(new Error(message),{status:400});
const ms=value=>{const n=Number(value)||0;return n>0&&n<1e11?n*1000:n;};
const label=status=>status==='active'?'Working':status==='idle'?'Idle':status==='pending'?'Starting':status==='notLoaded'?'Not loaded':/error|failed/i.test(status||'')?'Error':'Unable to verify';
const clip=(value,max=420)=>{const text=String(value||'').trim();return text.length<=max?{text,full:true}:{text:text.slice(0,max).trimEnd()+'… Open the session for the full update.',full:false};};
/** Deterministic observed-material check-ins. Scheduling never submits work or marks it read. */
export class CoordinatorReports {
 constructor({db,catchup,clock=Date.now,readThread=async()=>{throw Error('Status verification is unavailable.');},publish=async()=>{},hidden=()=>false,refreshRoster=async()=>({complete:false,reason:'Only previously observed work is available.'})}){
  Object.assign(this,{db,catchup,clock,readThread,publish,hidden,refreshRoster});this.busy=false;this.lastScope=null;
  db.exec(`CREATE TABLE IF NOT EXISTS coordinator_reporting(device_id TEXT PRIMARY KEY,enabled INTEGER NOT NULL DEFAULT 0,interval_minutes INTEGER NOT NULL DEFAULT 30,stale_after_minutes INTEGER NOT NULL DEFAULT 120,next_due_at INTEGER,last_report_at INTEGER);
   CREATE TABLE IF NOT EXISTS coordinator_report_seen(device_id TEXT NOT NULL,report_id TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(device_id,report_id));
   CREATE TABLE IF NOT EXISTS coordinator_reporting_inventory(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS coordinator_report_runs(device_id TEXT NOT NULL,window_key TEXT NOT NULL,report_id TEXT NOT NULL UNIQUE,state TEXT NOT NULL,payload TEXT,at INTEGER NOT NULL,PRIMARY KEY(device_id,window_key));
   CREATE TABLE IF NOT EXISTS coordinator_report_offered(device_id TEXT NOT NULL,event_seq INTEGER NOT NULL,report_id TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(device_id,event_seq));
   CREATE TABLE IF NOT EXISTS coordinator_report_check_offered(device_id TEXT NOT NULL,thread_id TEXT NOT NULL,checked_at INTEGER NOT NULL,report_id TEXT NOT NULL,PRIMARY KEY(device_id,thread_id,checked_at));
   CREATE TABLE IF NOT EXISTS coordinator_report_checks(thread_id TEXT PRIMARY KEY,checked_at INTEGER NOT NULL,status TEXT NOT NULL,detail TEXT NOT NULL,offered_at INTEGER NOT NULL DEFAULT 0);
   CREATE TABLE IF NOT EXISTS voice_turns(device TEXT,id TEXT,hash TEXT,state TEXT,transcript TEXT,response TEXT,error TEXT,actions TEXT DEFAULT '[]',created_at INTEGER,PRIMARY KEY(device,id));`);
  const inventory=db.prepare('SELECT payload FROM coordinator_reporting_inventory WHERE id=1').get();if(inventory)this.lastScope=JSON.parse(inventory.payload);
 }
 validDevice(device){return this.exists('devices')&&!!this.db.prepare('SELECT 1 FROM devices WHERE id=?').get(idOf(device));}
 settings(device){const id=idOf(device);if(!id)throw fail('A device is required.');const row=this.db.prepare('SELECT * FROM coordinator_reporting WHERE device_id=?').get(id);
  return {enabled:this.validDevice(id)&&!!row?.enabled,intervalMinutes:row?.interval_minutes||30,staleAfterMinutes:row?.stale_after_minutes||120,nextDueAt:this.validDevice(id)&&row?.enabled?row.next_due_at:null,lastReportAt:row?.last_report_at||null,mode:'observed-material',automaticTaskMutation:false,inventory:this.lastScope||{complete:false,state:'not-checked'}};
 }
 configure(device,changes={}){
  const id=idOf(device),before=this.settings(id);if(!this.validDevice(id))throw Object.assign(Error('Pair a device before enabling reports.'),{status:403});if(!changes||typeof changes!=='object'||Array.isArray(changes))throw fail('Invalid reporting preferences.');
  const allowed=new Set(['enabled','intervalMinutes','cadenceMinutes','staleAfterMinutes']);if(Object.keys(changes).some(key=>!allowed.has(key)))throw fail('Unsupported reporting preference.');
  if(changes.enabled!==undefined&&typeof changes.enabled!=='boolean')throw fail('Reporting enabled must be a boolean.');
  if(changes.intervalMinutes!==undefined&&changes.cadenceMinutes!==undefined&&changes.intervalMinutes!==changes.cadenceMinutes)throw fail('Choose one reporting interval.');
  const interval=changes.intervalMinutes??changes.cadenceMinutes??before.intervalMinutes,stale=changes.staleAfterMinutes??before.staleAfterMinutes,enabled=changes.enabled??before.enabled;
  if(!Number.isInteger(interval)||interval<5||interval>1440)throw fail('Choose a reporting interval of 5–1440 minutes.');
  if(!Number.isInteger(stale)||stale<30||stale>10080)throw fail('Choose a stale-work interval of 30–10080 minutes.');
  const due=enabled?(!before.enabled?this.clock():interval!==before.intervalMinutes?this.clock()+interval*minute:before.nextDueAt??this.clock()):null;
  this.db.prepare('INSERT INTO coordinator_reporting(device_id,enabled,interval_minutes,stale_after_minutes,next_due_at) VALUES(?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET enabled=excluded.enabled,interval_minutes=excluded.interval_minutes,stale_after_minutes=excluded.stale_after_minutes,next_due_at=excluded.next_due_at').run(id,enabled?1:0,interval,stale,due);
  return this.settings(id);
 }
 inbox(device,{before=Number.MAX_SAFE_INTEGER,limit=40}={}){
  const id=idOf(device);if(!this.validDevice(id))throw Object.assign(Error('Pair a device to read work updates.'),{status:403});
  if(!Number.isSafeInteger(before)||before<=0||!Number.isInteger(limit)||limit<1||limit>100)throw fail('Invalid work updates cursor.');
  const select=`SELECT r.rowid AS cursor,r.report_id,r.payload,r.at,s.at AS seen_at FROM coordinator_report_runs r LEFT JOIN coordinator_report_seen s ON s.device_id=r.device_id AND s.report_id=r.report_id WHERE r.device_id=? AND r.state IN ('prepared','published')`;
  const decode=row=>{if(!row)return null;const p=JSON.parse(row.payload);return {id:row.report_id,cursor:row.cursor,response:p.response,actions:p.actions||[],createdAt:row.at,unread:row.seen_at==null};};
  const rows=this.db.prepare(select+' AND r.rowid<? ORDER BY r.rowid DESC LIMIT ?').all(id,before,limit+1);
  const latest=decode(this.db.prepare(select+' ORDER BY r.rowid DESC LIMIT 1').get(id));
  const unreadCount=this.db.prepare("SELECT COUNT(*) AS n FROM coordinator_report_runs r LEFT JOIN coordinator_report_seen s ON s.device_id=r.device_id AND s.report_id=r.report_id WHERE r.device_id=? AND r.state IN ('prepared','published') AND s.report_id IS NULL").get(id).n;
  return {reports:rows.slice(0,limit).map(decode).reverse(),latest,unreadCount,hasEarlier:rows.length>limit,before:rows[Math.min(rows.length,limit)-1]?.cursor??null};
 }
 markPresented(device,reportId){
  const id=idOf(device);if(!this.db.prepare("SELECT 1 FROM coordinator_report_runs WHERE device_id=? AND report_id=? AND state IN ('prepared','published')").get(id,reportId))return false;
  this.db.prepare('INSERT OR IGNORE INTO coordinator_report_seen(device_id,report_id,at) VALUES(?,?,?)').run(id,reportId,this.clock());return true;
 }
 context(device,{limit=3}={}){
  const id=idOf(device);if(!id||!this.validDevice(id))return [];
  return this.db.prepare("SELECT report_id,payload,at FROM coordinator_report_runs WHERE device_id=? AND state IN ('prepared','published') AND at>=? ORDER BY at DESC LIMIT ?").all(id,this.clock()-24*hour,Math.max(1,Math.min(3,Number(limit)||3))).map(row=>{const p=JSON.parse(row.payload);return {id:row.report_id,response:p.response.slice(0,2400),createdAt:row.at,origin:'report',scope:p.scope||null,sources:p.actions.filter(a=>a.type==='route').map(({threadId,name,status,manualAskUpdate})=>({threadId,name,status,manualAskUpdate}))};});
 }
 exists(table){return !!this.db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").get(table);}
 metadata(){if(!this.exists('pocket_discovered_threads'))return new Map();return new Map(this.db.prepare("SELECT thread_id,metadata FROM pocket_discovered_threads WHERE archived=0 ORDER BY CASE WHEN json_extract(metadata,'$.status.type')='active' THEN 0 ELSE 1 END,seen_at DESC LIMIT 1200").all().map(row=>[row.thread_id,JSON.parse(row.metadata)]).filter(([id])=>!this.hidden(id)));}
 staleCandidates(device,metadata,staleAfter,{respectCooldown=true}={}){
  const cutoff=this.clock()-staleAfter*minute,candidates=new Map();
  if(this.exists('session_catchup_interactions'))for(const row of this.db.prepare('SELECT thread_id,cursor,at FROM session_catchup_interactions WHERE device_id=? AND at<=? ORDER BY at DESC LIMIT 100').all(device,cutoff)){
   if(this.hidden(row.thread_id))continue;
   const last=this.db.prepare('SELECT MAX(at) AS at FROM session_catchup_events WHERE thread_id=?').get(row.thread_id)?.at||0;
   const stopped=this.db.prepare("SELECT 1 FROM session_catchup_events WHERE thread_id=? AND seq>? AND kind IN ('result','completed','failure','blocked','question','approval') LIMIT 1").get(row.thread_id,row.cursor);
   if(!stopped&&Math.max(row.at,last)<=cutoff)candidates.set(row.thread_id,{threadId:row.thread_id,at:Math.max(row.at,last),origin:'accepted-guidance'});
  }
  for(const [threadId,thread] of metadata){if(thread.status?.type!=='active')continue;
   const last=this.db.prepare('SELECT MAX(at) AS at FROM session_catchup_events WHERE thread_id=?').get(threadId)?.at||0;
   const activity=Math.max(ms(thread.activityAt),ms(thread.updatedAt),last);if(activity>0&&activity<=cutoff&&!candidates.has(threadId))candidates.set(threadId,{threadId,at:activity,origin:thread.parentThreadId?'known-child':'known-live-work'});
  }
  return [...candidates.values()].filter(c=>{const old=this.db.prepare('SELECT checked_at FROM coordinator_report_checks WHERE thread_id=?').get(c.threadId);return !respectCooldown||!old||this.clock()-old.checked_at>=4*hour;}).sort((a,b)=>a.at-b.at);
 }
 async collect(device,prefs,budget,scope){
  const metadata=this.metadata();for(const thread of scope?.threads||[])if(!this.hidden(thread.id))metadata.set(thread.id,thread);
  const snapshot=await this.catchup.snapshot(device,{limit:100});
  const sections=[],actions=[],offered=[],checks=[],known=new Set();let remaining=2200;
  const priority={question:0,approval:0,failure:1,blocked:1,result:2,answer:3,completed:4};
  const groups=snapshot.sessions.map(group=>({...group,items:group.items.filter(item=>!this.db.prepare('SELECT 1 FROM coordinator_report_offered WHERE device_id=? AND event_seq=?').get(device,item.seq))})).filter(group=>group.items.length).sort((a,b)=>Math.min(...a.items.map(i=>priority[i.kind]??5))-Math.min(...b.items.map(i=>priority[i.kind]??5))||b.cursor-a.cursor);
  for(const group of groups.slice(0,5)){
   const name=String(metadata.get(group.threadId)?.name||metadata.get(group.threadId)?.preview||'Work session').slice(0,100),lines=[],presented=[];
   for(const item of [...group.items].sort((a,b)=>(priority[a.kind]??5)-(priority[b.kind]??5)||b.seq-a.seq).slice(0,2)){
    if(remaining<100)break;const excerpt=clip(item.text,Math.min(420,remaining-80)),prefix=item.pending?'Needs your answer':item.kind==='failure'?'Failed':item.kind==='blocked'?'Stopped':item.kind==='answer'?'Progress':'Result';
    const line=prefix+': '+excerpt.text;lines.push(line);remaining-=line.length;offered.push(item.seq);if(excerpt.full)presented.push({threadId:group.threadId,id:item.id,seq:item.seq});
   }
   if(!lines.length)continue;known.add(group.threadId);sections.push(name+'\n'+lines.join('\n'));
   if(presented.length)actions.push({type:'catchup',items:presented});
   actions.push({type:'route',threadId:group.threadId,name,operation:'read',state:'observed',origin:'report',manualAskUpdate:false});
  }
  for(const candidate of this.staleCandidates(device,metadata,prefs.stale_after_minutes)){
   if(budget.remaining<=0||!this.validDevice(device)||!this.settings(device).enabled)break;budget.remaining--;
   // Claim the read cooldown before I/O so concurrent/restarted workers cannot poll repeatedly.
   const checkedAt=this.clock(),old=this.db.prepare('SELECT checked_at FROM coordinator_report_checks WHERE thread_id=?').get(candidate.threadId);
   const claimed=old?this.db.prepare("UPDATE coordinator_report_checks SET checked_at=?,status='checking',detail='' WHERE thread_id=? AND checked_at=?").run(checkedAt,candidate.threadId,old.checked_at):this.db.prepare("INSERT OR IGNORE INTO coordinator_report_checks(thread_id,checked_at,status,detail) VALUES(?,?,'checking','')").run(candidate.threadId,checkedAt);
   if(!claimed.changes)continue;let status='unverified',detail='';
   try{const response=await this.readThread(candidate.threadId),thread=response?.thread||response;if(thread?.id!==candidate.threadId)throw Error('Session identity could not be verified.');status=thread.status?.type||'unverified';detail='Latest checked state: '+label(status)+'.';}
   catch(error){detail='Status could not be verified. '+String(error.message||'The workstation is unavailable.').slice(0,220);}
   this.db.prepare('UPDATE coordinator_report_checks SET status=?,detail=? WHERE thread_id=? AND checked_at=?').run(status,detail,candidate.threadId,checkedAt);
  }
  // Reuse factual read results across devices/restarts without another status RPC.
  for(const candidate of this.staleCandidates(device,metadata,prefs.stale_after_minutes,{respectCooldown:false})){
   if(known.has(candidate.threadId)||remaining<120||sections.length>=5)continue;
   const check=this.db.prepare('SELECT * FROM coordinator_report_checks WHERE thread_id=?').get(candidate.threadId);
   if(!check||this.clock()-check.checked_at>4*hour||this.db.prepare('SELECT 1 FROM coordinator_report_check_offered WHERE device_id=? AND thread_id=? AND checked_at=?').get(device,candidate.threadId,check.checked_at))continue;
   const name=String(metadata.get(candidate.threadId)?.name||'Work session').slice(0,100),status=check.status==='checking'?'unverified':check.status,detail=check.status==='checking'?'The last status check was interrupted; current status is unverified.':check.detail;
   const text='No material update for at least '+Math.floor((this.clock()-candidate.at)/minute)+' minutes. '+detail+' Quiet alone does not establish a failure.';
   sections.push(name+'\n'+text);remaining-=text.length;checks.push({threadId:candidate.threadId,checkedAt:check.checked_at});actions.push({type:'route',threadId:candidate.threadId,name,operation:'read',state:'checked',status,origin:'report',manualAskUpdate:true});
  }
  const publicScope=scope?{complete:!!scope.complete,unverified:!!scope.unverified,truncated:!!scope.truncated,threadCount:scope.threadCount??scope.threads?.length??null,nextCursor:scope.nextCursor||null,reason:scope.reason||null}:null;
  const coverage=publicScope&&!publicScope.complete?'\n\nCoverage is partial: '+(publicScope.reason||'More sessions remain outside this bounded inventory check.') : '';
  return {response:sections.length?'Check-in\n\n'+sections.join('\n\n')+coverage:'',actions,offered,checks,scope:publicScope};
 }
 async send(row){const prefs=this.settings(row.device_id);if(!prefs.enabled||!this.validDevice(row.device_id))return false;const payload=JSON.parse(row.payload);await this.publish({device:row.device_id,id:row.report_id,...payload,createdAt:row.at});
  this.db.prepare("UPDATE coordinator_report_runs SET state='published' WHERE report_id=? AND state='prepared'").run(row.report_id);this.db.prepare('UPDATE coordinator_reporting SET last_report_at=? WHERE device_id=?').run(this.clock(),row.device_id);return true;
 }
 async tick(){
  if(this.busy)return {busy:true,reports:0};this.busy=true;let reports=0;const budget={remaining:3};
  try{
   for(const pref of this.db.prepare('SELECT device_id FROM coordinator_reporting WHERE enabled=1').all())if(!this.validDevice(pref.device_id)){this.db.prepare('UPDATE coordinator_reporting SET enabled=0,next_due_at=NULL WHERE device_id=?').run(pref.device_id);this.db.prepare("UPDATE coordinator_report_runs SET state='cancelled' WHERE device_id=? AND state IN ('collecting','prepared')").run(pref.device_id);}
   let scope=null;
   for(const row of this.db.prepare("SELECT * FROM coordinator_report_runs WHERE state='prepared' ORDER BY at LIMIT 20").all())try{if(await this.send(row))reports++;}catch{/* Persisted prepared report retries on the next tick. */}
   const now=this.clock(),prefsRows=this.db.prepare('SELECT * FROM coordinator_reporting WHERE enabled=1 ORDER BY next_due_at LIMIT 20').all();
   for(const prefs of prefsRows){
    let run=this.db.prepare("SELECT * FROM coordinator_report_runs WHERE device_id=? AND state='collecting' ORDER BY at LIMIT 1").get(prefs.device_id);
    if(!run){if(prefs.next_due_at>now)continue;const windowKey=prefs.interval_minutes+'-'+Math.floor(now/(prefs.interval_minutes*minute)),reportId='report-'+createHash('sha256').update(prefs.device_id).digest('hex').slice(0,12)+'-'+windowKey;
     this.db.exec('BEGIN IMMEDIATE');try{const claimed=this.db.prepare('UPDATE coordinator_reporting SET next_due_at=? WHERE device_id=? AND enabled=1 AND next_due_at=?').run(now+prefs.interval_minutes*minute,prefs.device_id,prefs.next_due_at);
      if(claimed.changes)this.db.prepare("INSERT OR IGNORE INTO coordinator_report_runs(device_id,window_key,report_id,state,at) VALUES(?,?,?,'collecting',?)").run(prefs.device_id,windowKey,reportId,now);this.db.exec('COMMIT');}catch(error){this.db.exec('ROLLBACK');throw error;}
     run=this.db.prepare("SELECT * FROM coordinator_report_runs WHERE report_id=? AND state='collecting'").get(reportId);if(!run)continue;
    }
    if(!scope){try{scope=await this.refreshRoster();}catch{scope={complete:false,unverified:true,reason:'Fresh inventory could not be verified; only previously observed work is available.'};}this.lastScope={complete:!!scope.complete,unverified:!!scope.unverified,truncated:!!scope.truncated,threadCount:scope.threadCount??scope.threads?.length??null,nextCursor:scope.nextCursor||null,checkedAt:this.clock(),reason:scope.reason||null};this.db.prepare('INSERT INTO coordinator_reporting_inventory(id,payload) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload').run(JSON.stringify(this.lastScope));}
    const payload=await this.collect(prefs.device_id,prefs,budget,scope);
    if(!this.validDevice(prefs.device_id)||!this.settings(prefs.device_id).enabled){this.db.prepare("UPDATE coordinator_report_runs SET state='cancelled' WHERE report_id=? AND state='collecting'").run(run.report_id);continue;}
    if(!payload.response){this.db.prepare("UPDATE coordinator_report_runs SET state='skipped' WHERE report_id=?").run(run.report_id);continue;}
    this.db.exec('BEGIN IMMEDIATE');try{
     for(const check of payload.checks||[])this.db.prepare('INSERT OR IGNORE INTO coordinator_report_check_offered(device_id,thread_id,checked_at,report_id) VALUES(?,?,?,?)').run(prefs.device_id,check.threadId,check.checkedAt,run.report_id);
     for(const seq of payload.offered)this.db.prepare('INSERT OR IGNORE INTO coordinator_report_offered(device_id,event_seq,report_id,at) VALUES(?,?,?,?)').run(prefs.device_id,seq,run.report_id,now);
     this.db.prepare("INSERT OR IGNORE INTO voice_turns(device,id,hash,state,transcript,response,actions,created_at) VALUES(?,?,?,'completed','',?,?,?)").run(prefs.device_id,run.report_id,createHash('sha256').update(payload.response).digest('hex'),payload.response,JSON.stringify(payload.actions),run.at);
     this.db.prepare("UPDATE coordinator_report_runs SET state='prepared',payload=? WHERE report_id=? AND state='collecting'").run(JSON.stringify(payload),run.report_id);this.db.exec('COMMIT');
    }catch(error){this.db.exec('ROLLBACK');throw error;}
    const prepared=this.db.prepare('SELECT * FROM coordinator_report_runs WHERE report_id=?').get(run.report_id);try{if(await this.send(prepared))reports++;}catch{/* Retry without re-generating or treating it as seen. */}
   }
   return {reports,statusChecks:3-budget.remaining};
  }finally{this.busy=false;}
 }
}
