/** ONE-TIME local artifact-reader provisioning. No new human issuer/host runtime. */
import {generateKeyPairSync,createPrivateKey,createPublicKey,sign,verify,createHash,randomUUID} from 'node:crypto';
import {constants,fstatSync,readFileSync,readdirSync,readlinkSync,writeSync,openSync,closeSync,fsyncSync,existsSync,renameSync,lstatSync,chownSync,chmodSync,realpathSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {resolve,isAbsolute,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseArgs} from 'node:util';
import {privateNodeFile,privateNodeDirectory,syncReaderDirectory} from './artifact-local-files.mjs';
const uuid=/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const check=v=>{if(!v)throw Error('Artifact reader provisioning held.');};
const hash=b=>createHash('sha256').update(b).digest('hex');
const canonical=v=>JSON.stringify(Object.fromEntries(Object.keys(v).sort().map(k=>[k,v[k]])));
function publicJson(path,expectedSha256){
 const fd=openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW);
 try{const s=fstatSync(fd);check(s.isFile()&&s.size<=65536);const raw=readFileSync(fd);
  check(/^[a-f0-9]{64}$/.test(expectedSha256)&&hash(raw)===expectedSha256);return JSON.parse(raw.toString('utf8'));}
 finally{closeSync(fd);}
}
function exclusive(path,value){
 const bytes=Buffer.isBuffer(value)?value:Buffer.from(canonical(value)+'\n');
 const fd=openSync(path,'wx',0o600);try{let at=0;while(at<bytes.length)at+=writeSync(fd,bytes,at,bytes.length-at);fsyncSync(fd);}finally{closeSync(fd);}
 syncReaderDirectory(path);
}
function publicRequest(value,nodeId,challenge){
 check(value.schema==='nextcomp-artifact-reader-request/v1'&&value.nodeId===nodeId&&value.challenge===challenge&&value.role==='artifact:read');
 const {signature,...body}=value,key=createPublicKey(value.publicKey);check(key.asymmetricKeyType==='ed25519');
 check(verify(null,Buffer.from(canonical(body)),key,Buffer.from(signature,'base64url')));return body;
}
/** Privileged CONFIG maintenance, not an exception to ordinary key readers. */
function configurationAccess(configFile,reviewFile){
 if(!reviewFile)return {read:()=>privateNodeFile(configFile,65536),original:()=>{},reviewSha256:null};
 check(process.platform==='linux'&&process.getuid()===0&&process.geteuid()===0);
 const raw=privateNodeFile(reviewFile,16384),review=JSON.parse(raw),parent=dirname(configFile);
 check(review.schema==='nextcomp-artifact-reader-config-owner-review/v1'&&review.configFile===configFile&&
   isAbsolute(configFile)&&realpathSync(configFile)===configFile&&/^[a-f0-9]{64}$/.test(review.expectedConfigSha256));
 for(const value of [review.file,review.parent]){
  check(value&&['uid','gid','mode','device','inode'].every(k=>Number.isSafeInteger(value[k])&&value[k]>=0));
 }
 check(review.parent.uid===0&&(review.parent.mode&0o022)===0&&review.parent.mode<=0o777&&
   review.file.mode===0o600);
 const matches=(info,value,identity)=>info.uid===value.uid&&info.gid===value.gid&&(info.mode&0o777)===value.mode&&
   (!identity||info.dev===value.device&&info.ino===value.inode);
 const read=()=>{
  const directory=lstatSync(parent);check(directory.isDirectory()&&!directory.isSymbolicLink()&&matches(directory,review.parent,true));
  const before=lstatSync(configFile);check(before.isFile()&&!before.isSymbolicLink()&&before.nlink===1&&matches(before,review.file,false));
  const fd=openSync(configFile,constants.O_RDONLY|constants.O_NOFOLLOW);
  try{const info=fstatSync(fd);check(info.isFile()&&info.size<=65536&&info.dev===before.dev&&info.ino===before.ino&&matches(info,review.file,false));return readFileSync(fd);}
  finally{closeSync(fd);}
 };
 return {read,reviewSha256:hash(raw),original:bytes=>{
  check(hash(bytes)===review.expectedConfigSha256&&matches(lstatSync(configFile),review.file,true));
 }};
}
export function createReaderRequest({dir,nodeId,challenge}){
 check(uuid.test(nodeId)&&uuid.test(challenge));privateNodeDirectory(dir);
 const intentFile=resolve(dir,'enrollment-intent.json'),keyFile=resolve(dir,'node-private.pem'),requestFile=resolve(dir,'request.public.json');
 if(!existsSync(intentFile))exclusive(intentFile,{nodeId,challenge,role:'artifact:read',createdAt:new Date().toISOString()});
 const intent=JSON.parse(privateNodeFile(intentFile,16384));check(intent.nodeId===nodeId&&intent.challenge===challenge&&intent.role==='artifact:read');
 if(!existsSync(keyFile)){
  check(!existsSync(requestFile));const pair=generateKeyPairSync('ed25519');
  exclusive(keyFile,Buffer.from(pair.privateKey.export({type:'pkcs8',format:'pem'})));
 }
 const key=createPrivateKey(privateNodeFile(keyFile,65536)),publicKey=createPublicKey(key).export({type:'spki',format:'pem'});
 if(existsSync(requestFile)){
  const previous=JSON.parse(privateNodeFile(requestFile,16384));publicRequest(previous,nodeId,challenge);check(previous.publicKey===publicKey);return previous;
 }
 const body={schema:'nextcomp-artifact-reader-request/v1',nodeId,challenge,role:'artifact:read',publicKey,createdAt:intent.createdAt};
 const request={...body,signature:sign(null,Buffer.from(canonical(body)),key).toString('base64url')};
 exclusive(requestFile,request);return request;
}
/** Must run as existing gateway config owner/operator after attributable review. */
export function acceptReader({configFile,requestFile,requestSha256,nodeId,challenge,dir,configOwnerReviewFile}){
 check(process.platform==='linux'&&isAbsolute(configFile));
 const lock=realpathSync(configFile)+'.artifact-reader.lock';
 try{const fd=openSync(lock,'wx',0o600);closeSync(fd);}catch(e){if(e.code!=='EEXIST')throw e;}
 privateNodeFile(lock,1);
 const result=spawnSync('/usr/bin/flock',['--exclusive','--nonblock','--no-fork',lock,process.execPath,
   fileURLToPath(import.meta.url),'--action','accept-locked','--fleet',configFile,'--request',requestFile,
   '--request-sha256',requestSha256,'--node',nodeId,'--challenge',challenge,'--state',dir,
   ...(configOwnerReviewFile?['--config-owner-review',configOwnerReviewFile]:[])],{encoding:'utf8',timeout:15000,maxBuffer:65536});
 check(result.status===0);return JSON.parse(result.stdout);
}
function requireConfigLock(configFile){
 const path=realpathSync(configFile)+'.artifact-reader.lock';privateNodeFile(path,1);
 // flock --no-fork leaves the kernel lock in THIS process across exec. Inspect
 // its actual fdinfo, not a caller-provided flag or an environment assertion.
 check(readdirSync('/proc/self/fd').some(fd=>{
  try{return readlinkSync('/proc/self/fd/'+fd)===path&&
    new RegExp('FLOCK\\s+ADVISORY\\s+WRITE\\s+'+process.pid+'\\s').test(readFileSync('/proc/self/fdinfo/'+fd,'utf8'));}
  catch{return false;}
 }));
}
function acceptReaderLocked({configFile,requestFile,requestSha256,nodeId,challenge,dir,configOwnerReviewFile}){
 check(uuid.test(nodeId)&&uuid.test(challenge)&&process.platform==='linux');privateNodeDirectory(dir);requireConfigLock(configFile);
 const request=publicJson(requestFile,requestSha256),body=publicRequest(request,nodeId,challenge);
 const access=configurationAccess(configFile,configOwnerReviewFile);
 const before=access.read(),config=JSON.parse(before),originalMembers=JSON.stringify(config.members);
 check(config.version===1&&[config.fleetId,config.accountId,config.nodeId].every(v=>uuid.test(v))&&Array.isArray(config.members));
 const readers=config.artifactReaders??[];check(Array.isArray(readers)&&readers.length<=128&&!config.members.some(m=>m.id===nodeId||createPublicKey(m.publicKey).equals(createPublicKey(body.publicKey))));
 const previous=readers.find(r=>r.id===nodeId);check(!previous||createPublicKey(previous.publicKey).equals(createPublicKey(body.publicKey)));
 const next=previous?readers:[...readers,{id:nodeId,publicKey:body.publicKey}];check(next.length<=128);
 const ids=new Set(config.members.map(m=>m.id)),keys=new Set(config.members.map(m=>createPublicKey(m.publicKey).export({type:'spki',format:'der'}).toString('hex')));
 for(const reader of next){
  check(Object.keys(reader).sort().join(',')==='id,publicKey'&&uuid.test(reader.id)&&!ids.has(reader.id));
  const key=createPublicKey(reader.publicKey),value=key.export({type:'spki',format:'der'}).toString('hex');
  check(key.asymmetricKeyType==='ed25519'&&!keys.has(value));ids.add(reader.id);keys.add(value);
 }
 const intentFile=resolve(dir,'accept-intent.private.json'),resultFile=resolve(dir,'accept-result.private.json');
 if(!existsSync(intentFile)){
  access.original(before);
  exclusive(resolve(dir,'fleet.original.private.json'),before);
  exclusive(intentFile,{schema:'nextcomp-artifact-reader-accept/v1',nodeId,challenge,requestSha256:hash(Buffer.from(canonical(request))),originalConfigSha256:hash(before),configOwnerReviewSha256:access.reviewSha256});
 }
 const intent=JSON.parse(privateNodeFile(intentFile,16384));check(intent.nodeId===nodeId&&intent.challenge===challenge&&intent.requestSha256===hash(Buffer.from(canonical(request))));
 check((intent.configOwnerReviewSha256??null)===access.reviewSha256);
 if(!previous){
  check(!existsSync(resultFile)&&intent.originalConfigSha256===hash(before));
  config.artifactReaders=next;
  check(JSON.stringify(config.members)===originalMembers);
  const temporary=configFile+'.reader-'+randomUUID(),info=lstatSync(configFile);
  exclusive(temporary,config);chownSync(temporary,info.uid,info.gid);chmodSync(temporary,info.mode&0o777);
  check(hash(access.read())===hash(before));renameSync(temporary,configFile);syncReaderDirectory(configFile);
 }
 const currentBytes=access.read(),current=JSON.parse(currentBytes);
 check(current.artifactReaders.some(r=>r.id===nodeId&&r.publicKey===body.publicKey)&&!current.members.some(m=>m.id===nodeId));
 const acceptance={schema:'nextcomp-artifact-reader-acceptance/v1',version:1,readerOnly:true,nodeId,
  readerPublicKey:body.publicKey,fleetId:current.fleetId,accountId:current.accountId,
  challenge,members:current.members.map(({id,name,origin,publicKey})=>({id,name,origin,publicKey}))};
 const acceptanceFile=resolve(dir,'acceptance.public.json');
 if(!existsSync(acceptanceFile))exclusive(acceptanceFile,acceptance);
 const result={nodeId,role:'artifact:read',registered:true,humanIssuerAdded:false,configSha256:hash(currentBytes)};
 if(!existsSync(resultFile))exclusive(resultFile,result);return result;
}
export function completeReader({dir,acceptanceFile}){
 privateNodeDirectory(dir);const request=JSON.parse(privateNodeFile(resolve(dir,'request.public.json'),16384));
 const a=JSON.parse(privateNodeFile(acceptanceFile,65536));
 check(a.schema==='nextcomp-artifact-reader-acceptance/v1'&&a.readerOnly===true&&a.nodeId===request.nodeId&&a.challenge===request.challenge&&a.readerPublicKey===request.publicKey);
 const {schema,challenge,...config}=a;config.privateKeyFile=resolve(dir,'node-private.pem');
 const file=resolve(dir,'reader-fleet.private.json');
 if(existsSync(file))check(canonical(JSON.parse(privateNodeFile(file,65536)))===canonical(config));else exclusive(file,config);
 return {nodeId:config.nodeId,role:'artifact:read',configFile:file,credentialsCopied:false};
}
if(process.argv[1]&&realpathSync(resolve(process.argv[1]))===fileURLToPath(import.meta.url)){
 try{
  const {values:v}=parseArgs({options:Object.fromEntries(['action','state','node','challenge','request','request-sha256','fleet','acceptance','config-owner-review'].map(k=>[k,{type:'string'}]))});
  const args={dir:resolve(v.state),nodeId:v.node,challenge:v.challenge,configFile:v.fleet,requestFile:v.request,requestSha256:v['request-sha256'],acceptanceFile:v.acceptance,configOwnerReviewFile:v['config-owner-review']};
  const result=v.action==='create'?createReaderRequest(args):v.action==='accept'?acceptReader(args):v.action==='accept-locked'?acceptReaderLocked(args):v.action==='complete'?completeReader(args):null;
  check(result);
  console.log(JSON.stringify(v.action==='create'?{request:result,requestFile:resolve(args.dir,'request.public.json'),requestFileSha256:hash(privateNodeFile(resolve(args.dir,'request.public.json'),16384)),privateKeyShared:false}:result));
 }catch{console.error('Local artifact reader provisioning held; retain original local intent/keys and inspect.');process.exitCode=1;}
}
