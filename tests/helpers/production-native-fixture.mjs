import {pluginReply,pluginAccountReply} from './native-plugin-fixture.mjs';
// Real server/index composition; the only substituted authority is native RPC.
// All data and credentials are disposable. No inherited provider/runtime config.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm,mkdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {once} from 'node:events';
import http from 'node:http';
import {WebSocket,WebSocketServer} from 'ws';
import {createRequire} from 'node:module';
import {AjvJsonSchemaValidator} from '@modelcontextprotocol/sdk/validation/ajv-provider.js';

const contract=JSON.parse(await readFile(new URL('../fixtures/native-contract-0167/schemas.json',import.meta.url)));
// Use the validator already owned by the declared MCP SDK dependency; no install.
const require=createRequire(import.meta.resolve('@modelcontextprotocol/sdk/validation/ajv-provider.js'));
const Ajv=require('ajv'),ajv=new Ajv({strict:false,allErrors:true,validateFormats:true});require('ajv-formats')(ajv);
for(const [format,max] of Object.entries({uint16:65535,uint32:4294967295,uint64:Number.MAX_SAFE_INTEGER,uint:Number.MAX_SAFE_INTEGER}))ajv.addFormat(format,{type:'number',validate:value=>Number.isSafeInteger(value)&&value>=0&&value<=max});
const validator=new AjvJsonSchemaValidator(ajv),checks=new Map();
export function wireCheck(name,value){
 let check=checks.get(name);
 if(!check){assert.ok(contract.schemas[name],`Missing generated schema ${name}`);check=validator.getValidator({...contract.schemas[name],definitions:contract.definitions});checks.set(name,check);}
 const result=check(value);assert.equal(result.valid,true,`${name}: ${result.errorMessage}`);return value;
}
export const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
export async function until(read,predicate,label,timeout=5000){
 const deadline=Date.now()+timeout;let value;
 do{value=await read();if(predicate(value))return value;await delay(20);}while(Date.now()<deadline);
 throw Error(`Timed out: ${label}; last=${JSON.stringify(value)}`);
}
export function nativeThread(id='thread-shared',name='Source A'){
 return {id,environments:null,extra:null,sessionId:id,forkedFromId:null,parentThreadId:null,preview:'Synthetic request',ephemeral:false,section:null,sectionEnteredAt:null,projectId:null,historyMode:'paginated',modelProvider:'openai',model:'fixture-no-model',reasoningEffort:null,createdAt:1700000000,updatedAt:1700000001,recencyAt:null,status:{type:'idle'},path:null,cwd:'/synthetic',cliVersion:'0.167.0',originator:'codex-tui',source:'cli',canAcceptDirectInput:true,threadSource:null,agentNickname:null,agentRole:null,gitInfo:null,name,daybreakEnabled:null,turns:[]};
}
export const userItem=(id,text)=>({id,type:'userMessage',content:[{type:'text',text,text_elements:[]}]});
export const agentItem=(id,text)=>({id,type:'agentMessage',text,phase:'final_answer',memoryCitation:null});
export const nativePage=(text='Current answer',start=10,cursor=null)=>({data:[{type:'item',position:start,turnId:'turn-current',item:userItem('input-'+start,'Synthetic request')},{type:'item',position:start+1,turnId:'turn-current',item:agentItem('answer-'+start,text)}],nextCursor:cursor,activeRealtimeSessionAtPageStart:null});
export function question(id=73,threadId='thread-shared'){
 return {id,method:'item/tool/requestUserInput',params:wireCheck('ToolRequestUserInputParams',{threadId,turnId:'turn-current',itemId:'question-item',isBlocking:true,autoResolutionMs:null,questions:[{id:'choice',header:'Choice',question:'Which fixture?',isOther:false,isSecret:true,options:null}]})};
}

