import {randomUUID} from 'node:crypto';
export const RECOVERY_INPUT='Continue the existing task from its preserved progress. First reconcile the conversation, files, tool results and any external actions already taken. Do not replay the original request or repeat submissions, applications, messages, purchases or charges. If an external action has an uncertain outcome, inspect its status without repeating it; report the uncertainty and stop for user guidance if it cannot be established. Keep the existing model, permissions and task constraints.';
export function transientFailure(error){
 const text=[error?.message,error?.additionalDetails].filter(Boolean).join('\n');
 if(/application network permission was revoked|application network policy is unavailable/i.test(text))return 'permission';
 if(error?.codexErrorInfo==='serverOverloaded'||/selected model is at capacity|server overloaded|temporarily unavailable|connection reset|stream disconnected|request timed out|network policy is unavailable/i.test(text))return 'transient';
 if(/compact/i.test(text)&&/network|timeout|capacity|temporar|server error/i.test(text))return 'transient';
 return null;
}
export function permissionRecoveryAudit(inherited){if(!inherited?.sandbox?.type||!inherited?.activePermissionProfile?.id)return 'waiting';return inherited.sandbox.type==='dangerFullAccess'&&inherited.activePermissionProfile.id===':danger-full-access'?'verified':'blocked';}
export class TurnRecovery {
 constructor({db,read,start,health,stop=async()=>{},publish=()=>{},clock=Date.now,random=Math.random,quickAttempts=4}){
  Object.assign(this,{db,read,start,health,stop,publish,clock,random,quickAttempts});this.busy=false;
  db.exec(`CREATE TABLE IF NOT EXISTS turn_recovery(thread_id TEXT PRIMARY KEY,source_turn TEXT NOT NULL,current_turn TEXT NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_at INTEGER NOT NULL,request_id TEXT,kind TEXT,error TEXT,notice INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL)`);
  db.exec('CREATE TABLE IF NOT EXISTS turn_recovery_failures(thread_id TEXT NOT NULL,turn_id TEXT NOT NULL,PRIMARY KEY(thread_id,turn_id))');
  db.prepare("INSERT OR IGNORE INTO turn_recovery_failures SELECT thread_id,source_turn FROM turn_recovery").run();
  db.prepare("INSERT OR IGNORE INTO turn_recovery_failures SELECT thread_id,current_turn FROM turn_recovery WHERE state IN ('waiting','blocked')").run();
  if(!db.prepare('PRAGMA table_info(turn_recovery)').all().some(column=>column.name==='last_notice'))db.exec('ALTER TABLE turn_recovery ADD COLUMN last_notice TEXT');
  db.prepare("UPDATE turn_recovery SET state='unknown' WHERE state='dispatching'").run();
  db.prepare("UPDATE turn_recovery SET next_at=MIN(next_at,?) WHERE state='running'").run(this.clock()+30000);
  db.prepare("UPDATE turn_recovery SET state='waiting',next_at=? WHERE state='exhausted'").run(this.clock()+this.delay(this.quickAttempts));
 }
 recoverableFailure(id,turnId){if(!id||!turnId)return false;return !!this.db.prepare('SELECT 1 FROM turn_recovery_failures WHERE thread_id=? AND turn_id=?').get(id,turnId);}
 get(id){return this.db.prepare('SELECT * FROM turn_recovery WHERE thread_id=?').get(id);}
 delay(n){const base=n<this.quickAttempts?30000*2**n:30*60000*2**Math.min(n-this.quickAttempts,3);return Math.min(4*3600000,base*(.8+.4*this.random()));}
 observeMissed(thread,checkpoint,alreadySeen=false){
  const latest=thread.turns?.at(-1);if(!checkpoint||alreadySeen||latest?.status!=='failed')return false;
  const at=Number(latest.completedAt)*1000;if((at>0&&at<checkpoint.since)||(!checkpoint.initialized&&!(at>=checkpoint.since)))return false;
  return this.observe(thread.id,latest);
 }
 observe(threadId,turn){
  if(!threadId||!turn?.id)return false;const row=this.get(threadId);
  if(row?.state==='cancelled'&&[row.current_turn,row.source_turn].includes(turn.id))return false;
  if(row&&row.current_turn===turn.id&&turn.status==='completed'){this.set(threadId,'completed');return false;}
  if(turn.status==='interrupted'){if(row&&[row.current_turn,row.source_turn].includes(turn.id))this.cancel(threadId);return false;}
  if(turn.status==='failed'&&!transientFailure(turn.error)){if(row?.current_turn===turn.id&&row.state!=='cancelled')this.set(threadId,'blocked');return false;}
  if(turn.status!=='failed')return false;
  if(row&&['cancelled','blocked','reconcile'].includes(row.state)&&[row.current_turn,row.source_turn].includes(turn.id))return false;
  if(row&&row.current_turn!==turn.id&&row.source_turn!==turn.id&&!['completed','cancelled','blocked'].includes(row.state))return false;
  if(row&&row.source_turn===turn.id)return false; // Repeated events/snapshots never reset a budget.
  if(row&&row.current_turn===turn.id&&row.state==='waiting')return true;
  if(row&&row.current_turn===turn.id){
   this.db.prepare("UPDATE turn_recovery SET state='waiting',kind=?,error=?,next_at=?,updated_at=? WHERE thread_id=?").run(transientFailure(turn.error),turn.error?.message||'',this.clock()+this.delay(row.attempts),this.clock(),threadId);
  }else this.db.prepare("INSERT INTO turn_recovery(thread_id,source_turn,current_turn,state,next_at,kind,error,updated_at) VALUES(?,?,?,'waiting',?,?,?,?) ON CONFLICT(thread_id) DO UPDATE SET source_turn=excluded.source_turn,current_turn=excluded.current_turn,state='waiting',attempts=0,next_at=excluded.next_at,request_id=NULL,kind=excluded.kind,error=excluded.error,notice=0,last_notice=NULL,updated_at=excluded.updated_at").run(threadId,turn.id,turn.id,this.clock()+this.delay(0),transientFailure(turn.error),turn.error?.message||'',this.clock());
  this.db.prepare('INSERT OR IGNORE INTO turn_recovery_failures(thread_id,turn_id) VALUES(?,?)').run(threadId,turn.id);
  return true;
 }
 set(id,state){this.db.prepare('UPDATE turn_recovery SET state=?,updated_at=? WHERE thread_id=?').run(state,this.clock(),id);this.publish(id,this.get(id));}
 cancel(id){if(this.get(id))this.set(id,'cancelled');}
 notice(id,text){if(this.db.prepare('UPDATE turn_recovery SET notice=notice+1,last_notice=? WHERE thread_id=? AND (last_notice IS NULL OR last_notice!=?)').run(text,id,text).changes)this.publish(id,{...this.get(id),message:text});}
 async tick(){
  if(this.busy)return;this.busy=true;
  try{for(const row of this.db.prepare("SELECT * FROM turn_recovery WHERE state IN ('waiting','unknown','running') AND next_at<=? ORDER BY next_at LIMIT 3").all(this.clock())){
   try{
    const thread=await this.read(row.thread_id),latest=thread.turns?.at(-1);
    if(!latest){this.db.prepare('UPDATE turn_recovery SET attempts=attempts+1,next_at=? WHERE thread_id=? AND state=? AND current_turn=? AND request_id IS ?').run(this.clock()+this.delay(row.attempts+1),row.thread_id,row.state,row.current_turn,row.request_id);continue;}
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
    if(latest.id!==row.current_turn||latest.status!=='failed'){this.cancel(row.thread_id);continue;}
    const health=await this.health(row.thread_id,row.kind,thread);
    if(!health||health.ok===false){
     const attempts=row.attempts+1;
     const changed=this.db.prepare('UPDATE turn_recovery SET attempts=?,state=?,next_at=?,updated_at=? WHERE thread_id=? AND state=\'waiting\' AND current_turn=? AND source_turn=?').run(attempts,health?.blocked?'blocked':'waiting',Math.max(this.clock()+this.delay(attempts),health?.nextAt||0),this.clock(),row.thread_id,row.current_turn,row.source_turn);
     if(changed.changes&&health?.blocked)this.notice(row.thread_id,health.reason||'Recovery is paused because the intended permissions or a usage-stop policy requires user guidance.');continue;
    }
    const id=randomUUID();
    if(!this.db.prepare("UPDATE turn_recovery SET state='dispatching',request_id=?,attempts=attempts+1,updated_at=? WHERE thread_id=? AND state='waiting' AND current_turn=? AND source_turn=?").run(id,this.clock(),row.thread_id,row.current_turn,row.source_turn).changes)continue;
    try{
     const result=await this.start(row.thread_id,id,RECOVERY_INPUT);
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
     this.db.prepare('UPDATE turn_recovery SET next_at=? WHERE thread_id=?').run(this.clock()+this.delay(current.attempts),row.thread_id);
     // Unknown acknowledgement is checked once before presenting a genuine blocker.

    }
   }catch(error){const attempts=row.attempts+1;const updated=this.db.prepare('UPDATE turn_recovery SET attempts=?,state=?,next_at=? WHERE thread_id=? AND state=? AND current_turn=? AND source_turn=? AND request_id IS ?').run(attempts,row.state,this.clock()+this.delay(attempts),row.thread_id,row.state,row.current_turn,row.source_turn,row.request_id);}
  }}finally{this.busy=false;}
 }
}
