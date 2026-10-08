import {createHash,randomUUID} from 'node:crypto';

const digest=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
const checked=(value,label)=>{if(typeof value!=='string'||!value.trim()||value.length>8000)throw Error(`Invalid ${label}`);return value;};

/** Transactional admission kernel, deliberately not a model runner.
 * Trusted adapters supply accepted work, runtime observations and verified results.
 * A worker checkpoint is a proposal, never a new admission event.
 */
export class WorkAdmission {
 constructor({db,clock=Date.now,maxConcurrent=2,snapshotMaxAge=10000}){
  if(!Number.isInteger(maxConcurrent)||maxConcurrent<1)throw Error('Invalid concurrency limit');
  Object.assign(this,{db,clock,maxConcurrent,snapshotMaxAge});
  db.exec(`
   CREATE TABLE IF NOT EXISTS work_units(
    id TEXT PRIMARY KEY,task_id TEXT NOT NULL,thread_id TEXT NOT NULL,instruction TEXT NOT NULL,
    acceptance TEXT NOT NULL,state TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,
    dependency_key TEXT,dependency_version TEXT,created_at INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS work_admission_events(
    unit_id TEXT NOT NULL,event_id TEXT NOT NULL,source TEXT NOT NULL,payload_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL,payload TEXT NOT NULL,consumed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(unit_id,event_id));
   CREATE TABLE IF NOT EXISTS work_admission_attempts(
    id TEXT PRIMARY KEY,unit_id TEXT NOT NULL,task_id TEXT NOT NULL,thread_id TEXT NOT NULL,
    state TEXT NOT NULL,turn_id TEXT,evidence TEXT,created_at INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS work_dependency_generations(
    unit_id TEXT NOT NULL,dependency_key TEXT NOT NULL,dependency_version TEXT NOT NULL,
    PRIMARY KEY(unit_id,dependency_key,dependency_version));
   CREATE UNIQUE INDEX IF NOT EXISTS work_active_task ON work_admission_attempts(task_id)
    WHERE state IN ('dispatching','running','unknown','stopping');
   CREATE UNIQUE INDEX IF NOT EXISTS work_active_thread ON work_admission_attempts(thread_id)
    WHERE state IN ('dispatching','running','unknown','stopping');
   CREATE UNIQUE INDEX IF NOT EXISTS work_active_unit ON work_admission_attempts(unit_id)
    WHERE state IN ('dispatching','running','unknown','stopping');
  `);
 }
 atomic(fn){this.db.exec('BEGIN IMMEDIATE');try{const value=fn();this.db.exec('COMMIT');return value;}catch(error){this.db.exec('ROLLBACK');throw error;}}
 unit(id){return this.db.prepare('SELECT * FROM work_units WHERE id=?').get(id);}
 attempt(id){return this.db.prepare('SELECT * FROM work_admission_attempts WHERE id=?').get(id);}
 event(unitId,eventId,source,payload){
  checked(eventId,'event identity');
  const payloadHash=digest(payload),old=this.db.prepare('SELECT * FROM work_admission_events WHERE unit_id=? AND event_id=?').get(unitId,eventId);
  if(old){if(old.source!==source||old.payload_hash!==payloadHash)throw Error('Conflicting event identity');return false;}
  this.db.prepare('INSERT INTO work_admission_events(unit_id,event_id,source,payload_hash,created_at,payload) VALUES(?,?,?,?,?,?)').run(unitId,eventId,source,payloadHash,this.clock(),JSON.stringify(payload));return true;
 }
 // Only the authorized task owner accepts a bounded unit with an acceptance
 // contract. No observer automatically enrolls idle/history/child threads.
 accept({id,taskId,threadId,instruction,acceptance,eventId,source}){
  if(!['owner','user'].includes(source))throw Error('Work requires owner/user acceptance');
  for(const [label,value] of Object.entries({id,taskId,threadId,instruction,acceptance}))checked(value,label);
  return this.atomic(()=>{
   const payload={id,taskId,threadId,instruction,acceptance};
   const existing=this.unit(id);
   if(existing){const fresh=this.event(id,eventId,source,payload);if(fresh)throw Error('Work identity already accepted');return existing;}
   this.event(id,eventId,source,payload);
   this.db.prepare("INSERT INTO work_units(id,task_id,thread_id,instruction,acceptance,state,created_at) VALUES(?,?,?,?,?,'ready',?)").run(id,taskId,threadId,instruction,acceptance,this.clock());return this.unit(id);
  });
 }
 // A native-complete turn releases compute; it does NOT establish that the
 // requested artifact/result meets the task's acceptance contract.
 settleAttempt(id,{turnId,status,receiptId,effectsReconciled=false}){
  return this.atomic(()=>{
   const a=this.attempt(id);if(!a||a.turn_id!==turnId||!['completed','failed','interrupted'].includes(status))return false;
   if(!this.event(a.unit_id,receiptId,'runtime',{attemptId:id,turnId,status,effectsReconciled}))return true;
   if(!['running','stopping','unknown'].includes(a.state))return false;
   if(effectsReconciled!==true){
    this.db.prepare("UPDATE work_admission_attempts SET state='unknown',evidence='Native turn ended; external effects not yet reconciled' WHERE id=?").run(id);
    if(this.unit(a.unit_id)?.state!=='stopping')this.db.prepare("UPDATE work_units SET state='unknown',revision=revision+1 WHERE id=?").run(a.unit_id);
    return true;
   }
   this.db.prepare('UPDATE work_admission_attempts SET state=? WHERE id=?').run(status,id);
   const state=this.unit(a.unit_id)?.state;
   if(state==='stopping')this.db.prepare("UPDATE work_units SET state='cancelled',revision=revision+1 WHERE id=?").run(a.unit_id);
   else if(state!=='cancelled')this.db.prepare('UPDATE work_units SET state=?,revision=revision+1 WHERE id=?').run(status==='completed'?'awaitingResult':status==='failed'?'failed':'interrupted',a.unit_id);
   return true;
  });
 }
 wait(id,{revision,key,version}){
  checked(key,'dependency key');checked(version,'dependency version');
  return this.atomic(()=>{
   if(this.activeForUnit(id))return false;
   const changed=!!this.db.prepare("UPDATE work_units SET state='waiting',dependency_key=?,dependency_version=?,revision=revision+1 WHERE id=? AND revision=? AND state IN ('ready','awaitingResult')").run(key,version,id,revision).changes;
   if(changed)this.satisfyDependency(id);return changed;
  });
 }
 signal(id,{eventId,source,key,version}){
  if(!['owner','user','job'].includes(source))throw Error('Worker prose cannot wake work');
  return this.atomic(()=>{
   const u=this.unit(id);if(!u)throw Error('Unknown work');
   if(!this.event(id,eventId,source,{key,version}))return false;
   return this.satisfyDependency(id);
  });
 }
 satisfyDependency(id){
  const u=this.unit(id);if(u?.state!=='waiting')return false;
  if(this.db.prepare('SELECT 1 FROM work_dependency_generations WHERE unit_id=? AND dependency_key=? AND dependency_version=?').get(id,u.dependency_key,u.dependency_version))return false;
  const event=this.db.prepare("SELECT event_id FROM work_admission_events WHERE unit_id=? AND consumed=0 AND source IN ('owner','user','job') AND json_extract(payload,'$.key')=? AND json_extract(payload,'$.version')=? ORDER BY created_at,event_id LIMIT 1").get(id,u.dependency_key,u.dependency_version);
  if(!event)return false;
  this.db.prepare('INSERT INTO work_dependency_generations VALUES(?,?,?)').run(id,u.dependency_key,u.dependency_version);
  // Consume the whole satisfied generation, not just one transport message.
  // Several sources may deliver the same job completion with different IDs.
  this.db.prepare("UPDATE work_admission_events SET consumed=1 WHERE unit_id=? AND json_extract(payload,'$.key')=? AND json_extract(payload,'$.version')=?").run(id,u.dependency_key,u.dependency_version);
  return !!this.db.prepare("UPDATE work_units SET state='ready',revision=revision+1 WHERE id=? AND state='waiting' AND revision=?").run(id,u.revision).changes;
 }
 activeForUnit(id){return this.db.prepare("SELECT * FROM work_admission_attempts WHERE unit_id=? AND state IN ('dispatching','running','unknown','stopping')").get(id);}
 claim({complete,observedAt,activeThreadIds}){
  // Stale/partial inventory must not create capacity from missing observations.
  if(complete!==true||!Number.isFinite(observedAt)||observedAt>this.clock()||this.clock()-observedAt>this.snapshotMaxAge||!Array.isArray(activeThreadIds)||activeThreadIds.some(id=>typeof id!=='string'))return null;
  return this.atomic(()=>{
   const leases=this.db.prepare("SELECT * FROM work_admission_attempts WHERE state IN ('dispatching','running','unknown','stopping')").all();
   const occupied=new Set([...activeThreadIds,...leases.map(a=>a.thread_id)]);
   if(occupied.size>=this.maxConcurrent)return null;
   const candidate=this.db.prepare("SELECT * FROM work_units WHERE state='ready' ORDER BY created_at,id").all().find(u=>!occupied.has(u.thread_id)&&!leases.some(a=>a.task_id===u.task_id));
   if(!candidate)return null;
   const id=randomUUID();
   if(!this.db.prepare("UPDATE work_units SET state='dispatching',revision=revision+1 WHERE id=? AND revision=? AND state='ready'").run(candidate.id,candidate.revision).changes)return null;
   this.db.prepare("INSERT INTO work_admission_attempts(id,unit_id,task_id,thread_id,state,created_at) VALUES(?,?,?,?,'dispatching',?)").run(id,candidate.id,candidate.task_id,candidate.thread_id,this.clock());return this.attempt(id);
  });
 }
 accepted(id,turnId){
  checked(turnId,'turn identity');
  return this.atomic(()=>{
   const a=this.attempt(id);if(!a)return false;
   if(a.turn_id)return a.turn_id===turnId&&['running','stopping'].includes(a.state);
   if(!['dispatching','unknown','stopping'].includes(a.state))return false;
   const stopping=a.state==='stopping';
   this.db.prepare('UPDATE work_admission_attempts SET state=?,turn_id=? WHERE id=?').run(stopping?'stopping':'running',turnId,id);
   if(!stopping)this.db.prepare("UPDATE work_units SET state='running',revision=revision+1 WHERE id=?").run(a.unit_id);return true;
  });
 }
 uncertain(id,evidence){return this.atomic(()=>{
  const a=this.attempt(id);if(!a||a.state!=='dispatching')return false;
  this.db.prepare("UPDATE work_admission_attempts SET state='unknown',evidence=? WHERE id=?").run(checked(evidence,'evidence'),id);
  this.db.prepare("UPDATE work_units SET state='unknown',revision=revision+1 WHERE id=?").run(a.unit_id);return true;
 });}
 // Called at owner startup: dispatch may have reached the runtime before the
 // process died. No TTL expiry or restart is proof that it is safe to resend.
 recoverDispatches(){return this.atomic(()=>{
  const rows=this.db.prepare("SELECT id FROM work_admission_attempts WHERE state='dispatching'").all();
  for(const {id} of rows){const a=this.attempt(id);this.db.prepare("UPDATE work_admission_attempts SET state='unknown',evidence='Owner restarted before delivery settlement' WHERE id=?").run(id);this.db.prepare("UPDATE work_units SET state='unknown',revision=revision+1 WHERE id=?").run(a.unit_id);}return rows.length;
 });}
 acceptResult(id,{revision,eventId,source,receipt}){
  if(!['owner','job'].includes(source))throw Error('A worker must propose a result to the acceptance adapter');
  checked(receipt,'verified result receipt');
  return this.atomic(()=>{
   const u=this.unit(id);if(!u||u.revision!==revision||u.state!=='awaitingResult'||this.activeForUnit(id))return false;
   if(!this.event(id,eventId,source,{receipt}))return false;
   return !!this.db.prepare("UPDATE work_units SET state='completed',revision=revision+1 WHERE id=? AND revision=?").run(id,revision).changes;
  });
 }
 cancel(id){return this.atomic(()=>{
  const u=this.unit(id);if(!u||u.state==='completed')return false;if(u.state==='cancelled')return true;const a=this.activeForUnit(id);
  if(a)this.db.prepare("UPDATE work_admission_attempts SET state='stopping' WHERE id=?").run(a.id);
  this.db.prepare('UPDATE work_units SET state=?,revision=revision+1 WHERE id=?').run(a?'stopping':'cancelled',id);return true;
 });}
}
