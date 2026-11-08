#!/usr/bin/env node
/** One-time operator-installed local bootstrap helper; Node stdlib ONLY.
 * It can sign artifact READ proofs, never human sessions or activation commands.
 * Normal closed releases also provide backend-artifact.mjs and jose verification.
 */
import {constants,openSync,closeSync,fstatSync,readFileSync,writeSync,fsyncSync,lstatSync,existsSync,renameSync,linkSync,unlinkSync,realpathSync} from 'node:fs';
import {createPrivateKey,createPublicKey,sign,randomUUID,createHash} from 'node:crypto';
import {resolve,isAbsolute} from 'node:path';
import {parseArgs} from 'node:util';
import {fileURLToPath} from 'node:url';
import {privateNodeFile,syncReaderDirectory} from './artifact-local-files.mjs';
const hash=/^[a-f0-9]{64}$/,uuid=/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const check=v=>{if(!v)throw Error('Invalid local bootstrap input.');};
function read(path,max,secret=false){if(secret)return privateNodeFile(path,max);const fd=openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW);try{const s=fstatSync(fd);check(s.isFile()&&s.size<=max);return readFileSync(fd);}finally{closeSync(fd);}}
const digest=b=>createHash('sha256').update(b).digest('hex');
function saved(path,value){const tmp=path+'.'+randomUUID()+'.tmp',fd=openSync(tmp,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);try{const b=Buffer.from(JSON.stringify(value)+'\n');let n=0;while(n<b.length)n+=writeSync(fd,b,n,b.length-n);fsyncSync(fd);}finally{closeSync(fd);}renameSync(tmp,path);syncReaderDirectory(path);}
function identity(configFile){
 const c=JSON.parse(read(configFile,65536,true));
 check(c.version===1&&[c.fleetId,c.accountId,c.nodeId].every(x=>uuid.test(x))&&Array.isArray(c.members)&&c.members.length<=32);
 check(c.readerOnly===undefined||c.readerOnly===true);
 const self=c.readerOnly?{id:c.nodeId,publicKey:c.readerPublicKey}:c.members.find(v=>v.id===c.nodeId);
 check(self&&(!c.readerOnly||!c.members.some(v=>v.id===c.nodeId))&&typeof c.privateKeyFile==='string'&&isAbsolute(c.privateKeyFile));
 const key=createPrivateKey(read(c.privateKeyFile,65536,true));
 const publicKey=createPublicKey(key).export({type:'spki',format:'pem'});
 check(key.asymmetricKeyType==='ed25519'&&publicKey===createPublicKey(self.publicKey).export({type:'spki',format:'pem'}));
 return {c,key,publicKeySha256:digest(publicKey)};
}
/** One local binding to an EXISTING enrolled identity, not enrollment/key minting. */
export function provisionReader({configFile,expectedNode,readerFile}){
 const {c,publicKeySha256}=identity(configFile);check(uuid.test(expectedNode)&&c.nodeId===expectedNode&&isAbsolute(readerFile));
 const value={schema:'nextcomp-local-artifact-reader/v1',configFile:realpathSync(configFile),nodeId:c.nodeId,
              fleetId:c.fleetId,accountId:c.accountId,publicKeySha256,
              proofType:c.readerOnly?'nextcomp-artifact-reader+jwt':'nextcomp-node+jwt'};
 if(existsSync(readerFile)){
  const old=JSON.parse(read(readerFile,16384,true));
  check(Object.entries(value).every(([k,v])=>(old[k]??(k==='proofType'?'nextcomp-node+jwt':undefined))===v));
 }
 else{const fd=openSync(readerFile,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);try{const b=Buffer.from(JSON.stringify(value)+'\n');let n=0;while(n<b.length)n+=writeSync(fd,b,n,b.length-n);fsyncSync(fd);}finally{closeSync(fd);}syncReaderDirectory(readerFile);}
 checkReader(readerFile);
 return {schema:value.schema,nodeId:c.nodeId,publicKeySha256,ready:true,enrolled:false,credentialsCopied:false};
}
export function checkReader(readerFile){
 const reader=JSON.parse(read(readerFile,16384,true));
 check(reader.schema==='nextcomp-local-artifact-reader/v1'&&isAbsolute(reader.configFile)&&
       [reader.nodeId,reader.fleetId,reader.accountId].every(v=>uuid.test(v))&&hash.test(reader.publicKeySha256));
 const info=identity(reader.configFile);check(info.c.nodeId===reader.nodeId&&info.c.fleetId===reader.fleetId&&
       info.c.accountId===reader.accountId&&info.publicKeySha256===reader.publicKeySha256&&
       (reader.proofType??'nextcomp-node+jwt')===(info.c.readerOnly?'nextcomp-artifact-reader+jwt':'nextcomp-node+jwt'));
 return info;
}
export async function bootstrapFetch({configFile,readerFile,manifestFile,sourceNode,dir,operationId,fetcher=fetch}){
 check(!(configFile&&readerFile));
 const {c,key}=readerFile?checkReader(readerFile):identity(configFile),m=JSON.parse(read(manifestFile,16384));
 check(uuid.test(sourceNode));
 check(Object.keys(m).sort().join(',')==='releaseDigest,schema,sha256,size,sourceCommit,version'&&m.schema==='nextcomp-backend-artifact/v1'&&hash.test(m.sha256)&&hash.test(m.releaseDigest)&&/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(m.sourceCommit)&&Number.isSafeInteger(m.size)&&m.size>0&&m.size<=512*1024**2&&typeof m.version==='string'&&/^[A-Za-z0-9.+_-]{1,100}$/.test(m.version));
 const member=c.members.find(v=>v.id===sourceNode);check(member);
 const origin=new URL(member.origin);check(origin.protocol==='https:'&&origin.origin===member.origin&&origin.pathname==='/'&&!origin.search&&!origin.hash&&!origin.username&&!origin.password);
 check(/^[A-Za-z0-9_-]{1,100}$/.test(operationId)&&lstatSync(dir).isDirectory()&&!lstatSync(dir).isSymbolicLink());
 const path='/backend-artifacts/v1/blobs/'+m.sha256,target=resolve(dir,m.sha256+'.tar.gz'),receipt=resolve(dir,operationId+'.json'),lock=receipt+'.lock',partial=receipt+'.partial';
 const fingerprint=digest(JSON.stringify({manifest:m,origin:member.origin,nodeId:c.nodeId,sourceNode}));
 const locked=openSync(lock,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);closeSync(locked);let out;
 try{
  const old=existsSync(receipt)?JSON.parse(read(receipt,16384)):null;check(!old||old.fingerprint===fingerprint);
  const result={schema:'nextcomp-bootstrap-fetch/v1',operationId,fingerprint,manifest:m,sourceNode,state:'verified'};
  if(existsSync(target)){const b=read(target,512*1024**2);check(b.length===m.size&&digest(b)===m.sha256);saved(receipt,{...result,reconciled:true});return {...result,reconciled:true};}
  check(old?.state!=='verified');saved(receipt,{...result,state:'fetching'});
  if(existsSync(partial)){check(lstatSync(partial).isFile()&&!lstatSync(partial).isSymbolicLink());unlinkSync(partial);}
  const iat=Math.floor(Date.now()/1000),header={alg:'EdDSA',typ:c.readerOnly?'nextcomp-artifact-reader+jwt':'nextcomp-node+jwt',kid:c.nodeId};
  const claims={iss:c.nodeId,sub:c.nodeId,aud:sourceNode,fleetId:c.fleetId,accountId:c.accountId,scope:'artifact:read',sha256:m.sha256,method:'GET',path,iat,exp:iat+60,jti:randomUUID()};
  const body=Buffer.from(JSON.stringify(header)).toString('base64url')+'.'+Buffer.from(JSON.stringify(claims)).toString('base64url');
  const proof=body+'.'+sign(null,Buffer.from(body),key).toString('base64url');
  const r=await fetcher(member.origin+path,{headers:{Authorization:'Bearer '+proof},redirect:'error',signal:AbortSignal.timeout(120000)});
  check(r.ok&&r.headers.get('X-Artifact-SHA256')===m.sha256&&r.headers.get('Content-Length')===String(m.size));
  out=openSync(partial,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);const h=createHash('sha256');let size=0;
  for await(const chunk of r.body){size+=chunk.length;check(size<=m.size);h.update(chunk);let at=0;while(at<chunk.length)at+=writeSync(out,chunk,at,chunk.length-at);}
  fsyncSync(out);closeSync(out);out=undefined;check(size===m.size&&h.digest('hex')===m.sha256);
  try{linkSync(partial,target);}catch(e){if(e.code!=='EEXIST')throw e;const b=read(target,512*1024**2);check(b.length===m.size&&digest(b)===m.sha256);}
  unlinkSync(partial);saved(receipt,{...result,reconciled:false});return {...result,reconciled:false};
 }finally{if(out!==undefined)closeSync(out);unlinkSync(lock);}
}
if(process.argv[1]&&realpathSync(resolve(process.argv[1]))===fileURLToPath(import.meta.url)){
 try{const {values:v}=parseArgs({options:{...Object.fromEntries(['fleet','reader','node','manifest','source-node','store','operation'].map(k=>[k,{type:'string'}])),'provision-reader':{type:'boolean'},'check-reader':{type:'boolean'}}});
  let value;
  if(v['provision-reader'])value=provisionReader({configFile:v.fleet,expectedNode:v.node,readerFile:v.reader});
  else if(v['check-reader']){const {c,publicKeySha256}=checkReader(v.reader);value={ready:true,nodeId:c.nodeId,publicKeySha256};}
  else value=await bootstrapFetch({configFile:v.fleet,readerFile:v.reader,manifestFile:v.manifest,sourceNode:v['source-node'],dir:v.store,operationId:v.operation});
  console.log(JSON.stringify(value));}
 catch{console.error('Bootstrap artifact read held; inspect the local receipt. No activation attempted.');process.exitCode=1;}
}
