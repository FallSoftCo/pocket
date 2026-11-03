import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {EventEmitter} from 'node:events';
import {VoiceController,VOICE_TOOL,VOICE_TOOL_REVISION} from '../server/voice-controller.mjs';
import {coordinatorRoomPath} from '../server/coordinator-room-paths.mjs';
import {gatewayControl,canonicalControl} from './room-control-fixture.mjs';
const source='11111111-1111-4111-8111-111111111111',agent='22222222-2222-4222-8222-222222222222';
const human='33333333-3333-4333-8333-333333333333',authority='44444444-4444-4444-8444-444444444444',binding='a'.repeat(64);
const err=(status,message='Unavailable')=>Object.assign(Error(message),{status});
function fixture(t){
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());const rooms=new Map([[source,{id:source,name:'Source',purpose:'Existing work',owner:agent}]]),receipts=new Map(),calls=[];
 let failCreate=false,failSend=false,missing=false,corrupt=false,deny=false,rejectPost=false;
 const api=async(path,body,actor)=>{
  calls.push({path,body,actor});assert.equal(actor.deviceId,'phone');
  if(path.endsWith('/identity'))return {bindingId:binding,authorityId:authority,principalId:human,roomControl:gatewayControl};
  assert.equal(actor.hollywoodBinding,binding);
  if(path==='/api/hollywood/v3/rooms'&&body){rooms.set(body.id,{...body,owner:body.ownerId});if(failCreate){failCreate=false;throw err(503);}return {room:rooms.get(body.id)};}
  if(path.includes('/messages')&&body){
   if(deny||rejectPost)throw err(403,'Private source cannot become public');
   const event={id:'55555555-5555-4555-8555-'+String(receipts.size+1).padStart(12,'0'),room:path.split('/')[5],author:human,authority,
    kind:body.kind,body:body.body,audience:body.audience,recipient:body.recipientId??null,source:body.sourceEventId??null,data:{authorKind:'human',conversationVersion:1}};
   const value={accepted:true,event,deliveries:[]};if(!missing)receipts.set(body.operationId,value);
   if(failSend){failSend=false;throw err(503);}return value;
  }
  if(path.includes('/commands/')){const v=receipts.get(path.split('/').at(-1));if(!v)throw err(404);return corrupt?{...v,event:{...v.event,author:agent}}:v;}
  if(path.includes('/history?'))return {events:[{id:'recent',room:source,body:'Actual recent finding'}],nextCursor:88,hasMore:true};
  if(path==='/api/hollywood/v3/rooms')return {authorityId:authority,rooms:[...rooms.values()],roomControl:canonicalControl(authority,[source])};
  const r=rooms.get(path.split('/').at(-1));if(!r)throw err(404);
  return {room:r,actors:[{id:agent,kind:'agent',active:true}],permissions:{can_read:true,can_request:!deny}};
 };
 const make=()=>new VoiceController({db,codex:new EventEmitter(),api,cwd:'/qa',host:'qa'});let c=make();
 db.prepare("INSERT INTO voice_sessions(device,selected,focus,selected_environment) VALUES('phone','selected_codex','selected','mac')").run();
 const active=id=>{db.prepare("INSERT OR IGNORE INTO voice_turns(device,id,hash,state,transcript,created_at) VALUES('phone',?,'h','thinking','Explicit synthetic human intent',1)").run(id);return {device:'phone',id};};
 const control=(op,args,id='human-turn-01',call='original-call')=>c.control(active(id),{operation:op,arguments:{environmentId:'current',...args}},{callId:call});
 return {db,calls,rooms,receipts,active,control,get controller(){return c;},reopen:()=>{c=make();},createLost:()=>failCreate=true,sendLost:()=>failSend=true,missing:()=>missing=true,corrupt:()=>corrupt=true,deny:()=>deny=true,rejectPost:()=>rejectPost=true};
}
const create={sourceRoomId:source,ownerId:agent,name:'New project',purpose:'Synthetic room work',text:'Start the explicit bounded inspection.'};
const send={roomId:source,text:'Inspect the existing project.',audience:'room'};
const writes=f=>f.calls.filter(c=>c.body);

