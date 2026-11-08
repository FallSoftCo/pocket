/** Immutable backend blobs. This is NOT a human, device, admin or execution API. */
import {constants,openSync,closeSync,fstatSync,readFileSync,readSync,writeSync,fsyncSync,realpathSync,lstatSync,existsSync,linkSync,unlinkSync,createReadStream} from 'node:fs';
import {resolve,isAbsolute,relative} from 'node:path';
import {createHash,createHmac,timingSafeEqual,randomUUID} from 'node:crypto';

export const MAX_BACKEND_ARTIFACT_BYTES=512*1024**2;
export const MAX_CAPABILITY_SECONDS=900;
const hash=/^[a-f0-9]{64}$/,commit=/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/;
const fail=(status,message)=>Object.assign(Error(message),{status});
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k));
export const canonicalJson=value=>JSON.stringify(sort(value));
function sort(v){return Array.isArray(v)?v.map(sort):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,sort(v[k])])):v;}
export const sha256=value=>createHash('sha256').update(value).digest('hex');
export function artifactManifest(v){
 if(!exact(v,['schema','sha256','size','version','sourceCommit','releaseDigest'])||v.schema!=='nextcomp-backend-artifact/v1'||
  !hash.test(v.sha256)||!hash.test(v.releaseDigest)||!commit.test(v.sourceCommit)||
  !Number.isSafeInteger(v.size)||v.size<1||v.size>MAX_BACKEND_ARTIFACT_BYTES||
  typeof v.version!=='string'||!/^[A-Za-z0-9.+_-]{1,100}$/.test(v.version))throw fail(400,'Invalid backend artifact manifest.');
 return v;
}
function regular(path,max,privateFile=false){
 const fd=openSync(path,constants.O_RDONLY|constants.O_NOFOLLOW);
 try{
  const st=fstatSync(fd);
  if(!st.isFile()||st.size>max||privateFile&&((st.mode&0o077)!==0||![0,process.getuid()].includes(st.uid)))throw Error();
  return readFileSync(fd);
 }finally{closeSync(fd);}
}
export function artifactKey(file){
 try{const key=regular(file,256,true).toString('utf8').trim();if(!hash.test(key))throw Error();return Buffer.from(key,'hex');}
 catch{throw fail(503,'Protected backend artifact key is unavailable.');}
}
export function issueArtifactCapability(manifest,key,{audience,ttlSeconds=300,now=Date.now()}={}){
 artifactManifest(manifest);
 if(!Buffer.isBuffer(key)||key.length!==32||typeof audience!=='string'||!/^[A-Za-z0-9._:-]{1,100}$/.test(audience)||
  !Number.isInteger(ttlSeconds)||ttlSeconds<1||ttlSeconds>MAX_CAPABILITY_SECONDS)throw fail(400,'Invalid artifact capability scope.');
 const iat=Math.floor(now/1000),exp=iat+ttlSeconds;
 const payload={v:1,sha256:manifest.sha256,size:manifest.size,manifestDigest:sha256(canonicalJson(manifest)),audience,iat,exp};
 const body=Buffer.from(canonicalJson(payload)).toString('base64url');
 return {token:body+'.'+createHmac('sha256',key).update(body).digest('base64url'),expiresAt:exp*1000};
}
function authorize(token,key,manifest,audience,now){
 try{
  if(typeof token!=='string'||token.length>2048)throw Error();
  const parts=token.split('.');if(parts.length!==2||!/^[A-Za-z0-9_-]+$/.test(parts[0])||!/^[A-Za-z0-9_-]{43}$/.test(parts[1]))throw Error();
  const expected=createHmac('sha256',key).update(parts[0]).digest(),signature=Buffer.from(parts[1],'base64url');
  if(signature.length!==expected.length||signature.toString('base64url')!==parts[1]||!timingSafeEqual(signature,expected))throw Error();
  const p=JSON.parse(Buffer.from(parts[0],'base64url').toString('utf8')),at=Math.floor(now/1000);
  if(!exact(p,['v','sha256','size','manifestDigest','audience','iat','exp'])||p.v!==1||p.sha256!==manifest.sha256||p.size!==manifest.size||
   p.manifestDigest!==sha256(canonicalJson(manifest))||p.audience!==audience||typeof audience!=='string'||
   !Number.isSafeInteger(p.iat)||!Number.isSafeInteger(p.exp)||p.iat>at||p.exp<=at||p.exp-p.iat<1||p.exp-p.iat>MAX_CAPABILITY_SECONDS)throw Error();
 }catch{throw fail(403,'Backend artifact read capability is invalid or expired.');}
}
export function verifyArtifactFd(fd,manifest){
 const before=fstatSync(fd);if(!before.isFile()||before.size!==manifest.size)throw fail(503,'Backend artifact is unavailable.');
 const h=createHash('sha256'),buffer=Buffer.alloc(1024*1024);let position=0,n;
 while((n=readSync(fd,buffer,0,buffer.length,position))>0){position+=n;h.update(buffer.subarray(0,n));}
 const after=fstatSync(fd);
 if(position!==manifest.size||h.digest('hex')!==manifest.sha256||before.mtimeMs!==after.mtimeMs||before.ctimeMs!==after.ctimeMs)
  throw fail(503,'Backend artifact integrity check failed.');
}
function below(path,parent){const r=relative(parent,path);return r===''||!r.startsWith('..')&&!isAbsolute(r);}
export class BackendArtifacts{
 constructor({dir,keyFile,dataDir,now=Date.now}){
  if(typeof dir!=='string'||!isAbsolute(dir)||!lstatSync(dir).isDirectory()||lstatSync(dir).isSymbolicLink()||keyFile!==undefined&&(typeof keyFile!=='string'||!isAbsolute(keyFile)))throw fail(503,'Invalid backend artifact store configuration.');
  this.dir=realpathSync(dir);this.key=keyFile?artifactKey(keyFile):null;this.now=now;
  if(keyFile&&below(realpathSync(keyFile),this.dir))throw fail(503,'Artifact keys must be outside the blob store.');
  if(dataDir){const data=realpathSync(dataDir);if(below(this.dir,data)||below(data,this.dir))throw fail(503,'Backend artifacts must be separate from private gateway data.');}
 }
 manifest(id){
  if(!hash.test(id))throw fail(404,'Backend artifact not found.');
  try{const m=artifactManifest(JSON.parse(regular(resolve(this.dir,id+'.json'),16384)));if(m.sha256!==id)throw Error();return m;}
  catch{throw fail(404,'Backend artifact not found.');}
 }
 open(id,token,audience){
  const manifest=this.manifest(id);if(!this.key)throw fail(403,'Artifact capability authentication is disabled.');authorize(token,this.key,manifest,audience,this.now());return this.verified(manifest);
 }
 verified(manifest){
  let fd;
  const id=manifest.sha256;
  try{fd=openSync(resolve(this.dir,id+'.blob'),constants.O_RDONLY|constants.O_NOFOLLOW);verifyArtifactFd(fd,manifest);return {fd,manifest};}
  catch(error){if(fd!==undefined)closeSync(fd);throw error.status?error:fail(503,'Backend artifact is unavailable.');}
 }
}
/** Local publication only. Never overwrite an existing content address. */
export function publishBackendArtifact({dir,file,manifest}){
 artifactManifest(manifest);const root=realpathSync(dir);let src,dst;const temps=[];
 try{
  src=openSync(file,constants.O_RDONLY|constants.O_NOFOLLOW);verifyArtifactFd(src,manifest);
  const target=resolve(root,manifest.sha256+'.blob');
  if(existsSync(target)){
   dst=openSync(target,constants.O_RDONLY|constants.O_NOFOLLOW);verifyArtifactFd(dst,manifest);closeSync(dst);dst=undefined;
  }else{
   const temp=resolve(root,'.publish-'+randomUUID());temps.push(temp);
   dst=openSync(temp,constants.O_RDWR|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o444);
   const buffer=Buffer.alloc(1024*1024);let position=0,n;
   while((n=readSync(src,buffer,0,buffer.length,position))>0){let offset=0;while(offset<n)offset+=writeSync(dst,buffer,offset,n-offset);position+=n;}
   fsyncSync(dst);verifyArtifactFd(dst,manifest);closeSync(dst);dst=undefined;
   try{linkSync(temp,target);}catch(e){if(e.code!=='EEXIST')throw e;dst=openSync(target,constants.O_RDONLY|constants.O_NOFOLLOW);verifyArtifactFd(dst,manifest);closeSync(dst);dst=undefined;}
  }
  const path=resolve(root,manifest.sha256+'.json'),raw=canonicalJson(manifest)+'\n';
  if(existsSync(path)){if(regular(path,16384).toString()!==raw)throw fail(409,'Existing artifact manifest differs.');}
  else{
   const temp=resolve(root,'.manifest-'+randomUUID());temps.push(temp);
   dst=openSync(temp,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o444);
   let offset=0;const bytes=Buffer.from(raw);while(offset<bytes.length)offset+=writeSync(dst,bytes,offset,bytes.length-offset);fsyncSync(dst);closeSync(dst);dst=undefined;
   try{linkSync(temp,path);}catch(e){if(e.code!=='EEXIST')throw e;if(regular(path,16384).toString()!==raw)throw fail(409,'Existing artifact manifest differs.');}
  }
  const directory=openSync(root,constants.O_RDONLY|constants.O_DIRECTORY);try{fsyncSync(directory);}finally{closeSync(directory);}
  return manifest;
 }finally{if(src!==undefined)closeSync(src);if(dst!==undefined)closeSync(dst);for(const temp of temps)try{unlinkSync(temp);}catch{}}
}
export function mountBackendArtifacts(app,{artifacts,nodeAuthorize}){
 app.use('/backend-artifacts/v1',async(req,res)=>{
  let fd;
  try{
   res.set('Cache-Control','private, no-store');res.set('X-Content-Type-Options','nosniff');
   if(!['GET','HEAD'].includes(req.method)){res.set('Allow','GET, HEAD');return res.status(405).json({error:'Backend artifacts are read-only.'});}
   const match=req.path.match(/^\/blobs\/([a-f0-9]{64})$/);
   if(!artifacts||!match||req.originalUrl.includes('?'))throw fail(404,'Backend artifact not found.');
   const header=req.get('Authorization')||'';let opened;
   if(header.startsWith('Bearer ')&&nodeAuthorize){
    await nodeAuthorize(header.slice(7),{method:req.method,path:'/backend-artifacts/v1'+req.path,sha256:match[1],scope:'artifact:read'});
    opened=artifacts.verified(artifacts.manifest(match[1]));
   }else if(header.startsWith('Artifact '))opened=artifacts.open(match[1],header.slice(9),req.get('X-NextComp-Artifact-Audience'));
   else throw fail(403,'Backend artifact read authorization required.');
   fd=opened.fd;
   res.set({'Content-Type':'application/octet-stream','Content-Length':String(opened.manifest.size),'X-Artifact-SHA256':opened.manifest.sha256});
   if(req.method==='HEAD'){closeSync(fd);fd=undefined;return res.end();}
   const stream=createReadStream(null,{fd,autoClose:true,start:0,end:opened.manifest.size-1});fd=undefined;
   stream.on('error',()=>res.destroy());res.on('close',()=>stream.destroy());stream.pipe(res);
  }catch(error){if(fd!==undefined)closeSync(fd);res.status(error.status||503).json({error:error.status?error.message:'Backend artifact is unavailable.'});}
 });
}
