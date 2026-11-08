import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {EventEmitter} from 'node:events';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {VoiceController} from '../server/voice-controller.mjs';
import {CoordinatorSources} from '../server/coordinator-sources.mjs';
import {CoordinatorRoomCommands} from '../server/coordinator-rooms.mjs';
import {coordinatorRoomCatalogue} from '../server/coordinator-room-catalogue.mjs';
import {prepareNoAdmission,applyNoAdmission,digest} from '../server/coordinator-room-reconciliation.mjs';
import {definitiveRoomRefusal} from '../server/coordinator-room-errors.mjs';
const current='c8a5f0a1-95ea-4c3e-b408-b5c445d8b133',legacy='e488f19b-0f60-46ca-bd48-4fe544f06757',human='0d30550b-3351-4e5d-a539-d3b3107d03de';
const oldRoom='9371e2b5-8787-4495-a6af-e03a49bbb245',oldOwner='3deee822-bd2e-5dd1-88cf-81cddeecce81',source='bfbd00a4-64c2-40f8-a7da-49c99a163645',owner='5a531c73-262b-4a3d-8e46-ad98ad64b0ac';
const operation='coordinator-room-5ab035a76a819b86cea47fbee457277ea71f6f9df4384ffe',requested='5ab035a7-6a81-5b86-8ea4-7fbee457277e';
const binding='a'.repeat(64),env='remote_7e41f7f97bd341e88671f86e5dc1fddb';
const gateway={protocol:'nextcomp-project-rooms/v1',createRoom:true};
const canonical=(authority,ids)=>({protocol:'hollywood-project-rooms/v1',authorityId:authority,principalId:human,sourceRoomIds:ids,createRoom:{allowed:true,policy:'source-room-request-owner'}});

test('typed refusal differs from generic4xx and post-response binding mismatch',()=>{
 assert.equal(definitiveRoomRefusal({status:404,code:'HOLLYWOOD_REQUEST_REJECTED'}),true);
 assert.equal(definitiveRoomRefusal({status:400,code:'HOLLYWOOD_REQUEST_REJECTED'}),true);
 for(const e of [{status:409,code:'HOLLYWOOD_BINDING_MISMATCH'},{status:404},{status:400},{status:409,code:'REMOTE_DELIVERY_UNKNOWN'}])assert.equal(definitiveRoomRefusal(e),false);
});

test('real legacy8bar title cannot select creator: historical pairing stays readable, current c8 creator only',async t=>{
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());const writes=[];
 const controller=new VoiceController({db,codex:new EventEmitter(),cwd:'/qa',host:'qa',api:async()=>{throw Error('No local room source');}});
 db.prepare("INSERT INTO voice_sessions(device,selected,focus,selected_environment) VALUES('phone','legacy_stock_session','selected',?)").run(env);
 db.prepare("INSERT INTO voice_turns(device,id,hash,state,transcript,created_at) VALUES('phone','new-room-human','h','thinking',?,1)").run("right so I'd like a room for 8bar development");
 controller.sources={list:()=>[{environmentId:env,name:'Historical OZZZ18885'},{environmentId:'cpu',name:'Current CPU19426'}],call:async(_d,e,path,body)=>{
  if(e==='current')throw Error('Not configured');const old=e===env,a=old?legacy:current,id=old?oldRoom:source,o=old?oldOwner:owner;
  if(path.endsWith('/identity'))return {bindingId:old?'b'.repeat(64):binding,authorityId:a,principalId:human,...(old?{}:{roomControl:gateway})};
  if(body){assert.equal(e,'cpu');writes.push({e,path,body});return {room:{...body,owner:body.ownerId}};}
  if(path.endsWith('/rooms'))return {authorityId:a,rooms:[{id,name:old?'8bar Development':'NextComp federation development',owner:o}],...(old?{}:{roomControl:canonical(a,[source])})};
  return {room:{id},actors:[{id:o,kind:'agent',active:true}]};
 }};
 const active={device:'phone',id:'new-room-human'};
 const cat=await controller.control(active,{operation:'rooms',arguments:{}});
 assert.equal(cat.creation.authorityId,current);assert.equal(cat.rooms.find(r=>r.id===oldRoom).control.createRoom,false);assert.equal(cat.sources.find(s=>s.environmentId===env).available,true);
 const refused=await controller.tool(active,{tool:'pocket_control',callId:'bad-old-source',arguments:{operation:'createRoom',arguments:{environmentId:env,sourceRoomId:oldRoom,ownerId:oldOwner,name:'8bar Development',purpose:'New project conversation'}}});
 assert.equal(refused.success,false);assert.equal(JSON.parse(refused.contentItems[0].text).admission,'no_admission');assert.equal(writes.length,0);
 const good=await controller.tool(active,{tool:'pocket_control',callId:'correct-current-source',arguments:{operation:'createRoom',arguments:{sourceRoomId:source,ownerId:owner,name:'8bar Development',purpose:'New project conversation'}}});
 assert.equal(good.success,true);assert.equal(writes.length,1);assert.equal(writes[0].e,'cpu');assert.equal(writes[0].body.sourceRoomId,source);assert.equal(controller.session('phone').selected_environment,env);
 controller.roomCommands.scope('phone',env,'b'.repeat(64)).start({operationId:operation,id:requested,name:'Earlier uncertain room',purpose:'Retained original',ownerId:oldOwner},'old-request');
 const held=await controller.tool(active,{tool:'pocket_control',callId:'cannot-bypass-old-unknown',arguments:{operation:'createRoom',arguments:{sourceRoomId:source,ownerId:owner,name:'Another new project',purpose:'Explicit but held behind original'}}});
 assert.equal(held.success,false);assert.equal(JSON.parse(held.contentItems[0].text).code,'ROOM_ORIGINAL_ADMISSION_UNRESOLVED');assert.equal(writes.length,1);
});