test('capability and recent conversation are read-only, exact source/cursor, selected Codex focus unchanged',async t=>{
 const f=fixture(t);assert.equal(VOICE_TOOL_REVISION,5);assert.match(VOICE_TOOL.description,/roomHistory/);
 await f.control('rooms',{});await f.control('roomHistory',{roomId:source,before:99,limit:8});
 assert.equal(writes(f).length,0);assert.ok(f.calls.some(c=>c.path.endsWith('/history?view=conversation&limit=8&before=99')));
 assert.equal(f.controller.session('phone').selected,'selected_codex');assert.equal(f.controller.session('phone').selected_environment,'mac');
 await assert.rejects(f.control('roomHistory',{roomId:source,limit:31}),/bounded/);
});

test('explicit create-and-start creates one room and one attributed human intent, accepted is not finished',async t=>{
 const f=fixture(t),result=await f.control('createRoomAndStart',create);
 assert.equal(writes(f).length,2);assert.equal(writes(f)[0].path,'/api/hollywood/v3/rooms');
 assert.equal(writes(f)[1].path,`/api/hollywood/v3/rooms/${result.roomId}/messages`);
 assert.equal(writes(f)[1].body.kind,'request');assert.equal(writes(f)[1].body.audience,'room');
 assert.equal(result.accepted,true);assert.equal(result.workCompletion,'not_inferred');
 assert.ok(!f.calls.some(c=>c.path.includes('/threads/')));assert.equal(f.controller.session('phone').selected,'selected_codex');
 const again=await f.control('createRoomAndStart',create);assert.equal(again.commandId,result.commandId);assert.equal(writes(f).length,2);
});

test('lost room ACK + reopen reuses exact created room and sends only originally unsubmitted initial intent',async t=>{
 const f=fixture(t);f.createLost();await assert.rejects(f.control('createRoomAndStart',create),/Original room command/);assert.equal(writes(f).length,1);
 const before=f.db.prepare('SELECT * FROM coordinator_room_intents').get();f.reopen();
 const result=await f.control('createRoomAndStart',create,'human-recovery-02','recover-original');
 assert.equal(result.commandId,before.id);assert.equal(result.accepted,true);assert.equal(writes(f).filter(x=>x.path.endsWith('/rooms')).length,1);assert.equal(writes(f).length,2);
});

test('lost initial-message ACK + reopen only reads canonical original, never repeats POST',async t=>{
 const f=fixture(t);f.sendLost();await assert.rejects(f.control('roomReply',send),/Original room command/);const original=f.db.prepare('SELECT * FROM coordinator_room_intents').get();
 f.reopen();const status=await f.control('roomReceipt',{commandId:original.id},'human-inspect-02','read-original');
 assert.equal(status.accepted,true);assert.equal(status.commandId,original.id);assert.equal(writes(f).length,1);
 const same=await f.control('roomReply',send);assert.equal(same.accepted,true);assert.equal(writes(f).length,1);
});

test('missing receipt remains unknown, changed request held, wrong human receipt cannot accept',async t=>{
 const f=fixture(t);f.sendLost();f.missing();await assert.rejects(f.control('roomReply',send),/Original room command/);
 const row=f.db.prepare('SELECT * FROM coordinator_room_intents').get(),before=row.payload;f.reopen();
 const missing=await f.control('roomReceipt',{commandId:row.id});assert.equal(missing.phase,'sending');assert.equal(missing.admission,'inaccessible_unknown');assert.equal(missing.observedReadStatus,404);
 await assert.rejects(f.control('roomReply',{...send,text:'Different instruction'},'human-new-02','new-call'),/Earlier room intent/);
 assert.equal(writes(f).length,1);assert.equal(f.db.prepare('SELECT payload FROM coordinator_room_intents').get().payload,before);
 const p=JSON.parse(before);f.receipts.set(p.message.operationId,{accepted:true,event:{id:source,author:agent},deliveries:[]});f.corrupt();
 await assert.rejects(f.control('roomReceipt',{commandId:row.id}),/unconfirmed/);assert.equal(writes(f).length,1);
});

