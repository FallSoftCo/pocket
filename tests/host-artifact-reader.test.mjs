import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,symlinkSync,rmSync,chmodSync,existsSync,statSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync,spawn} from 'node:child_process';
import {randomUUID,generateKeyPairSync,createPrivateKey,sign} from 'node:crypto';
import {SignJWT} from 'jose';
import https from 'node:https';
import express from 'express';
import {provisionReader,checkReader,bootstrapFetch} from '../scripts/backend-bootstrap-fetch.mjs';
import {createReaderRequest,acceptReader,completeReader} from '../scripts/artifact-reader-enrollment.mjs';
import {FleetProofs} from '../server/fleet.mjs';
import {BackendArtifacts,mountBackendArtifacts,publishBackendArtifact,sha256} from '../server/backend-artifacts.mjs';

const script=fileURLToPath(new URL('../scripts/backend-bootstrap-fetch.mjs',import.meta.url));
function run(args,env={}){
 return new Promise((resolve,reject)=>{
  const child=spawn(process.execPath,args,{env:{...process.env,...env},stdio:['ignore','pipe','pipe']});
  let out='',err='';child.stdout.on('data',b=>out+=b);child.stderr.on('data',b=>err+=b);
  const timer=setTimeout(()=>child.kill('SIGTERM'),15000);
  child.once('error',reject);child.once('exit',code=>{clearTimeout(timer);resolve({code,out,err});});
 });
}

