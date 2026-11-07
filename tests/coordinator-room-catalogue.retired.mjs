import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {EventEmitter} from 'node:events';
import {coordinatorRoomCatalogue} from '../server/coordinator-room-catalogue.mjs';
import {VoiceController} from '../server/voice-controller.mjs';
import {qaHuman,gatewayControl,canonicalControl} from './room-control-fixture.mjs';
const room='11111111-1111-4111-8111-111111111111',agent='22222222-2222-4222-8222-222222222222',binding='a'.repeat(64);

test('room catalogue uses each binding, deduplicates federation and keeps source preference despite response latency',async()=>{
 const calls=[];
 const result=await coordinatorRoomCatalogue({targets:[{environmentId:'cpu',name:'CPU'},{environmentId:'gpu',name:'GPU'},{environmentId:'mac',name:'Mac'}],call:async(env,path,options)=>{
  calls.push({env,path,binding:options.hollywoodBinding});
  if(env==='mac')throw Error('Offline');
  if(path.endsWith('identity')){if(env==='cpu')await new Promise(r=>setTimeout(r,12));return {bindingId:env==='cpu'?binding:'b'.repeat(64),authorityId:'fleet'};}
  assert.equal(options.hollywoodBinding,env==='cpu'?binding:'b'.repeat(64));return {authorityId:'fleet',rooms:[{id:room,name:'Work',owner:agent}]};
 }});
 assert.equal(result.rooms.length,1);assert.equal(result.rooms[0].environmentId,'cpu');assert.deepEqual(result.rooms[0].routes.map(r=>r.environmentId),['cpu','gpu']);
 assert.equal(result.available,true);assert.equal(result.complete,false);assert.equal(result.sources[2].available,false);assert.equal(result.workSubmitted,false);
 assert.equal(calls.length,5);assert.doesNotMatch(JSON.stringify(result),new RegExp(binding));
});

test('one room discovery deadline bounds unavailable sources without manufacturing an empty success',async()=>{
 const result=await coordinatorRoomCatalogue({targets:[{environmentId:'offline',name:'Offline'}],deadlineMs:20,call:(_env,_path,{signal})=>new Promise((resolve,reject)=>{
  const timer=setTimeout(resolve,300);signal.addEventListener('abort',()=>{clearTimeout(timer);reject(signal.reason);},{once:true});
 })});
 assert.equal(result.available,false);assert.equal(result.complete,false);assert.deepEqual(result.rooms,[]);assert.equal(result.sources[0].available,false);
});

test('selected Codex host cannot override discovered room source or original human creation provenance',async t=>{
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());const calls=[];let stored=null;
 const controller=new VoiceController({db,codex:new EventEmitter(),api:async()=>{throw Error('Local source has no rooms');},cwd:'/fixture',host:'fixture'});
 db.prepare("INSERT INTO voice_sessions(device,selected,focus,selected_environment) VALUES('phone','mac-session','selected','mac')").run();
 db.prepare("INSERT INTO voice_turns(device,id,hash,state,transcript,created_at) VALUES('phone','voice-room','hash','thinking','Create a project room',1)").run();
 controller.sources={list:()=>[{environmentId:'cpu',name:'CPU'},{environmentId:'mac',name:'Mac'}],call:async(device,env,path,body,actor,options)=>{
  calls.push({device,env,path,body,actor});if(env!=='cpu')throw Error('Room source unavailable');
  if(path.endsWith('/identity'))return {bindingId:binding,authorityId:'fleet',principalId:qaHuman,roomControl:gatewayControl};
  assert.equal(actor.hollywoodBinding,binding);
  if(body){stored={...body,owner:body.ownerId};return {room:stored};}
  if(path.endsWith('/rooms'))return {authorityId:'fleet',rooms:[{id:room,name:'Source',owner:agent}],roomControl:canonicalControl('fleet',[room])};
  if(path.endsWith(room))return {room:{id:room,name:'Source',owner:agent},actors:[{id:agent,kind:'agent'}]};
  return {room:stored};
 }};
 const active={device:'phone',id:'voice-room'};
 const catalogue=await controller.control(active,{operation:'rooms',arguments:{}});assert.equal(catalogue.rooms[0].environmentId,'cpu');
 const args={operation:'createRoom',arguments:{name:'New project',purpose:'Project purpose',ownerId:agent,sourceRoomId:room}};
 const tool={tool:'pocket_control',callId:'create-one',arguments:args};
 const answer=await controller.tool(active,tool);assert.equal(answer.success,true);const result=JSON.parse(answer.contentItems[0].text);
 assert.equal(result.created,true);assert.equal(result.environmentId,'cpu');assert.equal(result.taskSubmitted,false);
 const writes=calls.filter(c=>c.body);assert.equal(writes.length,1);assert.equal(writes[0].env,'cpu');
 assert.deepEqual(writes[0].actor,{deviceId:'phone',voiceTurnId:'voice-room',voiceCallId:'create-one',hollywoodBinding:binding});
 assert.deepEqual(await controller.tool(active,tool),answer);assert.equal(calls.filter(c=>c.body).length,1);
});

