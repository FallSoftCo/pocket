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
 event(type,payload){return type==='reply'&&payload.threadId?{...payload,...this.fields(payload.threadId)}:payload;}
}
