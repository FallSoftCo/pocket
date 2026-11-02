import assert from 'node:assert/strict';
import fs from 'node:fs';
import express from 'express';
import {DatabaseSync} from 'node:sqlite';
import {coordinatorRooms} from '../server/coordinator-rooms.mjs';
import {HollywoodGateway,loadHollywood,mountHollywood} from '../server/hollywood.mjs';
import {HumanIngress} from '../server/human-ingress.mjs';
const [qaData,evidence]=process.argv.slice(2);
if(!qaData||!evidence)throw Error('Explicit isolated QA binding and new evidence directory required');
process.umask(0o077);fs.mkdirSync(evidence,{recursive:false,mode:0o700});
const save=(name,value)=>fs.writeFileSync(evidence+'/'+name+'.json',JSON.stringify(value,null,2)+'\n',{flag:'wx',mode:0o600});
const gateway=new HollywoodGateway(loadHollywood(qaData)),identity=await gateway.client('qa-device').verify();
assert.equal(identity.authorityId,'6b8d1e3c-12c0-4c6d-8943-a7fdb32deece');
const source=await gateway.client('qa-device').call('rooms/'+identity.defaultRoomId);
const owner=source.actors.find(a=>a.kind==='agent');assert.ok(owner);
const db=new DatabaseSync(':memory:');db.exec("CREATE TABLE devices(id TEXT PRIMARY KEY,name TEXT);CREATE TABLE voice_turns(device TEXT,id TEXT,state TEXT);INSERT INTO devices VALUES('qa-device','Owned coordinator QA');INSERT INTO voice_turns VALUES('qa-device','room-create-real-qa','thinking');");
const ingress=new HumanIngress(db),app=express();app.use(express.json());
app.use((req,res,next)=>{req.device={id:'owner'};next();});app.use((req,res,next)=>ingress.middleware(req,res,next));
mountHollywood(app,{dir:qaData,gateway});app.use((error,req,res,next)=>res.status(error.status||500).json({error:error.message,code:error.code}));
const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));
const origin='http://127.0.0.1:'+server.address().port;
const actor={deviceId:'qa-device',voiceTurnId:'room-create-real-qa',hollywoodBinding:identity.bindingId};
const actions=[],writes=[];
async function api(path,body){
 const method=body===undefined?'GET':'POST',grant=ingress.delegate(actor,method,path,body);
 try{
  if(body)writes.push({path,body});
  const r=await fetch(origin+path,{method,headers:{'Content-Type':'application/json','X-Hollywood-Binding':identity.bindingId,...grant.headers},...(body===undefined?{}:{body:JSON.stringify(body)})});
  const value=await r.json();if(!r.ok)throw Object.assign(Error(value.error),{status:r.status});return value;
 }finally{grant.release();}
}
try{
 save('intent',{authorityId:identity.authorityId,sourceRoomId:identity.defaultRoomId,ownerId:owner.id,modelsAuthorized:0,activePhone:false});
 const params={operation:'createRoom',args:{name:'Synthetic coordinator project 20261102',purpose:'Isolated room creation transport QA; no task, provider or runtime enrolled.',ownerId:owner.id,sourceRoomId:identity.defaultRoomId},active:{device:'qa-device',id:'room-create-real-qa'},callId:'canonical-room-creation-r01',get:path=>api(path),post:api,action:value=>actions.push(value),prior:actions};
 const result=await coordinatorRooms(params);save('accepted',result);assert.equal(result.created,true);
 const opened=await api('/api/hollywood/v3/rooms/'+result.room.id);
 assert.equal(opened.room.id,result.room.id);assert.equal(opened.permissions.can_post,true);assert.equal(opened.permissions.can_request,true);
 const original=actions.find(a=>a.state==='submitting');
 const reconciled=await coordinatorRooms({...params,callId:'read-original-only',prior:[original]});
 assert.equal(reconciled.reconciledOriginal,true);assert.equal(writes.length,1);
 const denied=await fetch(origin+'/api/hollywood/v3/rooms',{method:'POST',headers:{'Content-Type':'application/json','X-Hollywood-Binding':identity.bindingId},body:JSON.stringify(writes[0].body)});assert.equal(denied.status,403);
 save('result',{passed:true,realHollywood:true,authenticatedHumanReadAndCreate:true,creatorCanOpenAndPost:true,originalRoomId:result.room.id,roomPosts:0,roomCreatePosts:writes.length,readReconciliationNoReplay:true,adminWithoutHumanRejected:true,agentsStarted:0,modelsStarted:0,phoneVerified:false});
 console.log('Real isolated Hollywood coordinator creation and read-back passed; one creation, zero task/model turns');
}finally{await new Promise(resolve=>server.close(resolve));db.close();}
