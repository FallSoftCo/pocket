import {createHash} from 'node:crypto';
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
 const calls=[];let rejectNext=false,holdNextAttach=false,heldAttach=null,snapshotTurns=[],threadStatus='idle',steerError=null,holdDelivery=false,heldDelivery=null,afterHeadStatus=null,loseStart=false;
 const thread=params=>({id:'task-recovery',name:'Recovery fixture',cwd:dir,status:{type:threadStatus},...(params?.includeTurns===false?{}:{turns:snapshotTurns})});
 wss.on('connection',ws=>ws.on('message',raw=>{
  const m=JSON.parse(raw);if(!m.id)return;calls.push(m);let result={};
  if(m.method==='thread/read'&&holdNextAttach){holdNextAttach=false;heldAttach={ws,id:m.id,params:m.params};return;}
  if(m.method==='thread/read'||m.method==='thread/resume')result={thread:thread(m.params)};
  if(m.method==='thread/turns/list'){result={data:snapshotTurns.slice(-1).map(turn=>({...turn,items:[]})),nextCursor:snapshotTurns.length?'older-do-not-follow':null,backwardsCursor:'current-anchor'};if(afterHeadStatus)threadStatus=afterHeadStatus;}
  if(m.method==='thread/items/list')result={data:[],nextCursor:null};
  if(m.method==='turn/steer'){
   if(steerError){const error=steerError;steerError=null;if(error.idleAfter)threadStatus='idle';if(error.disconnect){ws.terminate();return;}ws.send(JSON.stringify({id:m.id,error:{code:-32602,message:error.message}}));return;}
   if(holdDelivery){holdDelivery=false;heldDelivery={ws,id:m.id};return;}
   result={turnId:snapshotTurns.at(-1)?.id};
  }
  if(m.method==='turn/start'){
   if(loseStart){loseStart=false;ws.terminate();return;}
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
 db=new DatabaseSync(join(dir,'pocket.sqlite'));const deviceToken='synthetic-paired-human-token';db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run('paired-human','Synthetic human',createHash('sha256').update(deviceToken).digest('hex'),Date.now());
 const api=async(path,body,asOwner=false)=>{const ownerRoute=asOwner||/\/(?:continuation|catalog-context|recovery)(?:\/|$)/.test(path);const authorization=ownerRoute?adminToken:deviceToken;const response=await fetch(url+path,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${authorization}`,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});return {status:response.status,data:await response.json()};};
 await api('/api/threads/task-recovery');
 const status=async id=>(await api('/api/replies/'+id)).data.state;
 const seed=(id,state,mode='steer')=>db.prepare('INSERT INTO outgoing(id,thread_id,text,state,result,created_at,updated_at,mode) VALUES(?,?,?,?,?,?,?,?)').run(id,'task-recovery','Preserved '+id,state,'Persisted delivery outcome',Date.now(),Date.now(),mode);
 return {api,afterHeadStatus:value=>afterHeadStatus=value,loseStart:()=>loseStart=true,setStatus:value=>threadStatus=value,steerError:value=>steerError=value,holdDelivery:()=>holdDelivery=true,deliveryBlocked:()=>!!heldDelivery,releaseDelivery:()=>{const held=heldDelivery;heldDelivery=null;held.ws.send(JSON.stringify({id:held.id,result:{turnId:snapshotTurns.at(-1)?.id}}));},releaseAttach:()=>{const held=heldAttach;heldAttach=null;held.ws.send(JSON.stringify({id:held.id,result:{thread:thread(held.params)}}));},adminApi:(path,body)=>api(path,body,true),status,seed,calls,db,snapshotTurn:turn=>{snapshotTurns=[turn];},reject:()=>{rejectNext=true;},blockAttach:()=>{holdNextAttach=true;},attachBlocked:()=>heldAttach!==null,rejectAttach:()=>{heldAttach.ws.send(JSON.stringify({id:heldAttach.id,error:{code:-32602,message:'Synthetic attachment rejection'}}));},turns:()=>calls.filter(x=>x.method==='turn/start'||x.method==='turn/steer')};
}

test('HTTP manual continuation and retry accept a confirmed failed native turn without draining queued work',async t=>{
 const f=await fixture(t);f.setStatus('systemError');f.snapshotTurn({id:'failed-original',status:'failed',error:{message:'Bad Request'},items:[]});
 await f.api('/api/threads/task-recovery/reply',{id:'still-queued',text:'Later work',mode:'queue'});
 assert.equal((await f.api('/api/threads/task-recovery/reply',{id:'manual-continue',text:'Continue preserved work',mode:'steer'})).status,202);
 await waitFor(async()=>await f.status('manual-continue')==='accepted');
 assert.equal(await f.status('still-queued'),'queued');
 f.seed('previously-rejected','failed');assert.equal((await f.api('/api/threads/task-recovery/replies/previously-rejected',{action:'retry'})).status,200);
 await waitFor(async()=>await f.status('previously-rejected')==='accepted');
 assert.equal((await f.api('/api/threads/task-recovery/replies/previously-rejected',{action:'retry'})).status,409);
 assert.deepEqual(f.turns().map(c=>[c.method,c.params.clientUserMessageId,c.params.input[0].text]),[['turn/start','manual-continue','Continue preserved work'],['turn/start','previously-rejected','Preserved previously-rejected']]);
 assert.ok(f.turns().every(c=>!('model' in c.params)&&!('effort' in c.params)&&!('approvalPolicy' in c.params)));
 const checks=f.calls.filter(c=>c.method==='thread/turns/list'&&c.params.itemsView==='summary');assert.ok(checks.length>=2);assert.ok(checks.every(c=>c.params.limit===1&&c.params.sortDirection==='desc'&&!c.params.cursor));
});

test('HTTP systemError is not permission to start without terminal evidence or across a concurrent start',async t=>{
 const f=await fixture(t);
 for(const status of ['inProgress','completed','interrupted']){
  f.setStatus('systemError');f.snapshotTurn({id:'head-'+status,status,items:[]});const id='blocked-'+status;
  await f.api('/api/threads/task-recovery/reply',{id,text:'New input',mode:'steer'});await waitFor(async()=>await f.status(id)==='failed');
 }
 f.setStatus('systemError');f.snapshotTurn({id:'failed-original',status:'failed',items:[]});f.afterHeadStatus('active');
 await f.api('/api/threads/task-recovery/reply',{id:'concurrent-start',text:'New input',mode:'steer'});await waitFor(async()=>await f.status('concurrent-start')==='failed');assert.equal(f.turns().length,0);
});

test('HTTP lost acceptance of manual failed-turn continuation stays unknown and is not replayed',async t=>{
 const f=await fixture(t);f.setStatus('systemError');f.snapshotTurn({id:'failed-original',status:'failed',items:[]});f.loseStart();
 await f.api('/api/threads/task-recovery/reply',{id:'lost-start',text:'Continue preserved work',mode:'steer'});await waitFor(async()=>await f.status('lost-start')==='unknown');
 assert.equal((await f.api('/api/threads/task-recovery/replies/lost-start',{action:'retry'})).status,409);assert.equal(f.turns().length,1);
});

test('HTTP checkpoints save progress on unregistered/stopped work without rearming recovery or starting a model',async t=>{
 const f=await fixture(t);f.snapshotTurn({id:'current-progress',status:'inProgress',items:[]});
 const report={state:'waitingDependency',evidence:'Saved exact job reference; waiting on producer generation'};
 const first=await f.api('/api/threads/task-recovery/continuation/checkpoint',report);
 assert.equal(first.status,200);assert.equal(first.data.observationOnly,true);assert.equal(first.data.admission,'none');
 assert.equal(f.db.prepare('SELECT count(*) n FROM turn_continuation_authorizations').get().n,0);
 f.db.prepare("INSERT INTO turn_recovery(thread_id,source_turn,current_turn,state,next_at,kind,updated_at) VALUES('task-recovery','old','old','cancelled',0,'continuation',0)").run();
 assert.equal((await f.api('/api/threads/task-recovery/continuation/checkpoint',report)).status,200);
 assert.equal(f.db.prepare("SELECT state FROM turn_recovery WHERE thread_id='task-recovery'").get().state,'cancelled');
 assert.equal((await f.api('/api/threads/task-recovery/continuation/checkpoint',{...report,state:'continue'})).status,409);
 assert.equal((await f.api('/api/threads/task-recovery/continuation/checkpoint',{...report,turnId:'old'})).status,409);
 assert.equal(f.turns().length,0);
});

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

test('real gateway rejects shared-admin human ingress while paired admission/retry preserves provenance and accepted receipt',async t=>{
 const f=await fixture(t);
 const before=f.turns().length;
 for(const [path,body] of [
  ['/api/threads',{id:'untrusted-create',cwd:'/tmp',prompt:'Peer text'}],
  ['/api/threads/task-recovery/reply',{id:'untrusted-reply',text:'Peer text'}],
  ['/api/threads/task-recovery/replies/absent',{action:'retry'}],
  ['/api/threads/task-recovery/queue/resume',{}],
  ['/api/voice/text',{turnId:'untrusted-voice',text:'Peer text'}],
  ['/api/voice/start',{}],
 ]){
  const r=await f.adminApi(path,body);assert.equal(r.status,403,path);assert.equal(r.data.code,'HUMAN_DEVICE_REQUIRED');
 }
 assert.equal(f.turns().length,before);
 assert.equal(f.db.prepare('SELECT count(*) n FROM outgoing').get().n,0);
 f.reject();
 assert.equal((await f.api('/api/threads/task-recovery/reply',{id:'human-provenance',text:'Real paired request',mode:'steer'})).status,202);
 await waitFor(async()=>await f.status('human-provenance')==='failed');
 const original=f.db.prepare("SELECT principal FROM human_admissions WHERE kind='reply' AND request_id='human-provenance'").get().principal;
 assert.deepEqual(JSON.parse(original),{version:1,kind:'paired-device',deviceId:'paired-human'});
 assert.equal((await f.adminApi('/api/threads/task-recovery/replies/human-provenance',{action:'retry'})).status,403);
 assert.equal((await f.api('/api/threads/task-recovery/replies/human-provenance',{action:'retry'})).status,200);
 await waitFor(async()=>await f.status('human-provenance')==='accepted');
 assert.equal(f.db.prepare("SELECT principal FROM human_admissions WHERE request_id='human-provenance'").get().principal,original);
 assert.equal(f.db.prepare("SELECT count(*) n FROM human_admission_actions WHERE request_id='human-provenance' AND action='retry'").get().n,1);
 const accepted=f.db.prepare("SELECT * FROM outgoing WHERE id='human-provenance'").get();
 assert.equal((await f.adminApi('/api/threads/task-recovery/reply',{id:'human-provenance',text:'Real paired request',mode:'steer'})).status,403);
 assert.deepEqual(f.db.prepare("SELECT * FROM outgoing WHERE id='human-provenance'").get(),accepted);
});


test('HTTP promotion keeps original queued identity through blocked drain, duplicate taps and later idle dispatch',async t=>{
 const f=await fixture(t);f.setStatus('active');f.snapshotTurn({id:'active-original',status:'inProgress',items:[]});f.blockAttach();
 const id='queued-promote',path='/api/threads/task-recovery/replies/'+id;
 assert.equal((await f.api('/api/threads/task-recovery/reply',{id,text:'Original queued guidance',mode:'queue'})).status,202);
 await waitFor(()=>f.attachBlocked());const original=f.db.prepare('SELECT * FROM outgoing WHERE id=?').get(id);const admitted=f.db.prepare("SELECT principal FROM human_admissions WHERE request_id=? AND kind='reply'").get(id).principal;
 const promoted=await f.api(path,{action:'send'});assert.equal(promoted.status,200);assert.equal(promoted.data.id,id);assert.equal(promoted.data.mode,'steer');
 assert.equal((await f.api(path,{action:'send'})).status,409);assert.equal(f.turns().length,0);f.holdDelivery();const releasedAt=Date.now();f.releaseAttach();await waitFor(()=>f.deliveryBlocked());assert.ok(Date.now()-releasedAt<2000,'explicit promotion wakes on drain release, not five-second periodic flush');
 assert.equal((await f.api(path,{action:'send'})).status,409);assert.equal(f.turns().length,1);assert.equal(f.turns()[0].method,'turn/steer');assert.equal(f.turns()[0].params.expectedTurnId,'active-original');assert.equal(f.turns()[0].params.clientUserMessageId,id);assert.deepEqual(f.turns()[0].params.input,[{type:'text',text:original.text}]);
 const pending=await f.api('/api/replies/'+id);assert.equal(pending.data.mode,'steer');assert.equal(pending.data.state,'sending');f.releaseDelivery();await waitFor(async()=>await f.status(id)==='accepted');
 assert.equal((await f.api(path,{action:'send'})).status,409);assert.equal(f.db.prepare('SELECT text,created_at FROM outgoing WHERE id=?').get(id).text,original.text);assert.equal(f.db.prepare('SELECT created_at FROM outgoing WHERE id=?').get(id).created_at,original.created_at);assert.equal(f.db.prepare("SELECT principal FROM human_admissions WHERE request_id=? AND kind='reply'").get(id).principal,admitted);assert.equal(f.db.prepare("SELECT count(*) n FROM human_admission_actions WHERE request_id=? AND action='send'").get(id).n,1);
 assert.equal((await f.api('/api/threads/task-recovery/reply',{id,text:original.text,mode:'queue'})).status,409,'stale original queue POST cannot replace promoted identity');
 // A different older queue row owns the lock: promotion must wake immediately
 // after that harmless active-metadata check, not wait for periodic queue polling.
 f.blockAttach();await f.api('/api/threads/task-recovery/reply',{id:'older-queued',text:'Wait for next turn',mode:'queue'});await waitFor(()=>f.attachBlocked());
 await f.api('/api/threads/task-recovery/reply',{id:'promote-behind-drain',text:'Steer this current turn',mode:'queue'});assert.equal((await f.api('/api/threads/task-recovery/replies/promote-behind-drain',{action:'send'})).status,200);
 const drainReleaseAt=Date.now();f.releaseAttach();await waitFor(async()=>await f.status('promote-behind-drain')==='accepted');assert.ok(Date.now()-drainReleaseAt<2000);assert.equal(f.turns().filter(c=>c.params.clientUserMessageId==='promote-behind-drain').length,1);assert.equal(f.turns().at(-1).method,'turn/steer');assert.equal(await f.status('older-queued'),'queued');
 assert.equal((await f.api('/api/threads/task-recovery/replies/older-queued',{action:'remove'})).status,200);
 f.setStatus('idle');f.snapshotTurn({id:'active-original',status:'completed',items:[]});f.seed('held-idle','held','queue');assert.equal((await f.api('/api/threads/task-recovery/replies/held-idle',{action:'send'})).status,200);await waitFor(async()=>await f.status('held-idle')==='accepted');assert.equal(f.turns().at(-1).method,'turn/start');assert.equal(f.turns().at(-1).params.clientUserMessageId,'held-idle');
 assert.ok(!f.calls.some(c=>['thread/turns/list','thread/items/list','thread/timeline/list'].includes(c.method)&&c.params.cursor),'promotion never scans older history');
});

test('HTTP promotion cannot reinterpret unknown delivery or start a turn without positive idle evidence',async t=>{
 const f=await fixture(t);const action=id=>f.api('/api/threads/task-recovery/replies/'+id,{action:'send',confirmUnknown:true});
 for(const state of ['failed','unknown','sending','accepted']){f.seed('blocked-'+state,state,'queue');assert.equal((await action('blocked-'+state)).status,409);}assert.equal(f.turns().length,0);
 for(const [id,message,idleAfter]of [['mismatch','expectedTurnId does not match current active turn',false],['still-active','no active turn',false],['finished','no active turn',true]]){
  f.setStatus('active');f.snapshotTurn({id:'turn-'+id,status:'inProgress',items:[]});f.seed(id,'held','queue');f.steerError({message,idleAfter});assert.equal((await action(id)).status,200);await waitFor(async()=>['accepted','failed'].includes(await f.status(id)));
  const deliveries=f.turns().filter(c=>c.params.clientUserMessageId===id);assert.deepEqual(deliveries.map(c=>c.method),idleAfter?['turn/steer','turn/start']:['turn/steer']);assert.equal(await f.status(id),idleAfter?'accepted':'failed');
 }
 f.setStatus('active');f.snapshotTurn({id:'uncertain-active',status:'inProgress',items:[]});f.seed('lost-steer','held','queue');f.steerError({disconnect:true});assert.equal((await action('lost-steer')).status,200);await waitFor(async()=>await f.status('lost-steer')==='unknown');const before=f.turns().length;assert.equal((await action('lost-steer')).status,409);await f.api('/api/replies/lost-steer');assert.equal(f.turns().length,before);
});
