import test from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import {DatabaseSync} from 'node:sqlite';
import {HumanIngress,admissionTransaction,humanInputRoute} from '../server/human-ingress.mjs';
import {mountHollywood} from '../server/hollywood.mjs';

test('canonical mutation classification covers controls and alternate route spellings',()=>{
 const paths=['/api/hollywood/v3/rooms/room/messages','/api/hollywood/v3/attention/item/response',
  '/api/hollywood/v3/deliveries/item/guidance','/api/hollywood/v3/deliveries/item/cancel',
  '/api/hollywood/v3/agents/item/contexts/writer/seed-drafts/draft/marks',
  '/API/Hollywood/V3/rooms/room/messages/','/api/%68ollywood/v3/rooms/room/messages',
  '/api/hollywood//v3/rooms/room/messages','/api/hollywood/v3/future-human-operation'];
 for(const path of paths){
  for(const method of ['POST','PUT','PATCH','DELETE'])assert.equal(humanInputRoute(method,path),true,path);
  for(const method of ['GET','HEAD','OPTIONS'])assert.equal(humanInputRoute(method,path),false,path);
 }
 assert.equal(humanInputRoute('POST','/API/THREADS/task/reply/'),true);
 assert.equal(humanInputRoute('POST','/api/hollywood/v30/admin'),false);
 assert.equal(humanInputRoute('POST','/api/hollywood/v3/%invalid'),true);
});

