import {sessionWorkTime} from './session-catalog.mjs';
/** Evidence-backed reading aid, invalidated by subsequent real work, never a runtime status. */
export class CatalogContext {
  constructor(db,clock=Date.now){this.db=db;this.clock=clock;db.exec('CREATE TABLE IF NOT EXISTS pocket_catalog_context(thread_id TEXT PRIMARY KEY,summary TEXT NOT NULL,needs_input INTEGER NOT NULL,at INTEGER NOT NULL)');}
  put(id,summary,needsInput=false){this.db.prepare('INSERT INTO pocket_catalog_context VALUES(?,?,?,?) ON CONFLICT(thread_id) DO UPDATE SET summary=excluded.summary,needs_input=excluded.needs_input,at=excluded.at').run(id,summary,needsInput?1:0,this.clock());}
  get(thread){const row=this.db.prepare('SELECT * FROM pocket_catalog_context WHERE thread_id=?').get(thread.id);return row&&thread.status?.type==='idle'&&sessionWorkTime(thread)<=row.at?{catalogContext:row.summary,needsInput:!!row.needs_input}:{};}
}