test('provision existing local identity and CLI HTTPS fetch through release symlink, no expiring grant',async()=>{
 const dir=mkdtempSync(resolve(tmpdir(),'reader-'));
 let server;
 try{
  const fleetId=randomUUID(),accountId=randomUUID();
  const members=[0,1].map(i=>{
   const keys=generateKeyPairSync('ed25519'),id=randomUUID(),file=resolve(dir,'key'+i);
   writeFileSync(file,keys.privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});
   return {id,name:'fixture',origin:'https://localhost',publicKey:keys.publicKey.export({type:'spki',format:'pem'}),privateKeyFile:file};
  });
  const cert=resolve(dir,'cert.pem'),key=resolve(dir,'tls.key');
  execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-keyout',key,'-out',cert,
    '-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],{stdio:'ignore',timeout:15000});
  const store=resolve(dir,'blobs'),downloads=resolve(dir,'downloads');mkdirSync(store);mkdirSync(downloads);
  const bytes=Buffer.from('private fixture immutable PROGRAM ONLY\n'),file=resolve(dir,'code');writeFileSync(file,bytes);
  const manifest={schema:'nextcomp-backend-artifact/v1',sha256:sha256(bytes),size:bytes.length,
    version:'reader-fixture',sourceCommit:'a'.repeat(40),releaseDigest:'b'.repeat(64)};
  publishBackendArtifact({dir:store,file,manifest});
  const app=express();let receiver;let requests=0;let lastProof;
  mountBackendArtifacts(app,{artifacts:new BackendArtifacts({dir:store}),nodeAuthorize:(token,scope)=>{
   requests++;lastProof=token;return receiver.authorizeNode(token,scope);
  }});
  server=https.createServer({cert:readFileSync(cert),key:readFileSync(key)},app);
  await new Promise(r=>server.listen(0,'127.0.0.1',r));members[0].origin='https://localhost:'+server.address().port;
  const configs=members.map(m=>({version:1,fleetId,accountId,nodeId:m.id,privateKeyFile:m.privateKeyFile,
    members:members.map(({privateKeyFile,...v})=>v)}));
  const receiverConfig=resolve(dir,'receiver-fleet.json');writeFileSync(receiverConfig,JSON.stringify(configs[0]),{mode:0o600});
  receiver=new FleetProofs({config:()=>JSON.parse(readFileSync(receiverConfig))});
  const configFile=resolve(dir,'fleet.json'),readerFile=resolve(dir,'reader.json'),manifestFile=resolve(dir,'manifest.json');
  writeFileSync(configFile,JSON.stringify(configs[1]),{mode:0o600});writeFileSync(manifestFile,JSON.stringify(manifest));
  const release=resolve(dir,'release');symlinkSync(dirname(dirname(script)),release,'dir');
  const cli=resolve(release,'scripts/backend-bootstrap-fetch.mjs');
  const args=['--provision-reader','--fleet',configFile,'--node',members[1].id,'--reader',readerFile];
  const provision=await run([cli,...args]);assert.equal(provision.code,0,provision.err);
  assert.equal(JSON.parse(provision.out).credentialsCopied,false);assert.equal(requests,0);
  const again=await run([cli,...args]);assert.equal(again.code,0,again.err);
  assert.equal(checkReader(readerFile).c.nodeId,members[1].id);
  const fetchArgs=[cli,'--reader',readerFile,'--manifest',manifestFile,'--source-node',members[0].id,
    '--store',downloads,'--operation','original-code-read'];
  const got=await run(fetchArgs,{NODE_EXTRA_CA_CERTS:cert});assert.equal(got.code,0,got.err);
  assert.equal(JSON.parse(got.out).state,'verified');assert.equal(requests,1);
  assert.deepEqual(readFileSync(resolve(downloads,manifest.sha256+'.tar.gz')),bytes);
  const receipt=readFileSync(resolve(downloads,'original-code-read.json'),'utf8');
  assert.ok(!receipt.includes('BEGIN PRIVATE KEY'));assert.ok(!receipt.includes('Bearer '));
  const replay=await run(fetchArgs,{NODE_EXTRA_CA_CERTS:cert});assert.equal(replay.code,0,replay.err);assert.equal(requests,1);
  const imported=await run(['--input-type=module','-e',`await import(${JSON.stringify('file://'+script)})`]);
  assert.equal(imported.code,0,imported.err);assert.equal(imported.out,'');
  // Artifact-only hosts retain original UUIDs but generate their own local key;
  // operator accepts ONLY read authority, never a Fleet human-token issuer.
  const local=resolve(dir,'reader-only'),acceptance=resolve(dir,'owner-accept');
  mkdirSync(local,{mode:0o700});mkdirSync(acceptance,{mode:0o700});
  const nodeId=randomUUID(),challenge=randomUUID();
  const request=createReaderRequest({dir:local,nodeId,challenge});
  const keyBefore=statSync(resolve(local,'node-private.pem')).ino;
  assert.deepEqual(createReaderRequest({dir:local,nodeId,challenge}),request);
  assert.equal(statSync(resolve(local,'node-private.pem')).ino,keyBefore);
  assert.throws(()=>createReaderRequest({dir:local,nodeId,challenge:randomUUID()}));
  const grantArgs={configFile:receiverConfig,requestFile:resolve(local,'request.public.json'),requestSha256:sha256(readFileSync(resolve(local,'request.public.json'))),nodeId,challenge,dir:acceptance};
  const accepted=acceptReader(grantArgs);assert.equal(accepted.humanIssuerAdded,false);
  const afterGrant=readFileSync(receiverConfig,'utf8');assert.equal(acceptReader(grantArgs).registered,true);
  assert.equal(readFileSync(receiverConfig,'utf8'),afterGrant);
  assert.deepEqual(JSON.parse(afterGrant).members,configs[0].members);
  const complete=completeReader({dir:local,acceptanceFile:resolve(acceptance,'acceptance.public.json')});
  const rolePolicy=resolve(local,'policy.json');
  provisionReader({configFile:complete.configFile,expectedNode:nodeId,readerFile:rolePolicy});
  const downloaded=await run([cli,'--reader',rolePolicy,'--manifest',manifestFile,'--source-node',members[0].id,
    '--store',downloads,'--operation','artifact-only-original-read'],{NODE_EXTRA_CA_CERTS:cert});
  // An existing verified blob is reconciled locally without any HTTP, so use a
  // fresh store to qualify the new role at the actual HTTPS boundary.
  assert.equal(downloaded.code,0,downloaded.err);
  const roleDownloads=resolve(dir,'reader-only-downloads');mkdirSync(roleDownloads);
  const roleFetch=await run([cli,'--reader',rolePolicy,'--manifest',manifestFile,'--source-node',members[0].id,
    '--store',roleDownloads,'--operation','artifact-only-proof'],{NODE_EXTRA_CA_CERTS:cert});
  assert.equal(roleFetch.code,0,roleFetch.err);assert.equal(requests,2);
  assert.deepEqual(readFileSync(resolve(roleDownloads,manifest.sha256+'.tar.gz')),bytes);
  const roleProof=lastProof;
  await assert.rejects(receiver.authorizeNode(roleProof,{scope:'device:status',method:'GET',path:'/fleet/v1/devices/'+randomUUID()}));
  await assert.rejects(receiver.authorizeNode(roleProof,{scope:'artifact:read',method:'HEAD',path:'/backend-artifacts/v1/blobs/'+manifest.sha256,sha256:manifest.sha256}));
  await assert.rejects(receiver.verify(roleProof,'nextcomp-device+jwt','nextcomp-fleet:'+fleetId,900));
  const forged=await new SignJWT({sub:randomUUID(),fleetId,accountId}).setProtectedHeader({alg:'EdDSA',typ:'nextcomp-device+jwt',kid:nodeId})
    .setIssuer(nodeId).setAudience('nextcomp-fleet:'+fleetId).setIssuedAt().setExpirationTime('60s').setJti(randomUUID())
    .sign(createPrivateKey(readFileSync(resolve(local,'node-private.pem'))));
  await assert.rejects(receiver.verify(forged,'nextcomp-device+jwt','nextcomp-fleet:'+fleetId,900));
  const revoked=JSON.parse(afterGrant);revoked.artifactReaders=[];writeFileSync(receiverConfig,JSON.stringify(revoked));
  const denied=await run(['--input-type=module','-e',
    `const r=await fetch(${JSON.stringify(members[0].origin+'/backend-artifacts/v1/blobs/'+manifest.sha256)},{headers:{Authorization:'Bearer '+process.env.FIXTURE_READER_PROOF}});process.stdout.write(String(r.status))`],
    {NODE_EXTRA_CA_CERTS:cert,FIXTURE_READER_PROOF:roleProof});
  assert.equal(denied.code,0,denied.err);assert.equal(denied.out,'401');
  await assert.rejects(receiver.authorizeNode(roleProof,{scope:'artifact:read',method:'GET',path:'/backend-artifacts/v1/blobs/'+manifest.sha256,sha256:manifest.sha256}));
  assert.throws(()=>provisionReader({configFile,expectedNode:randomUUID(),readerFile}));
  writeFileSync(configFile,JSON.stringify({...configs[1],accountId:randomUUID()}));assert.throws(()=>checkReader(readerFile));
  writeFileSync(configFile,JSON.stringify(configs[1]));chmodSync(configFile,0o644);assert.throws(()=>checkReader(readerFile));
  assert.ok(existsSync(readerFile));
 }finally{if(server)await new Promise(r=>server.close(r));rmSync(dir,{recursive:true,force:true});}
});

