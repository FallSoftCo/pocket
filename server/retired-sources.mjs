/** Explicit fleet retirement deletes local credentials and command copies.
 * It never dispatches, cancels or retries work on the removed host. */
export function purgeRetiredSources(db, sourceAllowed) {
 const tables=new Set(db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all().map(r=>r.name));
 if(!tables.has('coordinator_sources'))return {};
 const removed=db.prepare('SELECT device_id,source_id,base FROM coordinator_sources').all().filter(r=>!sourceAllowed(r.base));
 const counts={};
 db.exec("SAVEPOINT retire_sources");
 try{
  for(const row of removed){
   for(const [table,device,source] of [
    ['coordinator_room_reconciliations','device','source'],
    ['coordinator_room_intent_recoveries','device','source'],
    ['coordinator_room_intents','device','source'],
    ['coordinator_room_commands','device','source'],
    ['coordinator_remote_commands','device_id','source_id'],
    ['coordinator_sources','device_id','source_id']]){
    if(tables.has(table))counts[table]=(counts[table]||0)+db.prepare(`DELETE FROM ${table} WHERE ${device}=? AND ${source}=?`).run(row.device_id,row.source_id).changes;
   }
  }
  db.exec("RELEASE retire_sources");
 }catch(error){db.exec("ROLLBACK TO retire_sources; RELEASE retire_sources");throw error;}
 return counts;
}
