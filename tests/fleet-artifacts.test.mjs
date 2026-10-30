import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,rmSync,chmodSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve} from 'node:path';
import {generateKeyPairSync,randomUUID} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';
import express from 'express';
import {FleetAccess,FleetProofs} from '../server/fleet.mjs';
import {BackendArtifacts,publishBackendArtifact,sha256,mountBackendArtifacts,issueArtifactCapability} from '../server/backend-artifacts.mjs';
import {fetchBackendArtifact} from '../scripts/backend-artifact.mjs';
function fixture(){
 const dir=mkdtempSync(resolve(tmpdir(),'nextcomp-fleet-')),fleetId=randomUUID(),accountId=randomUUID();
 const members=[0,1].map((_,i)=>{const pair=generateKeyPairSync('ed25519'),id=randomUUID();writeFileSync(resolve(dir,id),pair.privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});return {id,name:'fixture'+i,origin:'https://fixture'+i+'.invalid',publicKey:pair.publicKey.export({type:'spki',format:'pem'})};});
 const configs=members.map(m=>({version:1,fleetId,accountId,nodeId:m.id,privateKeyFile:resolve(dir,m.id),members}));
 const dbs=[0,1].map(i=>{const db=new DatabaseSync(resolve(dir,i+'.sqlite'));db.exec('CREATE TABLE devices(id TEXT PRIMARY KEY,name TEXT,token_hash TEXT UNIQUE,created_at INTEGER);');return db;});
 const a=new FleetAccess({db:dbs[0],config:configs[0]}),b=new FleetAccess({db:dbs[1],config:configs[1],fetcher:async(url,options)=>{
  const path=new URL(url).pathname,id=path.split('/').at(-1);await a.authorizeNode(options.headers.Authorization.slice(7),{scope:'device:status',method:'GET',path,deviceId:id});
  try{return new Response(JSON.stringify(a.status(id)));}catch{return new Response('{}',{status:401});}
 }});
 return {dir,a,b,configs,dbs,close(){dbs.forEach(d=>d.close());rmSync(dir,{recursive:true,force:true});}};
}
test('paired fleet login, stable aliases, restart, refresh, revocation, and strict authority types',async()=>{
 const f=fixture();try{
  const id=randomUUID();f.dbs[0].prepare('INSERT INTO devices VALUES(?,?,?,?)').run(id,'original phone','opaque-hash',123);
  await assert.rejects(f.a.session({id:'owner'}));
  const session=await f.a.session({id}),home=await f.a.authenticate(session.token),remote=await f.b.authenticate(session.token);
  assert.equal(home.device.id,id);assert.equal(remote.device.id,session.deviceId);
  const refreshed=await f.b.session(remote.device,remote.principal);assert.equal(refreshed.deviceId,session.deviceId);assert.notEqual(refreshed.token,session.token);
  assert.equal((await f.b.authenticate(refreshed.token)).device.id,remote.device.id);
  const restarted=new FleetAccess({db:f.dbs[0],config:f.configs[0]});assert.equal((await restarted.authenticate(refreshed.token)).device.id,id);
  const path='/backend-artifacts/v1/blobs/'+'a'.repeat(64),scope={method:'GET',path,scope:'artifact:read',sha256:'a'.repeat(64)};
  const proof=await f.b.nodeProof(f.configs[0].nodeId,scope);await f.a.authorizeNode(proof,scope);
  await assert.rejects(f.a.authenticate(proof));await assert.rejects(f.a.authorizeNode(session.token,scope));
  await assert.rejects(f.a.authorizeNode(proof,{...scope,method:'HEAD'}));await assert.rejects(f.a.authorizeNode(proof,{...scope,path:path+'/elsewhere'}));
  const other=new FleetProofs({config:{...f.configs[0],accountId:randomUUID()}});await assert.rejects(other.authorizeNode(proof,scope));
  const future=new FleetProofs({config:f.configs[0],now:()=>Date.now()+61000});await assert.rejects(future.authorizeNode(proof,scope));
  const removed=new FleetProofs({config:{...f.configs[0],members:[f.configs[0].members[0]]}});await assert.rejects(removed.authorizeNode(proof,scope));
  f.dbs[0].prepare('DELETE FROM devices WHERE id=?').run(id);
  await assert.rejects(f.b.authenticate(session.token));await assert.rejects(restarted.authenticate(refreshed.token));
  const down=new FleetAccess({db:f.dbs[1],config:f.configs[1],fetcher:async()=>{throw Error('offline');}});await assert.rejects(down.authenticate(session.token));
 }finally{f.close();}
});
test('real blob GET/HEAD with node proofs; interrupted read and lost receipt never activate',async()=>{
 const f=fixture();let server;try{
  const store=resolve(f.dir,'blobs'),downloads=resolve(f.dir,'downloads');mkdirSync(store);mkdirSync(downloads);
  const bytes=Buffer.from('immutable backend fixture\n'),file=resolve(f.dir,'release');writeFileSync(file,bytes);
  const manifest={schema:'nextcomp-backend-artifact/v1',sha256:sha256(bytes),size:bytes.length,version:'fixture',sourceCommit:'a'.repeat(40),releaseDigest:'b'.repeat(64)};
  publishBackendArtifact({dir:store,file,manifest});publishBackendArtifact({dir:store,file,manifest});
  writeFileSync(resolve(store,'.publish-abandoned'),'partial'); // Interrupted publication cannot poison the canonical address.
  const key=Buffer.alloc(32,7),keyFile=resolve(f.dir,'hmac');writeFileSync(keyFile,key.toString('hex'),{mode:0o600});
  const app=express(),artifacts=new BackendArtifacts({dir:store,keyFile});mountBackendArtifacts(app,{artifacts,nodeAuthorize:(t,e)=>f.a.authorizeNode(t,e)});
  server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));const base='http://127.0.0.1:'+server.address().port,path='/backend-artifacts/v1/blobs/'+manifest.sha256;
  const proof=await f.b.nodeProof(f.configs[0].nodeId,{scope:'artifact:read',sha256:manifest.sha256,method:'HEAD',path});
  const head=await fetch(base+path,{method:'HEAD',headers:{Authorization:'Bearer '+proof}});assert.equal(head.status,200);assert.equal((await head.arrayBuffer()).byteLength,0);
  assert.equal((await fetch(base+path,{method:'POST'})).status,405);assert.equal((await fetch(base+path+'?path=private')).status,404);
  const cap=issueArtifactCapability(manifest,key,{audience:'fixture'}).token;
  const request=t=>fetch(base+path,{headers:{Authorization:'Artifact '+t,'X-NextComp-Artifact-Audience':'fixture'}});
  assert.equal((await request(cap+'x')).status,403);assert.equal((await request(issueArtifactCapability(manifest,key,{audience:'fixture',now:Date.now()-901000}).token)).status,403);
  assert.equal((await fetch(base+path,{headers:{Authorization:'Bearer owner-admin-token'}})).status,401);
  assert.equal(sha256(Buffer.from(await (await request(cap)).arrayBuffer())),manifest.sha256);
  const fetcher=(url,options)=>{assert.equal(new URL(url).origin,f.configs[0].members[0].origin);return fetch(base+new URL(url).pathname,options);};
  const args={manifest,dir:downloads,operationId:'same-read',sourceNode:f.configs[0].nodeId,proofs:f.b,fetcher};
  await assert.rejects(fetchBackendArtifact({...args,fetcher:async()=>new Response(new ReadableStream({start(c){c.enqueue(bytes.subarray(0,3));c.error(Error('cut'));}}),{headers:{'X-Artifact-SHA256':manifest.sha256,'Content-Length':String(bytes.length)}})}));
  assert.equal(JSON.parse(readFileSync(resolve(downloads,'same-read.json'))).state,'fetching');
  assert.equal((await fetchBackendArtifact(args)).state,'verified');
  rmSync(resolve(downloads,'same-read.json')); // Lost verification receipt: immutable readback, no HTTP or activation replay.
  assert.equal((await fetchBackendArtifact({...args,fetcher:()=>{throw Error('must not fetch');}})).state,'verified');
  assert.deepEqual(readFileSync(resolve(downloads,manifest.sha256+'.tar.gz')),bytes);
  assert.ok(!readFileSync(resolve(downloads,'same-read.json'),'utf8').includes(cap));
  chmodSync(keyFile,0o644);assert.throws(()=>new BackendArtifacts({dir:store,keyFile}));
 }finally{if(server)await new Promise(r=>server.close(r));f.close();}
});