test('private source/recipient preserved, invalid scope held, canonical rejection is not acceptance',async t=>{
 const f=fixture(t);const privateSend={...send,audience:'direct',recipientId:agent,sourceEventId:source};
 const r=await f.control('roomReply',privateSend);assert.equal(r.accepted,true);assert.deepEqual(writes(f)[0].body,{operationId:r.operationId,kind:'request',body:send.text,audience:'direct',recipientId:agent,sourceEventId:source});
 await assert.rejects(f.control('roomReply',{...send,audience:'direct'},'human-02'),/audience/);
 f.deny();await assert.rejects(f.control('roomReply',send,'human-03'),/cannot request/);assert.equal(writes(f).length,1);
});

test('ledger is device/environment/binding scoped and original payload is immutable',async t=>{
 const f=fixture(t),r=await f.control('roomReply',send);
 await assert.rejects(f.control('roomReply',{...send,text:'Changed same identity'}),/payload conflicts/);
 await assert.rejects(f.controller.roomIntents.command({operation:'roomReceipt',args:{commandId:r.commandId},active:{device:'other'},environmentId:'current',identity:{bindingId:binding}}),/not available/);
 await assert.rejects(f.controller.roomIntents.command({operation:'roomReceipt',args:{commandId:r.commandId},active:{device:'phone'},environmentId:'current',identity:{bindingId:'b'.repeat(64)}}),/not available/);
 assert.equal(writes(f).length,1);
});

test('shared narrow route gates reject broad proxy and history scope/duplicate query expansion',()=>{
 assert.ok(coordinatorRoomPath('/api/hollywood/v3/rooms/'+source+'/history?view=conversation&limit=12'));
 for(const path of ['/api/admin','/api/hollywood/v3/principals','/api/hollywood/v3/rooms/'+source+'/history?view=all&limit=12','/api/hollywood/v3/rooms/'+source+'/history?view=conversation&limit=12&limit=30'])assert.equal(coordinatorRoomPath(path),false);
 assert.ok(coordinatorRoomPath('/api/hollywood/v3/rooms/'+source+'/messages','POST'));
});

test('partial creation with a rejected initial instruction retains the room, does not recreate or retry',async t=>{
 const f=fixture(t);f.rejectPost();await assert.rejects(f.control('createRoomAndStart',create),/instruction rejected/);
 const row=f.db.prepare('SELECT * FROM coordinator_room_intents').get();assert.equal(row.phase,'initial_rejected');assert.equal(writes(f).length,2);
 f.reopen();const result=await f.control('createRoomAndStart',create,'human-retry-02','new-call');
 assert.equal(result.commandId,row.id);assert.equal(result.accepted,false);assert.equal(result.creation.state,'accepted');assert.equal(result.phase,'initial_rejected');assert.equal(writes(f).length,2);
});

test('concurrent identical tool calls share one original dispatch, legacy unknown creation blocks replacement',async t=>{
 const f=fixture(t);const [a,b]=await Promise.all([f.control('createRoomAndStart',create),f.control('createRoomAndStart',create)]);
 assert.equal(a.commandId,b.commandId);assert.equal(writes(f).length,2);
 f.controller.roomCommands.scope('phone','current',binding).start({operationId:'legacy-uncertain',id:source},'original-hash');
 await assert.rejects(f.control('createRoomAndStart',{...create,name:'Different project'},'human-other-02'),e=>e.code==='ROOM_ORIGINAL_ADMISSION_UNRESOLVED'&&e.admission==='inaccessible_unknown');
 assert.equal(writes(f).length,2);
});
