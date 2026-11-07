import {resolve} from 'node:path';

/** Catalog membership follows native ownership and residency, not titles.
 * Ephemeral runtimes are not resumable saved conversations. Retain that fact
 * across reconnects, but allow an explicit native persistence transition. */
export class InternalSessions {
  constructor(db, directories = []) {
    this.db=db;this.directories=new Map(directories.map(({cwd,kind})=>[resolve(cwd),kind]));
    db.exec('CREATE TABLE IF NOT EXISTS pocket_internal_sessions(thread_id TEXT PRIMARY KEY,kind TEXT NOT NULL)');
    if(db.prepare("SELECT 1 FROM sqlite_master WHERE name='pocket_discovered_threads'").get())for(const row of db.prepare('SELECT metadata FROM pocket_discovered_threads').all())this.owns(null,JSON.parse(row.metadata));
  }
  register(id,kind){if(id)this.db.prepare("INSERT INTO pocket_internal_sessions VALUES(?,?) ON CONFLICT(thread_id) DO UPDATE SET kind=excluded.kind WHERE pocket_internal_sessions.kind='temporary-runtime' AND excluded.kind!='temporary-runtime'").run(id,kind);}
  owns(id,thread){
    id=id||thread?.id;if(!id)return false;
    if(thread?.ephemeral===false)this.db.prepare("DELETE FROM pocket_internal_sessions WHERE thread_id=? AND kind='temporary-runtime'").run(id);
    // Legacy room workers share Codex's discovery directory, but their owning
    // runtime has a separate history store. Never present those records as
    // stock sessions. Use persisted producer identity, not title/path guesses.
    const kind=(thread?.originator==='lax-host-agent'?'retired-room-agent':
      typeof thread?.cwd==='string'?this.directories.get(resolve(thread.cwd)):null)||
      (thread?.ephemeral===true?'temporary-runtime':null);
    if(kind)this.register(id,kind);
    return !!this.db.prepare('SELECT 1 FROM pocket_internal_sessions WHERE thread_id=?').get(id);
  }
}
