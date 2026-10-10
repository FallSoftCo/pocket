import {randomUUID} from 'node:crypto';
import {mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {resolve} from 'node:path';
import {exportNativeFork,importNativeFork,workspaceEvidence,digest,nativeId} from './native-fork-bundle.mjs';
import {displayItem,displayTurnError} from './timeline.mjs';

const fail=(message,status=409)=>Object.assign(Error(message),{status});
const canonical=value=>JSON.stringify(value);
const terminal=new Set(['transferred','blocked','cancelled']);
export function accountIdentity(result){
  const a=result?.account;if(!a||a.type!=='chatgpt'||typeof a.email!=='string'||!a.email)throw fail('A signed-in account identity is required for terminal transfer.');
  return digest(canonical([a.type,a.email.toLowerCase()]));
}
export async function toolInventory(codex,threadId){
  const rows=[],seen=new Set();let cursor=null;
  for(let page=0;page<8;page++){
    const result=await codex.call('mcpServerStatus/list',{threadId,detail:'toolsAndAuthOnly',limit:50,...(cursor?{cursor}:{})});
    for(const server of result.data||[]){if(server.toolsError||['failed','authenticationRequired','cancelled'].includes(server.runtimeStatus)||server.authStatus==='notLoggedIn')throw fail('A source or destination MCP dependency is unavailable: '+server.name);
      rows.push({name:server.name,authStatus:server.authStatus,tools:Object.entries(server.tools||{}).sort(([a],[b])=>a.localeCompare(b)).map(([name,tool])=>({name,inputSchema:tool.inputSchema,outputSchema:tool.outputSchema,description:tool.description}))});}
    if(!result.nextCursor)return rows.sort((a,b)=>a.name.localeCompare(b.name));cursor=result.nextCursor;if(seen.has(cursor))throw fail('MCP discovery returned a repeated cursor.');seen.add(cursor);
  }
  throw fail('MCP dependency inventory exceeds its bounded qualification limit.');
}
function effective(result){
  const fields=['model','modelProvider','approvalPolicy','sandbox'];const value={};
  for(const field of fields){if(result[field]==null)throw fail('Codex did not expose the effective '+field+'; migration cannot verify it.');value[field]=result[field];}
  for(const field of ['reasoningEffort','collaborationMode','serviceTier','disabledPluginIds'])if(result[field]!=null)value[field]=result[field];
  return value;
}
function plan(input){
  if(!nativeId(input?.id)||!nativeId(input?.threadId)||typeof input.target!=='string'||!/^[a-zA-Z0-9_-]{1,80}$/.test(input.target))throw fail('Use stable migration, source and environment identifiers.',400);
  if(typeof input.cwd!=='string'||!input.cwd.startsWith('/')||input.cwd.length>4096)throw fail('Choose an absolute destination workspace.',400);
  if(typeof input.handoff!=='string'||!input.handoff.trim()||input.handoff.length>16000)throw fail('Provide a checkpoint of 1–16000 characters.',400);
  if(typeof input.primaryScope!=='string'||!input.primaryScope.trim()||input.primaryScope.length>4000||typeof (input.sourceScope??'')!=='string'||(input.sourceScope||'').length>4000)throw fail('Describe the successor’s primary task and any retained source dependency scope.',400);
  if(!Array.isArray(input.dependencies||[])||(input.dependencies||[]).length>64)throw fail('Invalid dependency inventory.',400);
  for(const d of input.dependencies||[]){if(!nativeId(d.id)||!['settled','source-retained','unknown'].includes(d.state)||typeof d.description!=='string'||!d.description.trim()||d.description.length>2000)throw fail('Each dependency needs an identity, state and description.',400);}
  if((input.dependencies||[]).some(d=>d.state==='unknown'))throw fail('Reconcile uncertain dependencies before requesting transfer.');
  if((input.dependencies||[]).some(d=>d.state==='source-retained')&&!input.sourceScope?.trim())throw fail('Retained dependencies require a distinct source-side scope.');
  const paths=input.workspaceFiles||[];if(!Array.isArray(paths)||paths.length>256||paths.some(p=>typeof p!=='string'))throw fail('Invalid workspace evidence inventory.',400);
  return {id:input.id,threadId:input.threadId,target:input.target,cwd:input.cwd,handoff:input.handoff.trim(),primaryScope:input.primaryScope.trim(),sourceScope:(input.sourceScope||'').trim(),dependencies:input.dependencies||[],workspaceFiles:paths};
}

/** Event-driven self transfer. Pending requests perform deterministic checks, not inference. */
export class TerminalForks {
  constructor({db,codex,codeHome,directory,environmentId,targets={},transport,readSource,checkSource=async()=>{},publicHistory=thread=>thread.turns||[],onFork=()=>{},publish=()=>{},clock=Date.now}){
    Object.assign(this,{db,codex,codeHome,directory,environmentId,targets,transport,readSource,checkSource,publicHistory,onFork,publish,clock});this.running=new Set();
    db.exec(`CREATE TABLE IF NOT EXISTS terminal_forks(id TEXT PRIMARY KEY,thread_id TEXT NOT NULL,target TEXT NOT NULL,plan TEXT NOT NULL,state TEXT NOT NULL,released INTEGER NOT NULL DEFAULT 0,fork_id TEXT,packet TEXT,error TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,next_check INTEGER NOT NULL DEFAULT 0);
      CREATE UNIQUE INDEX IF NOT EXISTS terminal_fork_source_active ON terminal_forks(thread_id) WHERE state NOT IN ('cancelled','blocked');`);
    // A non-idempotent native fork may have been created; never guess and fork again.
    db.prepare("UPDATE terminal_forks SET state='unknown',error='The native fork acknowledgement was lost. Inspect the source before creating another fork.' WHERE state='forking'").run();
    db.prepare("UPDATE terminal_forks SET state='reconcile' WHERE state IN ('transferring','activating')").run();
  }
  row(id,{packet=true}={}){const r=this.db.prepare(packet?'SELECT * FROM terminal_forks WHERE id=?':'SELECT id,thread_id,target,plan,state,released,fork_id,error,created_at,updated_at,next_check FROM terminal_forks WHERE id=?').get(id);return r?{...r,plan:JSON.parse(r.plan),packet:r.packet?JSON.parse(r.packet):null}:null;}
  view(id){const r=this.row(id,{packet:false});if(!r)return null;return {id:r.id,sourceThreadId:r.thread_id,target:r.target,state:r.state,released:!!r.released,destinationThreadId:r.fork_id,primaryScope:r.plan.primaryScope,sourceScope:r.plan.sourceScope,dependencies:r.plan.dependencies,error:r.error||null};}
  source(threadId){const r=this.db.prepare("SELECT id FROM terminal_forks WHERE thread_id=? AND state NOT IN ('cancelled','blocked')").get(threadId);return r?this.row(r.id,{packet:false}):null;}
  request(input){
    const p=plan(input);if(!this.targets[p.target])throw fail('That destination is not registered by the environment owner.');
    const old=this.row(p.id);if(old){if(canonical(old.plan)!==canonical(p))throw fail('This migration identifier already belongs to another checkpoint.');return this.view(p.id);}
    if(this.source(p.threadId))throw fail('This source already has a pending or completed transfer.');
    const at=this.clock();this.db.prepare("INSERT INTO terminal_forks(id,thread_id,target,plan,state,created_at,updated_at) VALUES(?,?,?,?,'requested',?,?)").run(p.id,p.threadId,p.target,canonical(p),at,at);return this.view(p.id);
  }
  release(id){const r=this.row(id);if(!r)throw fail('Migration not found.',404);if(terminal.has(r.state)||r.state==='unknown')return this.view(id);this.db.prepare('UPDATE terminal_forks SET released=1,next_check=0,updated_at=? WHERE id=?').run(this.clock(),id);this.publish(this.view(id));return this.view(id);}
  cancel(id){const r=this.row(id);if(!r)throw fail('Migration not found.',404);if(!['requested','waiting'].includes(r.state))throw fail('Transfer may already have crossed environments. Reconcile it before changing ownership.');this.set(id,'cancelled');return this.view(id);}
  set(id,state,extra={}){const r=this.row(id),at=this.clock();this.db.prepare('UPDATE terminal_forks SET state=?,fork_id=?,packet=?,error=?,updated_at=?,next_check=? WHERE id=?').run(state,extra.forkId??r.fork_id,extra.packet?canonical(extra.packet):r.packet?canonical(r.packet):null,extra.error||null,at,extra.nextCheck??at+30000,id);this.publish(this.view(id));}
  /** Covers NextComp admission; independent native terminals still require cooperative scope. */
  inputRole(threadId){const r=this.source(threadId);if(!r||r.state==='requested'&&!r.released||r.state==='waiting'&&!r.released)return null;
    if(r.state==='transferred'&&r.plan.sourceScope)return `Terminal fork ${r.id}: primary work is owned by ${r.target}/${r.fork_id}. This source may handle ONLY these retained dependencies: ${r.plan.sourceScope}. Do not repeat primary work or uncertain external actions.`;
    throw fail(`This source is yielding primary work to ${r.target}. ${r.state==='transferred'?'Open the destination conversation.':'Transfer state: '+r.state+'.'} Its checkpoint and drafts remain here.`);
  }
  recoveryAllowed(threadId){return !this.source(threadId);}
  async tick(){for(const {id} of this.db.prepare("SELECT id FROM terminal_forks WHERE released=1 AND state NOT IN ('transferred','blocked','cancelled','unknown') AND next_check<=? ORDER BY created_at LIMIT 4").all(this.clock()))await this.advance(id);}
  async advance(id){
    if(this.running.has(id))return;this.running.add(id);let row=this.row(id),exportingDirectory=null;
    try{
      if(!row?.released||terminal.has(row.state)||row.state==='unknown')return;
      if(row.state==='reconcile'){
        const observation=await this.transport(this.targets[row.target],{operation:'observe',id});
        if(observation.forkId&&observation.forkId!==row.fork_id){this.set(id,'unknown',{error:'Destination receipt belongs to a different fork. No continuation was submitted again.'});return;}
        if(observation.state==='accepted'){this.set(id,'transferred');return;}
        if(observation.state==='prepared'){this.set(id,'ready');row=this.row(id);}
        else{this.set(id,'unknown',{error:'Destination acknowledgement is uncertain. No turn was sent again.'});return;}
      }
      if(['requested','waiting'].includes(row.state)){
        await this.checkSource(row.thread_id);const source=await this.readSource(row.thread_id);if(terminal.has(this.row(id)?.state))return;
        if(source.thread.status?.type==='active'||source.turn?.status==='inProgress'){this.set(id,'waiting');return;}
        if(source.thread.status?.type==='systemError'||source.turn&&source.turn.status!=='completed')throw fail('The source turn stopped or failed; terminal transfer will not override that stop.');
        const inherited=await this.codex.call('thread/resume',{threadId:row.thread_id,excludeTurns:true});
        const account=accountIdentity(await this.codex.call('account/read',{}));const settings=effective(inherited),tools=await toolInventory(this.codex,row.thread_id);
        const evidence=await workspaceEvidence(source.thread.cwd,row.plan.workspaceFiles);
        if(!this.db.prepare("UPDATE terminal_forks SET state='forking',updated_at=? WHERE id=? AND released=1 AND state IN ('requested','waiting')").run(this.clock(),id).changes)return;this.publish(this.view(id));
        const result=await this.codex.call('thread/fork',{threadId:row.thread_id,excludeTurns:true,...(source.turn?.id?{lastTurnId:source.turn.id}:{})});
        if(result.thread.forkedFromId&&result.thread.forkedFromId!==row.thread_id)throw fail('The runtime fork belongs to a different source.');
        this.onFork(result.thread.id);
        this.set(id,'exporting',{forkId:result.thread.id,packet:{sourceEnvironment:this.environmentId,sourceThreadId:row.thread_id,sourceName:source.thread.name||null,boundaryTurnId:source.turn?.rootTurnId||source.turn?.id||null,account,settings,tools,evidence,fork:result.thread,plan:row.plan}});row=this.row(id);
      }
      if(row.state==='exporting'){
        const staging=resolve(this.directory,id,randomUUID());exportingDirectory=staging;await mkdir(staging,{recursive:true,mode:0o700});
        const bundle=await exportNativeFork({codex:this.codex,fork:row.packet.fork,codeHome:this.codeHome,directory:staging});
        if(row.packet.boundaryTurnId&&!bundle.files.some(f=>f.completedTurnIds.includes(row.packet.boundaryTurnId)))throw fail('The native ancestor snapshot does not include the completed source boundary.');
        const original=(await this.codex.call('thread/read',{threadId:row.thread_id,includeTurns:true})).thread;
        if(original.status?.type==='active'||row.packet.boundaryTurnId&&((original.turns||[]).at(-1)?.rootTurnId||(original.turns||[]).at(-1)?.id)!==row.packet.boundaryTurnId)throw fail('The source acquired new work while the native snapshot was prepared.');
        const archive=[...new Map(this.publicHistory(original).map(t=>[t.rootTurnId||t.id,t])).values()];
        const publicHistory=archive.map(t=>({id:t.id,rootTurnId:t.rootTurnId,status:t.status,startedAt:t.startedAt,completedAt:t.completedAt,durationMs:t.durationMs,error:displayTurnError(t.error),items:(t.items||[]).map(item=>displayItem(item,t.id)).filter(Boolean)}));
        if(Buffer.byteLength(canonical(publicHistory))>32*1024*1024)throw fail('The public history archive exceeds the configured transfer limit. Native history and source work were preserved.');
        const packet={...row.packet,bundle,publicHistory};await writeFile(resolve(staging,'manifest.json'),canonical(packet),{mode:0o600});this.set(id,'transferring',{packet});
        const target=await this.transport(this.targets[row.target],{operation:'prepare',id,packet,staging});if(target.state!=='prepared'||target.forkId!==row.fork_id)throw fail('Destination did not confirm the native fork.');this.set(id,'ready');row=this.row(id);
      }
      if(row.state==='ready'){
        await this.checkSource(row.thread_id);const source=await this.readSource(row.thread_id);if(source.thread.status?.type==='active'||row.packet.boundaryTurnId&&(source.turn?.rootTurnId||source.turn?.id)!==row.packet.boundaryTurnId||source.turn&&source.turn.status!=='completed')throw fail('The source acquired new work during handover. Destination execution was withheld.');
        const fresh=await this.codex.call('thread/resume',{threadId:row.thread_id,excludeTurns:true});if(accountIdentity(await this.codex.call('account/read',{}))!==row.packet.account||canonical(effective(fresh))!==canonical(row.packet.settings)||canonical(await toolInventory(this.codex,row.thread_id))!==canonical(row.packet.tools))throw fail('Source account, permissions, model or dependencies changed during handover.');
        if(canonical(await workspaceEvidence(source.thread.cwd,row.plan.workspaceFiles))!==canonical(row.packet.evidence))throw fail('Source workspace changed during handover. Capture a new settled checkpoint.');
        this.set(id,'activating');
        const result=await this.transport(this.targets[row.target],{operation:'activate',id});if(result.state!=='accepted'||result.forkId!==row.fork_id)throw fail('Destination continuation was not confirmed.');this.set(id,'transferred');
      }
    }catch(e){row=this.row(id);if(exportingDirectory&&row?.state==='exporting')await rm(exportingDirectory,{recursive:true,force:true}).catch(()=>{});if(terminal.has(row?.state))return;const uncertain=['forking','transferring','activating','reconcile'].includes(row?.state);
      const transient=/CODEX_(UNAVAILABLE|DISCONNECTED|TIMEOUT)|ECONNREFUSED|ETIMEDOUT|ECONNRESET/.test(e.code||'');
      if(!uncertain&&transient)this.set(id,row.state,{error:'Waiting for runtime connectivity.',nextCheck:this.clock()+5*60000});
      else this.set(id,uncertain?'reconcile':'blocked',{error:e.message});
      if(row?.state==='forking')this.set(id,'unknown',{error:'Native fork delivery is uncertain. '+e.message});}
    finally{this.running.delete(id);}
  }
}

export class TerminalForkImports {
  constructor({db,codex,codeHome,directory,environmentId=null,onImportPlan=()=>{},onPrepared=()=>{},clock=Date.now}){Object.assign(this,{db,codex,codeHome,directory,environmentId,onImportPlan,onPrepared,clock});this.running=new Set();db.exec('CREATE TABLE IF NOT EXISTS terminal_fork_imports(id TEXT PRIMARY KEY,digest TEXT NOT NULL,packet TEXT NOT NULL,state TEXT NOT NULL,turn_id TEXT,error TEXT,updated_at INTEGER NOT NULL,fork_id TEXT)');if(!db.prepare('PRAGMA table_info(terminal_fork_imports)').all().some(r=>r.name==='fork_id'))db.exec('ALTER TABLE terminal_fork_imports ADD COLUMN fork_id TEXT');db.exec("UPDATE terminal_fork_imports SET fork_id=json_extract(packet,'$.bundle.forkId') WHERE fork_id IS NULL;CREATE INDEX IF NOT EXISTS terminal_fork_import_thread ON terminal_fork_imports(fork_id)");db.prepare("UPDATE terminal_fork_imports SET state='unknown',error='Continuation acknowledgement lost; no duplicate was started.' WHERE state='activating'").run();}
  row(id){return this.db.prepare('SELECT * FROM terminal_fork_imports WHERE id=?').get(id);}
  view(id){const r=this.db.prepare('SELECT id,state,fork_id,turn_id,error FROM terminal_fork_imports WHERE id=?').get(id);return r?{id,state:r.state,forkId:r.fork_id,turnId:r.turn_id,error:r.error||null}:{id,state:'absent'};}
  inputAllowed(threadId){if(this.db.prepare("SELECT 1 FROM terminal_fork_imports WHERE fork_id=? AND state!='accepted' LIMIT 1").get(threadId))throw fail('This destination is a prepared terminal fork. Its source must release primary ownership before it can accept input.');}
  async prepare(id){
    if(!nativeId(id))throw fail('Invalid migration identity.',400);if(this.running.has(id))throw fail('This import is already being checked.');this.running.add(id);
    try{
      const staging=resolve(this.directory,id),packet=JSON.parse(await readFile(resolve(staging,'manifest.json'),'utf8'));if(packet.plan?.id!==id||!nativeId(packet.bundle?.forkId))throw fail('Migration checkpoint identity mismatch.');
      if(this.environmentId&&packet.plan.target!==this.environmentId)throw fail('This checkpoint belongs to another destination environment.');
      const signature=digest(canonical(packet)),old=this.row(id);if(old&&old.digest!==signature)throw fail('This transfer ID already has a different native checkpoint.');if(old&&['prepared','accepted','unknown','activating'].includes(old.state))return this.view(id);
      if(accountIdentity(await this.codex.call('account/read',{}))!==packet.account)throw fail('Destination is signed in to a different account.');
      const evidence=await workspaceEvidence(packet.plan.cwd,packet.evidence.map(e=>e.path));if(canonical(evidence)!==canonical(packet.evidence))throw fail('Destination files do not match the source checkpoint. Retained dependencies are not workspace replacements.');
      if(!old)this.db.prepare("INSERT INTO terminal_fork_imports(id,digest,packet,state,turn_id,error,updated_at,fork_id) VALUES(?,?,?,'preparing',NULL,NULL,?,?)").run(id,signature,canonical(packet),this.clock(),packet.bundle.forkId);
      await importNativeFork({bundle:packet.bundle,codeHome:this.codeHome,directory:staging,onPlan:plan=>this.onImportPlan(plan,packet)});
      // Rebuild the native index from its own immutable logs; copied DB paths are stale.
      await this.codex.call('thread/list',{limit:1,sourceKinds:['cli','vscode','appServer'],useStateDbOnly:false});
      const result=await this.codex.call('thread/resume',{threadId:packet.bundle.forkId,cwd:packet.plan.cwd,excludeTurns:true});
      if(canonical(await toolInventory(this.codex,packet.bundle.forkId))!==canonical(packet.tools))throw fail('Destination MCP tools differ from the source checkpoint. Provision the matching dependencies before continuation.');
      if(canonical(effective(result))!==canonical(packet.settings))throw fail('Destination model or permissions differ from the source; no continuation started.');
      await this.codex.call('thread/read',{threadId:packet.bundle.forkId,includeTurns:true});
      if(packet.boundaryTurnId&&!packet.bundle.files.some(f=>(f.completedTurnIds||[]).includes(packet.boundaryTurnId)))throw fail('The imported native snapshot has no completed source boundary.');
      if(packet.sourceName)await this.codex.call('thread/name/set',{threadId:packet.bundle.forkId,name:packet.sourceName});
      this.db.prepare("UPDATE terminal_fork_imports SET state='prepared',error=NULL,updated_at=? WHERE id=?").run(this.clock(),id);this.onPrepared(result.thread,packet);return this.view(id);
    }catch(e){const row=this.row(id);if(row&&row.state==='preparing')this.db.prepare("UPDATE terminal_fork_imports SET state='blocked',error=?,updated_at=? WHERE id=?").run(e.message,this.clock(),id);throw e;}
    finally{this.running.delete(id);}
  }
  async activate(id){
    const row=this.row(id);if(!row)throw fail('Prepare the native fork before continuing.',404);if(['accepted','activating','unknown'].includes(row.state))return this.view(id);if(row.state!=='prepared')throw fail('The fork is not ready.');
    const p=JSON.parse(row.packet);const {thread}=await this.codex.call('thread/read',{threadId:p.bundle.forkId,includeTurns:false});if(thread.status?.type==='active')throw fail('The destination fork already has independent work.');
    if(accountIdentity(await this.codex.call('account/read',{}))!==p.account)throw fail('Destination account changed after preparation.');
    const inherited=await this.codex.call('thread/resume',{threadId:p.bundle.forkId,excludeTurns:true});if(canonical(effective(inherited))!==canonical(p.settings))throw fail('Destination permissions or model changed after preparation.');
    if(canonical(await toolInventory(this.codex,p.bundle.forkId))!==canonical(p.tools))throw fail('Destination MCP dependencies changed after preparation.');
    const {goal}=await this.codex.call('thread/goal/get',{threadId:p.bundle.forkId});if(goal)throw fail('The destination acquired a native goal. Reconcile its budget and ownership before continuing.');
    const evidence=await workspaceEvidence(p.plan.cwd,p.evidence.map(e=>e.path));if(canonical(evidence)!==canonical(p.evidence))throw fail('Destination workspace changed after preparation.');
    if(!this.db.prepare("UPDATE terminal_fork_imports SET state='activating',updated_at=? WHERE id=? AND state='prepared'").run(this.clock(),id).changes)return this.view(id);
    try{
      const text=`Continue from this native terminal fork; its original model context is preserved. Migration ${id} transferred primary ownership from ${p.sourceEnvironment}/${p.sourceThreadId}. Your primary scope: ${p.plan.primaryScope}. The source retains ONLY: ${p.plan.sourceScope||'read-only history; no delegated execution'}. Do not repeat source-side dependencies, accepted actions or uncertain delivery. Reconcile actual files, jobs and external receipts before acting. Preserve the original user scope, model, permissions, deliberate stops and holds. This is continuation, not a replay of the original request.\n\nCheckpoint:\n${p.plan.handoff}\n\nDependency inventory:\n${canonical(p.plan.dependencies)}`;
      const result=await this.codex.call('turn/start',{threadId:p.bundle.forkId,clientUserMessageId:'terminal-fork-'+id,input:[{type:'text',text}]});
      this.db.prepare("UPDATE terminal_fork_imports SET state='accepted',turn_id=?,error=NULL,updated_at=? WHERE id=?").run(result.turn.id,this.clock(),id);return this.view(id);
    }catch(e){this.db.prepare("UPDATE terminal_fork_imports SET state=?,error=?,updated_at=? WHERE id=?").run(e.rpc?'blocked':'unknown',e.message,this.clock(),id);throw e;}
  }
}
