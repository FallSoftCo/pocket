import test from 'node:test';import assert from 'node:assert/strict';
import {roomActorLabel,roomActorNames,roomOriginSummary,roomOriginRows} from '../web/room-identity.mjs';
import {RoomHistory} from '../web/room-model.mjs';
const a='11111111-1111-4111-8111-111111111111',b='22222222-2222-4222-8222-222222222222',h='33333333-3333-4333-8333-333333333333',r='44444444-4444-4444-8444-444444444444',u='55555555-5555-4555-8555-555555555555',e='66666666-6666-4666-8666-666666666666';
const actor=(id,name)=>({id,name:'Global',kind:'agent',identity:{version:1,agentId:id,host:{id:h,name}}});
test('canonical hosts distinguish same-name participants without rename/UUID heuristics',()=>{assert.equal(roomActorLabel(actor(a,'MacBook Pro native host')),'Global · MacBook Pro native host');assert.equal(roomActorLabel(actor(b,'Mac development VM')),'Global · Mac development VM');assert.equal(roomActorLabel({...actor(a,'Wrong'),identity:{version:1,agentId:b,host:{id:h,name:'Wrong'}}}),'Global');assert.equal(roomActorLabel({id:a,name:'Global',kind:'agent',home_node:h}),'Global');});
test('late roster read labels original immutable author and keeps private scope/event bytes',()=>{const source={id:e,authority:h,room:r,seq:1,author:a,authorName:'Global',body:'Original private blocker',audience:'direct',recipient:u,kind:'message',data:{}};const raw=JSON.stringify(source);const history=new RoomHistory({authorityId:h,roomId:r,principalId:u});history.apply({authorityId:h,roomId:r,view:'all',events:[source],nextCursor:1,olderCursor:null});const row=history.rows(roomActorNames([actor(a,'MacBook Pro native host')]))[0];assert.equal(row.roomAuthor,'Global · MacBook Pro native host');assert.equal(row.roomAudience,'direct');assert.equal(row.roomRecipient,u);assert.equal(JSON.stringify(source),raw);});
test('allocation/enrollment is not native-ready; stale detail cannot claim ready',()=>{const detail={roomAgents:{version:1,agents:[{node:h,host_name:'Mac',ready:false,state:'enrolled',readiness:'awaiting-fresh-native-observation',note:'Original browser operation stays old-owned'},{node:a,host_name:'CPU',ready:true,state:'provisioning'}]}};assert.equal(roomOriginSummary(detail),'Room agents: 1/2 ready · Initialization incomplete');assert.match(roomOriginRows(detail)[0],/awaiting-fresh-native-observation/);assert.equal(roomOriginSummary(detail,{available:false}),'Room agent status unavailable');assert.equal(roomOriginSummary({}),'Room participants');assert.equal(roomOriginRows(detail,{available:false}).some(s=>/Ready/.test(s)),false);});

test('staged readiness remains an explicit handoff, not active fleet completion',()=>{assert.equal(roomOriginSummary({roomAgents:{version:1,generation:{phase:'staging'},agents:[{ready:true}]}}),'Room agents: 1/1 ready · Handoff required');});

// Canonical initialization is separate from verified activity and retains scope.
const originRoom='33333333-3333-4333-8333-333333333333';
const originAgent='11111111-1111-4111-8111-111111111111',originNode='22222222-2222-4222-8222-222222222222';
const setupRoom=(ready=false)=>({id:originRoom,roomAgents:{version:1,roomId:originRoom,generation:{phase:'active'},agents:[{agent:originAgent,node:originNode,ready}]}});
test('setup is visible while activity is unavailable, and actual activity still wins',async()=>{
 const {roomState}=await import('../web/room-model.mjs');const room=setupRoom();
 assert.equal(roomState(room).label,'Preparing agents · 0/1');
 const now=Date.now();room.nativeState={version:1,observedAt:now,runtimeCount:1,agents:[{agentId:originAgent,nativeThread:'source-native',expiresAt:now+75000,status:{type:'active',activeFlags:[]}}]};
 assert.equal(roomState(room).tone,'live');
});
test('foreign scope, duplicate environments and malformed readiness never become idle',async()=>{
 const {roomState}=await import('../web/room-model.mjs');
 const duplicate=setupRoom();duplicate.roomAgents.agents.push({agent:originRoom,node:originNode,ready:true});assert.equal(roomState(duplicate).known,false);
 const foreign=setupRoom();foreign.roomAgents.roomId=originNode;assert.equal(roomState(foreign).known,false);
 const malformed=setupRoom();malformed.roomAgents.agents[0].ready='true';assert.equal(roomState(malformed).known,false);assert.deepEqual(roomOriginRows({roomAgents:{version:1,agents:'unavailable'}}),[]);assert.deepEqual(roomOriginRows(foreign),[]);
});

test('retained and Global rooms are not falsely uninitialized',async()=>{const {roomOriginPendingState}=await import('../web/room-identity.mjs');for(const roomKind of ['global','project']){const room={id:originRoom,room_kind:roomKind,roomAgents:{version:1,roomId:originRoom,initializationRequired:false,agents:[]}};assert.equal(roomOriginPendingState(room),null);assert.equal(roomOriginSummary(room),'Room participants');}});
