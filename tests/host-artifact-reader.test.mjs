import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync,symlinkSync,rmSync,chmodSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync,spawn} from 'node:child_process';
import {randomUUID,generateKeyPairSync} from 'node:crypto';
import https from 'node:https';
import express from 'express';
import {provisionReader,checkReader,bootstrapFetch} from '../scripts/backend-bootstrap-fetch.mjs';
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
  const app=express();let receiver;let requests=0;
  mountBackendArtifacts(app,{artifacts:new BackendArtifacts({dir:store}),nodeAuthorize:(token,scope)=>{
   requests++;return receiver.authorizeNode(token,scope);
  }});
  server=https.createServer({cert:readFileSync(cert),key:readFileSync(key)},app);
  await new Promise(r=>server.listen(0,'127.0.0.1',r));members[0].origin='https://localhost:'+server.address().port;
  const configs=members.map(m=>({version:1,fleetId,accountId,nodeId:m.id,privateKeyFile:m.privateKeyFile,
    members:members.map(({privateKeyFile,...v})=>v)}));
  receiver=new FleetProofs({config:configs[0]});
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
  assert.throws(()=>provisionReader({configFile,expectedNode:randomUUID(),readerFile}));
  writeFileSync(configFile,JSON.stringify({...configs[1],accountId:randomUUID()}));assert.throws(()=>checkReader(readerFile));
  writeFileSync(configFile,JSON.stringify(configs[1]));chmodSync(configFile,0o644);assert.throws(()=>checkReader(readerFile));
  assert.ok(existsSync(readerFile));
 }finally{if(server)await new Promise(r=>server.close(r));rmSync(dir,{recursive:true,force:true});}
});