test('no advertised writer and multiple unrelated supported authorities are not guessed',async()=>{
 const make=async controls=>coordinatorRoomCatalogue({targets:[{environmentId:'old'},{environmentId:'one'},{environmentId:'two'}],call:async(e,path)=>{
  const authority=e==='two'?legacy:current;
  return path.endsWith('/identity')?{bindingId:binding,authorityId:authority,principalId:human,...(controls&&e!=='old'?{roomControl:gateway}:{})}:{authorityId:authority,rooms:[],...(controls&&e!=='old'?{roomControl:canonical(authority,[source])}:{})};
 }});
 assert.equal((await make(false)).creation.reason,'NO_SUPPORTED_CREATOR');assert.equal((await make(true)).creation.reason,'AMBIGUOUS_CREATION_AUTHORITY');
});

test('federated historical room keeps its real authority even through the current gateway',async()=>{
 const r=await coordinatorRoomCatalogue({targets:[{environmentId:'current'}],call:async(_e,path)=>path.endsWith('/identity')?{bindingId:binding,authorityId:current,principalId:human,roomControl:gateway}:{authorityId:current,roomControl:canonical(current,[source]),rooms:[{id:source,name:'Current source',authority:current},{id:oldRoom,name:'8bar Development',authority:legacy}]}});
 const old=r.rooms.find(x=>x.id===oldRoom);assert.equal(old.authorityId,legacy);assert.equal(old.control.createRoom,false);assert.equal(r.creation.authorityId,current);
});

test('exact unsupported old gateway404/code/error is durable no-admission, not unknown, no second POST',async t=>{
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());let posts=0;
 const server=createServer((req,res)=>{
  if(req.url==='/api/status')return res.end(JSON.stringify({deviceId:'original-human'}));
  posts++;res.writeHead(404,{'Content-Type':'application/json'});res.end(JSON.stringify({code:'HOLLYWOOD_REQUEST_REJECTED',error:'Unknown Hollywood human operation.'}));
 });server.listen(0,'127.0.0.1');await once(server,'listening');t.after(()=>new Promise(r=>server.close(r)));
 const sources=new CoordinatorSources({db,key:'qa',authorize:()=>true,fetchImpl:(url,o)=>fetch('http://127.0.0.1:'+server.address().port+new URL(url).pathname,o)});
 await sources.register('phone',[{environmentId:env,name:'Old gateway',base:'https://legacy.example.ts.net',token:'original-qa-device-token'}]);
 const body={operationId:operation,id:requested,name:'8bar Development',purpose:'New room',ownerId:oldOwner},actor={deviceId:'phone',voiceTurnId:'5b7760ea-7d8a-47ca-b592-8250c45b5120',voiceCallId:'original-create',hollywoodBinding:binding};
 for(let i=0;i<2;i++)await assert.rejects(sources.call('phone',env,'/api/hollywood/v3/rooms',body,actor),e=>e.status===404&&e.code==='HOLLYWOOD_REQUEST_REJECTED'&&e.admission==='no_admission');
 const row=db.prepare('SELECT state,result FROM coordinator_remote_commands').get();assert.equal(row.state,'rejected');assert.equal(posts,1);assert.deepEqual(JSON.parse(row.result),{status:404,code:'HOLLYWOOD_REQUEST_REJECTED',error:'Unknown Hollywood human operation.',admission:'no_admission'});
});

