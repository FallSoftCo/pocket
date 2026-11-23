import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
export const RECOVERY_INPUT='Continue the existing task from its preserved progress. First reconcile the conversation, files, tool results and any external actions already taken. Do not replay the original request or repeat submissions, applications, messages, purchases or charges. If an external action has an uncertain outcome, inspect its status without repeating it; report the uncertainty and stop for user guidance if it cannot be established. Keep the existing model, permissions and task constraints.';
export const CONTINUATION_INPUT=RECOVERY_INPUT+' This task has durable continuation enabled. Keep working within this turn while authorized independent work remains; do not stop merely to give a progress report. Checkpoint continuation is a safety net when a turn must end, not a reason to end it. Before ending your turn, run node '+JSON.stringify(fileURLToPath(new URL('../scripts/task-checkpoint.mjs',import.meta.url)))+' --state continue --evidence "concrete remaining work" (use --state completed, --state needsInput, or --state waitingDependency as appropriate) to report continue if authorized work remains and can proceed independently, completed only when the requested outcome is achieved, needsInput for a genuine dependency on the user, or waitingDependency when progress requires another agent, owner clearance or an external state change. Do not use continue merely to recheck an unchanged inbox, service, hold or dependency; do not spend a model turn waiting for replies. Resume from a material external signal or cheap deterministic checks, not repeated model polling. Include concrete evidence and preserve external-action reconciliation. A partial progress report is not completion.';
export function dependencyOnlyCheckpoint(evidence){
 const text=String(evidence||'');
 return /no (?:new )?(?:owner )?(?:replies|clearance)|no replies after|unchanged (?:external )?dependency/i.test(text)
   && /wait|dependenc|coordination|clearance|expired.{0,30}unarmed/i.test(text)
   && !/(?:implemented|fixed|compiled|rendered|deployed|installed|submitted|new (?:result|artifact|owner reply|clearance)|independent work remains)/i.test(text);
}
export function transientFailure(error){
 const text=[error?.message,error?.additionalDetails].filter(Boolean).join('\n');
 if(/application network permission was revoked|application network policy is unavailable/i.test(text))return 'permission';
 // Definitive quota failures preserve the existing task and wait for account
 // health; this does not authorize purchases or override spending/task stops.
 if(error?.codexErrorInfo==='usageLimitExceeded'&&!/spend cap|spending limit|workspace.*credits|not included/i.test(text))return 'quota';
 if(error?.codexErrorInfo==='serverOverloaded'||/selected model is at capacity|server overloaded|temporarily unavailable|connection reset|stream disconnected|request timed out|network policy is unavailable/i.test(text))return 'transient';
 if(/no space left on device|no file descriptors available|too many open files|resource temporarily unavailable|\bENOSPC\b/i.test(text))return 'transient';
 if(/compact/i.test(text)&&/network|timeout|capacity|temporar|server error/i.test(text))return 'transient';
 return null;
}
export function permissionRecoveryAudit(inherited){if(!inherited?.sandbox?.type||!inherited?.activePermissionProfile?.id)return 'waiting';return inherited.sandbox.type==='dangerFullAccess'&&inherited.activePermissionProfile.id===':danger-full-access'?'verified':'blocked';}
export class TurnRecovery {
 constructor({db,read,start,health,stop=async()=>{},publish=()=>{},clock=Date.now,random=Math.random,quickAttempts=4}){
  Object.assign(this,{db,read,start,health,stop,publish,clock,random,quickAttempts});this.busy=false;
  db.exec(`CREATE TABLE IF NOT EXISTS turn_recovery(thread_id TEXT PRIMARY KEY,source_turn TEXT NOT NULL,current_turn TEXT NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_at INTEGER NOT NULL,request_id TEXT,kind TEXT,error TEXT,notice INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL)`);
  db.exec('CREATE TABLE IF NOT EXISTS turn_continuation_checkpoints(thread_id TEXT NOT NULL,turn_id TEXT NOT NULL,status TEXT NOT NULL,evidence TEXT NOT NULL,reported_at INTEGER NOT NULL,PRIMARY KEY(thread_id,turn_id))');
  db.exec('CREATE TABLE IF NOT EXISTS turn_continuation_authorizations(thread_id TEXT PRIMARY KEY,turn_id TEXT NOT NULL,evidence TEXT NOT NULL,authorized_at INTEGER NOT NULL)');
  if(!db.prepare('PRAGMA table_info(turn_continuation_authorizations)').all().some(column=>column.name==='audit_evidence')){db.exec('ALTER TABLE turn_continuation_authorizations ADD COLUMN audit_evidence TEXT');db.exec('UPDATE turn_continuation_authorizations SET audit_evidence=evidence');}
  db.exec('CREATE TABLE IF NOT EXISTS continuation_dependency_signals(thread_id TEXT NOT NULL,signal_id TEXT NOT NULL,PRIMARY KEY(thread_id,signal_id))');
  db.exec('CREATE TABLE IF NOT EXISTS continuation_policy(id INTEGER PRIMARY KEY CHECK(id=1),enabled INTEGER NOT NULL,reason TEXT NOT NULL,updated_at INTEGER NOT NULL)');
  db.exec('CREATE TABLE IF NOT EXISTS continuation_operator_holds(thread_id TEXT PRIMARY KEY,turn_id TEXT NOT NULL,evidence TEXT NOT NULL,at INTEGER NOT NULL)');
  db.exec('CREATE TABLE IF NOT EXISTS turn_recovery_failures(thread_id TEXT NOT NULL,turn_id TEXT NOT NULL,PRIMARY KEY(thread_id,turn_id))');
  db.exec('CREATE TABLE IF NOT EXISTS turn_recovery_migrations(name TEXT PRIMARY KEY)');
  if(!db.prepare("SELECT 1 FROM turn_recovery_migrations WHERE name='actual-failure-backfill'").get()){
   // Old pre-authorization retry rows represent failures. Registered continuation
   // rows do not: successful partial finals must stay visible in catch-up.
   db.prepare("INSERT OR IGNORE INTO turn_recovery_failures SELECT thread_id,source_turn FROM turn_recovery WHERE kind IN ('transient','permission') AND NOT EXISTS (SELECT 1 FROM turn_continuation_authorizations a WHERE a.thread_id=turn_recovery.thread_id)").run();
   db.prepare("INSERT OR IGNORE INTO turn_recovery_failures SELECT thread_id,current_turn FROM turn_recovery WHERE kind IN ('transient','permission') AND state IN ('waiting','blocked') AND NOT EXISTS (SELECT 1 FROM turn_continuation_authorizations a WHERE a.thread_id=turn_recovery.thread_id)").run();
   db.prepare("INSERT INTO turn_recovery_migrations(name) VALUES('actual-failure-backfill')").run();
  }
  if(!db.prepare('PRAGMA table_info(turn_recovery)').all().some(column=>column.name==='last_notice'))db.exec('ALTER TABLE turn_recovery ADD COLUMN last_notice TEXT');
  if(!db.prepare('PRAGMA table_info(turn_recovery)').all().some(column=>column.name==='backoff_attempts')){db.exec('ALTER TABLE turn_recovery ADD COLUMN backoff_attempts INTEGER NOT NULL DEFAULT 0');db.exec('UPDATE turn_recovery SET backoff_attempts=attempts');}
  db.prepare("UPDATE turn_recovery SET state='unknown' WHERE state='dispatching'").run();
  db.prepare("UPDATE turn_recovery SET next_at=MIN(next_at,?) WHERE state='running'").run(this.clock()+30000);
  db.prepare("UPDATE turn_recovery SET state='waiting',next_at=? WHERE state='exhausted'").run(this.clock()+this.delay(this.quickAttempts));
 }
 recoverableFailure(id,turnId){if(!id||!turnId)return false;return !!this.db.prepare('SELECT 1 FROM turn_recovery_failures WHERE thread_id=? AND turn_id=?').get(id,turnId);}
 get(id){return this.db.prepare('SELECT * FROM turn_recovery WHERE thread_id=?').get(id);}
 continuationEnabled(){return this.db.prepare('SELECT enabled FROM continuation_policy WHERE id=1').get()?.enabled!==0;}
 setContinuationEnabled(enabled,reason){this.db.prepare('INSERT INTO continuation_policy VALUES(1,?,?,?) ON CONFLICT(id) DO UPDATE SET enabled=excluded.enabled,reason=excluded.reason,updated_at=excluded.updated_at').run(enabled?1:0,String(reason),this.clock());}
 delay(n){const base=n<this.quickAttempts?30000*2**n:30*60000*2**Math.min(n-this.quickAttempts,3);return Math.min(4*3600000,base*(.8+.4*this.random()));}
 // Admission is deliberately evidence based: callers must read the latest target and
 // reconcile intent, questions and delivery before authorizing unfinished terminal work.
 authorizeContinuation(thread,assessment={}){
  const turn=thread?.turns?.at(-1),id=thread?.id;
  if(!this.continuationEnabled()&&turn?.status!=='failed')return false;
  const hold=this.db.prepare('SELECT * FROM continuation_operator_holds WHERE thread_id=?').get(id||'');
  const dependencySignal=typeof assessment.dependencySignal==='string'&&assessment.dependencySignal.trim().length>0;
  if(hold&&(!dependencySignal||this.db.prepare('SELECT 1 FROM continuation_dependency_signals WHERE thread_id=? AND signal_id=?').get(id,assessment.dependencySignal.trim())))return false;
  if(!id||!turn?.id||assessment.turnId!==turn.id||assessment.unfinished!==true||assessment.authorized!==true||!assessment.evidence?.trim())return false;
  if(['explicitStop','cancelled','needsInput','usageStopped','managedChild','uncertainDelivery','completed'].some(key=>assessment[key]===true))return false;
  if(!['interrupted','completed','failed'].includes(turn.status))return false;
  if(turn.status==='interrupted'&&assessment.runtimeInterrupted!==true)return false;
  if(turn.status==='failed'&&!transientFailure(turn.error))return false;
  const row=this.get(id);
  // An existing receipt, blocker or cancellation is not permission to send again.
  if(row&&((['unknown','dispatching','reconcile'].includes(row.state)||(row.state==='blocked'&&!(hold&&dependencySignal)))||(row.state==='cancelled'&&[row.source_turn,row.current_turn].includes(turn.id))||(row.state==='running'&&row.current_turn!==turn.id)))return false;
  if(row?.source_turn===turn.id&&row.state==='waiting')return true;
  if(hold&&dependencySignal){this.db.prepare('INSERT INTO continuation_dependency_signals VALUES(?,?)').run(id,assessment.dependencySignal.trim());this.db.prepare('DELETE FROM continuation_operator_holds WHERE thread_id=? AND turn_id=?').run(id,hold.turn_id);}
  const priorAudit=['running','awaitingAssessment'].includes(row?.state)?this.db.prepare('SELECT audit_evidence FROM turn_continuation_authorizations WHERE thread_id=?').get(id)?.audit_evidence:null;
  this.db.prepare('INSERT INTO turn_continuation_authorizations(thread_id,turn_id,evidence,audit_evidence,authorized_at) VALUES(?,?,?,?,?) ON CONFLICT(thread_id) DO UPDATE SET turn_id=excluded.turn_id,evidence=excluded.evidence,audit_evidence=excluded.audit_evidence,authorized_at=excluded.authorized_at').run(id,turn.id,assessment.evidence.trim(),priorAudit||assessment.evidence.trim(),this.clock());
  if(turn.status==='failed')return this.observe(id,turn);
  if(['running','awaitingAssessment'].includes(row?.state)&&row.current_turn===turn.id){
   // Successful, evidence-backed progress is not an outage: resume promptly.
   // Total attempts remain diagnostic; only the consecutive failure budget resets.
   const backoff=turn.status==='completed'?0:row.backoff_attempts;
   this.db.prepare("UPDATE turn_recovery SET state='waiting',kind='continuation',error='',backoff_attempts=?,next_at=?,updated_at=? WHERE thread_id=? AND state IN ('running','awaitingAssessment') AND current_turn=?").run(backoff,this.clock()+this.delay(backoff),this.clock(),id,turn.id);return this.get(id)?.state==='waiting';
  }
  this.db.prepare("INSERT INTO turn_recovery(thread_id,source_turn,current_turn,state,next_at,kind,error,updated_at) VALUES(?,?,?,'waiting',?,'continuation','',?) ON CONFLICT(thread_id) DO UPDATE SET source_turn=excluded.source_turn,current_turn=excluded.current_turn,state='waiting',attempts=0,backoff_attempts=0,next_at=excluded.next_at,request_id=NULL,kind='continuation',error='',notice=0,last_notice=NULL,updated_at=excluded.updated_at").run(id,turn.id,turn.id,this.clock()+this.delay(0),this.clock());
  return true;
 }
 recordCheckpoint(thread,{turnId,state,evidence}={}){
  const latest=thread?.turns?.at(-1);
  if(!thread?.id||latest?.id!==turnId)return false;
  const row=this.get(thread.id);
  // A worker may report before its start acknowledgement reaches this service.
  // Adopt only the exact persisted request receipt, never a guessed active turn.
  if(row&&['unknown','dispatching'].includes(row.state)&&row.request_id&&latest.items?.some(item=>item.clientId===row.request_id||item.clientUserMessageId===row.request_id)){
   this.db.prepare("UPDATE turn_recovery SET current_turn=?,state='running',next_at=?,updated_at=? WHERE thread_id=? AND state=? AND request_id=?").run(turnId,this.clock()+30*60000,this.clock(),thread.id,row.state,row.request_id);
  }
  const accepted=this.reportContinuation(thread.id,turnId,{status:state,evidence});
  if(accepted&&latest.status==='completed')this.observe(thread.id,latest);
  return accepted;
 }
 reportContinuation(threadId,turnId,{status,evidence}={}){
  const row=this.get(threadId);
  if(!row||row.current_turn!==turnId||row.state==='cancelled'||!['continue','completed','needsInput','waitingDependency'].includes(status)||!evidence?.trim())return false;
  if(!this.db.prepare('SELECT 1 FROM turn_continuation_authorizations WHERE thread_id=?').get(threadId))return false;
  const previous=this.db.prepare('SELECT status,evidence FROM turn_continuation_checkpoints WHERE thread_id=? AND turn_id=?').get(threadId,turnId);
  if(previous)return previous.status===status&&previous.evidence===evidence.trim();
  if(!['running','awaitingAssessment'].includes(row.state))return false;
  if(status==='continue'&&dependencyOnlyCheckpoint(evidence))status='waitingDependency';
  this.db.prepare('INSERT INTO turn_continuation_checkpoints(thread_id,turn_id,status,evidence,reported_at) VALUES(?,?,?,?,?)').run(threadId,turnId,status,evidence.trim(),this.clock());
  if(status==='waitingDependency'){this.holdDependency(threadId,turnId,evidence.trim());return true;}
  if(row.state==='awaitingAssessment')this.observe(threadId,{id:turnId,status:'completed'});
  return true;
 }
 observeMissed(thread,checkpoint,alreadySeen=false){
  const latest=thread.turns?.at(-1);if(!checkpoint||alreadySeen||latest?.status!=='failed')return false;
  const at=Number(latest.completedAt)*1000;if((at>0&&at<checkpoint.since)||(!checkpoint.initialized&&!(at>=checkpoint.since)))return false;
  return this.observe(thread.id,latest);
 }
 observe(threadId,turn,assessment){
  if(!threadId||!turn?.id)return false;const row=this.get(threadId);
  if(this.db.prepare('SELECT 1 FROM continuation_operator_holds WHERE thread_id=?').get(threadId))return false;
  if(row?.state==='cancelled'&&[row.current_turn,row.source_turn].includes(turn.id))return false;
  const authorization=this.db.prepare('SELECT turn_id FROM turn_continuation_authorizations WHERE thread_id=?').get(threadId);
  if(row?.state==='waiting'&&row.kind==='continuation'&&authorization?.turn_id===turn.id&&['completed','interrupted'].includes(turn.status))return true;
  if(turn.status==='interrupted'&&assessment&&this.authorizeContinuation({id:threadId,turns:[turn]},{...assessment,turnId:turn.id}))return true;
  if(row&&row.current_turn===turn.id&&turn.status==='completed'){
   if(authorization&&!this.continuationEnabled()){this.set(threadId,'awaitingAssessment');return false;}
   if(authorization){
    const checkpoint=this.db.prepare('SELECT * FROM turn_continuation_checkpoints WHERE thread_id=? AND turn_id=?').get(threadId,turn.id);
    if(checkpoint?.status==='continue')return this.authorizeContinuation({id:threadId,turns:[turn]},{turnId:turn.id,authorized:true,unfinished:true,evidence:checkpoint.evidence});
    if(checkpoint?.status==='needsInput'){this.db.prepare('UPDATE turn_recovery SET error=? WHERE thread_id=?').run(checkpoint.evidence,threadId);this.set(threadId,'blocked');return false;}
    if(!checkpoint){this.set(threadId,'awaitingAssessment');return false;}
   }
   this.set(threadId,'completed');return false;
  }
  if(turn.status==='interrupted'){if(row&&[row.current_turn,row.source_turn].includes(turn.id))this.cancel(threadId);return false;}
  if(turn.status==='failed'&&!transientFailure(turn.error)){if(row?.current_turn===turn.id&&row.state!=='cancelled')this.set(threadId,'blocked');return false;}
  if(turn.status!=='failed')return false;
  if(row&&['cancelled','blocked','reconcile'].includes(row.state)&&[row.current_turn,row.source_turn].includes(turn.id))return false;
  if(row&&row.current_turn!==turn.id&&row.source_turn!==turn.id&&!['completed','cancelled','blocked'].includes(row.state))return false;
  if(row&&row.source_turn===turn.id)return false; // Repeated events/snapshots never reset a budget.
  if(row&&row.current_turn===turn.id&&row.state==='waiting')return true;
  if(row&&row.current_turn===turn.id){
   this.db.prepare("UPDATE turn_recovery SET state='waiting',kind=?,error=?,next_at=?,updated_at=? WHERE thread_id=?").run(transientFailure(turn.error),turn.error?.message||'',this.clock()+this.delay(row.backoff_attempts),this.clock(),threadId);
  }else this.db.prepare("INSERT INTO turn_recovery(thread_id,source_turn,current_turn,state,next_at,kind,error,updated_at) VALUES(?,?,?,'waiting',?,?,?,?) ON CONFLICT(thread_id) DO UPDATE SET source_turn=excluded.source_turn,current_turn=excluded.current_turn,state='waiting',attempts=0,backoff_attempts=0,next_at=excluded.next_at,request_id=NULL,kind=excluded.kind,error=excluded.error,notice=0,last_notice=NULL,updated_at=excluded.updated_at").run(threadId,turn.id,turn.id,this.clock()+this.delay(0),transientFailure(turn.error),turn.error?.message||'',this.clock());
  this.db.prepare('INSERT OR IGNORE INTO turn_recovery_failures(thread_id,turn_id) VALUES(?,?)').run(threadId,turn.id);
  return true;
 }
 holdDependency(id,turnId,evidence){
  const row=this.get(id);if(!row||row.current_turn!==turnId||row.kind!=='continuation')return false;
  this.db.prepare('INSERT INTO continuation_operator_holds VALUES(?,?,?,?) ON CONFLICT(thread_id) DO UPDATE SET turn_id=excluded.turn_id,evidence=excluded.evidence,at=excluded.at').run(id,turnId,evidence,this.clock());
  this.db.prepare("UPDATE turn_recovery SET state='blocked',error=?,updated_at=? WHERE thread_id=? AND current_turn=?").run(evidence,this.clock(),id,turnId);return true;
 }
 set(id,state){this.db.prepare('UPDATE turn_recovery SET state=?,updated_at=? WHERE thread_id=?').run(state,this.clock(),id);this.publish(id,this.get(id));}
 cancel(id){this.db.prepare('DELETE FROM continuation_operator_holds WHERE thread_id=?').run(id);this.db.prepare('DELETE FROM turn_continuation_authorizations WHERE thread_id=?').run(id);if(this.get(id))this.set(id,'cancelled');}
 notice(id,text){if(this.db.prepare('UPDATE turn_recovery SET notice=notice+1,last_notice=? WHERE thread_id=? AND (last_notice IS NULL OR last_notice!=?)').run(text,id,text).changes)this.publish(id,{...this.get(id),message:text});}
 async tick(){
  if(this.busy)return;this.busy=true;
  try{for(const row of this.db.prepare("SELECT * FROM turn_recovery WHERE state IN ('waiting','unknown','running') AND next_at<=? ORDER BY next_at LIMIT 3").all(this.clock())){
   try{
    if(this.db.prepare('SELECT 1 FROM continuation_operator_holds WHERE thread_id=?').get(row.thread_id))continue;
    if(row.state==='waiting'&&row.kind==='continuation'&&!this.continuationEnabled()){this.set(row.thread_id,'awaitingAssessment');continue;}
    const thread=await this.read(row.thread_id),latest=thread.turns?.at(-1);
    if(!latest){this.db.prepare('UPDATE turn_recovery SET attempts=attempts+1,backoff_attempts=backoff_attempts+1,next_at=? WHERE thread_id=? AND state=? AND current_turn=? AND request_id IS ?').run(this.clock()+this.delay(row.backoff_attempts+1),row.thread_id,row.state,row.current_turn,row.request_id);continue;}
    if(row.state==='unknown'){
     const accepted=(thread.turns||[]).find(t=>t.items?.some(i=>i.clientId===row.request_id||i.clientUserMessageId===row.request_id));
     if(accepted){const changed=this.db.prepare("UPDATE turn_recovery SET current_turn=?,state='running',next_at=? WHERE thread_id=? AND state='unknown' AND request_id=?").run(accepted.id,this.clock()+30*60000,row.thread_id,row.request_id);if(changed.changes)this.observe(row.thread_id,accepted);else if(this.get(row.thread_id)?.state==='cancelled'&&accepted.status==='inProgress')await this.stop(row.thread_id,accepted.id);}
     else if(this.get(row.thread_id)?.state==='unknown'&&this.get(row.thread_id)?.request_id===row.request_id){this.set(row.thread_id,'reconcile');this.notice(row.thread_id,'Recovery delivery is uncertain. Review this conversation before retrying; nothing was sent again.');}continue;
    }
    if(row.state==='running'){
     if(latest.id===row.current_turn)this.observe(row.thread_id,latest);
     else this.cancel(row.thread_id);
     this.db.prepare('UPDATE turn_recovery SET next_at=? WHERE thread_id=? AND state=\'running\'').run(this.clock()+30*60000,row.thread_id);continue;
    }
    const admitted=this.db.prepare('SELECT turn_id FROM turn_continuation_authorizations WHERE thread_id=?').get(row.thread_id);
    const terminal=latest.status==='failed'||(row.kind==='continuation'&&admitted?.turn_id===latest.id&&['completed','interrupted'].includes(latest.status));
    if(latest.id!==row.current_turn||!terminal){this.cancel(row.thread_id);continue;}
    const health=await this.health(row.thread_id,row.kind,thread);
    if(!health||health.ok===false){
     const attempts=row.attempts+1,backoff=row.backoff_attempts+1;
     const changed=this.db.prepare('UPDATE turn_recovery SET attempts=?,backoff_attempts=?,state=?,next_at=?,updated_at=? WHERE thread_id=? AND state=\'waiting\' AND current_turn=? AND source_turn=?').run(attempts,backoff,health?.blocked?'blocked':'waiting',Math.max(this.clock()+this.delay(backoff),health?.nextAt||0),this.clock(),row.thread_id,row.current_turn,row.source_turn);
     if(changed.changes&&health?.blocked)this.notice(row.thread_id,health.reason||'Recovery is paused because the intended permissions or a usage-stop policy requires user guidance.');continue;
    }
    const id=randomUUID();
    if(row.kind==='continuation'&&!this.continuationEnabled()){this.set(row.thread_id,'awaitingAssessment');continue;}
    if(!this.db.prepare("UPDATE turn_recovery SET state='dispatching',request_id=?,attempts=attempts+1,backoff_attempts=backoff_attempts+1,updated_at=? WHERE thread_id=? AND state='waiting' AND current_turn=? AND source_turn=?").run(id,this.clock(),row.thread_id,row.current_turn,row.source_turn).changes)continue;
    try{
     const continuation=this.continuationEnabled()?this.db.prepare('SELECT evidence,audit_evidence FROM turn_continuation_authorizations WHERE thread_id=?').get(row.thread_id):null;
     const context=continuation?continuation.audit_evidence+(continuation.evidence!==continuation.audit_evidence?' Latest progress checkpoint: '+continuation.evidence:''):'';
     const input=continuation?CONTINUATION_INPUT+' Audited continuation context: '+context.slice(0,8000):RECOVERY_INPUT;
     const result=await this.start(row.thread_id,id,input);
     const current=this.get(row.thread_id);if(current.state==='cancelled'){
      try{await this.stop(row.thread_id,result.turn.id);}catch{this.notice(row.thread_id,'Cancellation could not be confirmed. Review the running session; recovery will not send anything else.');}
      continue;
     }
     this.db.prepare("UPDATE turn_recovery SET state='running',current_turn=?,next_at=?,updated_at=? WHERE thread_id=?").run(result.turn.id,this.clock()+30*60000,this.clock(),row.thread_id);
     this.observe(row.thread_id,result.turn);
     this.publish(row.thread_id,this.get(row.thread_id));
    }catch(error){
     const current=this.get(row.thread_id);if(current.state!=='dispatching'||current.request_id!==id)continue;
     const safe=!!error.rpc&&!!transientFailure({message:error.message});
     this.set(row.thread_id,safe?'waiting':'unknown');
     this.db.prepare('UPDATE turn_recovery SET next_at=? WHERE thread_id=?').run(this.clock()+this.delay(current.backoff_attempts),row.thread_id);
     // Unknown acknowledgement is checked once before presenting a genuine blocker.

    }
   }catch(error){const attempts=row.attempts+1,backoff=row.backoff_attempts+1;const updated=this.db.prepare('UPDATE turn_recovery SET attempts=?,backoff_attempts=?,state=?,next_at=? WHERE thread_id=? AND state=? AND current_turn=? AND source_turn=? AND request_id IS ?').run(attempts,backoff,row.state,this.clock()+this.delay(backoff),row.thread_id,row.state,row.current_turn,row.source_turn,row.request_id);}
  }}finally{this.busy=false;}
 }
}
