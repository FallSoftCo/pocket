import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {WebSocketServer} from 'ws';
import {pairedTestClient} from './paired-human.mjs';
const waitFor=async fn=>{for(let i=0;i<150;i++){if(await fn())return;await new Promise(r=>setTimeout(r,30));}throw Error('Fixture did not start');};

test('paired HTTP native settings preserve initialization identity, reject owner mutation, and dispatch exact native writes once',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'nextcomp-native-config-')),socket=join(dir,'codex.sock'),calls=[];
 const server=http.createServer(),wss=new WebSocketServer({server});await new Promise(r=>server.listen(socket,r));
 wss.on('connection',ws=>ws.on('message',raw=>{
  const m=JSON.parse(raw);if(m.id===undefined)return;calls.push(m);let result={};
  if(m.method==='initialize')result={userAgent:'codex_cli_rs/0.167.0 (fixture)',platformOs:'linux',platformFamily:'unix',codexHome:'/fake-private-home'};
  if(m.method==='thread/read')result={thread:{id:m.params.threadId,cwd:'/fake/project',status:{type:'idle'},turns:[]}};
  if(m.method==='skills/list')result={data:[{skills:[{name:'fixture-skill',path:'/fake/skills/fixture/SKILL.md',enabled:true,scope:'user',description:'Synthetic fixture only'}],errors:[]}]};
  if(m.method==='model/list')result={data:[{model:'fixture-model',displayName:'Fixture model',defaultReasoningEffort:'medium',supportedReasoningEfforts:[{reasoningEffort:'medium'}],serviceTiers:[{id:'priority',name:'Priority',description:'Fixture tier'}]}]};
  ws.send(JSON.stringify({id:m.id,result}));
 }));
 const port=32000+Math.floor(Math.random()*2000),base=`http://127.0.0.1:${port}`;
 const child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});
 t.after(async()=>{if(child.exitCode===null)await new Promise(r=>{child.once('exit',r);child.kill();});for(const ws of wss.clients)ws.terminate();await new Promise(r=>wss.close(r));await new Promise(r=>server.close(r));await rm(dir,{recursive:true,force:true});});
 await waitFor(async()=>{try{return (await fetch(base+'/health')).ok;}catch{return false;}});
 const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json'),'utf8')),api=await pairedTestClient(base,adminToken);
 const caps=await api('/api/native/capabilities?threadId=fixture-thread');assert.equal(caps.status,200);assert.equal(caps.data.runtime.version,'0.167.0');assert.equal(caps.data.observed.state,'unavailable');assert.equal(caps.data.features.goals.state,'unsupported');assert.ok(!JSON.stringify(caps.data).includes('/fake-private-home'));
 const view=await api('/api/native/configuration?threadId=fixture-thread&section=skills');assert.equal(view.status,200);assert.equal(view.data.items[0].name,'fixture-skill');
 const body={threadId:'fixture-thread',section:'skills',id:'/fake/skills/fixture/SKILL.md',enabled:false};
 const owner=await fetch(base+'/api/native/configuration',{method:'POST',headers:{Authorization:'Bearer '+adminToken,'Content-Type':'application/json'},body:JSON.stringify(body)});assert.equal(owner.status,403);
 assert.equal((await api('/api/native/configuration',body)).status,200);
 assert.deepEqual(calls.filter(c=>c.method==='skills/config/write').map(c=>c.params),[{path:'/fake/skills/fixture/SKILL.md',enabled:false}]);
 assert.equal((await api('/api/models')).data.models[0].serviceTiers[0].id,'priority');
 assert.equal((await api('/api/threads/fixture-thread/settings',{model:'fixture-model',effort:'medium',mode:'default',serviceTier:'priority'})).status,200);
 assert.equal((await api('/api/native/capabilities?threadId=fixture-thread')).data.requested.turnSettings.serviceTier,'priority');
 assert.ok(!calls.some(c=>/thread\/(goal|resume)|turn\/start/.test(c.method)));
});