test('owner-reviewed exact rejection overlay settles old unknown without rewriting original intent/conversation rows',async t=>{
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());const controller=new VoiceController({db,codex:new EventEmitter(),api:()=>{},cwd:'/qa',host:'qa'});
 new CoordinatorSources({db,key:'qa',authorize:()=>false});
 const scope={device:'phone',source:env,binding,operation,remoteId:'47db084752615d1abba58d972c91ec735144d2fd74f6e791336e34ed4cd60f61'};
 const body={operationId:operation,id:requested,name:'8bar Development',purpose:'Synthetic original scope',ownerId:oldOwner};
 const metadata=controller.roomCommands.scope('phone',env,binding);metadata.start(body,'original-request-hash');
 db.prepare("INSERT INTO coordinator_remote_commands(id,device_id,source_id,voice_turn,path,body_hash,state,result,at,request_id) VALUES(?,?,?,?,?,?,'unknown',NULL,1,?)").run(scope.remoteId,'phone',env,'5b7760ea-7d8a-47ca-b592-8250c45b5120','/api/hollywood/v3/rooms',digest(body),requested);
 db.prepare("INSERT INTO voice_turns(device,id,hash,state,transcript,response,created_at) VALUES('phone','old-human','h','completed','Original user request','Original answered conversation',1)").run();
 const snapshot=()=>JSON.stringify(['coordinator_room_commands','coordinator_remote_commands','voice_turns','voice_calls'].map(table=>db.prepare('SELECT * FROM '+table).all()));const before=snapshot();
 const proof={operationId:operation,requestId:requested,state:'definitively-rejected-before-canonical-admission',gatewayModuleSha256:'d158108233eb2677e22238d1e49a985945bd128cfa69540eacb0154a9e3cd308',journalTimestamp:'1793719987756450',journalInvocationId:'a5fb93c87db6411abddca019e730cac2',journalMessage:'POST /api/hollywood/v3/rooms HOLLYWOOD_REQUEST_REJECTED Unknown Hollywood human operation.'};
 assert.throws(()=>prepareNoAdmission(db,scope,{...proof,state:'readback403'},'f'.repeat(64)),/Positive exact/);
 const plan=prepareNoAdmission(db,scope,proof,'f'.repeat(64));assert.throws(()=>applyNoAdmission(db,{...plan,remoteDigest:'changed'},proof,'f'.repeat(64)),/CAS changed/);
 assert.equal(applyNoAdmission(db,plan,proof,'f'.repeat(64)).applied,true);assert.equal(applyNoAdmission(db,plan,proof,'f'.repeat(64)).applied,false);assert.equal(snapshot(),before);assert.equal(controller.roomCommands.unresolved('phone').length,0);
 const receipt=await metadata.receipt(operation,()=>{throw Error('No remote action needed for positive original rejection');});assert.equal(receipt.admission,'no_admission');assert.equal(receipt.originalRowsPreserved,true);
 db.prepare("UPDATE coordinator_remote_commands SET body_hash='different' WHERE id=?").run(scope.remoteId);assert.equal(controller.roomCommands.unresolved('phone').length,1,'Changed original evidence invalidates the overlay');
});

