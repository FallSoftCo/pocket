import test from 'node:test';
import assert from 'node:assert/strict';
import {RoomClient} from '../web/room-client.mjs';
import {RoomHistory,RoomConversationHistory} from '../web/room-model.mjs';
import {roomLead,assertLeadTarget} from '../web/room-lead.mjs';
const id=n=>`${String(n).padStart(8,'0')}-0000-4000-8000-000000000000`;
const authority=id(1),room=id(2),human=id(3),lead=id(4),worker=id(5);
const binding={bindingId:'a'.repeat(64),installationId:id(6),authorityId:authority,principalId:human,defaultRoomId:room};
const policy={version:1,leadAgentId:lead,leadName:'Project lead',revision:1,inputAfterSeq:10};
const event=(seq,author,body,data={})=>({id:id(100+seq),authority,room,seq,author,kind:'message',audience:'room',recipient:null,body,data});
const humanEvent=event(11,human,'Original human',{authorKind:'human',channel:'human',leadRevision:1});
const internal=event(12,lead,'Internal assigned task',{authorKind:'agent',channel:'coordination'});
const started={...event(13,worker,'Inspect local source'),kind:'progress',data:{type:'executionActivity',activityVersion:1,actionId:'run-one',nativeType:'commandExecution',phase:'started',status:'inProgress'}};
const completed={...event(14,worker,'Inspect local source'),kind:'progress',data:{...started.data,phase:'completed',status:'completed'}};
const answer=event(15,lead,'One human answer',{authorKind:'agent',channel:'human',leadRevision:1});
const old=event(1,worker,'Old team archive',{authorKind:'agent'});
function fixture(){
 const calls=[],client=new RoomClient(async path=>{
  calls.push(path);const u=new URL(path,'http://fixture');const view=u.searchParams.get('view');
  const events={conversation:[humanEvent,answer],activity:[started,completed],coordination:[internal],all:[old,humanEvent,internal,started,completed,answer]}[view];assert.ok(events,view);
  return {binding,authorityId:authority,roomId:room,view,events,nextCursor:20,olderCursor:null};
 });
 const scope='room:'+authority+':'+human+':'+room;
 client.entries.set(scope,{id:scope,roomId:room,binding,room:{id:room,authority,leadPolicy:policy},selected:'current',routes:[{environment:'current',name:'Fixture',binding,available:true}],detail:{actors:[]}});
 return {client,scope,calls};
}
test('lead main view composes canonical human and activity lanes; team/archive stay independent',async()=>{
 const {client,scope,calls}=fixture();const result=await client.read(scope);
 assert.ok(client.history(scope,'all') instanceof RoomConversationHistory);
 assert.deepEqual(result.rows.map(r=>r.text),['Original human','Inspect local source','One human answer']);
 assert.equal(result.rows[1].status,'completed');assert.equal(calls.length,2);
 assert.ok(calls.every(path=>!path.includes('view=all')));
 assert.deepEqual((await client.read(scope,'coordination')).rows.map(r=>r.text),['Internal assigned task']);
 assert.ok((await client.read(scope,'archive')).rows.some(r=>r.text==='Old team archive'));
 assert.deepEqual(client.history(scope,'all').rows().map(r=>r.text),result.rows.map(r=>r.text));
});
test('policy adoption preserves exact local canonical records without reusing a mixed-view cursor',()=>{
 const {client,scope}=fixture(),e=client.entry(scope);delete e.room.leadPolicy;
 const oldHistory=client.history(scope,'all');oldHistory.apply({authorityId:authority,roomId:room,view:'all',events:[old,humanEvent,internal,completed,answer],nextCursor:20,olderCursor:1});
 e.room.leadPolicy=policy;const next=client.history(scope,'all');
 assert.ok(next instanceof RoomConversationHistory);assert.equal(client.history(scope,'archive'),oldHistory);
 assert.deepEqual(next.rows().map(r=>r.text),['Original human','Inspect local source','One human answer']);
 assert.equal(client.history(scope,'conversation').latestSeq,null);
 assert.equal(client.history(scope,'activity').latestSeq,null);
 assert.equal(client.history(scope,'conversation').events.get(humanEvent.id),humanEvent);
});
test('new input never silently retargets an earlier saved recipient',()=>{
 const roomData={leadPolicy:policy},saved={recipientId:worker,audience:'direct',sourceEventId:id(70)};
 const before=structuredClone(saved);assert.throws(()=>assertLeadTarget(roomData,saved),/saved recipient/i);assert.deepEqual(saved,before);
 assert.equal(assertLeadTarget(roomData,{recipientId:null,audience:'room'}).agent,lead);
 assert.throws(()=>roomLead({leadPolicy:{...policy,revision:-1}}),/unavailable/);
});
