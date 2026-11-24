import {newThreadComputerUseOptions} from './computer-use.mjs';
import {permissionOptions} from './sessions.mjs';

const pending=message=>Object.assign(Error(message),{code:'CONTINUATION_PENDING',status:409,continuationCreation:true});
const failed=(message,cause)=>Object.assign(Error(message,{cause}),{code:'CONTINUATION_CREATE_FAILED',continuationCreation:true});
const cleanName=value=>String(value||'Task').replace(/\s*·\s*continued\s*$/i,'').trim()||'Task';

export function continuedName(name){return `${cleanName(name).slice(0,111)} · continued`;}

export function buildContinuationHandoff(source,usage){
  return [
    'NextComp automatically continued this task into a fresh text-only session because the source thread had an unsafe active image payload.',
    `Source thread: ${source.id}`,
    `Source task: ${cleanName(source.name)}`,
    `Workspace: ${source.cwd}`,
    `Continuation reason: ${usage.reason||'The active payload exceeded the safe request budget.'}`,
    'The source history remains preserved and must not be replayed, forked, resumed, or compacted for this continuation.',
    'The first user turn in this session is the exact next user message. Treat it as the original user intent, not as a duplicate or a replay. Re-inspect current files and external state before acting; preserve the original scope, permissions, deliberate stops, and holds.'
  ].join('\n');
}

export function continuationInput(row){return [{type:'text',text:String(row.text)}];}

export function continuationReceipt(resultText){
  try{
    const value=JSON.parse(resultText),continuation=value?.continuation;
    if(!continuation||typeof continuation.successorThreadId!=='string')return null;
    return {continuationThreadId:continuation.successorThreadId,continuationSourceThreadId:continuation.sourceThreadId||null,continuationReason:continuation.reason||null};
  }catch{return null;}
}

/** Durable source→successor edges. Forks are deliberately not used: a fork
 * copies the oversized history and can recreate the same rejected request.
 */