test('inaccessible original receipt is structured unknown, never absence or a retry request',async t=>{
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());const commands=new CoordinatorRoomCommands(db),scoped=commands.scope('phone',env,binding);
 scoped.start({operationId:operation,id:requested,name:'8bar Development',purpose:'Original',ownerId:oldOwner},'hash');
 const r=await scoped.receipt(operation,()=>{throw Object.assign(Error('Access denied'),{status:403});});
 assert.equal(r.admission,'inaccessible_unknown');assert.equal(r.noNewAdmission,true);assert.equal(db.prepare('SELECT state FROM coordinator_room_commands').get().state,'submitting');
});

test('actual owner repair CLI requires pinned proof/plan, appends once, original rows stay exact',async()=>{
 const {mkdtempSync,writeFileSync,readFileSync,chmodSync}=await import('node:fs'),{tmpdir}=await import('node:os'),{join}=await import('node:path'),{spawnSync}=await import('node:child_process'),{fileURLToPath}=await import('node:url'),{createHash}=await import('node:crypto');
 const dir=mkdtempSync(join(tmpdir(),'nextcomp-room-proof-')),database=join(dir,'original.sqlite'),proofFile=join(dir,'proof.json'),planFile=join(dir,'plan.json');
 const db=new DatabaseSync(database),commands=new CoordinatorRoomCommands(db);new CoordinatorSources({db,key:'synthetic-key',authorize:()=>false});
 const body={operationId:operation,id:requested,name:'8bar Development',purpose:'Synthetic exact rejection',ownerId:oldOwner};commands.scope('phone',env,binding).start(body,'original-hash');
 const remote='47db084752615d1abba58d972c91ec735144d2fd74f6e791336e34ed4cd60f61';
 db.prepare("INSERT INTO coordinator_remote_commands(id,device_id,source_id,voice_turn,path,body_hash,state,result,at,request_id) VALUES(?,?,?,?,?,?,'unknown',NULL,1,?)").run(remote,'phone',env,'original-human','/api/hollywood/v3/rooms',digest(body),requested);
 const snapshot=d=>JSON.stringify(['coordinator_room_commands','coordinator_remote_commands'].map(table=>d.prepare('SELECT * FROM '+table).all()));const before=snapshot(db);db.close();chmodSync(database,0o600);
 const proof={operationId:operation,requestId:requested,state:'definitively-rejected-before-canonical-admission',gatewayModuleSha256:'d158108233eb2677e22238d1e49a985945bd128cfa69540eacb0154a9e3cd308',journalTimestamp:'1793719987756450',journalInvocationId:'a5fb93c87db6411abddca019e730cac2',journalMessage:'POST /api/hollywood/v3/rooms HOLLYWOOD_REQUEST_REJECTED Unknown Hollywood human operation.'};
 writeFileSync(proofFile,JSON.stringify(proof),{mode:0o600});const hash=p=>createHash('sha256').update(readFileSync(p)).digest('hex');
 const script=fileURLToPath(new URL('../scripts/reconcile-coordinator-room-rejection.mjs',import.meta.url));
 const common=[`--database=${database}`,`--proof=${proofFile}`,`--proof-sha256=${hash(proofFile)}`];
 const run=args=>spawnSync(process.execPath,[script,...common,...args],{encoding:'utf8'});
 const prepare=run(['--action=prepare','--device=phone',`--source=${env}`,`--binding=${binding}`,`--operation=${operation}`,`--remote-command=${remote}`,`--output=${planFile}`]);assert.equal(prepare.status,0,prepare.stderr);
 const wrong=run(['--action=apply',`--plan=${planFile}`,'--plan-sha256='+'0'.repeat(64)]);assert.notEqual(wrong.status,0);
 const apply=run(['--action=apply',`--plan=${planFile}`,`--plan-sha256=${hash(planFile)}`]);assert.equal(apply.status,0,apply.stderr);assert.equal(JSON.parse(apply.stdout).admission,'no_admission');
 const repeat=run(['--action=apply',`--plan=${planFile}`,`--plan-sha256=${hash(planFile)}`]);assert.equal(repeat.status,0,repeat.stderr);assert.equal(JSON.parse(repeat.stdout).applied,false);
 const after=new DatabaseSync(database);try{assert.equal(snapshot(after),before);assert.equal(after.prepare('SELECT count(*) AS n FROM coordinator_room_reconciliations').get().n,1);}finally{after.close();}
});
