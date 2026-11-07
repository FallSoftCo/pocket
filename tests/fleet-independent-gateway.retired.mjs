/** Owned real gateway processes + trusted local TLS backplane, never live nodes. */
import test from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import https from 'node:https';
import http from 'node:http';
import {createServer} from 'node:net';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,rmSync,openSync,closeSync} from 'node:fs';
import {resolve} from 'node:path';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {generateKeyPairSync,randomUUID,randomBytes} from 'node:crypto';
import {openStore,hash} from '../server/store.mjs';
import {startOwnedFixture,stopOwnedFixture} from '../scripts/owned-fixture.mjs';
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const freePort=()=>new Promise(r=>{const s=createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>r(p));});});
test('actual independent gateway: local opaque auth/actions after home shutdown, lost ACK lookup, restart and TLS revocation catch-up',async()=>{
 const root=mkdtempSync(resolve(tmpdir(),'nextcomp-independent-')),children=[],proxies=[];let upstream,mutationCount=0,submissionCount=0;const commands=new Map();
 const fleetId=randomUUID(),accountId=randomUUID(),authority=randomUUID(),principal=randomUUID(),room=randomUUID(),device=randomUUID(),legacy=randomBytes(32).toString('hex'),bindingToken=randomBytes(32).toString('base64url');
 const pairPaths=[0,1].map(i=>resolve(root,'node'+i));pairPaths.forEach(p=>mkdirSync(p));
 const cert=resolve(root,'tls-cert.pem'),tlsKey=resolve(root,'tls-private.pem');
 execFileSync('openssl',['req','-x509','-newkey','ed25519','-keyout',tlsKey,'-out',cert,'-days','1','-nodes','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost,IP:127.0.0.1'],{stdio:'ignore'});
 const ports=await Promise.all([freePort(),freePort()]);
 const members=[];
 try{
  const app=express();app.use(express.json());app.get('/hollywood/v3/principals/:id',(_q,s)=>s.json({id:principal,authority,kind:'human',active:true}));
  app.get('/hollywood/v3/rooms',(_q,s)=>s.json({authorityId:authority,rooms:[{id:room}]}));
  app.get('/hollywood/v3/rooms/:id/history',(_q,s)=>s.json({authorityId:authority,events:[{id:'fixture-read',body:'synthetic read only'}]}));
  app.post('/hollywood/v3/rooms/:id/messages',(q,s)=>{
   submissionCount++;if(!commands.has(q.body.operationId)){mutationCount++;commands.set(q.body.operationId,{authorityId:authority,accepted:true,event:{id:randomUUID(),authority},operationId:q.body.operationId});s.socket.destroy();return;}
   s.json(commands.get(q.body.operationId));
  });
  app.get('/hollywood/v3/commands/:operation',(q,s)=>commands.has(q.params.operation)?s.json(commands.get(q.params.operation)):s.status(404).json({error:'Not recorded'}));
  upstream=app.listen(0,'127.0.0.1');await new Promise(r=>upstream.once('listening',r));
  for(let i=0;i<2;i++){
   const proxy=https.createServer({key:readFileSync(tlsKey),cert:readFileSync(cert)},(q,s)=>{const out=http.request({host:'127.0.0.1',port:ports[i],path:q.url,method:q.method,headers:q.headers},r=>{s.writeHead(r.statusCode,r.headers);r.pipe(s);});out.on('error',()=>{if(!s.headersSent)s.writeHead(503);s.end();});q.pipe(out);});
   proxy.listen(0,'127.0.0.1');await new Promise(r=>proxy.once('listening',r));proxies.push(proxy);
   const pair=generateKeyPairSync('ed25519'),id=randomUUID();writeFileSync(resolve(pairPaths[i],'node.pem'),pair.privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});members.push({id,name:'Owned fixture'+i,origin:'https://localhost:'+proxy.address().port,publicKey:pair.publicKey.export({type:'spki',format:'pem'})});
  }
  const admins=[];
  for(let i=0;i<2;i++){
   const {db,secrets}=openStore(resolve(pairPaths[i],'data'));admins.push(secrets.adminToken);
   if(i===0)db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run(device,'Original paired fixture',hash(legacy),123);db.close();
   const tokenFile=resolve(pairPaths[i],'human.key');writeFileSync(tokenFile,bindingToken,{mode:0o600});
   writeFileSync(resolve(pairPaths[i],'data','hollywood.json'),JSON.stringify({format:'hollywood-nextcomp-binding/v1',installationId:randomUUID(),native:true,default:{url:'http://127.0.0.1:'+upstream.address().port,tokenFile,authorityId:authority,principalId:principal,defaultRoomId:room}}),{mode:0o600});
   writeFileSync(resolve(pairPaths[i],'fleet.json'),JSON.stringify({version:1,fleetId,accountId,nodeId:members[i].id,privateKeyFile:resolve(pairPaths[i],'node.pem'),members}),{mode:0o600});
  }
  async function launch(i){const fd=openSync(resolve(pairPaths[i],'gateway.log'),'a',0o600);children[i]=startOwnedFixture(fileURLToPath(new URL('../server/index.mjs',import.meta.url)),{cwd:pairPaths[i],fd,env:{...process.env,PORT:String(ports[i]),POCKET_DATA:resolve(pairPaths[i],'data'),POCKET_EXECUTION_BACKEND:'hollywood',POCKET_FLEET_CONFIG:resolve(pairPaths[i],'fleet.json'),POCKET_LOCAL:'',POCKET_VOICE_API_KEY:'',POCKET_BACKEND_ARTIFACTS_DIR:'',POCKET_BACKEND_ARTIFACTS_KEY_FILE:'',NODE_EXTRA_CA_CERTS:cert}});closeSync(fd);
   for(let n=0;n<100;n++){if(children[i].exitCode!==null)throw Error('Owned fixture failed: '+readFileSync(resolve(pairPaths[i],'gateway.log'),'utf8'));try{if((await fetch('http://127.0.0.1:'+ports[i]+'/health')).ok)return;}catch{}await pause(40);}throw Error('Owned fixture readiness timeout');
  }
  let currentBinding;
  const call=(i,path,token,body)=>fetch('http://127.0.0.1:'+ports[i]+path,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json',...(path.startsWith('/api/hollywood/')?{'X-Hollywood-Binding':currentBinding}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})});
  await launch(0);await launch(1);
  assert.equal((await call(1,'/api/fleet/enroll',admins[1],{operationId:'no-admin-enroll-r01'})).status,401);
  const session=await (await call(0,'/api/fleet/session',legacy,{})).json(),op='owned-enrollment-r01';
  const enrolledResponse=await call(1,'/api/fleet/enroll',session.token,{operationId:op});assert.equal(enrolledResponse.status,200);const enrolled=await enrolledResponse.json();
  assert.deepEqual(await (await call(1,'/api/fleet/enrollments/'+op,session.token)).json(),enrolled);
  assert.deepEqual(await (await call(1,'/api/fleet/enroll',session.token,{operationId:op})).json(),enrolled);
  assert.equal(enrolled.ownerDeviceId,session.deviceId);assert.equal((await (await call(1,'/api/backends',enrolled.token)).json()).fleet.enrollmentPath,'/api/fleet/enroll');
  await stopOwnedFixture(children[0]);children[0]=null; // ACTUAL original gateway process is now stopped.
  const began=Date.now(),refreshResponse=await call(1,'/api/fleet/session',enrolled.token,{});assert.equal(refreshResponse.status,200);assert.ok(Date.now()-began<1500,'No online-home authentication dependency');
  const binding=(await (await call(1,'/api/backends',enrolled.token)).json()).backends[0].bindingId;
  currentBinding=binding;
  assert.equal((await (await call(1,'/api/hollywood/v3/rooms/'+room+'/history',enrolled.token)).json()).events[0].id,'fixture-read');
  const operation='owned-unknown-mutation-r01';
  const mutation=await fetch('http://127.0.0.1:'+ports[1]+'/api/hollywood/v3/rooms/'+room+'/messages',{method:'POST',headers:{Authorization:'Bearer '+enrolled.token,'Content-Type':'application/json','X-Hollywood-Binding':binding},body:JSON.stringify({operationId:operation,kind:'message',body:'synthetic owned fixture only',audience:'room'})});
  assert.equal(mutation.status,503);assert.equal(submissionCount,1);assert.equal(mutationCount,1);
  const saved=await (await call(1,'/api/hollywood/v3/commands/'+operation,enrolled.token)).json();assert.equal(saved.operationId,operation);
  await stopOwnedFixture(children[1]);await launch(1);
  assert.equal((await call(1,'/api/hollywood/v3/commands/'+operation,enrolled.token)).status,200);assert.equal(submissionCount,1);assert.equal(mutationCount,1);
  assert.deepEqual(await (await call(1,'/api/fleet/enrollments/'+op,enrolled.token)).json(),enrolled);
  const revokeOp='owned-revocation-r01',revocation=await call(1,'/api/fleet/revoke',enrolled.token,{operationId:revokeOp});assert.equal(revocation.status,200);
  assert.equal((await call(1,'/api/backends',enrolled.token)).status,401);assert.equal((await call(1,'/api/fleet/revocations/'+revokeOp,enrolled.token)).status,200);
  await launch(0);let caught=false;for(let n=0;n<100;n++){if((await call(0,'/api/backends',legacy)).status===401){caught=true;break;}await pause(100);}assert.ok(caught,'Automatic TLS catch-up must propagate durable denial');
  await stopOwnedFixture(children[0]);await launch(0);assert.equal((await call(0,'/api/backends',legacy)).status,401);
  assert.equal((await call(1,'/api/fleet/enroll',session.token,{operationId:op})).status,401);
  const check=openStore(resolve(pairPaths[1],'data'));assert.equal(check.db.prepare('SELECT count(*) n FROM fleet_enrollments').get().n,1);assert.equal(check.db.prepare('SELECT count(*) n FROM fleet_credentials').get().n,1);check.db.close();
  if(process.env.NEXTCOMP_FLEET_QA_RECEIPT)writeFileSync(process.env.NEXTCOMP_FLEET_QA_RECEIPT,JSON.stringify({schema:'nextcomp-independent-gateway-qa/v1',fixtureOnly:true,homeProcessStopped:true,localReadActionAndRefresh:true,lostEnrollmentAckRecovered:true,localCredentialSurvivedRestart:true,submissionCount,mutationCount,unknownMutationRecoveredByReadOnlyLookup:true,automaticTlsRevocationCatchup:true,revocationSurvivedRestart:true,tombstonePreventedReenrollment:true,at:new Date().toISOString()},null,2)+'\n',{flag:'wx',mode:0o600});
 }finally{for(const child of children)await stopOwnedFixture(child);for(const p of proxies)await new Promise(r=>p.close(r));if(upstream)await new Promise(r=>upstream.close(r));rmSync(root,{recursive:true,force:true});}
});
