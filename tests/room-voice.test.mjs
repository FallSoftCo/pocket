import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {RoomVoice} from '../server/room-voice.mjs';
const room='11111111-1111-4111-8111-111111111111',actor='22222222-2222-4222-8222-222222222222',authority='33333333-3333-4333-8333-333333333333';
function fixture(){
 const db=new DatabaseSync(':memory:');let posted=0,lost=false,turnReady=true;
 const event={id:10,task:'team-enterprise',author:'you',body:'Read the room',visibility:'room'};
 const client={config:{environmentId:'host-A'},async call(path,options={}){
  if(path.endsWith('/rooms/'+room))return {id:room,task:event.task,title:'Global',members:[{id:actor,agent:'global-steward',paused:0}]};
  if(path.endsWith('/rooms'))return {authorityId:authority,rooms:[{id:room}]};
  if(path.endsWith('/messages')&&options.body){posted++;if(lost)throw Object.assign(Error('Lost reply'),{status:503});return event;}
  if(path.includes('/commands/'))return {event,deliveries:[{agent:'global-steward',state:'confirmed',turn:turnReady?'turn-a':null}]};
  return {data:[
   {id:11,kind:'message',task:event.task,author:'global-steward',visibility:'room',body:'Unrelated answer',data:{sourceEvent:2}},
   {id:12,kind:'message',task:event.task,author:'global-steward',visibility:'direct:global-steward',body:'Private answer',data:{sourceEvent:10}},
   {id:13,kind:'message',task:event.task,author:'global-steward',visibility:'room',body:'Verified room status',data:{replyTo:10}},
   {id:14,kind:'turn',task:event.task,author:'global-steward',visibility:'room',body:'completed',data:{sourceEvent:10,turn:'turn-a'}}]};
 }};
 const voice=new RoomVoice({db,client});return {db,voice,client,get posted(){return posted;},lose(){lost=true;},noTurn(){turnReady=false;},turn(){turnReady=true;}};
}
async function destination(f){return f.voice.destination({team:'enterprise',roomId:room,actorId:actor,authorityId:authority,direct:false});}
const settle=()=>new Promise(resolve=>setImmediate(resolve));
test('native receipt and correlated reply complete one captured room recording',async()=>{
 const f=fixture();try{const d=await destination(f);f.voice.submitText('phone','recording-a','Read the room',d);await settle();
  const result=await f.voice.refresh('phone','recording-a');assert.equal(result.state,'completed');assert.equal(result.response,'Verified room status');assert.equal(f.posted,1);
  assert.equal(f.voice.get('other-phone','recording-a'),null);
  assert.throws(()=>f.voice.submitText('phone','recording-a','Changed request',d),/different request/);
 }finally{f.db.close();}
});
test('lost submission reply reconciles across restart without sending another command',async()=>{
 const f=fixture();try{f.lose();const d=await destination(f);f.voice.submitText('phone','recording-b','Read the room',d);await settle();assert.equal(f.voice.get('phone','recording-b').state,'unknown');
  const restarted=new RoomVoice({db:f.db,client:f.client});const result=await restarted.refresh('phone','recording-b');assert.equal(result.state,'completed');assert.equal(f.posted,1);
 }finally{f.db.close();}
});
test('completion before turn receipt is retained for later read and changed environment cannot route',async()=>{
 const f=fixture();try{const d=await destination(f);f.voice.submitText('phone','recording-c','Read the room',d);await settle();f.noTurn();assert.equal((await f.voice.refresh('phone','recording-c')).state,'thinking');
  f.turn();assert.equal((await f.voice.refresh('phone','recording-c')).response,'Verified room status');
  f.client.config.environmentId='host-B';assert.throws(()=>f.voice.submitText('phone','recording-d','Read the room',d),/connection changed/);assert.equal(f.posted,1);
 }finally{f.db.close();}
});
test('authority mismatch and unregistered actor are rejected before execution',async()=>{
 const f=fixture();try{
  await assert.rejects(()=>f.voice.destination({team:'enterprise',roomId:room,actorId:actor,authorityId:room,direct:false}),/authority changed/);
  await assert.rejects(()=>f.voice.destination({team:'enterprise',roomId:room,actorId:room,authorityId:authority,direct:false}),/not a member/);
  assert.equal(f.posted,0);
 }finally{f.db.close();}
});
