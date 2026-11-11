import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {DatabaseSync} from 'node:sqlite';
import {WebSocketServer} from 'ws';
import {pairedTestClient} from './paired-human.mjs';
const until=async fn=>{for(let i=0;i<180;i++){const result=await fn();if(result)return result;await new Promise(r=>setTimeout(r,20));}throw Error('Condition not reached');};

test('catalog Needs you follows original request and delivery identities without notifications, history, or work dispatch',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'pocket-work-attention-')),socket=join(dir,'native.sock');
 const native=http.createServer(),wss=new WebSocketServer({server:native});await new Promise(r=>native.listen(socket,r));
 const calls=[];let peer,holdList=false;const held=[];
 const threads=[{id:'first',status:{type:'idle'}},{id:'child',parentThreadId:'first',source:{subAgent:{thread_spawn:{parent_thread_id:'first'}}},status:{type:'idle'}},{id:'runtime',status:{type:'systemError'}},{id:'stopped',status:{type:'idle'}},{id:'unknown',status:{type:'unknown'}},{id:'future',status:{type:'futureNativeStatus'}}].map(t=>({...t,name:t.id,createdAt:1,recencyAt:1,cwd:dir}));
 wss.on('connection',ws=>{peer=ws;ws.on('message',raw=>{
  const m=JSON.parse(raw);if(m.id===undefined||!m.method)return;calls.push(m);let result={};
  if(m.method==='initialize')result={userAgent:'codex/0.167.0'};
  if(m.method==='thread/list')result={data:threads,nextCursor:null};
  if(m.method==='thread/read')result={thread:threads.find(t=>t.id===m.params.threadId)};
  const reply=()=>ws.send(JSON.stringify({id:m.id,result}));
  if(m.method==='thread/list'&&holdList)held.push(reply);else reply();
 });});
 const allocator=http.createServer();await new Promise(r=>allocator.listen(0,'127.0.0.1',r));const port=allocator.address().port;await new Promise(r=>allocator.close(r));
 const url=`http://127.0.0.1:${port}`,child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});let db;
 t.after(async()=>{if(child.exitCode===null)await new Promise(r=>{child.once('exit',r);child.kill();});db?.close();for(const ws of wss.clients)ws.terminate();await new Promise(r=>wss.close(r));await new Promise(r=>native.close(r));await rm(dir,{recursive:true,force:true});});
 await until(async()=>{if(child.exitCode!==null)throw Error('Server exited');try{return (await fetch(url+'/health')).ok;}catch{return false;}});
 const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json'),'utf8')),api=await pairedTestClient(url,adminToken);
 db=new DatabaseSync(join(dir,'pocket.sqlite'));
 const catalog=async()=>{const response=await api('/api/threads');assert.equal(response.status,200);return new Map(response.data.threads.map(t=>[t.id,t]));};
 let rows=await catalog();assert.equal(rows.get('first').attention.verified,true);assert.deepEqual(rows.get('stopped').attention.items,[]);
 for(const id of ['unknown','future']){assert.equal(rows.get(id).attention.verified,false);assert.deepEqual(rows.get(id).attention.items,[]);}
 assert.deepEqual(rows.get('runtime').attention.items.map(i=>i.kind),['runtimeError']);
 const seed=(id,thread,state)=>db.prepare('INSERT INTO outgoing(id,thread_id,text,state,result,created_at,updated_at,mode) VALUES(?,?,?,?,?,?,?,?)').run(id,thread,'PRIVATE MESSAGE',state,'PRIVATE RESULT',Date.now(),Date.now(),'steer');
 seed('failed-original','child','failed');seed('unknown-original','first','unknown');seed('accepted-original','first','accepted');seed('foreign','not-returned','unknown');
 db.prepare('INSERT INTO pocket_catalog_context VALUES(?,?,?,?)').run('stopped','PRIVATE SUMMARY',1,2000);
 const event=(method,params,id)=>peer.send(JSON.stringify({method,params,...(id===undefined?{}:{id})}));
 event('item/tool/requestUserInput',{threadId:'first',turnId:'turn',questions:[{id:'q',question:'PRIVATE QUESTION',header:'Question',isOther:true}]},700);
 event('item/commandExecution/requestApproval',{threadId:'child',turnId:'turn',itemId:'command',command:'PRIVATE COMMAND'},701);
 rows=await until(async()=>{const r=await catalog();return r.get('first').attention.items.some(i=>i.kind==='question')&&r.get('child').attention.items.some(i=>i.kind==='approval')&&r;});
 assert.equal(rows.get('child').parentThreadId,'first');
 assert.deepEqual(rows.get('first').attention.items.map(i=>i.kind),['question','deliveryUnknown']);
 assert.equal(rows.get('child').attention.items.find(i=>i.kind==='deliveryFailed').id,'outgoing:failed-original');
 assert.deepEqual(rows.get('stopped').attention.items.map(i=>i.kind),['needsInput']);
 const oldNotice=db.prepare("INSERT INTO notifications(thread_id,title,body,kind,created_at) VALUES('stopped','Old failure','STALE PRIVATE ERROR','error',1)").run();
 db.prepare('INSERT INTO notification_attention(notification_id) VALUES(?)').run(Number(oldNotice.lastInsertRowid));
 const original=rows.get('first').attention.items[0].id;
 assert.ok(!JSON.stringify([...rows.values()].map(t=>t.attention)).includes('PRIVATE'));
 event('serverRequest/resolved',{requestId:700});
 db.prepare("UPDATE outgoing SET state='accepted' WHERE id='unknown-original'").run();
 threads.find(t=>t.id==='stopped').recencyAt=3;
 rows=await until(async()=>{const r=await catalog();return !r.get('first').attention.items.length&&!r.get('stopped').attention.items.length&&r;});
 // Native request number reuse is a new identity, not an old dismissed card.
 event('item/tool/requestUserInput',{threadId:'first',turnId:'turn2',questions:[{id:'q',question:'New question',header:'Question',isOther:true}]},700);
 rows=await until(async()=>{const r=await catalog();return r.get('first').attention.items.some(i=>i.kind==='question'&&i.id!==original)&&r;});
 holdList=true;rows=await catalog();
 assert.equal(rows.get('runtime').attention.verified,false);assert.deepEqual(rows.get('runtime').attention.items,[]);
 assert.equal(rows.get('child').attention.items.find(i=>i.kind==='deliveryFailed').id,'outgoing:failed-original');
 holdList=false;for(const reply of held.splice(0))reply();
 const old=peer;old.terminate();await until(()=>old.readyState===3);
 // A reconnect expires connection-owned pending requests, but keeps durable delivery failures.
 rows=await until(async()=>{try{const r=await catalog();return !r.get('first').attention.items.some(i=>i.kind==='question')&&r;}catch{return false;}});
 assert.deepEqual(rows.get('child').attention.items.map(i=>i.kind),['deliveryFailed']);
 const coordinator=await api('/api/threads?view=coordinator&includeChildren=true');
 assert.equal(coordinator.status,200);assert.equal(coordinator.data.threads.find(t=>t.id==='child').attention.items[0].id,'outgoing:failed-original');
 assert.ok(!calls.some(c=>['turn/start','turn/steer','thread/resume','thread/turns/list','thread/items/list'].includes(c.method)));
 assert.ok(calls.filter(c=>c.method==='thread/read').every(c=>c.params.includeTurns!==true),JSON.stringify(calls.filter(c=>c.method==='thread/read')));
 // Real native ordering: systemError arrives before the failed terminal event.
 // Indexed metadata may still say idle; the event projection must retain failure.
 event('thread/status/changed',{threadId:'future',status:{type:'systemError'}});
 event('turn/completed',{threadId:'future',turn:{id:'failed-turn',status:'failed',items:[],error:{message:'Bad Request',codexErrorInfo:'other'}}});
 rows=await until(async()=>{const r=await catalog();return r.get('future').status.type==='systemError'&&r.get('future').attention.items.some(i=>i.kind==='runtimeError')&&r;});
 assert.equal(rows.get('future').attention.verified,true);
 event('turn/started',{threadId:'future',turn:{id:'next-turn',status:'inProgress'}});
 rows=await until(async()=>{const r=await catalog();return r.get('future').status.type==='active'&&r;});
 assert.ok(!rows.get('future').attention.items.some(i=>i.kind==='runtimeError'));
 event('turn/completed',{threadId:'future',turn:{id:'next-turn',status:'completed',items:[]}});
 await until(async()=>{const r=await catalog();return r.get('future').status.type==='idle';});
 event('thread/status/changed',{threadId:'future',status:{type:'futureNativeStatus'}});
 assert.ok(!calls.some(c=>['turn/start','turn/steer','thread/resume'].includes(c.method)));

});
