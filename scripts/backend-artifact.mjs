#!/usr/bin/env node
/** Local service helper. Never print a credential or accept a shell command. */
import {parseArgs} from 'node:util';
import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
import {constants,openSync,closeSync,readFileSync,writeSync,fsyncSync,renameSync,unlinkSync,existsSync,linkSync,lstatSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {artifactManifest,canonicalJson,sha256,verifyArtifactFd,publishBackendArtifact,artifactKey,issueArtifactCapability} from '../server/backend-artifacts.mjs';
import {FleetProofs,loadFleet} from '../server/fleet.mjs';
export function durableJson(path,value){
 const temp=path+'.'+randomUUID()+'.tmp',fd=openSync(temp,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);
 try{const bytes=Buffer.from(canonicalJson(value)+'\n');let at=0;while(at<bytes.length)at+=writeSync(fd,bytes,at,bytes.length-at);fsyncSync(fd);}finally{closeSync(fd);}
 renameSync(temp,path);const d=openSync(resolve(path,'..'),constants.O_RDONLY|constants.O_DIRECTORY);try{fsyncSync(d);}finally{closeSync(d);}
}
function verified(path,manifest){let fd;try{fd=openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW);verifyArtifactFd(fd,manifest);return true;}catch{return false;}finally{if(fd!==undefined)closeSync(fd);}}
export async function fetchBackendArtifact({manifest,dir,operationId,sourceNode,proofs,fetcher=fetch}){
 artifactManifest(manifest);if(!/^[A-Za-z0-9_-]{1,100}$/.test(operationId)||!lstatSync(dir).isDirectory()||lstatSync(dir).isSymbolicLink())throw Error('Invalid artifact download workspace.');
 const c=proofs.config(),member=c.members.find(m=>m.id===sourceNode);if(!member)throw Error('Artifact source is not enrolled.');
 const receipt=resolve(dir,operationId+'.json'),lock=receipt+'.lock',partial=resolve(dir,operationId+'.partial'),target=resolve(dir,manifest.sha256+'.tar.gz');
 const fingerprint=sha256(canonicalJson({manifest,origin:member.origin,sourceNode,nodeId:c.nodeId}));
 const fd=openSync(lock,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);closeSync(fd);
 let output;
 try{
  const previous=existsSync(receipt)?JSON.parse(readFileSync(receipt,'utf8')):null;
  if(previous&&previous.fingerprint!==fingerprint)throw Error('Operation ID has different immutable intent.');
  if(verified(target,manifest)){const result={schema:'nextcomp-artifact-fetch/v1',operationId,fingerprint,state:'verified',manifest,sourceNode,reconciled:!!previous};durableJson(receipt,result);return result;}
  if(existsSync(target)||previous?.state==='verified')throw Error('Previously verified artifact changed; held for review.');
  durableJson(receipt,{schema:'nextcomp-artifact-fetch/v1',operationId,fingerprint,state:'fetching',manifest,sourceNode});
  // Only the same known read is retryable. This helper has no activation operation.
  if(existsSync(partial)){if(!lstatSync(partial).isFile()||lstatSync(partial).isSymbolicLink())throw Error('Unsafe partial download.');unlinkSync(partial);}
  const path='/backend-artifacts/v1/blobs/'+manifest.sha256;
  const token=await proofs.nodeProof(sourceNode,{scope:'artifact:read',sha256:manifest.sha256,method:'GET',path});
  const response=await fetcher(member.origin+path,{headers:{Authorization:'Bearer '+token},redirect:'error',signal:AbortSignal.timeout(120000)});
  if(!response.ok||response.headers.get('X-Artifact-SHA256')!==manifest.sha256||response.headers.get('Content-Length')!==String(manifest.size))throw Error('Artifact response does not match its manifest.');
  output=openSync(partial,constants.O_RDWR|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);let size=0;
  for await(const chunk of response.body){size+=chunk.length;if(size>manifest.size)throw Error('Artifact response exceeds manifest.');let at=0;while(at<chunk.length)at+=writeSync(output,chunk,at,chunk.length-at);}
  fsyncSync(output);verifyArtifactFd(output,manifest);closeSync(output);output=undefined;
  try{linkSync(partial,target);}catch(e){if(e.code!=='EEXIST'||!verified(target,manifest))throw Error('Artifact target conflicts.');}
  unlinkSync(partial);const result={schema:'nextcomp-artifact-fetch/v1',operationId,fingerprint,state:'verified',manifest,sourceNode,reconciled:false};durableJson(receipt,result);return result;
 }catch{throw Error('Artifact download held; inspect the durable local receipt. No activation was attempted.');}
 finally{if(output!==undefined)closeSync(output);unlinkSync(lock);}
}
async function main(){
 const command=process.argv[2],{values:v}=parseArgs({args:process.argv.slice(3),options:Object.fromEntries(['store','artifact','manifest','fleet','source-node','operation','key-file','output','audience','ttl'].map(k=>[k,{type:'string'}]))});
 const manifest=artifactManifest(JSON.parse(readFileSync(v.manifest,'utf8')));
 if(command==='publish')console.log(JSON.stringify(publishBackendArtifact({dir:v.store,file:v.artifact,manifest})));
 else if(command==='fetch')console.log(JSON.stringify(await fetchBackendArtifact({manifest,dir:v.store,operationId:v.operation,sourceNode:v['source-node'],proofs:new FleetProofs({config:()=>loadFleet(v.fleet)})})));
 else if(command==='issue'){
  const cap=issueArtifactCapability(manifest,artifactKey(v['key-file']),{audience:v.audience,ttlSeconds:v.ttl?Number(v.ttl):300});
  const fd=openSync(v.output,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);try{const b=Buffer.from(canonicalJson(cap)+'\n');let at=0;while(at<b.length)at+=writeSync(fd,b,at,b.length-at);fsyncSync(fd);}finally{closeSync(fd);}
  console.log(JSON.stringify({written:true,sha256:manifest.sha256,audience:v.audience,expiresAt:cap.expiresAt}));
 }else throw Error('Unknown local artifact command.');
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(()=>{console.error('Local artifact operation failed; inspect protected local state.');process.exitCode=1;});