export async function productionFixture(t,{name='Source A'}={}){
 const dir=await mkdtemp(join(tmpdir(),'nextcomp-seam-')),socket=join(dir,'native.sock');
 await mkdir(join(dir,'home'));const calls=[],answers=[],faults=[],events=[];
 const f={dir,calls,answers,faults,events,thread:nativeThread('thread-shared',name),page:nativePage(name),hold:null,dropNext:false,loseCompact:false,loseSend:false};
 let child,logs='',base,admin,device;
 const native=http.createServer(),wss=new WebSocketServer({server:native});
 await new Promise(resolve=>native.listen(socket,resolve));
 const send=(ws,id,result,schema)=>ws.send(JSON.stringify({id,result:schema?wireCheck(schema,result):result}));
 wss.on('connection',ws=>ws.on('message',async raw=>{
  const m=JSON.parse(raw);if(m.id===undefined)return;
  if(!m.method){try{wireCheck('ToolRequestUserInputResponse',m.result);answers.push(m);}catch(e){faults.push(e.message);}return;}
  calls.push(m);
  try{
   const paramsSchema={initialize:'InitializeParams','thread/list':'ThreadListParams','thread/read':'ThreadReadParams','thread/timeline/list':'ThreadTimelineListParams','thread/turns/list':'ThreadTurnsListParams','thread/resume':'ThreadResumeParams','thread/items/list':'ThreadItemsListParams'}[m.method];if(paramsSchema)wireCheck(paramsSchema,m.params);
   if(m.method==='account/rateLimits/read')return send(ws,m.id,pluginAccountReply(m.params));
   if(m.method==='initialize')return send(ws,m.id,{userAgent:'codex/0.167.0',codexHome:join(dir,'home'),platformFamily:'unix',platformOs:'linux'},'InitializeResponse');
   if(m.method==='thread/list')return send(ws,m.id,{data:[f.thread],nextCursor:null},'ThreadListResponse');
   if(m.method==='thread/read')return send(ws,m.id,{thread:f.thread},'ThreadReadResponse');
   if(m.method==='thread/timeline/list'){
    if(f.dropNext){f.dropNext=false;ws.terminate();return;}
    const page=structuredClone(f.page);
    if(f.hold){const hold=f.hold;f.hold=null;hold.started();await hold.wait;}
    return send(ws,m.id,page,'ThreadTimelineListResponse');
   }
   if(m.method==='thread/turns/list')return send(ws,m.id,{data:[{id:'turn-current',rootTurnId:null,items:[userItem('original','Original request')],itemsView:'full',status:'completed',error:null,startedAt:1700000000,completedAt:1700000001,durationMs:1000}],nextCursor:null,backwardsCursor:null},'ThreadTurnsListResponse');
   if(m.method==='thread/resume')return send(ws,m.id,{thread:f.thread,model:'fixture-no-model',modelProvider:'openai',serviceTier:null,disabledPluginIds:[],cwd:'/synthetic',runtimeWorkspaceRoots:[],instructionSources:[],approvalPolicy:'never',approvalsReviewer:'user',sandbox:{type:'readOnly'},activePermissionProfile:null,reasoningEffort:null,collaborationMode:null,multiAgentMode:'explicitRequestOnly',initialTurnsPage:null,turnsBackwardsCursor:null,itemsBackwardsCursor:null},'ThreadResumeResponse');
   if(m.method==='thread/compact/start'){
    wireCheck('ThreadCompactStartParams',m.params);
    assert.equal(f.loseCompact,true,'Fixture only permits explicitly armed synthetic compaction loss');
    ws.terminate();return;
   }
   if(m.method==='thread/items/list'){
    assert.equal(m.params.threadId,f.thread.id);
    assert.ok(!m.params.cursor,'Cold-page fixture has only one retained items page');
    const entries=f.page.data.filter(e=>e.type==='item'&&(!m.params.turnId||e.turnId===m.params.turnId)).map(e=>({turnId:e.turnId,item:e.item,startedAtMs:null,completedAtMs:null}));
    if(m.params.sortDirection==='desc')entries.reverse();
    return send(ws,m.id,{data:entries,nextCursor:null,backwardsCursor:null},'ThreadItemsListResponse');
   }
   if(m.method==='turn/start'){
    wireCheck('TurnStartParams',m.params);
    assert.equal(f.loseSend,true,'Fixture only permits explicitly armed synthetic human-send loss');
    assert.equal(m.params.threadId,f.thread.id);
    assert.ok(m.params.clientUserMessageId,'Synthetic send must retain its original client ID');
    ws.terminate();return;
   }
   // Opt-in synthetic configuration state. Validate both directions against
   // the generated protocol; never write a real config or refresh connectors.
   if(f.configuration&&['config/read','app/installed','config/value/write'].includes(m.method)){
    const state=f.configuration,version=()=>`fixture-config-${state.version}`,filePath=join(dir,'home','config.toml');
    wireCheck({'config/read':'ConfigReadParams','app/installed':'AppsInstalledParams','config/value/write':'ConfigValueWriteParams'}[m.method],m.params);
    if(m.method==='app/installed'){
     assert.equal(m.params.forceRefresh,false);assert.equal(m.params.threadId,f.thread.id);
     return send(ws,m.id,{apps:[{id:state.id,runtimeName:state.name,enabled:state.enabled,callable:false}]},'AppsInstalledResponse');
    }
    const config={model:'fixture-no-model',apps:{[state.id]:{enabled:state.enabled}}};
    if(m.method==='config/read')return send(ws,m.id,{config,origins:{},layers:m.params.includeLayers?[{name:{type:'user',file:filePath,profile:null},version:version(),config,disabledReason:null}]:null},'ConfigReadResponse');
    assert.equal(m.params.keyPath,`apps.${state.id}.enabled`);assert.equal(m.params.mergeStrategy,'replace');assert.equal(typeof m.params.value,'boolean');
    if(m.params.expectedVersion!==version())return ws.send(JSON.stringify({id:m.id,error:{code:-32600,message:'Synthetic native configuration version conflict'}}));
    state.enabled=m.params.value;state.version++;
    return send(ws,m.id,{status:'ok',version:version(),filePath,overriddenMetadata:null},'ConfigWriteResponse');
   }
   if(f.plugins&&m.method.startsWith('plugin/')){const reply=await pluginReply(f.plugins,m.method,m.params);if(reply){if(reply.drop){ws.terminate();return;}return ws.send(JSON.stringify({id:m.id,...reply}));}}
   // Startup probes are explicitly unsupported, never permissive empty success.
   if(!['account/read','account/rateLimits/read','model/list','thread/loaded/list'].includes(m.method))faults.push(`Unexpected native method ${m.method}`);
   ws.send(JSON.stringify({id:m.id,error:{code:-32601,message:`${m.method} not supported by bounded fixture`}}));
  }catch(e){faults.push(e.stack);ws.send(JSON.stringify({id:m.id,error:{code:-32603,message:e.message}}));}
 }));
 const stop=async()=>{if(child&&child.exitCode===null){const exited=once(child,'exit');child.kill('SIGTERM');const timer=setTimeout(()=>child.kill('SIGKILL'),2000);await exited;clearTimeout(timer);}child=null;};
 f.close=async()=>{await stop();for(const s of wss.clients)s.terminate();wss.close();await new Promise(r=>native.close(r));await rm(dir,{recursive:true,force:true});};
 t.after(f.close);
 f.boot=async()=>{
  const allocator=http.createServer();await new Promise(r=>allocator.listen(0,'127.0.0.1',r));const port=allocator.address().port;await new Promise(r=>allocator.close(r));base=`http://127.0.0.1:${port}`;
  child=spawn(process.execPath,['server/index.mjs'],{cwd:new URL('../../',import.meta.url),env:{PATH:process.env.PATH,HOME:join(dir,'home'),CODEX_HOME:join(dir,'home'),POCKET_DATA:dir,POCKET_DEFAULT_CWD:dir,CODEX_SOCKET:socket,PORT:String(port)},stdio:['ignore','pipe','pipe']});
  child.stdout.on('data',data=>logs=(logs+data).slice(-20000));child.stderr.on('data',data=>logs=(logs+data).slice(-20000));
  await until(async()=>{if(child.exitCode!==null)throw Error('Backend exited: '+logs);try{return (await fetch(base+'/health',{signal:AbortSignal.timeout(500)})).ok;}catch{return false;}},Boolean,'isolated backend health');
  admin=JSON.parse(await readFile(join(dir,'secrets.json'))).adminToken;
 };
 f.request=async(path,body,token=device?.token||admin)=>{
  const response=await fetch(base+path,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(7000)});
  return {status:response.status,data:await response.json()};
 };
 f.api=async(path,body)=>{const r=await f.request(path,body);if(r.status>=400)throw Object.assign(Error(r.data.error),{status:r.status,code:r.data.code});return r.data;};
 f.restart=async()=>{await stop();await f.boot();};
 f.emit=value=>{const schema={'thread/reverted':'ThreadRevertedNotification','serverRequest/resolved':'ServerRequestResolvedNotification','item/tool/requestUserInput':'ToolRequestUserInputParams'}[value.method];assert.ok(schema,'Only declared native fixture events are permitted');wireCheck(schema,value.params);for(const ws of wss.clients)if(ws.readyState===1)ws.send(JSON.stringify(value));};
 f.disconnect=()=>{for(const ws of wss.clients)ws.terminate();};
 f.barrier=()=>{let started,release;const entered=new Promise(r=>started=r),wait=new Promise(r=>release=r);f.hold={started,wait};return {entered,release};};
 f.watch=async()=>{const ws=new WebSocket(base.replace('http','ws')+'/events',{headers:{Authorization:'Bearer '+device.token}});ws.on('message',raw=>events.push(JSON.parse(raw)));await once(ws,'open');t.after(()=>ws.terminate());return ws;};
 f.assertClean=()=>assert.deepEqual(faults,[],`Fixture contract failures; backend tail: ${logs}`);
 Object.defineProperties(f,{pid:{get:()=>child?.pid},logs:{get:()=>logs},base:{get:()=>base},token:{get:()=>device.token},deviceId:{get:()=>device.id}});
 await f.boot();const pairing=await f.request('/api/pairing',{},admin);assert.equal(pairing.status,200);const paired=await f.request('/api/pair',{code:pairing.data.code,name:'Disposable integration client'},'');assert.equal(paired.status,200);device=paired.data;
 await f.api('/api/threads/thread-shared/native-timeline');f.assertClean();return f;
}