export class ContinuationRouter {
  constructor({db,codex,onCreated=()=>{},permissionFor=()=>null,clock=Date.now}={}){
    Object.assign(this,{db,codex,onCreated,permissionFor,clock});
    db.exec(`CREATE TABLE IF NOT EXISTS thread_continuations(
      source_thread_id TEXT PRIMARY KEY,
      successor_thread_id TEXT NOT NULL,
      state TEXT NOT NULL,
      reason TEXT,
      error TEXT,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    )`);
    db.prepare("UPDATE thread_continuations SET state='retry',error='The bridge restarted while creating a continuation.',updated_at=? WHERE state='creating'").run(clock());
  }
  resolve(threadId){
    let current=threadId,seen=new Set(),mapping=null;
    for(let depth=0;depth<16;depth++){
      if(seen.has(current))throw Object.assign(Error('Continuation routing contains a cycle.'),{code:'CONTINUATION_CYCLE',status:409});
      seen.add(current);
      const row=this.db.prepare('SELECT * FROM thread_continuations WHERE source_thread_id=?').get(current);
      if(!row)return {threadId:current,sourceThreadId:threadId,continued:current!==threadId,mapping,chain:seen.size-1};
      if(row.state==='creating')throw pending('The fresh continuation session is still being created. This message remains saved.');
      if(row.state!=='ready'||!row.successor_thread_id)throw pending('The fresh continuation session is not ready yet. This message remains saved.');
      mapping=row;current=row.successor_thread_id;
    }
    throw Object.assign(Error('Continuation routing exceeded its bounded depth.'),{code:'CONTINUATION_DEPTH',status:409});
  }
  receiptFields(threadId,resultText=null){
    return continuationReceipt(resultText)||(()=>{try{const route=this.resolve(threadId);return route.continued?{continuationThreadId:route.threadId,continuationSourceThreadId:threadId}:{};}catch{return {};}})();
  }
  options(thread,handoff){
    const permission=this.permissionFor(thread.id),selection={};
    if(thread.modelProvider)selection.modelProvider=thread.modelProvider;
    if(thread.model)selection.model=thread.model;
    if(thread.reasoningEffort)selection.config={model_reasoning_effort:thread.reasoningEffort};
    return {cwd:thread.cwd,...selection,...(permission?permissionOptions(permission):this.codex.executionOptions?.()),...newThreadComputerUseOptions(handoff)};
  }
  async ensureSuccessor(thread,{usage,messageId=null}={}){
    const now=this.clock(),reason=usage?.reason||'The active payload exceeded the safe request budget.';
    const current=this.db.prepare('SELECT * FROM thread_continuations WHERE source_thread_id=?').get(thread.id);
    if(current?.state==='ready'&&current.successor_thread_id)
      return {thread:null,destinationThreadId:this.resolve(thread.id).threadId,created:false};
    if(current&&!['retry','failed','unknown'].includes(current.state))throw pending('A continuation is already being prepared. This message remains saved.');
    if(current)this.db.prepare("UPDATE thread_continuations SET state='creating',reason=?,error=NULL,updated_at=? WHERE source_thread_id=? AND state IN ('retry','failed','unknown')").run(reason,now,thread.id);
    else this.db.prepare("INSERT INTO thread_continuations(source_thread_id,successor_thread_id,state,reason,created_at,updated_at) VALUES(?,?,'creating',?,?,?)").run(thread.id,'',reason,now,now);
    const claimed=this.db.prepare("SELECT state FROM thread_continuations WHERE source_thread_id=?").get(thread.id);
    if(claimed?.state!=='creating')throw pending('A continuation is already being prepared. This message remains saved.');
    const handoff=buildContinuationHandoff(thread,usage||{});
    try{
      const {thread:successor}=await this.codex.call('thread/start',this.options(thread,handoff));
      if(!successor?.id||successor.id===thread.id)throw Error('Codex did not return a distinct continuation session.');
      const name=continuedName(thread.name);
      this.db.exec('BEGIN IMMEDIATE');
      try{
        this.db.prepare("UPDATE thread_continuations SET successor_thread_id=?,state='ready',error=NULL,updated_at=? WHERE source_thread_id=?").run(successor.id,this.clock(),thread.id);
        this.db.prepare('INSERT INTO watches(thread_id,name,enabled) VALUES(?,?,1) ON CONFLICT(thread_id) DO UPDATE SET name=excluded.name,enabled=1').run(successor.id,name);
        const settings=this.db.prepare('SELECT settings FROM turn_settings WHERE thread_id=?').get(thread.id);
        if(settings)this.db.prepare('INSERT INTO turn_settings(thread_id,settings) VALUES(?,?) ON CONFLICT(thread_id) DO UPDATE SET settings=excluded.settings').run(successor.id,settings.settings);
        const permissions=this.db.prepare('SELECT permissions FROM thread_permissions WHERE thread_id=?').get(thread.id);
        if(permissions)this.db.prepare('INSERT INTO thread_permissions(thread_id,permissions) VALUES(?,?) ON CONFLICT(thread_id) DO UPDATE SET permissions=excluded.permissions').run(successor.id,permissions.permissions);
        this.db.exec('COMMIT');
      }catch(error){this.db.exec('ROLLBACK');throw error;}
      successor.name=name;
      try{await this.codex.call('thread/name/set',{threadId:successor.id,name});}catch(error){console.error('Continuation naming failed',error.message);}
      this.onCreated(successor,thread,{messageId,usage,reason});
      return {thread:successor,destinationThreadId:successor.id,created:true};
    }catch(error){
      const row=this.db.prepare('SELECT state FROM thread_continuations WHERE source_thread_id=?').get(thread.id);
      if(row?.state==='creating')this.db.prepare("UPDATE thread_continuations SET state='retry',error=?,updated_at=? WHERE source_thread_id=?").run(error.message,this.clock(),thread.id);
      throw failed(`Could not create a fresh continuation session: ${error.message}`,error);
    }
  }
}
