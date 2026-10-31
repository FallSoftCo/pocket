import test from 'node:test';
import assert from 'node:assert/strict';
import {roomActivityState,roomEventRows} from '../web/room-model.mjs';
const id=n=>'aaaaaaaa-aaaa-4aaa-8aaa-'+String(n).padStart(12,'0');
const scope={authorityId:id(1),roomId:id(2),principalId:id(3)};
const event=(n,phase,author=id(4))=>({id:id(n),seq:n,authority:scope.authorityId,room:scope.roomId,author,audience:'room',body:'Run observation',data:{type:'executionActivity',activityVersion:1,actionId:'action-'+String(50).padStart(64,'0'),phase,status:phase==='completed'?'completed':'inProgress',title:'Compile',hostId:id(60),deliveryId:id(70),output:'compiler output'}});
test('room state uses only complete authoritative counts and never double counts attention',()=>{
 assert.equal(roomActivityState(null).label,'Status unavailable');
 const base={version:1,observedAt:1,pending:0,claimed:0,running:0,cancelRequested:0,unknown:0,waitingForHuman:0};
 assert.equal(roomActivityState(base).label,'Idle');
 assert.equal(roomActivityState({...base,running:1,waitingForHuman:1}).label,'1 needs you · 1 running');
 assert.equal(roomActivityState({...base,unknown:-1}).known,false);
});
test('concurrent actions retain one stable operation, provenance and terminal state without chat copies',()=>{
 const one=event(10,'completed'),late=event(12,'started'),two=event(11,'started',id(5));two.data={...two.data,actionId:'action-'+String(51).padStart(64,'0')};
 const message={id:id(13),seq:13,authority:scope.authorityId,room:scope.roomId,author:scope.principalId,audience:'room',body:'A deliberate human message'};
 const rows=roomEventRows([late,message,one,two,one],scope);
 assert.equal(rows.length,3);assert.equal(rows.filter(r=>r.kind==='message').length,0);assert.equal(rows.filter(r=>r.kind==='user').length,1);
 assert.equal(rows[0].status,'completed');assert.equal(rows[0].actionObservations.length,2);assert.equal(rows[1].agentId,id(5));
 assert.equal(rows[0].id,roomEventRows([one],scope)[0].id);assert.match(rows[0].detail,/compiler output/);
 assert.throws(()=>roomEventRows([{...one,authority:id(99)}],scope),/identity/);
 assert.throws(()=>roomEventRows([one,{...one,body:'Changed'}],scope),/changed/);
});

test('room head refresh retains loaded history and independent older cursor without duplicating reconnect events',async()=>{
 const {RoomHistory}=await import('../web/room-model.mjs');
 const history=new RoomHistory(scope);
 const page=(events,olderCursor)=>({...scope,view:'all',events,olderCursor,nextCursor:999});
 const a=event(10,'started'),b=event(11,'completed');
 history.apply(page([b],10));
 assert.equal(history.olderCursor,10,'Continuation must use olderCursor, not the independent live head');
 history.apply(page([a],null),{older:true});
 assert.equal(history.olderCursor,null);
 history.apply(page([b],10));
 assert.equal(history.olderCursor,null);assert.equal(history.events.size,2);
 assert.equal(history.rows().length,1);assert.equal(history.rows()[0].status,'completed');
 assert.throws(()=>history.apply({...page([b],10),view:'activity'}),/scope/);
 assert.throws(()=>history.apply(page([{...b,body:'mutated'}],10)),/changed/);
 assert.equal(history.events.get(b.id).body,b.body);
 const conflict={...b,id:id(90)};
 assert.throws(()=>history.apply(page([conflict],null)),/sequence/);
 assert.equal(history.events.size,2);
});
