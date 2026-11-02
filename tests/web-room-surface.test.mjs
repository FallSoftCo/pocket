import test from 'node:test';
import assert from 'node:assert/strict';
import {RoomClient,verifiedIdentity,canAnswer,sourcePath} from '../web/room-client.mjs';
import {roomState} from '../web/room-model.mjs';
import {deliveryAccepted,bindingMatches} from '../web/receipt-policy.mjs';
const id=n=>'dddddddd-dddd-4ddd-8ddd-'+String(n).padStart(12,'0');
const binding={bindingId:'a'.repeat(64),installationId:id(1),authorityId:id(2),principalId:id(3),defaultRoomId:id(4)};
const room={id:id(4),authority:id(2),name:'Synthetic room'};
const event=n=>({id:id(n),seq:n,authority:id(2),room:id(4),author:id(5),audience:'room',body:'Answer '+n});
test('native fresh Idle supersedes old executor counts; missing, expired and malformed native state never fall back',()=>{
 const value={activity:{version:1,observedAt:1,pending:0,claimed:1,running:7,cancelRequested:0,unknown:0,waitingForHuman:0},nativeState:{version:1,observedAt:1000,runtimeCount:1,agents:[{agentId:id(5),nativeThread:'original',expiresAt:2000,status:{type:'idle'}}]}};
 assert.equal(roomState(value,{now:1500}).label,'Idle');
 assert.match(roomState(value,{now:2001}).label,/unavailable/);
 assert.equal(roomState({...value,nativeState:null}).known,false);
 assert.equal(roomState(value,{available:false}).known,false);
 assert.equal(roomState({...value,nativeState:{...value.nativeState,agents:[value.nativeState.agents[0],value.nativeState.agents[0]],runtimeCount:2}},{now:1500}).known,false);
});
test('binding verification rejects malformed or changed principal/installation and routes only existing environment IDs',()=>{
 assert.deepEqual(verifiedIdentity(binding),binding);
 assert.throws(()=>verifiedIdentity({...binding,bindingId:'short'}),/unconfirmed/);
 assert.equal(bindingMatches(binding,{...binding,installationId:id(99)}),false);
 assert.equal(sourcePath('gpu','/api/hollywood/v3/rooms'),'/api/environments/gpu/hollywood/v3/rooms');
});
test('room reader service uses explicit newest first, then live after; older continuation stays independent by view',async()=>{
 const calls=[];const client=new RoomClient(async(path,body,options)=>{
  calls.push({path,body,options});if(path.endsWith('/identity'))return binding;
  assert.equal(options.headers['X-Hollywood-Binding'],binding.bindingId);
  if(path.endsWith('/rooms'))return {authorityId:id(2),rooms:[room],binding};
  const u=new URL(path,'https://fixture');const before=u.searchParams.get('before'),after=u.searchParams.get('after'),view=u.searchParams.get('view');
  return {authorityId:id(2),roomId:id(4),view,events:[event(before==='9007199254740991'?20:after?21:10)],olderCursor:before==='10'?null:10,nextCursor:21,binding};
 });
 const [entry]=await client.discover();const first=await client.read(entry.id);
 assert.equal(first.rows[0].text,'Answer 20');assert.match(calls.at(-1).path,/before=9007199254740991/);
 await client.read(entry.id,'all',10);assert.equal(client.history(entry.id,'all').olderCursor,null);
 const head=await client.read(entry.id);assert.match(calls.at(-1).path,/after=20/);assert.deepEqual(head.rows.map(r=>r.canonicalSeq),[10,20,21]);assert.equal(head.before,null);
 await client.read(entry.id,'conversation');assert.match(calls.at(-1).path,/before=9007199254740991/);
 assert.equal(client.history(entry.id,'all').events.size,3);
});
test('original stock/coordinator receipt policy is fail-closed for unknown, 404 bodies, ID/scope mismatch',()=>{
 const intent={id:'original',scope:'stock-a',text:'Draft'};
 for(const receipt of [{},{id:'original',thread_id:'stock-a',state:'unknown'},{id:'other',thread_id:'stock-a',state:'accepted'},{id:'original',thread_id:'stock-b',state:'accepted'}])assert.equal(deliveryAccepted(intent,receipt),false);
 assert.equal(deliveryAccepted(intent,{id:'original',thread_id:'stock-a',state:'queued'}),true);
 const c={...intent,scope:'coordinator'};
 assert.equal(deliveryAccepted(c,{id:'original',state:'completed',transcript:'Draft'}),true);
 assert.equal(deliveryAccepted(c,{id:'original',state:'completed',transcript:'Different input'}),false);
});
test('public room fanout receipt needs original human event, exact captured target/body and native conversation attestation',()=>{
 const intent={id:'web-original',room:id(4),route:{binding},body:{operationId:'web-original',kind:'request',body:'Draft',audience:'room'}};
 const receipt={accepted:true,binding,event:{...event(20),author:id(3),kind:'request',body:'Draft',data:{conversationVersion:1,authorKind:'human'}},deliveries:[]};
 assert.equal(deliveryAccepted(intent,receipt),true);
 for(const altered of [{...receipt,accepted:false},{...receipt,event:{...receipt.event,recipient:id(7)}},{...receipt,event:{...receipt.event,room:id(8)}},{...receipt,binding:{...binding,principalId:id(9)}},{...receipt,event:{...receipt.event,data:{}}}])assert.equal(deliveryAccepted(intent,altered),false);
});
test('conversation question and legacy approval require exact native scope and actionable original callback',()=>{
 const scope={conversationVersion:1,authorityId:id(2),attentionId:id(10),agentId:id(5),roomId:id(4),sourceEventId:id(11),nativeThread:'original',nativeTurn:'turn',requestDigest:'b'.repeat(64)};
 const attention={id:id(10),authority:id(2),agent:id(5),room:id(4),event:id(11),native_thread:'original',native_turn:'turn',digest:'b'.repeat(64),state:'pending'};
 const value={scope,attention,canRespond:true};assert.equal(canAnswer(value),true);
 assert.equal(canAnswer({...value,canRespond:false}),false);
 assert.equal(canAnswer({...value,scope:{...scope,nativeTurn:'other'}}),false);
 assert.equal(canAnswer({...value,attention:{...attention,state:'expired'}}),false);
});


test('detail runtime observation replaces retained catalog status without erasing preview or retargeting writes',async()=>{
 const native=type=>({version:1,observedAt:1000,runtimeCount:1,agents:[{agentId:id(5),nativeThread:'original',expiresAt:2000,status:{type,activeFlags:[]}}]});
 let observed=native('idle');
 const client=new RoomClient(async path=>{
  if(path.endsWith('/identity'))return binding;
  if(path.endsWith('/rooms'))return {authorityId:id(2),rooms:[{...room,feedSummary:{version:1,marker:'retained'},nativeState:native('unavailable')}],binding};
  return {room,actors:[],permissions:{can_post:true},binding,...(observed===undefined?{}:{nativeState:observed})};
 });
 const [entry]=await client.discover();await client.discovery.done;
 const captured=structuredClone(client.route(entry.id));
 await client.detail(entry.id);
 assert.equal(roomState(client.entry(entry.id).room,{now:1500}).label,'Idle');
 assert.equal(client.entry(entry.id).room.feedSummary.marker,'retained');
 assert.equal(client.route(entry.id).binding.bindingId,captured.binding.bindingId);
 observed=undefined;await client.detail(entry.id);
 assert.equal(roomState(client.entry(entry.id).room,{now:1500}).known,false);
 assert.equal(client.entry(entry.id).room.nativeState,null);
});