test('one kernel config lock serializes simultaneous reader admissions; duplicate keys never write',async()=>{
 const dir=mkdtempSync(resolve(tmpdir(),'reader-admission-'));
 let first;
 try{
  const key=generateKeyPairSync('ed25519'),gateway=randomUUID();
  const configFile=resolve(dir,'fleet.json'),lock=configFile+'.artifact-reader.lock';
  const original={version:1,fleetId:randomUUID(),accountId:randomUUID(),nodeId:gateway,privateKeyFile:resolve(dir,'unused-private-reference'),
    members:[{id:gateway,name:'fixture gateway',origin:'https://fixture.invalid',publicKey:key.publicKey.export({type:'spki',format:'pem'})}]};
  writeFileSync(configFile,JSON.stringify(original),{mode:0o600});writeFileSync(lock,'',{mode:0o600});
  const setup=label=>{
   const state=resolve(dir,label),op=resolve(dir,label+'-accept');mkdirSync(state,{mode:0o700});mkdirSync(op,{mode:0o700});
   const nodeId=randomUUID(),challenge=randomUUID(),request=createReaderRequest({dir:state,nodeId,challenge});
   return {state,dir:op,nodeId,challenge,request,configFile,requestFile:resolve(state,'request.public.json'),requestSha256:sha256(readFileSync(resolve(state,'request.public.json')))};
  };
  const a=setup('a'),b=setup('b'),enrollment=fileURLToPath(new URL('../scripts/artifact-reader-enrollment.mjs',import.meta.url));
  const preload=resolve(dir,'rename-barrier.mjs');
  writeFileSync(preload,`import fs from 'node:fs';import {syncBuiltinESMExports} from 'node:module';
const replace=fs.renameSync;fs.renameSync=(from,to)=>{if(to===process.env.FIXTURE_CONFIG){process.stdout.write('RENAME_READY\\n');const b=Buffer.alloc(1);if(fs.readSync(0,b,0,1,null)!==1||b.toString()!=='R')throw Error('Barrier not released');}return replace(from,to)};syncBuiltinESMExports();`);
  const args=['--action','accept-locked','--fleet',configFile,'--request',a.requestFile,'--request-sha256',a.requestSha256,'--node',a.nodeId,'--challenge',a.challenge,'--state',a.dir];
  first=spawn('/usr/bin/flock',['--exclusive','--nonblock','--no-fork',lock,process.execPath,'--import',preload,enrollment,...args],
    {env:{...process.env,FIXTURE_CONFIG:configFile},stdio:['pipe','pipe','pipe']});
  const done=new Promise(r=>first.once('close',code=>r(code)));let err='';first.stderr.on('data',v=>err+=v);
  await new Promise((resolve,reject)=>{
   const timeout=setTimeout(()=>reject(Error('Admission barrier not reached')),5000);
   first.stdout.once('data',v=>{clearTimeout(timeout);v.toString().includes('RENAME_READY')?resolve():reject(Error('Unexpected fixture result'));});
   first.once('exit',code=>{if(code){clearTimeout(timeout);reject(Error('First admission exited: '+err));}});
  });
  assert.throws(()=>acceptReader(b));assert.ok(!existsSync(resolve(b.dir,'accept-intent.private.json')));
  assert.deepEqual(JSON.parse(readFileSync(configFile)),original);
  first.stdin.end('R');assert.equal(await done,0,err);
  // The second attempt definitively failed at the kernel lock before any
  // acceptance intent/effect; reuse that SAME original request/operation.
  assert.equal(acceptReader(b).registered,true);
  const config=JSON.parse(readFileSync(configFile));assert.equal(config.artifactReaders.length,2);assert.deepEqual(config.members,original.members);
  const duplicate=setup('duplicate');const {signature,...body}=duplicate.request;
  body.publicKey=a.request.publicKey;
  const canonical=v=>JSON.stringify(Object.fromEntries(Object.keys(v).sort().map(k=>[k,v[k]])));
  const copied={...body,signature:sign(null,Buffer.from(canonical(body)),createPrivateKey(readFileSync(resolve(a.state,'node-private.pem')))).toString('base64url')};
  writeFileSync(duplicate.requestFile,JSON.stringify(copied));
  duplicate.requestSha256=sha256(readFileSync(duplicate.requestFile));
  const before=readFileSync(configFile);assert.throws(()=>acceptReader(duplicate));assert.deepEqual(readFileSync(configFile),before);
 }finally{
  if(first&&first.exitCode===null){first.kill('SIGKILL');await new Promise(r=>first.once('close',r));}
  rmSync(dir,{recursive:true,force:true});
 }
});
