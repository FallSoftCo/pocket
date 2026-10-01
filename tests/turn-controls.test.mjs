import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {WebSocketServer} from 'ws';

const waitFor=async fn=>{for(let i=0;i<180;i++){const value=await fn();if(value)return value;await new Promise(r=>setTimeout(r,30));}throw Error('Condition not reached');};
async function fixture(t){
 const dir=await mkdtemp(join(tmpdir(),'pocket-turn-controls-')),socket=join(dir,'codex.sock');
 const host=http.createServer(),wss=new WebSocketServer({server:host});await new Promise(r=>host.listen(socket,r));
 const state={active:true,turnId:'turn-current',calls:[],peer:null,busy:false,drop:false};
 const thread=()=>({id:'task-live',name:'Controls',status:{type:state.active?'active':'idle'},turns:[{id:state.turnId,status:state.active?'inProgress':'completed',items:[]}]});
 wss.on('connection',ws=>{state.peer=ws;ws.on('message',raw=>{
  const m=JSON.parse(raw);if(!m.id)return;state.calls.push(m);let result={};
  if(m.method==='thread/read'||m.method==='thread/resume')result={thread:thread()};
  if(m.method==='turn/steer')result={turnId:state.turnId};
  if(m.method==='model/list')result={data:[{model:'test-model',displayName:'Test model',defaultReasoningEffort:'medium',supportedReasoningEfforts:[{reasoningEffort:'medium',description:'Standard'},{reasoningEffort:'high',description:'More reasoning'}]}]};
  if(m.method==='turn/start'){
   if(state.busy){state.busy=false;state.active=true;ws.send(JSON.stringify({id:m.id,error:{code:-32600,message:'A turn is already active'}}));return;}
   state.active=true;state.turnId=`turn-${state.calls.length}`;result={turn:{id:state.turnId,status:'inProgress'}};
   if(state.drop){state.drop=false;ws.terminate();return;}
  }
  ws.send(JSON.stringify({id:m.id,result}));
 });});
 const port=24000+Math.floor(Math.random()*3000),url=`http://127.0.0.1:${port}`;
 let child;
 const start=async()=>{child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});await waitFor(async()=>{try{return (await fetch(url+'/health')).ok;}catch{return false;}});};
 const stop=async()=>{if(!child||child.exitCode!==null)return;await new Promise(r=>{child.once('exit',r);child.kill();});};
 t.after(async()=>{await stop();for(const ws of wss.clients)ws.terminate();await new Promise(r=>wss.close(r));await new Promise(r=>host.close(r));await rm(dir,{recursive:true,force:true});});
 await start();const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json'),'utf8'));
 const api=async(path,body)=>{const r=await fetch(url+path,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${adminToken}`,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});return {status:r.status,data:await r.json()};};
 await api('/api/threads/task-live');
 const reply=(id,text,mode='queue')=>api('/api/threads/task-live/reply',{id,text,mode});
 const status=async id=>(await api(`/api/replies/${id}`)).data.state;
 const finish=async(status='completed')=>{state.active=false;state.peer.send(JSON.stringify({method:'turn/completed',params:{threadId:'task-live',turn:{id:state.turnId,status,items:[]}}}));};
 return {state,api,reply,status,finish,start,stop};
}

test('queued messages wait, can be edited or removed, and run in FIFO order one turn at a time',async t=>{
 const f=await fixture(t);
 assert.equal((await f.reply('invalid','Bad','unknown')).status,400);
 await f.reply('first','Original');await f.reply('second','Second');await f.reply('removed','Remove this');
 assert.equal((await f.reply('first','Original','steer')).status,409);
 assert.equal((await f.api('/api/threads/other/replies/first',{action:'remove'})).status,404);
 assert.equal((await f.api('/api/threads/task-live/replies/first',{action:'edit',text:''})).status,400);
 assert.equal((await f.api('/api/threads/task-live/replies/first',{action:'edit',text:'Edited first'})).status,200);
 await f.api('/api/threads/task-live/replies/removed',{action:'remove'});
 assert.equal(await f.status('removed'),'cancelled');
 assert.equal(f.state.calls.filter(x=>x.method==='turn/start'||x.method==='turn/steer').length,0);
 await f.finish();await waitFor(async()=>await f.status('first')==='accepted');
 assert.equal(await f.status('second'),'queued');
 assert.deepEqual(f.state.calls.filter(x=>x.method==='turn/start').map(x=>x.params.input[0].text),['Edited first']);
 assert.equal((await f.api('/api/threads/task-live/replies/first',{action:'edit',text:'Too late'})).status,409);
 await f.finish();await waitFor(async()=>await f.status('second')==='accepted');
 assert.deepEqual(f.state.calls.filter(x=>x.method==='turn/start').map(x=>x.params.input[0].text),['Edited first','Second']);
});

test('steer now promotes one queued message without disturbing the rest; stop pauses the queue until resume',async t=>{
 const f=await fixture(t);await f.reply('first','Hold this');await f.reply('second','Guide now');
 await f.api('/api/threads/task-live/replies/second',{action:'send'});await waitFor(async()=>await f.status('second')==='accepted');
 assert.deepEqual(f.state.calls.find(x=>x.method==='turn/steer').params,{threadId:'task-live',expectedTurnId:'turn-current',input:[{type:'text',text:'Guide now'}]});
 assert.equal((await f.api('/api/threads/task-live/interrupt',{})).status,200);
 assert.equal(await f.status('first'),'held');
 await f.finish('interrupted');await f.reply('third','After resume');
 assert.equal(await f.status('third'),'queued');
 assert.equal(f.state.calls.filter(x=>x.method==='turn/start').length,0);
 await f.api('/api/threads/task-live/queue/resume',{});await waitFor(async()=>await f.status('first')==='accepted');
 assert.equal(await f.status('third'),'queued');
 await f.finish();await waitFor(async()=>await f.status('third')==='accepted');
});

test('queue survives bridge restart and pauses after failed turns',async t=>{
 const f=await fixture(t);await f.reply('survivor','Durable follow-up');
 await f.stop();await f.start();assert.equal(await f.status('survivor'),'queued');
 await f.api('/api/threads/task-live');await f.finish('failed');await waitFor(async()=>await f.status('survivor')==='held');
 await f.stop();await f.start();assert.equal(await f.status('survivor'),'held');
 await f.api('/api/threads/task-live/queue/resume',{});await waitFor(async()=>await f.status('survivor')==='accepted');
 assert.equal(f.state.calls.filter(x=>x.method==='turn/start').length,1);
});

test('definite busy rejection remains queued; ambiguous delivery is never automatically resent',async t=>{
 const f=await fixture(t);f.state.active=false;f.state.busy=true;
 await f.reply('busy','Wait for the race');await waitFor(()=>f.state.calls.some(x=>x.method==='turn/start'));
 assert.equal(await f.status('busy'),'queued');
 await f.finish();await waitFor(async()=>await f.status('busy')==='accepted');
 await f.finish();f.state.drop=true;await f.reply('uncertain','Only once');
 await waitFor(async()=>await f.status('uncertain')==='unknown');
 await f.stop();await f.start();assert.equal(await f.status('uncertain'),'unknown');
 assert.equal(f.state.calls.filter(x=>x.method==='turn/start'&&x.params.input[0].text==='Only once').length,1);
});

test('next-turn model, effort and plan controls persist and never override an active steer',async t=>{
 const f=await fixture(t);
 assert.equal((await f.api('/api/models')).data.models[0].model,'test-model');
 assert.equal((await f.api('/api/threads/task-live/settings',{model:'not-offered',effort:'high',mode:'plan'})).status,400);
 assert.equal((await f.api('/api/threads/task-live/settings',{model:'test-model',effort:'ultra',mode:'plan'})).status,400);
 assert.equal((await f.api('/api/threads/task-live/settings',{model:'test-model',effort:'high',mode:'unknown'})).status,400);
 const settings={model:'test-model',effort:'high',mode:'plan'};
 assert.equal((await f.api('/api/threads/task-live/settings',settings)).status,200);
 await f.reply('steer-settings','Guide the current turn','steer');await waitFor(async()=>await f.status('steer-settings')==='accepted');
 const steer=f.state.calls.find(x=>x.method==='turn/steer');assert.equal(steer.params.model,undefined);assert.equal(steer.params.collaborationMode,undefined);
 await f.reply('planned','Do this after planning');await f.stop();await f.start();
 assert.deepEqual((await f.api('/api/threads/task-live')).data.turnSettings,settings);
 await f.finish();await waitFor(async()=>await f.status('planned')==='accepted');
 const next=f.state.calls.find(x=>x.method==='turn/start');
 assert.equal(next.params.model,'test-model');assert.equal(next.params.effort,'high');
 assert.deepEqual(next.params.collaborationMode,{mode:'plan',settings:{model:'test-model',reasoning_effort:'high',developer_instructions:null}});
});

test('rename validates input and archive refuses running turns and holds waiting messages',async t=>{
 const f=await fixture(t);
 assert.equal((await f.api('/api/threads/task-live/rename',{name:''})).status,400);
 assert.equal((await f.api('/api/threads/task-live/rename',{name:'My renamed task'})).status,200);
 assert.deepEqual(f.state.calls.find(x=>x.method==='thread/name/set').params,{threadId:'task-live',name:'My renamed task'});
 assert.equal((await f.api('/api/threads/task-live/archive',{})).status,409);
 await f.reply('archive-wait','Keep paused');await f.api('/api/threads/task-live/interrupt',{});await f.finish('interrupted');await waitFor(async()=>await f.status('archive-wait')==='held');
 assert.equal((await f.api('/api/threads/task-live/archive',{})).status,200);
 assert.equal(await f.status('archive-wait'),'held');
 assert.equal(f.state.calls.filter(x=>x.method==='turn/start').length,0);
 assert.equal((await f.api('/api/threads/task-live/unarchive',{})).status,200);
 assert.deepEqual(f.state.calls.find(x=>x.method==='thread/unarchive').params,{threadId:'task-live'});
 assert.equal(await f.status('archive-wait'),'held');
});
