import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {readFile,realpath,stat} from 'node:fs/promises';
import {resolve,relative,isAbsolute} from 'node:path';
import {projectPath} from './sessions.mjs';
import {requireDirectSessionInput} from './session-catalog.mjs';
const execute=promisify(execFile);
const fail=(message,status=409)=>Object.assign(Error(message),{status});
const identifier=value=>{if(typeof value!=='string'||!/^[a-zA-Z0-9_-]{5,100}$/.test(value))throw fail('Invalid source identifier.',400);return value;};
const methods={fork:'thread/fork',revert:'thread/revert',compact:'thread/compact/start',review:'review/start',changeDirectory:'thread/settings/update'};
export const HISTORY_WARNING='This changes conversation context only. Files, commands, running jobs and external actions are not undone. Review their actual state before sending more work.';
const textOf=turn=>(turn.items||[]).filter(i=>i.type==='userMessage').flatMap(i=>i.content||[]).filter(c=>c.type==='text').map(c=>c.text).join('\n');

/** Explicit native controls. Every mutation has a durable source/device-bound
 * receipt BEFORE dispatch. Unknown outcomes are never retried, including after
 * process restart. Reads never attach a runtime or create model work. */
export class ConversationTools {
 constructor({db,codex,supports=()=>false,publish=()=>{},checkSource=()=>{},hasPending=()=>false,onFork=()=>{},onChanged=()=>{},clock=Date.now}){
  Object.assign(this,{db,codex,supports,publish,checkSource,hasPending,onFork,onChanged,clock});this.busy=new Set();this.usage=new Map();this.revertEvents=new Map();
  db.exec(`CREATE TABLE IF NOT EXISTS conversation_operations(id TEXT PRIMARY KEY,device TEXT NOT NULL,thread TEXT NOT NULL,kind TEXT NOT NULL,request TEXT NOT NULL,state TEXT NOT NULL,result TEXT,error TEXT,at INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS conversation_operation_acknowledgements(operation_id TEXT PRIMARY KEY,device TEXT NOT NULL,at INTEGER NOT NULL);
   UPDATE conversation_operations SET state='unknown',error='Connection ended before native acknowledgement. Inspect the session; this operation will not be replayed.' WHERE state='dispatching';`);
  codex.on?.('event',m=>this.observe(m));
 }
 observe(m){const p=m.params||{},id=p.threadId;if(!id)return;
  if(m.method==='thread/tokenUsage/updated'&&p.tokenUsage){this.usage.delete(id);this.usage.set(id,{...p.tokenUsage,turnId:p.turnId,observedAt:this.clock()});while(this.usage.size>256)this.usage.delete(this.usage.keys().next().value);this.publish('conversationUsage',{threadId:id,usage:this.usage.get(id)});}
  if(m.method==='thread/reverted'){const head=this.onChanged(id,'revert');if(this.busy.has(id))this.revertEvents.set(id,head);}
 }
 allowed(kind){return this.supports(methods[kind]);}
 assertInputAllowed(threadId,{ownOperation=false}={}){if(!ownOperation&&this.busy.has(threadId))throw fail('A conversation control is running. Wait for its receipt before sending more work.');if(this.db.prepare("SELECT 1 FROM conversation_operations WHERE thread=? AND state IN ('dispatching','unknown') AND kind IN ('revert','changeDirectory','compact','review') LIMIT 1").get(threadId))throw fail('A conversation operation is pending or uncertain. Inspect its receipt before sending more work.');}
 pairedDevice(device){return typeof device==='string'&&device!=='owner'&&!!this.db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='devices'").get()&&!!this.db.prepare('SELECT 1 FROM devices WHERE id=?').get(device);}
 receipt(device,thread,id){const r=this.db.prepare('SELECT * FROM conversation_operations WHERE id=?').get(identifier(id));if(!r||r.thread!==thread||r.device!==device&&(!['dispatching','unknown','acknowledged'].includes(r.state)||!this.pairedDevice(device)))return null;const acknowledgement=this.db.prepare('SELECT device,at FROM conversation_operation_acknowledgements WHERE operation_id=?').get(r.id);const result=r.result?JSON.parse(r.result):null;return {id:r.id,threadId:r.thread,kind:r.kind,state:r.state,sharedRecovery:r.device!==device,...(acknowledgement?{acknowledgedBy:acknowledgement.device,acknowledgedAt:acknowledgement.at}:{}),...(result?.thread?{thread:result.thread}:{}),result,error:r.error,createdAt:r.at};}
 async source(threadId){identifier(threadId);await this.codex.connect();const {thread}=await this.codex.call('thread/read',{threadId,includeTurns:false});if(thread?.id!==threadId)throw fail('The native response belongs to another session.');return thread;}
 async turns(threadId,cursor=null){if(cursor!==null&&(typeof cursor!=='string'||cursor.length>16384))throw fail('Invalid turn cursor.',400);return this.codex.call('thread/turns/list',{threadId,limit:30,sortDirection:'desc',itemsView:'full',...(cursor?{cursor}:{})});}
 async describe(threadId,device,cursor=null){const thread=await this.source(threadId),page=await this.turns(threadId,cursor);return {threadId,cwd:thread.cwd,historyMode:thread.historyMode,active:thread.status?.type==='active',usage:this.usage.get(threadId)||null,warning:HISTORY_WARNING,
  features:Object.fromEntries(Object.keys(methods).map(k=>[k,{available:this.allowed(k)&&(k!=='revert'||thread.historyMode==='paginated'),reason:k==='revert'&&thread.historyMode!=='paginated'?'Native revert requires paginated history.':this.allowed(k)?null:'This native runtime version is not qualified for this control.'}])),
  turns:(page.data||[]).map(t=>({id:t.id,status:t.status,startedAt:t.startedAt,userText:textOf(t).slice(0,32000)})),nextCursor:page.nextCursor||null,
  operations:this.db.prepare("SELECT id FROM conversation_operations WHERE thread=? AND (device=? OR (? AND state IN ('dispatching','unknown','acknowledged'))) ORDER BY CASE WHEN state IN ('dispatching','unknown') THEN 0 ELSE 1 END,at DESC LIMIT 12").all(threadId,device,this.pairedDevice(device)?1:0).map(r=>this.receipt(device,threadId,r.id)),
  worktree:{available:false,reason:'Managed worktree creation is not exposed by the qualified native protocol. Select an existing worktree folder or use native Codex to create one.'}};}
 validate(threadId,input){identifier(threadId);identifier(input?.id);const kind=input.kind;if(!methods[kind])throw fail('Unsupported conversation operation.',400);const p={id:input.id,kind};
  if(['fork','revert'].includes(kind)){p.turnId=identifier(input.turnId);p.expectedLastTurnId=identifier(input.expectedLastTurnId);}
  if(kind==='revert'&&input.confirmContextOnly!==true)throw fail(HISTORY_WARNING,400);
  if(kind==='revert')p.confirmContextOnly=true;
  if(kind==='changeDirectory')p.cwd=projectPath(input.cwd);
  if(kind==='review'){const target=input.target||{type:'uncommittedChanges'};if(target.type==='uncommittedChanges')p.target={type:target.type};else if(target.type==='baseBranch'&&typeof target.branch==='string'&&target.branch.trim()&&!target.branch.startsWith('-')&&target.branch.length<=256)p.target={type:target.type,branch:target.branch};else if(target.type==='commit'&&/^[a-fA-F0-9]{7,64}$/.test(target.sha||''))p.target={type:target.type,sha:target.sha};else throw fail('Choose working changes, a base branch or a commit SHA.',400);}
  return p;
 }
 async operate(device,threadId,input){const p=this.validate(threadId,input),request=JSON.stringify(p),old=this.db.prepare('SELECT * FROM conversation_operations WHERE id=?').get(p.id);
  if(old){if(old.device!==device||old.thread!==threadId||old.request!==request)throw fail('This operation identifier belongs to another request.');return this.receipt(device,threadId,p.id);}
  if(this.busy.has(threadId))throw fail('A conversation control is already running.');this.busy.add(threadId);
  try{
   const thread=await this.source(threadId);if(!this.allowed(p.kind))throw fail('This native runtime version does not support the requested control.',422);
   this.checkSource(threadId);this.assertInputAllowed(threadId,{ownOperation:true});if(this.db.prepare("SELECT 1 FROM conversation_operations WHERE thread=? AND kind='fork' AND state IN ('dispatching','unknown') LIMIT 1").get(threadId))throw fail('An earlier fork has an uncertain outcome. Inspect and acknowledge its receipt before creating another branch.');if(p.kind!=='fork')requireDirectSessionInput(thread);
   if(p.kind!=='fork'&&(thread.status?.type==='active'||this.hasPending(threadId)))throw fail('Wait for active work and queued messages to settle before changing this conversation.');
   if(p.kind==='changeDirectory'&&thread.parentThreadId)throw fail('Change folders in a primary conversation. A delegated agent keeps its assigned workspace.');
   let selected;
   if(['fork','revert'].includes(p.kind)){
    if(p.kind==='revert'&&thread.historyMode!=='paginated')throw fail('Native revert is available only for paginated history.',422);
    const page=await this.turns(threadId);if(page.data?.[0]?.id!==p.expectedLastTurnId)throw fail('The conversation has changed. Refresh and choose the boundary again.');
    selected=page.data.find(t=>t.id===p.turnId);if(!selected)throw fail('Choose a turn from the current recent boundary list.');
    if(selected.status==='inProgress')throw fail('Choose a settled turn.');
   }
   const params={threadId};if(p.kind==='fork')Object.assign(params,{lastTurnId:p.turnId,excludeTurns:true,deferGoalContinuation:true});if(p.kind==='revert')params.beforeTurnId=p.turnId;if(p.kind==='review')Object.assign(params,{target:p.target,delivery:'inline'});if(p.kind==='changeDirectory')params.cwd=p.cwd;
   this.db.prepare("INSERT INTO conversation_operations(id,device,thread,kind,request,state,at) VALUES(?,?,?,?,?,'dispatching',?)").run(p.id,device,threadId,p.kind,request,this.clock());
   try{
    // These explicit controls need a loaded session. Resume preserves every native
    // setting and never sends model input. Fork loads its own persisted source.
    if(p.kind!=='fork'){const resumed=await this.codex.call('thread/resume',{threadId,excludeTurns:true});if(resumed.thread?.id!==threadId)throw fail('Native attachment returned another session.');}
    const native=await this.codex.call(methods[p.kind],params);const result={};
    if(p.kind==='fork'){if(!native.thread?.id||native.thread.id===threadId||native.thread.forkedFromId&&native.thread.forkedFromId!==threadId)throw fail('Native fork identity was not confirmed.');result.thread=native.thread;this.onFork(native.thread);}
    if(p.kind==='revert'){if(native.thread?.id!==threadId)throw fail('Native history replacement identity was not confirmed.');result.draft=selected?textOf(selected):'';result.beforeTurnId=p.turnId;result.warning=HISTORY_WARNING;result.historyHead=this.revertEvents.has(threadId)?this.revertEvents.get(threadId):this.onChanged(threadId,'revert');}
    if(p.kind==='review')result.turnId=native.turn?.id||null;
    if(p.kind==='compact')result.message='Compaction requested. Completion and context usage follow native events.';
    if(p.kind==='changeDirectory'){result.cwd=p.cwd;this.onChanged(threadId,'changeDirectory');}
    this.db.prepare("UPDATE conversation_operations SET state='accepted',result=? WHERE id=?").run(JSON.stringify(result),p.id);
   }catch(e){this.db.prepare('UPDATE conversation_operations SET state=?,error=? WHERE id=?').run(e.rpc?'failed':'unknown',String(e.message).slice(0,2000),p.id);}
   const receipt=this.receipt(device,threadId,p.id);this.publish('conversationOperation',{threadId,operation:receipt});return receipt;
  }finally{this.busy.delete(threadId);this.revertEvents.delete(threadId);}
 }
 acknowledge(device,threadId,id){
  if(device==='owner')throw fail('Human recovery requires a paired device; an administrator cannot impersonate it.',403);
  const r=this.receipt(device,threadId,id);if(!r)throw fail('Operation not found.',404);if(r.state!=='unknown')return r;
  this.db.exec('BEGIN IMMEDIATE');try{
   this.db.prepare("UPDATE conversation_operations SET state='acknowledged',error='Unknown outcome acknowledged after inspection; operation was not replayed.' WHERE id=? AND state='unknown'").run(id);
   this.db.prepare('INSERT OR IGNORE INTO conversation_operation_acknowledgements(operation_id,device,at) VALUES(?,?,?)').run(id,device,this.clock());
   this.db.exec('COMMIT');
  }catch(error){this.db.exec('ROLLBACK');throw error;}
  return this.receipt(device,threadId,id);
 }
 async files(threadId,query){if(typeof query!=='string'||!query.trim()||query.length>256)throw fail('Enter a file search of 1–256 characters.',400);const t=await this.source(threadId);if(!this.supports('fuzzyFileSearch'))throw fail('Native file search is unavailable.',422);const cwd=projectPath(t.cwd),r=await this.codex.call('fuzzyFileSearch',{query,roots:[cwd]});const files=[];for(const f of (r.files||[]).slice(0,60)){const path=resolve(f.root||cwd,f.path||'');if(!inside(cwd,path))continue;files.push({path,relativePath:relative(cwd,path),type:f.match_type});}return {threadId,cwd,files};}
 async selection(threadId,input){const t=await this.source(threadId),cwd=projectPath(t.cwd);if(typeof input.path!=='string'||input.path.length>4096)throw fail('Choose a host file.',400);const path=await realpath(resolve(cwd,input.path));if(!inside(cwd,path))throw fail('Choose a file within this conversation’s working directory.',400);const st=await stat(path);if(!st.isFile()||st.size>512000)throw fail('Choose a text file smaller than 512 KB.',400);const content=await readFile(path,'utf8');if(content.includes('\0'))throw fail('Binary files cannot be inserted as text.',400);const start=Number(input.start||1),end=Number(input.end||start+79);if(!Number.isInteger(start)||!Number.isInteger(end)||start<1||end<start||end-start>=200)throw fail('Choose a selection of at most 200 lines.',400);const lines=content.split('\n');if(start>lines.length)throw fail('The selected line is past the end of the file.',400);const text=lines.slice(start-1,end).join('\n');if(text.length>32000)throw fail('This selection is too large. Choose fewer lines.',400);return {threadId,path,start,end:Math.min(end,lines.length),totalLines:lines.length,text};}
 async diff(threadId){const thread=await this.source(threadId);return {threadId,...await workspaceDiff(projectPath(thread.cwd))};}
}
const inside=(root,path)=>{const rel=relative(root,path);return rel!==''&&!rel.startsWith('../')&&rel!=='..'&&!isAbsolute(rel);};
export async function workspaceDiff(cwd){const git=async args=>(await execute('git',['-c','core.fsmonitor=false','-c','core.quotePath=false',...args],{cwd,timeout:8000,maxBuffer:1024*1024,env:{...process.env,GIT_OPTIONAL_LOCKS:'0',GIT_PAGER:'cat'}})).stdout;
 const root=(await git(['rev-parse','--show-toplevel'])).trim();let tracked;try{tracked=await git(['diff','--no-ext-diff','--no-textconv','--no-color','HEAD','--']);}catch(e){if((await git(['rev-parse','--verify','--quiet','HEAD']).catch(()=>'')))throw e;tracked=await git(['diff','--cached','--no-ext-diff','--no-textconv','--no-color','--']);tracked+=await git(['diff','--no-ext-diff','--no-textconv','--no-color','--']);}
 const status=await git(['status','--short','--untracked-files=normal']);const untracked=(await git(['ls-files','--others','--exclude-standard','-z'])).split('\0').filter(Boolean);let text=tracked;let omitted=0;
 for(const path of untracked.slice(0,30)){const absolute=resolve(cwd,path);const canonical=await realpath(absolute).catch(()=>null);if(!canonical||!inside(root,canonical)){omitted++;continue;}const st=await stat(canonical);if(!st.isFile()||st.size>64000||text.length>800000){omitted++;continue;}const body=await readFile(canonical,'utf8');text+='\nUntracked: '+path+'\n'+(body.includes('\0')?'[binary file]\n':body.split('\n').map(l=>'+'+l).join('\n'))+'\n';}
 return {cwd,root,status,diff:text,untrackedCount:untracked.length,omittedUntracked:omitted+Math.max(0,untracked.length-30),readOnly:true};}
export function registerConversationTools(app,controller,route){const root='/api/threads/:id';app.get(root+'/conversation-tools',route(async(req,res)=>res.set('Cache-Control','no-store').json(await controller.describe(req.params.id,req.device.id,req.query.cursor||null))));app.post(root+'/conversation-operations',route(async(req,res)=>res.json(await controller.operate(req.device.id,req.params.id,req.body))));app.get(root+'/conversation-operations/:operationId',route(async(req,res)=>{const r=controller.receipt(req.device.id,req.params.id,req.params.operationId);if(!r)return res.status(404).json({error:'Operation not found.'});res.set('Cache-Control','no-store').json(r);}));app.post(root+'/conversation-operations/:operationId/acknowledge',route(async(req,res)=>{if(req.body?.inspected!==true)throw fail('Inspect the conversation and workspace before acknowledging an unknown result.',400);res.json(controller.acknowledge(req.device.id,req.params.id,req.params.operationId));}));app.get(root+'/workspace-files',route(async(req,res)=>res.set('Cache-Control','no-store').json(await controller.files(req.params.id,req.query.q))));app.get(root+'/workspace-selection',route(async(req,res)=>res.set('Cache-Control','no-store').json(await controller.selection(req.params.id,req.query))));app.get(root+'/workspace-diff',route(async(req,res)=>res.set('Cache-Control','no-store').json(await controller.diff(req.params.id))));}