test('HTTP room admission uses the paired human binding; lost ACK is reconciled after controller reopen without a second POST',async t=>{
 const http=await import('node:http'),{once}=await import('node:events'),{CoordinatorSources}=await import('../server/coordinator-sources.mjs');
 let stored=null,posts=0;
 const server=http.createServer(async(req,res)=>{
  if(req.headers.authorization!=='Bearer original-human-device-token')return res.writeHead(403).end('{}');
  if(req.url==='/api/status')return res.end(JSON.stringify({deviceId:'remote-human'}));
  if(req.url.endsWith('/identity'))return res.end(JSON.stringify({bindingId:binding,authorityId:'qa',principalId:qaHuman,roomControl:gatewayControl}));
  if(req.headers['x-hollywood-binding']!==binding)return res.writeHead(409).end('{}');
  if(req.method==='POST'){
   assert.equal(req.url,'/api/hollywood/v3/rooms');assert.equal(req.headers['x-nextcomp-coordinator-turn'],'human-create');
   let text='';for await(const chunk of req)text+=chunk;const body=JSON.parse(text);stored={...body,owner:body.ownerId};posts++;req.socket.destroy();return;
  }
  if(req.url.endsWith('/rooms'))return res.end(JSON.stringify({authorityId:'qa',rooms:[{id:room,name:'QA source',owner:agent}],roomControl:canonicalControl('qa',[room])}));
  if(req.url.endsWith(room))return res.end(JSON.stringify({room:{id:room},actors:[{id:agent,kind:'agent'}]}));
  res.end(JSON.stringify({room:stored}));
 });
 server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>server.close());
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());
 const make=()=>{
  const c=new VoiceController({db,codex:new EventEmitter(),cwd:'/fixture',host:'fixture',api:async()=>{throw Error('No local rooms');}});
  c.sources=new CoordinatorSources({db,key:'qa-key',authorize:()=>true,localApi:c.api,fetchImpl:(url,options)=>fetch('http://127.0.0.1:'+server.address().port+new URL(url).pathname,options)});return c;
 };
 let c=make();await c.sources.register('phone',[{environmentId:'cpu',name:'CPU',base:'https://cpu.example.ts.net',token:'original-human-device-token'}]);
 db.prepare("INSERT INTO voice_sessions(device) VALUES('phone')").run();
 const put=db.prepare("INSERT INTO voice_turns(device,id,hash,state,transcript,created_at) VALUES('phone',?,'hash','thinking','QA room creation',1)");put.run('human-create');
 const command={tool:'pocket_control',callId:'original-room',arguments:{operation:'createRoom',arguments:{environmentId:'cpu',name:'QA only',purpose:'No model or agent execution',ownerId:agent,sourceRoomId:room}}};
 assert.equal((await c.tool({device:'phone',id:'human-create'},command)).success,false);assert.equal(posts,1);
 c=make();put.run('human-inspect');
 const reply=await c.tool({device:'phone',id:'human-inspect'},{...command,callId:'inspect-original'});
 assert.equal(reply.success,true);const value=JSON.parse(reply.contentItems[0].text);assert.equal(value.reconciledOriginal,true);assert.equal(value.room.id,stored.id);assert.equal(posts,1);
 assert.equal(db.prepare("SELECT state FROM coordinator_room_commands").get().state,'accepted');
});
