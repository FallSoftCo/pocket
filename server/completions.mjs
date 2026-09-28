// Persist the subscription boundary and completed turn IDs across bridge restarts.
export class CompletionRecovery {
  constructor(db,publish){this.db=db;this.publish=publish;}
  follow(threadId,thread=null){
    this.db.prepare('INSERT INTO completion_watches(thread_id,since,initialized) VALUES(?,?,0) ON CONFLICT(thread_id) DO UPDATE SET since=excluded.since,initialized=0').run(threadId,Date.now());
    if(thread)this.observe(thread);
  }
  seen(threadId,turnId){return this.db.prepare('SELECT 1 FROM completion_seen WHERE thread_id=? AND turn_id=?').get(threadId,turnId);}
  mark(threadId,turnId){this.db.prepare('INSERT OR IGNORE INTO completion_seen VALUES(?,?)').run(threadId,turnId);}
  needsEarlier(thread){
    if(!this.db.prepare('SELECT 1 FROM watches WHERE thread_id=? AND enabled=1').get(thread.id))return false;
    const checkpoint=this.db.prepare('SELECT * FROM completion_watches WHERE thread_id=?').get(thread.id);
    if(!checkpoint)return false;
    const finished=(thread.turns||[]).filter(t=>['completed','failed','interrupted'].includes(t.status));
    if(finished.some(t=>this.seen(thread.id,t.id)||(Number(t.completedAt)>0&&Number(t.completedAt)*1000<checkpoint.since)))return false;
    return !!checkpoint.initialized||finished.some(t=>Number(t.completedAt)*1000>=checkpoint.since);
  }
  complete(threadId,turn){
    const watch=this.db.prepare('SELECT * FROM watches WHERE thread_id=? AND enabled=1').get(threadId);
    if(!watch||!turn||!['completed','failed','interrupted'].includes(turn.status))return;
    if(turn.id&&this.seen(threadId,turn.id))return;
    // Stopping a task deliberately is not a successful completion alert.
    if(turn.status!=='interrupted'){
      const failed=turn.status==='failed';
      const message=turn.items?.filter(x=>x.type==='agentMessage').at(-1)?.text;
      this.publish(threadId,failed?'Codex hit a problem':watch.name||'Codex finished',failed?(turn.error?.message||message||'Open the task to review what stopped the work.'):(message||'The latest turn is ready to review.'),failed?'error':'complete',turn.id||null);
    }
    if(turn.id)this.mark(threadId,turn.id);
  }
  observe(thread){
    if(!this.db.prepare('SELECT 1 FROM watches WHERE thread_id=? AND enabled=1').get(thread.id))return;
    let checkpoint=this.db.prepare('SELECT * FROM completion_watches WHERE thread_id=?').get(thread.id);
    if(!checkpoint){this.follow(thread.id);checkpoint=this.db.prepare('SELECT * FROM completion_watches WHERE thread_id=?').get(thread.id);}
    for(const turn of thread.turns||[]){
      if(!turn.id||!['completed','failed','interrupted'].includes(turn.status)||this.seen(thread.id,turn.id))continue;
      const at=Number(turn.completedAt)*1000;
      // First upgrade/follow establishes a baseline, never a flood of old alerts.
      if((at>0&&at<checkpoint.since)||(!checkpoint.initialized&&!(at>=checkpoint.since)))this.mark(thread.id,turn.id);
      else this.complete(thread.id,turn);
    }
    this.db.prepare('UPDATE completion_watches SET initialized=1 WHERE thread_id=?').run(thread.id);
  }
}
