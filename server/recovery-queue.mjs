/** Release only follow-ups held by this exact automatic recovery, never a user's stop. */
export class RecoveryQueue {
 constructor(db,clock=Date.now){this.db=db;this.clock=clock;db.exec('CREATE TABLE IF NOT EXISTS recovery_queue_holds(outgoing_id TEXT PRIMARY KEY,thread_id TEXT NOT NULL,source_turn TEXT NOT NULL)');}
 atomic(work){this.db.exec('BEGIN IMMEDIATE');try{const result=work();this.db.exec('COMMIT');return result;}catch(error){this.db.exec('ROLLBACK');throw error;}}
 hold(threadId,sourceTurn){return this.atomic(()=>{this.db.prepare("INSERT OR IGNORE INTO recovery_queue_holds SELECT id,thread_id,? FROM outgoing WHERE thread_id=? AND mode='queue' AND state='queued'").run(sourceTurn,threadId);return this.db.prepare("UPDATE outgoing SET state='held',updated_at=? WHERE state='queued' AND id IN (SELECT outgoing_id FROM recovery_queue_holds WHERE thread_id=? AND source_turn=?)").run(this.clock(),threadId,sourceTurn).changes;});}
 release(threadId,sourceTurn){return this.atomic(()=>{const changed=this.db.prepare("UPDATE outgoing SET state='queued',updated_at=? WHERE state='held' AND id IN (SELECT outgoing_id FROM recovery_queue_holds WHERE thread_id=? AND source_turn=?)").run(this.clock(),threadId,sourceTurn).changes;this.forget(threadId,sourceTurn);return changed;});}
 forget(threadId,sourceTurn){this.db.prepare('DELETE FROM recovery_queue_holds WHERE thread_id=? AND source_turn=?').run(threadId,sourceTurn);}
}
