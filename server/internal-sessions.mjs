import {resolve} from 'node:path';

/** Product-owned workers stay private across reconnects/restarts. No title heuristics. */
export class InternalSessions {
  constructor(db, directories = []) {
    this.db=db;this.directories=new Map(directories.map(({cwd,kind})=>[resolve(cwd),kind]));
    db.exec('CREATE TABLE IF NOT EXISTS pocket_internal_sessions(thread_id TEXT PRIMARY KEY,kind TEXT NOT NULL)');
    if(db.prepare("SELECT 1 FROM sqlite_master WHERE name='pocket_discovered_threads'").get())for(const row of db.prepare('SELECT metadata FROM pocket_discovered_threads').all())this.owns(null,JSON.parse(row.metadata));
  }
  register(id,kind){if(id)this.db.prepare('INSERT OR IGNORE INTO pocket_internal_sessions VALUES(?,?)').run(id,kind);}
  owns(id,thread){
    id=id||thread?.id;if(!id)return false;
    const kind=typeof thread?.cwd==='string'?this.directories.get(resolve(thread.cwd)):null;
    if(kind)this.register(id,kind);
    return !!this.db.prepare('SELECT 1 FROM pocket_internal_sessions WHERE thread_id=?').get(id);
  }
}
