import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,readFile,rm,stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {EventEmitter} from 'node:events';
import {openStore} from '../server/store.mjs';
import {HumanIngress} from '../server/human-ingress.mjs';
import {ProviderHandoff,publicHandoffItem} from '../server/provider-handoff.mjs';
import {ConversationTools,registerConversationTools} from '../server/conversation-tools.mjs';
import {qualifyPayload} from '../server/web-environments.mjs';

async function fixture(t){
 const dir=await mkdtemp(join(tmpdir(),'provider-handoff-'));t.after(()=>rm(dir,{recursive:true,force:true}));
 const {db}=openStore(dir);t.after(()=>db.close());
 db.prepare('INSERT INTO devices(id,name) VALUES(?,?)').run('phone-original','Phone');
 const admissions=new HumanIngress(db),calls=[],sent=[];
 const codex=new EventEmitter();codex.initializeResult={userAgent:'codex_pocket/0.171.0'};codex.transport={};codex.connect=async()=>{};
 const source={id:'source-thread',cwd:dir,path:join(dir,'rollout.jsonl'),status:{type:'idle'},historyMode:'paginated',modelProvider:'openai'};
 await writeFile(source.path,JSON.stringify({type:'session_meta',payload:{id:source.id,base_instructions:{text:'Preserve approvals and never buy anything.'},developer_instructions:'Keep source constraints.'}})+'\n',{mode:0o600});
 const settings={model:'source-model',modelProvider:'openai',cwd:dir,approvalPolicy:'on-request',sandbox:{type:'readOnly',networkAccess:false},activePermissionProfile:{id:':read-only'},runtimeWorkspaceRoots:[dir],disabledPluginIds:['disabled-plugin'],collaborationMode:{mode:'plan',settings:{model:'source-model',reasoning_effort:'medium',developer_instructions:null}}};
 const turns=[{id:'latest-turn',status:'completed',itemsView:'full',items:[{id:'u-last',type:'userMessage',content:[{type:'text',text:'Continue the correction, no purchases.'}]},{id:'agent-last',type:'agentMessage',text:'Commit abc123 is ready; external action receipt unknown.'},{id:'secret-state',type:'reasoning',content:['opaque-provider-state']} ]},{id:'older-turn',status:'completed',itemsView:'full',items:[{id:'cmd-old',type:'commandExecution',command:'git status',aggregatedOutput:'working tree clean',status:'completed',exitCode:0},{id:'child-old',type:'collabAgentToolCall',tool:'spawnAgent',receiverThreadIds:['existing-child'],status:'completed'}]}];
 let startError=null,onPage=()=>{},changePermissions=false;
 codex.call=async(method,p)=>{
  calls.push({method,p});
  if(method==='thread/queue/list')return {data:[],nextCursor:null};
  if(method==='thread/read')return {thread:source};
  if(method==='thread/resume')return {thread:source,...settings};
  if(method==='thread/turns/list'){onPage();return p.cursor?{data:[turns[1]],nextCursor:null}:{data:[turns[0]],nextCursor:'older'};}
  if(method==='thread/start'){if(startError)throw startError;return {...settings,modelProvider:p.modelProvider,model:p.model,sandbox:changePermissions?{type:'dangerFullAccess'}:settings.sandbox,thread:{...source,id:'destination-thread',modelProvider:p.modelProvider}};}
  throw Error('Unexpected '+method);
 };
 const handoff=new ProviderHandoff({db,codex,directory:join(dir,'handoffs'),providers:{selection:async p=>p}});
 const options={db,codex,handoff,admissions,supports:()=>true,admitContinuation:(thread,request,h)=>{
  const id='continue-'+request.id;db.prepare("INSERT INTO outgoing(id,thread_id,text,state,created_at,updated_at) VALUES(?,?,?,'queued',1,1)").run(id,thread.id,h.prompt);admissions.inherit('conversation',request.id,'reply',id);return id;
 },dispatchContinuation:id=>sent.push(id)};
 const controller=new ConversationTools(options),principal={kind:'paired-device',deviceId:'phone-original'};
 const request={id:'handoff-original',kind:'continue',expectedLastTurnId:'latest-turn',selection:{providerId:'deepseek',model:'deepseek-flash',effort:'high'},note:'Keep the pending receipt unresolved until inspected.'};
 return {db,dir,codex,controller,options,source,calls,sent,turns,settings,principal,request,setStartError:e=>startError=e,onPage:f=>onPage=f,changePermissions:()=>changePermissions=true};
}

