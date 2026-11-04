import {pendingOutgoing} from './reply-recovery.mjs';

/** Delivery metadata has its own durable revision; transcript reads cannot order it. */
export class OutgoingProjection {
 constructor(db){
  this.db=db;
  db.exec(`
   CREATE TABLE IF NOT EXISTS outgoing_revisions(thread_id TEXT PRIMARY KEY,revision INTEGER NOT NULL);
   CREATE TRIGGER IF NOT EXISTS outgoing_projection_insert AFTER INSERT ON outgoing BEGIN
    INSERT INTO outgoing_revisions VALUES(NEW.thread_id,1) ON CONFLICT(thread_id) DO UPDATE SET revision=revision+1;
   END;
   CREATE TRIGGER IF NOT EXISTS outgoing_projection_update AFTER UPDATE ON outgoing
    WHEN OLD.text IS NOT NEW.text OR OLD.mode IS NOT NEW.mode OR OLD.state IS NOT NEW.state OR OLD.result IS NOT NEW.result
   BEGIN
    INSERT INTO outgoing_revisions VALUES(NEW.thread_id,1) ON CONFLICT(thread_id) DO UPDATE SET revision=revision+1;
   END;
   CREATE TRIGGER IF NOT EXISTS outgoing_projection_delete AFTER DELETE ON outgoing BEGIN
    INSERT INTO outgoing_revisions VALUES(OLD.thread_id,1) ON CONFLICT(thread_id) DO UPDATE SET revision=revision+1;
   END;
   CREATE INDEX IF NOT EXISTS outgoing_projection_pending ON outgoing(thread_id,created_at) WHERE state NOT IN ('accepted','cancelled');
   CREATE INDEX IF NOT EXISTS outgoing_projection_recent ON outgoing(created_at DESC,thread_id) WHERE state NOT IN ('accepted','cancelled');
  `);
 }
 fields(threadId){
  // Also consistent against another process writing this WAL database. A
  // savepoint composes with admission transactions without committing them.
  this.db.exec('SAVEPOINT outgoing_projection_snapshot');
  try{
   const revision=this.db.prepare('SELECT revision FROM outgoing_revisions WHERE thread_id=?').get(threadId)?.revision||0;
   const items=pendingOutgoing(this.db,threadId);
   return {outbox:{version:1,revision,items},outgoing:items};
  }finally{this.db.exec('RELEASE outgoing_projection_snapshot');}
 }
 *bootstrap(){
  // Reconnect hydrates recent delivery metadata, independently of history.
  // Every emitted snapshot is complete; oversized groups wait for their ordinary
  // authenticated head read rather than being misrepresented as an empty queue.
  const recent=this.db.prepare("SELECT thread_id FROM outgoing WHERE state NOT IN ('accepted','cancelled') ORDER BY created_at DESC LIMIT 512").all();
  let budget=1024*1024;const seen=new Set();
  for(const row of recent){
   if(seen.has(row.thread_id))continue;seen.add(row.thread_id);if(seen.size>128)break;
   let snapshot;this.db.exec('SAVEPOINT outgoing_bootstrap_entry');
   try{
    const size=this.db.prepare("SELECT COALESCE(SUM(512+6*(length(CAST(text AS BLOB))+length(CAST(COALESCE(result,'') AS BLOB)))),0) AS bytes FROM outgoing WHERE thread_id=? AND state NOT IN ('accepted','cancelled')").get(row.thread_id).bytes;
    if(size<=256*1024&&size<=budget){snapshot={threadId:row.thread_id,outbox:this.fields(row.thread_id).outbox};budget-=size;}
   }finally{this.db.exec('RELEASE outgoing_bootstrap_entry');}
   if(snapshot)yield snapshot;
  }
 }
 event(type,payload){return type==='reply'&&payload.threadId?{...payload,outbox:this.fields(payload.threadId).outbox}:payload;}
}
