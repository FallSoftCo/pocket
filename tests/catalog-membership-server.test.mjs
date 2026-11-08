import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {once} from 'node:events';
import http from 'node:http';
import {WebSocketServer} from 'ws';
import {DatabaseSync} from 'node:sqlite';

test('real gateway reconciles temporary runtime residency across cached/live/search/coordinator paths and restart',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'nextcomp-membership-')),socket=join(dir,'native.sock');
 const native=http.createServer(),wss=new WebSocketServer({server:native});await new Promise(r=>native.listen(socket,r));
 const allocation=http.createServer();await new Promise(r=>allocation.listen(0,'127.0.0.1',r));const port=allocation.address().port;await new Promise(r=>allocation.close(r));
 const base={source:'vscode',originator:'codex-tui',status:{type:'notLoaded'},createdAt:Math.floor(Date.now()/1000)};
 const durable={...base,id:'durable-user',name:'Ordinary task',ephemeral:false,path:'/synthetic/sessions/ordinary.jsonl'};
 const child={...base,id:'durable-child',name:'Delegated work',ephemeral:false,source:{subAgent:{thread_spawn:{parent_thread_id:durable.id}}}};
 const temporary={...base,id:'temporary-runtime',name:'Temporary response',ephemeral:true,path:null};
 const late={...temporary,id:'late-residency'};
 const missing={...durable,id:'missing-runtime'},offline={...durable,id:'offline-runtime'};
 const pending={...base,id:'pending-user',name:'New human task',ephemeral:false,status:{type:'pending'},discoveryPending:true};
 const external={...durable,id:'external-archive'};
 let promoted=false,reappeared=false,externalArchived=false;const calls=[];
 wss.on('connection',ws=>ws.on('message',raw=>{const m=JSON.parse(raw);if(!m.id)return;calls.push(m);let result={};
  if(m.method==='thread/list')result={data:[durable,child,pending,...(!externalArchived?[external]:[]),...(reappeared?[missing]:[]),...(promoted?[{...temporary,ephemeral:false,path:'/synthetic/sessions/promoted.jsonl'}]:[temporary])],nextCursor:null};
  else if(m.method==='thread/read'&&[missing.id,offline.id].includes(m.params.threadId)){const id=m.params.threadId;ws.send(JSON.stringify({id:m.id,error:{code:id===missing.id?-32600:-32603,message:id===missing.id?`thread not loaded: ${id}`:'Native metadata temporarily unavailable'}}));return;}
  else if(m.method==='thread/read'&&m.params.threadId===external.id)result={thread:{...external,path:'/synthetic/'+(externalArchived?'archived_sessions':'sessions')+'/external.jsonl'}};
  else if(m.method==='thread/read')result={thread:m.params.threadId===late.id?late:[durable,child,pending,temporary].find(t=>t.id===m.params.threadId)};
  ws.send(JSON.stringify({id:m.id,result}));
 }));
 let process;
 const stop=async()=>{if(process&&process.exitCode===null){const exited=once(process,'exit');process.kill();await exited;}};
 t.after(async()=>{await stop();for(const ws of wss.clients)ws.terminate();wss.close();await new Promise(r=>native.close(r));await rm(dir,{recursive:true,force:true});});
 const boot=async()=>{process=spawn(globalThis.process.execPath,['server/index.mjs'],{env:{...globalThis.process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});for(let n=0;n<100;n++){try{if((await fetch(`http://127.0.0.1:${port}/health`)).ok)return;}catch{}await new Promise(r=>setTimeout(r,30));}throw Error('Gateway did not become ready');};
 await boot();const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json')));
 const get=async path=>{const r=await fetch(`http://127.0.0.1:${port}${path}`,{headers:{Authorization:'Bearer '+adminToken}});assert.equal(r.status,200);return r.json();};
 const db=new DatabaseSync(join(dir,'pocket.sqlite'));
 for(const row of [temporary,{...late,ephemeral:undefined},missing,offline])db.prepare('INSERT INTO pocket_discovered_threads VALUES(?,?,0,?)').run(row.id,JSON.stringify(row),Date.now());
 // Existing stale rows are encountered through native/cache metadata, not names.
 const normal=await get('/api/threads');assert.deepEqual(new Set(normal.threads.map(t=>t.id)),new Set([durable.id,child.id,pending.id,missing.id,offline.id,external.id]));
 for(const query of ['?search=Temporary','?cursor=older','?archived=true','?view=coordinator&includeChildren=true']){
  const p=await get('/api/threads'+query);assert.ok(p.threads.every(t=>![temporary.id,late.id].includes(t.id)),query);
 }
 // A new native ephemeral creation is never broadcast into a saved work catalog.
 const event={method:'thread/started',params:{thread:{...temporary,id:'new-temporary'}}};for(const ws of wss.clients)ws.send(JSON.stringify(event));
 await get('/api/threads');assert.equal(db.prepare("SELECT kind FROM pocket_internal_sessions WHERE thread_id='new-temporary'").get()?.kind,'temporary-runtime');
 assert.ok(db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE thread_id=?').get(temporary.id),'old cache preserved, not a destructive cleanup');
 await stop();await boot();const restarted=await get('/api/threads');assert.ok(restarted.threads.every(t=>![temporary.id,late.id].includes(t.id)));
 assert.ok(restarted.threads.some(t=>t.id===missing.id),'unloaded durable history remains discoverable after restart; missing source does not prove permanent loss');
 assert.ok(db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE thread_id=?').get(missing.id),'unavailable history cache remains retained');
 reappeared=true;assert.equal((await get('/api/threads')).threads.filter(t=>t.id===missing.id).length,1,'fresh native evidence reconciles the retained durable identity without duplication');
 promoted=true;assert.ok((await get('/api/threads')).threads.some(t=>t.id===temporary.id),'explicit native persistence makes the same ID discoverable');
 assert.equal(db.prepare('SELECT 1 FROM pocket_internal_sessions WHERE thread_id=?').get(temporary.id),undefined);
 externalArchived=true;for(let i=0;i<2;i++)assert.ok(!(await get('/api/threads')).threads.some(t=>t.id===external.id),'native external archive cannot reappear from cached metadata');
 assert.equal(db.prepare('SELECT archived FROM pocket_discovered_threads WHERE thread_id=?').get(external.id)?.archived,1);
 assert.ok(!calls.some(c=>['thread/resume','thread/start','turn/start','thread/archive'].includes(c.method)),'catalog reads cannot activate or archive work');db.close();
});