test('authenticated continue saves evidence and linked outbox once; restart and repeated receipt never repeat creation',async t=>{
 const f=await fixture(t),{default:express}=await import('express');const app=express();app.use(express.json());
 app.use((req,res,next)=>{req.device={id:req.get('Authorization')==='Bearer phone'?'phone-original':'owner'};next();});
 app.use((req,res,next)=>f.options.admissions.middleware(req,res,next));
 registerConversationTools(app,f.controller,fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next));app.use((e,req,res,next)=>res.status(e.status||500).json({error:e.message}));
 const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));t.after(()=>new Promise(r=>server.close(r)));
 const url=`http://127.0.0.1:${server.address().port}/api/threads/${f.source.id}/conversation-operations`;
 const post=token=>fetch(url,{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify(f.request)});
 assert.equal((await post('owner')).status,403);
 const result=await (await post('phone')).json();assert.equal(result.state,'accepted',result.error);assert.equal(result.thread.id,'destination-thread');
 assert.equal(result.result.turnOptions,undefined);assert.equal(f.calls.filter(c=>c.method==='thread/start').length,1);
 const checkpoint=JSON.parse(await readFile(result.result.checkpoint.path,'utf8')),transcript=await readFile(checkpoint.transcript.path,'utf8');
 assert(!transcript.includes('opaque-provider-state'));assert(transcript.includes('existing-child'));assert(transcript.indexOf('older-turn')<transcript.indexOf('latest-turn'));
 assert.equal(checkpoint.latestUserRequest,'Continue the correction, no purchases.');assert.equal(checkpoint.coverage.turns,2);assert.equal((await stat(checkpoint.transcript.path)).mode&0o777,0o600);
 const start=f.calls.find(c=>c.method==='thread/start').p;assert.equal(start.permissions,':read-only');assert.equal(start.approvalPolicy,'on-request');assert.equal(start.developerInstructions,'Keep source constraints.');assert.equal(start.allowProviderModelFallback,false);
 const outgoing=f.db.prepare('SELECT * FROM outgoing').all();assert.equal(outgoing.length,1);assert.match(outgoing[0].text,/never replay unknown/);
 const authority=f.db.prepare("SELECT principal FROM human_admissions WHERE kind='reply'").get();assert.equal(JSON.parse(authority.principal).deviceId,'phone-original');
 const restored=new ConversationTools(f.options);await restored.operate('phone-original',f.source.id,f.request,f.principal);await post('phone');assert.equal(f.calls.filter(c=>c.method==='thread/start').length,1);assert.equal(f.sent.length,1);
 const scoped=qualifyPayload(result,'remote');assert.match(scoped.thread.id,/^env_remote__/);assert.match(scoped.threadId,/^env_remote__/);
 await assert.rejects(restored.operate('phone-original',f.source.id,{...f.request,id:'another-request'},f.principal),/already has a continuation/);
});

test('lost native creation acknowledgement stays unknown without another thread or turn',async t=>{
 const f=await fixture(t);f.setStartError(Error('Lost native acknowledgement'));
 const first=await f.controller.operate('phone-original',f.source.id,f.request,f.principal);assert.equal(first.state,'unknown');
 const restored=new ConversationTools(f.options);assert.equal((await restored.operate('phone-original',f.source.id,f.request,f.principal)).state,'unknown');
 assert.equal(f.calls.filter(c=>c.method==='thread/start').length,1);assert.equal(f.db.prepare('SELECT count(*) AS n FROM outgoing').get().n,0);assert.throws(()=>restored.assertInputAllowed(f.source.id),/uncertain/);
});

test('permission mismatch retains empty destination identity and never admits continuation',async t=>{
 const f=await fixture(t);f.changePermissions();const result=await f.controller.operate('phone-original',f.source.id,f.request,f.principal);
 assert.equal(result.state,'unknown');assert.equal(result.thread.id,'destination-thread');assert.match(result.error,/settings differ/);assert.equal(f.sent.length,0);
});

test('source activity/account changes and partial history abort before destination creation',async t=>{
 for(const scenario of ['active','account','partial','settings']){
  const f=await fixture(t);
  if(scenario==='active')f.onPage(()=>{f.source.status.type='active';});
  if(scenario==='settings')f.onPage(()=>f.codex.emit('event',{method:'thread/settings/updated',params:{threadId:f.source.id}}));
  if(scenario==='account')f.onPage(()=>f.codex.emit('event',{method:'account/updated'}));
  if(scenario==='partial')f.turns[0].itemsView='summary';
  const result=await f.controller.operate('phone-original',f.source.id,f.request,f.principal);assert.equal(result.state,'failed',scenario);assert(!f.calls.some(c=>c.method==='thread/start'));assert.equal(f.sent.length,0);
 }
});

test('public tool evidence excludes binary payloads and structured credentials',()=>{
 const item=publicHandoffItem({id:'tool',type:'mcpToolCall',result:{content:[{type:'text',text:'artifact ready'},{type:'image',data:'PRIVATE-BINARY'}],structuredContent:{api_key:'PRIVATE-KEY',encrypted_content:'OPAQUE',path:'/synthetic/artifact.png'}}});
 assert.match(item.output,/artifact ready/);assert.match(item.output,/artifact.png/);assert(!/PRIVATE-BINARY|PRIVATE-KEY|OPAQUE/.test(item.output));
});
