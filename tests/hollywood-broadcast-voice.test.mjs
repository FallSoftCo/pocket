import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {HollywoodVoice} from '../server/hollywood-voice.mjs';
const id=n=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
test('room voice captures no default owner, collects correlated agents, and reconciles without repost',async()=>{
 const db=new DatabaseSync(':memory:');const binding={bindingId:'a'.repeat(64),installationId:id(1),authorityId:id(2),principalId:id(3),defaultRoomId:id(4)};
 let posts=0,event;const outputs=[{id:id(6),room:id(4),author:id(8),recipient:null,kind:'message',body:'Agent A answer',data:{authorKind:'agent',responseVersion:1,responsePhase:'answer'}},{id:id(7),room:id(4),author:id(9),recipient:null,kind:'message',body:'Agent B update',data:{authorKind:'agent',responseVersion:1,responsePhase:'update'}},{id:id(10),room:id(4),author:id(3),recipient:null,kind:'message',body:'Human follow-up',data:{authorKind:'human'}}];
 const client={config:binding,envelope:()=>binding,verify:async()=>{},call:async(path,o={})=>{
  if(path==='rooms/'+id(4))return {room:{name:'Room',owner:id(99)},permissions:{can_request:true},actors:[{id:id(99),kind:'agent',can_read:false}]};
  if(path.endsWith('/messages')){posts++;assert.equal(Object.hasOwn(o.body,'recipientId'),false);event={id:id(5),seq:1,room:id(4),authority:id(2),author:id(3),recipient:null,kind:'request',body:o.body.body,audience:'room',data:{conversationVersion:1,authorKind:'human'}};return {accepted:true,event,deliveries:[]};}
  if(path.startsWith('commands/'))return {accepted:true,event,deliveries:[]};
  if(path.endsWith('/history'))return {events:outputs.map(e=>({...e,source:event.id})),nextCursor:5};
  throw Error(path);
 }};
 try{
  const gateway={client:()=>client};const voice=new HollywoodVoice({db,gateway});
  const d=await voice.destination('phone',{backend:'lax',bindingId:binding.bindingId,authorityId:id(2),roomId:id(4),audience:'room'});
  await assert.rejects(()=>voice.destination('phone',{...d,audience:'direct',binding:undefined,title:undefined}),/Choose/);
  await voice.submitText('phone','recording-123','Read this room',d);await new Promise(r=>setImmediate(r));
  const result=await voice.refresh('phone','recording-123');assert.equal(result.state,'completed');assert.equal(result.response,'Agent A answer');assert.equal(posts,1);
  const restarted=new HollywoodVoice({db,gateway});assert.equal((await restarted.refresh('phone','recording-123')).response,'Agent A answer');assert.equal(posts,1);
  const row=db.prepare('select outputs from hollywood_voice_turns').get();assert.equal(JSON.parse(row.outputs).length,2);
 }finally{db.close();}
});
