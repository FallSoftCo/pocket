/** Real canonical HTTP/PostgreSQL + Pocket gateway/admission/remote source.
 * Only Codex/model and private HTTPS DNS transport are stubbed. No live state. */
import assert from 'node:assert/strict';
import express from 'express';
import {DatabaseSync} from 'node:sqlite';
import {EventEmitter,once} from 'node:events';
import {randomUUID,randomBytes,createHash} from 'node:crypto';
import {readFileSync,writeFileSync,existsSync} from 'node:fs';
import {resolve} from 'node:path';
import {VoiceController} from '../server/voice-controller.mjs';
import {CoordinatorSources} from '../server/coordinator-sources.mjs';
import {HollywoodGateway,mountHollywood} from '../server/hollywood.mjs';
import {HumanIngress} from '../server/human-ingress.mjs';

process.umask(0o077);
const root=resolve(process.argv[2]),f=JSON.parse(readFileSync(resolve(root,'canonical.private.json'))),trace=[];
const installation=randomUUID(),phone=randomUUID(),paired=randomBytes(32).toString('hex'),key=randomBytes(32).toString('hex');
const binding=createHash('sha256').update(JSON.stringify([installation,f.authority,f.principal])).digest('hex');
const config={url:f.url,token:f.token,bindingId:binding,installationId:installation,authorityId:f.authority,principalId:f.principal,defaultRoomId:f.room};
const gateway=new HollywoodGateway({devices:new Map([[phone,config]])});
const local=new DatabaseSync(':memory:');local.exec('CREATE TABLE devices(id TEXT PRIMARY KEY,name TEXT);CREATE TABLE voice_turns(device TEXT,id TEXT,state TEXT);');
local.prepare('INSERT INTO devices VALUES(?,?)').run(phone,'Original synthetic paired human');
const ingress=new HumanIngress(local),app=express();app.use(express.json());
app.use((req,res,next)=>{req.device={id:req.get('Authorization')==='Bearer '+paired?phone:'owner'};next();});
app.get('/api/status',(req,res)=>res.json({deviceId:req.device.id}));
app.use((req,res,next)=>ingress.middleware(req,res,next));mountHollywood(app,{gateway});
app.use((error,_req,res,_next)=>res.status(error.status||500).json({error:error.message,code:error.code}));
const server=app.listen(0,'127.0.0.1');await once(server,'listening');
const origin='http://127.0.0.1:'+server.address().port;
const database=resolve(root,'coordinator.sqlite');assert.equal(existsSync(database),false,'Never overwrite an original QA DB');
let db,controller,drop=null;const counts={create:0,message:0,models:0};
const make=()=>{
 db=new DatabaseSync(database);
 controller=new VoiceController({db,codex:new EventEmitter(),api:async()=>{throw Error('No local room fallback or Codex control allowed');},cwd:root,host:'isolated-qa'});
 controller.sources=new CoordinatorSources({db,key,localApi:controller.api,authorize:actor=>actor.deviceId==='qa-phone'&&!!db.prepare("SELECT 1 FROM voice_turns WHERE device=? AND id=? AND state='thinking'").get(actor.deviceId,actor.voiceTurnId),
 fetchImpl:async(url,options)=>{
  const target=new URL(url);assert.equal(target.origin,'https://room-qa.example.ts.net');
  const response=await fetch(origin+target.pathname+target.search,options);
  trace.push({method:options.method||'GET',path:target.pathname+target.search,status:response.status});
  if(options.method==='POST'){
   assert.equal(options.headers.Authorization,'Bearer '+paired);assert.ok(options.headers['X-NextComp-Coordinator-Turn']);
   const kind=target.pathname.endsWith('/rooms')?'create':target.pathname.endsWith('/messages')?'message':null;
   if(kind)counts[kind]++;
   if(drop===kind&&response.ok){drop=null;await response.json();throw Error('Synthetic lost ACK AFTER actual canonical acceptance');}
  }
  return response;
 }});
};
const active=id=>{db.prepare("INSERT OR IGNORE INTO voice_turns(device,id,hash,state,transcript,created_at) VALUES('qa-phone',?,'original','thinking','Explicit isolated QA human intent',1)").run(id);return {device:'qa-phone',id};};
const control=(operation,args,id='human-create-qa',call='original-call')=>controller.control(active(id),{operation,arguments:{environmentId:'cpu',...args}},{callId:call});
try{
 make();await controller.sources.register('qa-phone',[{environmentId:'cpu',name:'Canonical QA CPU',base:'https://room-qa.example.ts.net',token:paired}]);
 db.prepare("INSERT INTO voice_sessions(device,selected,focus,selected_environment) VALUES('qa-phone','original_codex_focus','selected','mac')").run();
 const capability=await control('rooms',{});assert.equal(capability.rooms.length,2);assert.deepEqual(counts,{create:0,message:0,models:0});
 const history=await control('roomHistory',{roomId:f.room,limit:8});assert.ok(history.events.some(e=>e.id===f.privateSource));
 const ownerAttempt=await fetch(origin+'/api/hollywood/v3/rooms/'+f.room+'/messages',{method:'POST',headers:{Authorization:'Bearer admin','Content-Type':'application/json','X-Hollywood-Binding':binding},body:JSON.stringify({operationId:'forbidden-agent-impersonation',kind:'request',body:'Must not enter',audience:'room'})});
 assert.equal(ownerAttempt.status,403);assert.equal((await ownerAttempt.json()).code,'HUMAN_DEVICE_REQUIRED');
 const create={name:'ISOLATED create-and-start',purpose:'Actual room path, no native runtime',sourceRoomId:f.room,ownerId:f.agent,text:'Initial synthetic human project instruction'};
 drop='create';await assert.rejects(control('createRoomAndStart',create),/Original room command/);assert.equal(counts.create,1);assert.equal(counts.message,0);
 db.close();make();const created=await control('createRoomAndStart',create,'human-recover-create','recover-original');
 assert.equal(created.accepted,true);assert.equal(created.workCompletion,'not_inferred');assert.equal(counts.create,1);assert.equal(counts.message,1);
 drop='message';await assert.rejects(control('roomReply',{roomId:f.room,text:'PRIVATE_REPLY_CANARY',audience:'direct',recipientId:f.agent,sourceEventId:f.privateSource},'human-send-private','send-original'),/Original room command/);
 const pending=db.prepare("SELECT id FROM coordinator_room_intents WHERE phase='sending'").get();assert.ok(pending);assert.equal(counts.message,2);
 db.close();make();const receipt=await control('roomReceipt',{commandId:pending.id},'human-read-receipt','read-original');assert.equal(receipt.accepted,true);assert.equal(counts.message,2);
 const privateHistory=await control('roomHistory',{roomId:f.room});assert.ok(privateHistory.events.some(e=>e.id===receipt.eventId&&e.author===f.principal&&e.data.authorKind==='human'));
 await assert.rejects(control('roomReply',{roomId:f.room,text:'PUBLIC_EXPANSION_MUST_NOT_ENTER',audience:'room',sourceEventId:f.privateSource},'human-invalid-expansion','invalid-scope'));
 const peer=await fetch(f.url+'/hollywood/v3/rooms/'+f.room+'/history?view=conversation&limit=30',{headers:{Authorization:'Bearer '+f.peerToken}});assert.equal(peer.status,200);const peerPage=await peer.json();assert.doesNotMatch(JSON.stringify(peerPage),/PRIVATE_ROOM_INTENT_CANARY|PRIVATE_REPLY_CANARY|PUBLIC_EXPANSION_MUST_NOT_ENTER/);
 const same=await control('roomReply',{roomId:f.room,text:'PRIVATE_REPLY_CANARY',audience:'direct',recipientId:f.agent,sourceEventId:f.privateSource},'human-send-private','send-original');assert.equal(same.commandId,receipt.commandId);assert.equal(counts.message,3,'Third POST was definitively denied expansion, not an admitted duplicate');
 const focus=controller.session('qa-phone');assert.equal(focus.selected,'original_codex_focus');assert.equal(focus.selected_environment,'mac');
 assert.ok(!trace.some(x=>x.path.includes('/threads/')));
 const result={passed:true,realCanonicalHttpAndPg:true,realPocketGatewayAndHumanIngress:true,remotePairedSourcePath:true,capabilityReadOnly:true,
   lostCreateAckReopenedWithoutSecondCreate:true,lostMessageAckReopenedWithoutSecondSend:true,privateAudienceAndSourceEnforced:true,agentHumanImpersonationRejected:true,
   selectedCodexFocusUnchanged:true,postAttempts:counts,acceptedNewHumanRequests:2,agentOrModelRuntimeStarted:false,workCompletion:'not_inferred',trace,
   limits:'Synthetic human/agent records in isolated PG only; no native execution/model, production credential, phone/client install or physical HTTPS/TLS qualification.'};
 writeFileSync(resolve(root,'integration-result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify({...result,trace:undefined}));
}finally{db?.close();await new Promise(r=>server.close(r));local.close();}
