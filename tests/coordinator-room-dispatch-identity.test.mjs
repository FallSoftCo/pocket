import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {createHash,randomUUID} from 'node:crypto';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {CoordinatorSources} from '../server/coordinator-sources.mjs';
import {CoordinatorRoomIntents} from '../server/coordinator-room-intents.mjs';
import {COLLIDING_SOURCE_SHA,prepareIntentCollisionRecovery,applyIntentCollisionRecovery,continueIntentCollisionRecovery} from '../server/coordinator-room-intent-recovery.mjs';
const digest=v=>createHash('sha256').update(JSON.stringify(v)).digest('hex');
async function fixture(t){
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());
 db.exec('CREATE TABLE devices(id TEXT PRIMARY KEY);CREATE TABLE voice_turns(device TEXT,id TEXT,state TEXT);CREATE TABLE voice_calls(device TEXT,turn_id TEXT,call_id TEXT,state TEXT,result TEXT);');
 const device=randomUUID(),source='cpu',binding='a'.repeat(64),turn=randomUUID(),call='original-call',human=randomUUID(),authority=randomUUID(),owner=randomUUID(),roomId=randomUUID(),sourceRoomId=randomUUID();
 db.prepare('INSERT INTO devices VALUES(?)').run(device);db.prepare('INSERT INTO voice_turns VALUES(?,?,?)').run(device,turn,'completed');
 const identity={bindingId:binding,principalId:human,authorityId:authority},actor={deviceId:device,voiceTurnId:turn,voiceCallId:call,hollywoodBinding:binding};
 const receipts=new Map(),counts={create:0,message:0},rooms=new Map([[sourceRoomId,{id:sourceRoomId}]]);let lose=false;
 const server=createServer(async(req,res)=>{
  assert.equal(req.headers.authorization,'Bearer synthetic-original-device-credential');
  const path=new URL(req.url,'http://local').pathname;let value;
  if(req.method==='POST'){
   let data='';for await(const chunk of req)data+=chunk;const body=JSON.parse(data);
   assert.equal(req.headers['x-nextcomp-coordinator-turn'],turn);
   if(path.endsWith('/rooms')){counts.create++;value={...body,owner:body.ownerId,binding:identity};rooms.set(body.id,value);}
   else{counts.message++;value={accepted:true,binding:identity,event:{id:randomUUID(),room:path.split('/')[5],author:human,authority,kind:'request',body:body.body,audience:body.audience,recipient:null,source:null,data:{authorKind:'human',conversationVersion:1}},deliveries:[]};receipts.set(body.operationId,value);if(lose){req.socket.destroy();return;}}
  }else if(path.endsWith('/identity'))value=identity;
  else if(path.includes('/commands/')){value=receipts.get(path.split('/').at(-1));if(!value){res.statusCode=403;value={error:'Access denied'};}}
  else value={room:rooms.get(path.split('/').at(-1)),permissions:{can_request:true},actors:[{id:owner,kind:'agent'}]};
  res.setHeader('Content-Type','application/json');res.end(JSON.stringify(value));
 });server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>new Promise(r=>server.close(r)));
 const sources=new CoordinatorSources({db,key:'synthetic',authorize:()=>true,fetchImpl:(url,options)=>fetch('http://127.0.0.1:'+server.address().port+new URL(url).pathname,options)});
 db.prepare('INSERT INTO coordinator_sources VALUES(?,?,?,?,?,?,?,NULL)').run(device,source,'CPU','https://cpu.example.ts.net',sources.seal(device,source,'synthetic-original-device-credential'),'synthetic',1);
 const manager=new CoordinatorRoomIntents(db),id='coordinator-intent-'+digest([device,source,binding,turn,call]).slice(0,48);
 const creation={id:roomId,operationId:id+'-room',name:'Synthetic Vipi',purpose:'Synthetic original work',ownerId:owner,sourceRoomId};
 const payload={roomId,sourceRoomId,creation,message:{operationId:id+'-message',kind:'request',body:'Original synthetic kickoff',audience:'room'}};
 const scope={device,source,binding,id},callApi=(path,body)=>sources.call(device,source,path,body,actor);
 function legacyCollision(){
  manager.scope(device,source,binding).insert({id,request_hash:'original',phase:'sending',payload});
  db.prepare('INSERT INTO voice_calls VALUES(?,?,?,?,?)').run(device,turn,call,'done',JSON.stringify({success:false,contentItems:[{type:'inputText',text:JSON.stringify({error:'Initial human instruction was not confirmed. Original room command',commandId:id,code:'ROOM_ADMISSION_UNKNOWN'})}]}));
  const created={...creation,owner,binding:identity};rooms.set(roomId,created);
  db.prepare('INSERT INTO coordinator_remote_commands VALUES(?,?,?,?,?,?,?,?,?,?)').run(digest([device,source,turn,call]),device,source,turn,'/api/hollywood/v3/rooms',digest(creation),'responded',JSON.stringify(created),1,roomId);
 }
 return {db,manager,scope,actor,identity,payload,creation,counts,callApi,legacyCollision,lose:()=>lose=true};
}
test('one tool call creates room then kickoff over real paired HTTP with independent durable identities',async t=>{
 const f=await fixture(t),s=f.scope;
 const result=await f.manager.command({operation:'createRoomAndStart',args:{...f.creation,text:f.payload.message.body},active:{device:s.device,id:f.actor.voiceTurnId},callId:f.actor.voiceCallId,environmentId:s.source,identity:f.identity,get:p=>f.callApi(p),post:f.callApi,action:()=>{}});
 assert.equal(result.accepted,true);assert.deepEqual(f.counts,{create:1,message:1});
 assert.equal(f.db.prepare('SELECT count(*) n FROM coordinator_remote_commands').get().n,2);
});
test('positive legacy collision recovery preserves original creation/call/snapshot and sends the exact kickoff once',async t=>{
 const f=await fixture(t);f.legacyCollision();const originalCreation=f.db.prepare('SELECT * FROM coordinator_remote_commands').get(),originalCall=f.db.prepare('SELECT * FROM voice_calls').get();
 const plan=prepareIntentCollisionRecovery(f.db,f.scope,COLLIDING_SOURCE_SHA);applyIntentCollisionRecovery(f.db,plan,COLLIDING_SOURCE_SHA);
 const result=await continueIntentCollisionRecovery(f.db,plan,{get:p=>f.callApi(p),post:f.callApi});assert.equal(result.accepted,true);assert.deepEqual(f.counts,{create:0,message:1});
 assert.deepEqual(f.db.prepare('SELECT * FROM coordinator_remote_commands WHERE id=?').get(originalCreation.id),originalCreation);
 assert.deepEqual(f.db.prepare('SELECT * FROM voice_calls').get(),originalCall);
 assert.equal(JSON.parse(f.db.prepare('SELECT plan FROM coordinator_room_intent_recoveries').get().plan).original.intent.phase,'sending');
 const again=await continueIntentCollisionRecovery(f.db,plan,{get:p=>f.callApi(p),post:f.callApi});assert.equal(again.eventId,result.eventId);assert.equal(f.counts.message,1);
});
test('lost recovery ACK stays inspect-only across reopen; no second dispatch despite inaccessible canonical receipt',async t=>{
 const f=await fixture(t);f.legacyCollision();const plan=prepareIntentCollisionRecovery(f.db,f.scope,COLLIDING_SOURCE_SHA);applyIntentCollisionRecovery(f.db,plan,COLLIDING_SOURCE_SHA);f.lose();
 await assert.rejects(continueIntentCollisionRecovery(f.db,plan,{get:p=>f.callApi(p),post:f.callApi}),/not confirmed/);
 await assert.rejects(continueIntentCollisionRecovery(f.db,plan,{get:p=>f.callApi(p),post:f.callApi}),/never resend/);
 assert.deepEqual(f.counts,{create:0,message:1});assert.equal(f.db.prepare('SELECT state FROM coordinator_room_intent_recoveries').get().state,'unknown');
});
test('any original message dispatch evidence or changed source/payload forbids owner recovery',async t=>{
 const f=await fixture(t);f.legacyCollision();assert.throws(()=>prepareIntentCollisionRecovery(f.db,f.scope,'b'.repeat(64)),/source/);
 const plan=prepareIntentCollisionRecovery(f.db,f.scope,COLLIDING_SOURCE_SHA);
 assert.throws(()=>applyIntentCollisionRecovery(f.db,{...plan,roomId:randomUUID()},COLLIDING_SOURCE_SHA),/CAS/);
 f.db.prepare('INSERT INTO coordinator_remote_commands VALUES(?,?,?,?,?,?,?,?,?,?)').run('old-uncertain',f.scope.device,f.scope.source,f.actor.voiceTurnId,'/api/hollywood/v3/rooms/'+f.scope.roomId+'/messages','x','unknown',null,2,f.payload.message.operationId);
 assert.throws(()=>prepareIntentCollisionRecovery(f.db,f.scope,COLLIDING_SOURCE_SHA),/dispatch evidence/);assert.deepEqual(f.counts,{create:0,message:0});
});
test('legacy exact message receipt keeps its original transport identity and never posts again',async t=>{
 const f=await fixture(t),body=f.payload.message,path='/api/hollywood/v3/rooms/'+f.payload.roomId+'/messages',id=digest([f.scope.device,f.scope.source,f.actor.voiceTurnId,f.actor.voiceCallId]);
 f.db.prepare('INSERT INTO coordinator_remote_commands VALUES(?,?,?,?,?,?,?,?,?,?)').run(id,f.scope.device,f.scope.source,f.actor.voiceTurnId,path,digest(body),'responded',JSON.stringify({accepted:true,original:true}),1,body.operationId);
 assert.deepEqual(await f.callApi(path,body),{accepted:true,original:true});assert.deepEqual(f.counts,{create:0,message:0});
});
