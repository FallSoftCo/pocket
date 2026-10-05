import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {DatabaseSync} from 'node:sqlite';
import {WebSocketServer} from 'ws';
const waitFor=async fn=>{for(let i=0;i<180;i++){if(await fn())return;await new Promise(r=>setTimeout(r,30));}throw Error('Condition not reached');};
async function fixture(t){
 const dir=await mkdtemp(join(tmpdir(),'pocket-reply-recovery-')),socket=join(dir,'codex.sock');
 const host=http.createServer(),wss=new WebSocketServer({server:host});await new Promise(r=>host.listen(socket,r));
 const calls=[];let rejectNext=false,holdNextAttach=false,heldAttach=null;
 wss.on('connection',ws=>ws.on('message',raw=>{
  const m=JSON.parse(raw);if(!m.id)return;calls.push(m);let result={};
  if(m.method==='thread/read'&&holdNextAttach){holdNextAttach=false;heldAttach={ws,id:m.id};return;}
  if(m.method==='thread/read'||m.method==='thread/resume')result={thread:{id:'task-recovery',name:'Recovery fixture',cwd:dir,status:{type:'idle'},turns:[]}};
  if(m.method==='turn/start'){
   if(rejectNext){rejectNext=false;ws.send(JSON.stringify({id:m.id,error:{code:-32602,message:'Synthetic definite rejection'}}));return;}
   result={turn:{id:'accepted-turn',status:'inProgress'}};
  }
  ws.send(JSON.stringify({id:m.id,result}));
 }));
 const allocator=http.createServer();await new Promise(r=>allocator.listen(0,'127.0.0.1',r));const port=allocator.address().port;await new Promise(r=>allocator.close(r));const url=`http://127.0.0.1:${port}`;
 const child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});let db;
 t.after(async()=>{if(child.exitCode===null)await new Promise(r=>{child.once('exit',r);child.kill();});db?.close();for(const ws of wss.clients)ws.terminate();await new Promise(r=>wss.close(r));await new Promise(r=>host.close(r));await rm(dir,{recursive:true,force:true});});
 await waitFor(async()=>{if(child.exitCode!==null)throw Error('Fixture server exited before startup');try{return (await fetch(url+'/health')).ok;}catch{return false;}});
 const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json'),'utf8'));
 const api=async(path,body)=>{const response=await fetch(url+path,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${adminToken}`,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});return {status:response.status,data:await response.json()};};
 await api('/api/threads/task-recovery');db=new DatabaseSync(join(dir,'pocket.sqlite'));
 const status=async id=>(await api('/api/replies/'+id)).data.state;
 const seed=(id,state)=>db.prepare('INSERT INTO outgoing(id,thread_id,text,state,result,created_at,updated_at,mode) VALUES(?,?,?,?,?,?,?,?)').run(id,'task-recovery','Preserved '+id,state,'Persisted delivery outcome',Date.now(),Date.now(),'steer');
 return {api,status,seed,calls,reject:()=>{rejectNext=true;},blockAttach:()=>{holdNextAttach=true;},attachBlocked:()=>heldAttach!==null,rejectAttach:()=>{heldAttach.ws.send(JSON.stringify({id:heldAttach.id,error:{code:-32602,message:'Synthetic attachment rejection'}}));},turns:()=>calls.filter(x=>x.method==='turn/start'||x.method==='turn/steer')};
}

test('HTTP definite-failure retry preserves id/input and repeated retry cannot duplicate upstream dispatch',async t=>{
 const f=await fixture(t);f.reject();
 assert.equal((await f.api('/api/threads/task-recovery/reply',{id:'definite-failure',text:'Preserve this exact input',mode:'steer'})).status,202);
 await waitFor(async()=>await f.status('definite-failure')==='failed');assert.equal(f.turns().length,1);
 const retried=await f.api('/api/threads/task-recovery/replies/definite-failure',{action:'retry'});
 assert.equal(retried.status,200);assert.equal(retried.data.id,'definite-failure');
 // The second request may see queued, sending, or accepted; all prevent duplication.
 assert.equal((await f.api('/api/threads/task-recovery/replies/definite-failure',{action:'retry'})).status,409);
 await waitFor(async()=>await f.status('definite-failure')==='accepted');assert.equal(f.turns().length,2);
 assert.deepEqual(f.turns().map(x=>x.params.clientUserMessageId),['definite-failure','definite-failure']);
 assert.deepEqual(f.turns().map(x=>x.params.input),[[{type:'text',text:'Preserve this exact input'}],[{type:'text',text:'Preserve this exact input'}]]);
 assert.equal((await f.api('/api/threads/task-recovery/replies/definite-failure',{action:'retry'})).status,409);assert.equal(f.turns().length,2);
});

test('HTTP removal does not call Codex and wrong-thread recovery cannot change failed/unknown replies',async t=>{
 const f=await fixture(t);f.seed('failed-remove','failed');f.seed('unknown-remove','unknown');const before=f.calls.length;
 assert.equal((await f.api('/api/threads/wrong-thread/replies/failed-remove',{action:'remove'})).status,404);
 assert.equal((await f.api('/api/threads/wrong-thread/replies/unknown-remove',{action:'retry',confirmUnknown:true})).status,404);
 assert.equal(await f.status('failed-remove'),'failed');assert.equal(await f.status('unknown-remove'),'unknown');
 for(const id of ['failed-remove','unknown-remove']){assert.equal((await f.api('/api/threads/task-recovery/replies/'+id,{action:'remove'})).status,200);assert.equal(await f.status(id),'cancelled');}
 assert.equal(f.calls.length,before);assert.equal(f.turns().length,0);
});

test('HTTP uncertain retry requires boolean confirmation and confirmed dispatch occurs once',async t=>{
 const f=await fixture(t);f.seed('uncertain','unknown');const before=f.calls.length;
 for(const body of [{action:'retry'},{action:'retry',confirmUnknown:false},{action:'retry',confirmUnknown:'true'},{action:'send'}]){
  const result=await f.api('/api/threads/task-recovery/replies/uncertain',body);assert.equal(result.status,409);assert.match(result.data.error,/uncertain|duplicate/i);
 }
 assert.equal(await f.status('uncertain'),'unknown');assert.equal(f.calls.length,before);
 assert.equal((await f.api('/api/threads/task-recovery/replies/uncertain',{action:'retry',confirmUnknown:true})).status,200);
 await waitFor(async()=>await f.status('uncertain')==='accepted');assert.equal(f.turns().length,1);
 assert.equal(f.turns()[0].params.clientUserMessageId,'uncertain');assert.deepEqual(f.turns()[0].params.input,[{type:'text',text:'Preserved uncertain'}]);
 assert.equal((await f.api('/api/threads/task-recovery/replies/uncertain',{action:'retry',confirmUnknown:true})).status,409);assert.equal(f.turns().length,1);
});


test('HTTP removal during blocked attachment remains cancelled after attachment fails',async t=>{
 const f=await fixture(t);f.seed('remove-in-flight','failed');f.blockAttach();
 assert.equal((await f.api('/api/threads/task-recovery/replies/remove-in-flight',{action:'retry'})).status,200);
 await waitFor(()=>f.attachBlocked());assert.equal(await f.status('remove-in-flight'),'queued');
 assert.equal((await f.api('/api/threads/task-recovery/replies/remove-in-flight',{action:'remove'})).status,200);
 assert.equal(await f.status('remove-in-flight'),'cancelled');
 f.rejectAttach();
 // Let the rejected RPC continuation finish before asserting durable cancellation.
 await new Promise(r=>setTimeout(r,80));
 assert.equal(await f.status('remove-in-flight'),'cancelled');assert.equal(f.turns().length,0);
});
