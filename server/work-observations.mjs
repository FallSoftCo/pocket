/** Model-authored progress is a saved observation, never execution authority. */
export class WorkObservations {
 constructor(db,clock=Date.now){this.db=db;this.clock=clock;db.exec('CREATE TABLE IF NOT EXISTS work_observations(thread_id TEXT NOT NULL,turn_id TEXT NOT NULL,state TEXT NOT NULL,evidence TEXT NOT NULL,reported_at INTEGER NOT NULL,PRIMARY KEY(thread_id,turn_id))');}
 record(thread,{turnId,state,evidence}){
  if(!thread?.id||thread.turns?.at(-1)?.id!==turnId||!['continue','completed','needsInput','waitingDependency'].includes(state)||typeof evidence!=='string'||!evidence.trim()||evidence.length>8000)return false;
  const previous=this.db.prepare('SELECT state,evidence FROM work_observations WHERE thread_id=? AND turn_id=?').get(thread.id,turnId);
  if(previous)return previous.state===state&&previous.evidence===evidence.trim();
  this.db.prepare('INSERT INTO work_observations VALUES(?,?,?,?,?)').run(thread.id,turnId,state,evidence.trim(),this.clock());return true;
 }
 latest(threadId){return this.db.prepare('SELECT * FROM work_observations WHERE thread_id=? ORDER BY reported_at DESC,rowid DESC LIMIT 1').get(threadId)||null;}
}
