import {isAbsolute,basename} from 'node:path';
import {realpathSync,statSync} from 'node:fs';
const invalid=message=>Object.assign(Error(message),{status:400});

export function projectPath(value){
  if(typeof value!=='string'||!isAbsolute(value)||value.length>4096)throw invalid('Choose an absolute folder path on your workstation.');
  let cwd;try{cwd=realpathSync(value);if(!statSync(cwd).isDirectory())throw Error();}catch{throw invalid('That project folder does not exist or is not accessible on the selected device.');}
  return cwd;
}
export class SessionStarts {
  constructor(db,codex,onCreated){this.db=db;this.codex=codex;this.onCreated=onCreated;this.running=new Set();
    db.exec("UPDATE session_starts SET state='unknown',error='The bridge restarted during creation. Check recent tasks before starting again.' WHERE state='creating'");
  }
  get(id){return this.db.prepare('SELECT * FROM session_starts WHERE id=?').get(id);}
  enqueue({id,cwd,prompt}={}){
    if(typeof id!=='string'||!/^[a-zA-Z0-9_-]{8,100}$/.test(id))throw invalid('Invalid task request identifier.');
    if(typeof prompt!=='string'||!prompt.trim()||prompt.length>32000)throw invalid('Enter a task of 1–32000 characters.');
    cwd=projectPath(cwd);prompt=prompt.trim();
    const old=this.get(id);
    if(old){if(old.cwd!==cwd||old.prompt!==prompt)throw Object.assign(Error('This request already belongs to another task.'),{status:409});return old;}
    const at=Date.now();this.db.prepare('INSERT INTO session_starts(id,cwd,prompt,state,created_at,updated_at) VALUES(?,?,?,\'queued\',?,?)').run(id,cwd,prompt,at,at);
    void this.flush();return this.get(id);
  }
  async flush(){
    if(!this.codex.ready)return;
    for(const row of this.db.prepare("SELECT * FROM session_starts WHERE state='queued' ORDER BY created_at").all()){
      if(this.running.has(row.id))continue;this.running.add(row.id);
      try{
        this.db.prepare("UPDATE session_starts SET state='creating',updated_at=? WHERE id=?").run(Date.now(),row.id);
        // Desktop installs inherit their normal configuration. Android-local installs opt in
        // explicitly because the desktop workspace sandbox cannot run under Termux/PRoot.
        const {thread}=await this.codex.call('thread/start',{cwd:row.cwd,...this.codex.executionOptions?.()});
        this.db.exec('BEGIN IMMEDIATE');
        try{
          this.db.prepare("UPDATE session_starts SET state='started',thread_id=?,updated_at=? WHERE id=?").run(thread.id,Date.now(),row.id);
          this.db.prepare('INSERT INTO watches(thread_id,name,enabled) VALUES(?,?,1)').run(thread.id,row.prompt.slice(0,80));
          this.db.prepare("INSERT INTO outgoing(id,thread_id,text,state,created_at,updated_at) VALUES(?,?,?,'queued',?,?)").run(`start-${row.id}`,thread.id,row.prompt,Date.now(),Date.now());
          this.db.exec('COMMIT');
        }catch(e){this.db.exec('ROLLBACK');throw e;}
        this.onCreated(thread,this.db.prepare('SELECT * FROM outgoing WHERE id=?').get(`start-${row.id}`));
      }catch(e){
        // Never silently create a second session after an ambiguous RPC outcome.
        const state=e.rpc?'failed':'unknown';
        this.db.prepare('UPDATE session_starts SET state=?,error=?,updated_at=? WHERE id=?').run(state,e.message,Date.now(),row.id);
      }finally{this.running.delete(row.id);}
    }
  }
}
export function recentProjects(threads){
  const seen=new Set(),projects=[];
  for(const t of threads){try{const cwd=projectPath(t.cwd);if(seen.has(cwd))continue;seen.add(cwd);projects.push({cwd,name:basename(cwd)||cwd,lastUsed:t.updatedAt});}catch{}}
  return projects;
}