test('canonical gateway rejects admin impersonation before forwarding; paired and original voice devices survive',async t=>{
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());
 const phone='11111111-1111-4111-8111-111111111111',room='22222222-2222-4222-8222-222222222222';
 db.exec('CREATE TABLE devices(id TEXT PRIMARY KEY,name TEXT);CREATE TABLE voice_turns(device TEXT,id TEXT,state TEXT);');
 db.prepare('INSERT INTO devices VALUES(?,?)').run(phone,'Paired fixture');
 db.prepare('INSERT INTO voice_turns VALUES(?,?,?)').run(phone,'original-voice-turn','thinking');
 const ingress=new HumanIngress(db),calls=[],devices=[];
 const client={config:{bindingId:'original-binding',authorityId:room},envelope:()=>({bindingId:'original-binding'}),verify:async()=>({}),
  call:async(path,options)=>{calls.push({path,options});return {accepted:true};}};
 const gateway={options:{},client:device=>{devices.push(device);return client;}};
 const app=express();app.use(express.json());
 app.use((req,res,next)=>{req.device={id:req.get('Authorization')==='Bearer paired'?phone:'owner'};next();});
 app.use((req,res,next)=>ingress.middleware(req,res,next));mountHollywood(app,{gateway});
 app.use((error,_req,res,_next)=>res.status(error.status||500).json({error:error.message}));
 const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));
 t.after(()=>new Promise(r=>server.close(r)));
 const path='/api/hollywood/v3/rooms/'+room+'/messages',body={operationId:'fixture-only',kind:'message',body:'Synthetic fixture',audience:'room'};
 const call=(token,payload=body,headers={},tail=path,method='POST')=>fetch('http://127.0.0.1:'+server.address().port+tail,{method,
  headers:{Authorization:'Bearer '+token,'Content-Type':'application/json','X-Hollywood-Binding':'original-binding',...headers},body:JSON.stringify(payload)});
 for(const tail of [path,path+'/',path.replace('/api/hollywood','/API/Hollywood'),path.replace('hollywood','%68ollywood'),
  '/api/hollywood/v3/attention/'+room+'/response','/api/hollywood/v3/deliveries/'+room+'/guidance',
  '/api/hollywood/v3/deliveries/'+room+'/cancel']){
  const r=await call('admin',{...body,principal:{kind:'paired-device',deviceId:phone}}, {},tail);
  assert.equal(r.status,403);assert.equal((await r.json()).code,'HUMAN_DEVICE_REQUIRED');
 }
 assert.equal(calls.length,0);assert.equal(devices.length,0,'admin cannot select a human binding');
 assert.equal((await call('paired')).status,200);assert.equal(calls.length,1);assert.equal(devices.at(-1),phone);
 const grant=ingress.delegate({deviceId:phone,voiceTurnId:'original-voice-turn'},'POST',path,body);
 assert.equal((await call('admin',body,grant.headers)).status,200);assert.equal(devices.at(-1),phone);
 assert.equal((await call('admin',body,grant.headers)).status,403,'delegation is consumed once');
 const changed=ingress.delegate({deviceId:phone,voiceTurnId:'original-voice-turn'},'POST',path,body);
 assert.equal((await call('admin',{...body,body:'Changed'},changed.headers)).status,403);
 const revoked=ingress.delegate({deviceId:phone,voiceTurnId:'original-voice-turn'},'POST',path,body);
 db.prepare('DELETE FROM devices WHERE id=?').run(phone);
 assert.equal((await call('admin',body,revoked.headers)).status,403);assert.equal(calls.length,2);
});
test('voice delegation is device/turn/request-bound, one-use, expiring and cannot be replaced by admin body claims',async t=>{
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());
 db.exec("CREATE TABLE devices(id TEXT PRIMARY KEY,name TEXT);CREATE TABLE voice_turns(device TEXT,id TEXT,state TEXT);INSERT INTO devices VALUES('phone','Paired phone');INSERT INTO voice_turns VALUES('phone','voice-turn','thinking');");
 let now=1000;const ingress=new HumanIngress(db,{now:()=>now});
 const app=express();app.use(express.json());app.use((req,res,next)=>{req.device={id:'owner'};next();});app.use((req,res,next)=>ingress.middleware(req,res,next));
 app.post('/api/threads/:id/reply',(req,res)=>{admissionTransaction(db,()=>ingress.record('reply',req.body.id,req.humanPrincipal));res.json({device:req.device.id,principal:req.humanPrincipal});});
 const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));t.after(()=>new Promise(r=>server.close(r)));
 const path='/api/threads/task-one/reply',body={id:'voice-request',text:'Original spoken request'};
 const call=async(payload,headers={})=>fetch('http://127.0.0.1:'+server.address().port+path,{method:'POST',headers:{'Content-Type':'application/json',...headers},body:JSON.stringify(payload)});
 assert.equal((await call({...body,principal:{kind:'voice',deviceId:'phone',voiceTurnId:'voice-turn'}})).status,403);
 assert.throws(()=>ingress.delegate({deviceId:'owner',voiceTurnId:'voice-turn'},'POST',path,body),/original paired device/);
 const actor={deviceId:'phone',voiceTurnId:'voice-turn'};
 const grant=ingress.delegate(actor,'POST',path,body);
 const accepted=await call(body,grant.headers);assert.equal(accepted.status,200);assert.deepEqual((await accepted.json()).principal,{version:1,kind:'voice',deviceId:'phone',voiceTurnId:'voice-turn'});
 assert.equal((await call(body,grant.headers)).status,403);
 assert.equal(db.prepare('SELECT count(*) n FROM human_admissions').get().n,1);
 const altered=ingress.delegate(actor,'POST',path,body);assert.equal((await call({...body,text:'Changed'},altered.headers)).status,403);
 const stale=ingress.delegate(actor,'POST',path,body);now+=35001;assert.equal((await call(body,stale.headers)).status,403);
 const revoked=ingress.delegate(actor,'POST',path,body);db.exec("UPDATE voice_turns SET state='completed'");assert.equal((await call(body,revoked.headers)).status,403);
 const saved=db.prepare('SELECT principal FROM human_admissions').get().principal;
 const reopened=new HumanIngress(db);assert.equal(db.prepare('SELECT principal FROM human_admissions').get().principal,saved);assert.equal(reopened.delegations.size,0);
});
