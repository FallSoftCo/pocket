// Durable dispatch boundary. A failed read is not an uncertain write. Only the
// original native response can resolve an observed mutation after its deadline.
export class ReplyDelivery {
 constructor(db,{clock=Date.now}={}){
  this.db=db;this.clock=clock;
  db.exec(`CREATE TABLE IF NOT EXISTS reply_dispatch (
   id TEXT PRIMARY KEY, phase TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
   next_at INTEGER NOT NULL DEFAULT 0, error TEXT, updated_at INTEGER NOT NULL
  )`);
 }
 ready(id){const r=this.db.prepare('SELECT next_at FROM reply_dispatch WHERE id=?').get(id);return !r||r.next_at<=this.clock();}
 prepare(id){this.db.prepare(`INSERT INTO reply_dispatch(id,phase,updated_at) VALUES(?,'preflight',?)
  ON CONFLICT(id) DO UPDATE SET phase='preflight',updated_at=excluded.updated_at`).run(id,this.clock());}
 dispatched(id){this.db.prepare("UPDATE reply_dispatch SET phase='dispatched',next_at=0,updated_at=? WHERE id=?").run(this.clock(),id);}
 accept(row,result){
  const changed=this.db.prepare("UPDATE outgoing SET state='accepted',result=?,updated_at=? WHERE id=? AND thread_id=? AND text=? AND state IN ('sending','unknown')")
   .run(JSON.stringify(result),this.clock(),row.id,row.thread_id,row.text).changes;
  if(changed)this.db.prepare("UPDATE reply_dispatch SET phase='acknowledged',next_at=0,updated_at=? WHERE id=?").run(this.clock(),row.id);
  return !!changed;
 }
 rejectLate(row,error){
  if(!error.rpc)return null;
  const state=row.mode==='queue'&&/already active|turn.*in progress|active turn/i.test(error.message)?'queued':'failed';
  const changed=this.db.prepare("UPDATE outgoing SET state=?,result=?,updated_at=? WHERE id=? AND thread_id=? AND text=? AND state='unknown'")
   .run(state,error.message,this.clock(),row.id,row.thread_id,row.text).changes;
  if(changed)this.db.prepare("UPDATE reply_dispatch SET phase='rejected',next_at=0,error=?,updated_at=? WHERE id=?").run(error.message,this.clock(),row.id);
  return changed?state:null;
 }
 preflightFailed(id,error){
  const r=this.db.prepare('SELECT phase,attempts FROM reply_dispatch WHERE id=?').get(id);
  if(r?.phase!=='preflight'||!['CODEX_TIMEOUT','CODEX_DISCONNECTED','CODEX_UNAVAILABLE'].includes(error.code))return false;
  const next=this.clock()+Math.min(120000,2000*2**Math.min(r.attempts,6));
  this.db.prepare("UPDATE reply_dispatch SET attempts=attempts+1,next_at=?,error=?,updated_at=? WHERE id=?").run(next,error.code,this.clock(),id);
  return !!this.db.prepare("UPDATE outgoing SET state='queued',result=?,updated_at=? WHERE id=? AND state IN ('queued','sending')")
   .run('Waiting for Codex before sending. Your message is saved and has not been dispatched.',this.clock(),id).changes;
 }
 retryLater(id,error,result='Saved. Waiting to create a fresh text-only continuation; this message has not been dispatched.'){
  const r=this.db.prepare('SELECT attempts FROM reply_dispatch WHERE id=?').get(id),attempts=(r?.attempts||0)+1;
  const next=this.clock()+Math.min(120000,2000*2**Math.min(attempts,6));
  this.db.prepare('UPDATE reply_dispatch SET attempts=?,next_at=?,error=?,updated_at=? WHERE id=?').run(attempts,next,error.message,this.clock(),id);
  const changed=this.db.prepare("UPDATE outgoing SET state='queued',result=?,updated_at=? WHERE id=? AND state='queued'")
   .run(result,this.clock(),id).changes;
  return changed?next:0;
 }
}
