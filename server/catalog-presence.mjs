import {cachedThreadMetadata,isThreadNotLoaded} from './thread-read-availability.mjs';
/** Native negative metadata evidence is distinct from an outage or archiving.
 * Keep cached history/receipts; only a proven temporary session can be hidden.
 * A fresh native list/read/event can admit that identity again. */
export class CatalogPresence {
 constructor(db){
  this.db=db;this.versions=new Map();
  db.exec('CREATE TABLE IF NOT EXISTS pocket_catalog_unavailable(thread_id TEXT PRIMARY KEY,reason TEXT NOT NULL,observed_at INTEGER NOT NULL)');
 }
 begin(id){let record=this.versions.get(id);if(!record){record={revision:0,pending:0};this.versions.set(id,record);}record.pending++;return {record,revision:record.revision};}
 finish(id,ticket){if(--ticket.record.pending===0&&this.versions.get(id)===ticket.record)this.versions.delete(id);}
 seen(id){
  const record=this.versions.get(id);if(record)record.revision++;
  this.db.prepare('DELETE FROM pocket_catalog_unavailable WHERE thread_id=?').run(id);
 }
 unavailable(id){return cachedThreadMetadata(this.db,id)?.ephemeral===true&&!!this.db.prepare('SELECT 1 FROM pocket_catalog_unavailable WHERE thread_id=?').get(id);}
 rejectMissing(id,error,ticket){
  // Require the actual native RPC's exact identity-bearing response. Transport
  // timeouts, generic 404s, archived histories and partial lists are not proof.
  // An unloaded durable session may have a temporarily unavailable source.
  if(!isThreadNotLoaded(error,id)||cachedThreadMetadata(this.db,id)?.ephemeral!==true||this.versions.get(id)!==ticket.record||ticket.record.revision!==ticket.revision)return false;
  const hasOutgoing=this.db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='outgoing'").get();
  if(hasOutgoing&&this.db.prepare("SELECT 1 FROM outgoing WHERE thread_id=? AND state IN ('queued','sending','unknown') LIMIT 1").get(id))return false;
  this.db.prepare('INSERT INTO pocket_catalog_unavailable VALUES(?,?,?) ON CONFLICT(thread_id) DO UPDATE SET reason=excluded.reason,observed_at=excluded.observed_at').run(id,'Temporary session is not currently loaded',Date.now());
  return true;
 }
}
