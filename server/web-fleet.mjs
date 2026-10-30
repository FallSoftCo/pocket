import {createHash,randomUUID} from 'node:crypto';
import {mkdirSync,readFileSync,lstatSync,fstatSync,openSync,writeSync,fsyncSync,closeSync,renameSync,unlinkSync,constants} from 'node:fs';
import {resolve,dirname} from 'node:path';
import {fleetDescriptor,fleetSession} from '../web/fleet.mjs';

const hash=value=>createHash('sha256').update(value).digest('hex');
const uuid=/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const fail=()=>Error('Fleet connection is unavailable; existing browser pairing is unchanged.');

/** Browser fleet credentials stay in the private web gateway, never browser JS.
 * Discovery runs beside normal reads. Only idempotent device enrollment is sent;
 * this class cannot send/retry a task or human message.
 */
export class WebFleet {
 constructor({directory,backend,fetchImpl=fetch,now=Date.now}) {
  this.directory=resolve(directory);this.backend=new URL(backend).origin;this.fetch=fetchImpl;this.now=now;
  mkdirSync(this.directory,{recursive:true,mode:0o700});
  const st=lstatSync(this.directory);if(!st.isDirectory()||st.isSymbolicLink()||st.mode&0o077)throw fail();
  this.flights=new Map();this.retryAt=new Map();this.errors=new Map();this.cache=new Map();this.listeners=new Map();
 }
 file(device){if(!uuid.test(device))throw fail();return resolve(this.directory,hash(this.backend+'\0'+device)+'.json');}
 load(device){
  if(this.cache.has(device))return this.cache.get(device);
  let state={version:1,backend:this.backend,device,profiles:[],pending:{}};
  try{
   const fd=openSync(this.file(device),constants.O_RDONLY|constants.O_NOFOLLOW);
   try{
    const st=fstatSync(fd);if(!st.isFile()||st.mode&0o077||st.size>1048576)throw fail();
    state=JSON.parse(readFileSync(fd,'utf8'));
   }finally{closeSync(fd);}
  }catch(e){if(e.code!=='ENOENT')throw fail();}
  if(state.version!==1||state.backend!==this.backend||state.device!==device||!Array.isArray(state.profiles)||state.profiles.length>128||
     !state.pending||typeof state.pending!=='object'||Object.keys(state.pending).length>128)throw fail();
  this.cache.set(device,state);return state;
 }
 save(device,state){
  const path=this.file(device),tmp=path+'.'+randomUUID()+'.tmp';let fd;
  try{
   fd=openSync(tmp,constants.O_WRONLY|constants.O_CREAT|constants.O_EXCL|constants.O_NOFOLLOW,0o600);
   const bytes=Buffer.from(JSON.stringify(state));let at=0;while(at<bytes.length)at+=writeSync(fd,bytes,at,bytes.length-at);
   fsyncSync(fd);closeSync(fd);fd=undefined;renameSync(tmp,path);
   const dir=openSync(dirname(path),constants.O_RDONLY|constants.O_DIRECTORY);try{fsyncSync(dir);}finally{closeSync(dir);}
   this.cache.set(device,state);
  }finally{if(fd!==undefined)closeSync(fd);try{unlinkSync(tmp);}catch(e){if(e.code!=='ENOENT')throw e;}}
 }
 list(device){return this.load(device).profiles.map(profile=>({...profile}));}
 onSources(device,listener){const set=this.listeners.get(device)||new Set();set.add(listener);this.listeners.set(device,set);return()=>{set.delete(listener);if(!set.size)this.listeners.delete(device);};}
 observe(device,token){
  if(!uuid.test(device)||typeof token!=='string'||token.length<32||this.flights.has(device)||this.flights.size>=2||(this.retryAt.get(device)||0)>this.now())return;
  const flight=this.discover(device,token).then(()=>{this.errors.delete(device);this.retryAt.set(device,this.now()+60000);})
   .catch(()=>{this.errors.set(device,'Fleet discovery unavailable; saved connections remain available.');this.retryAt.set(device,this.now()+60000);})
   .finally(()=>this.flights.delete(device));
  this.flights.set(device,flight);
 }
 async json(origin,path,token,body){
  const r=await this.fetch(new URL(path,origin),{method:body===undefined?'GET':'POST',redirect:'error',
   headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(8000)});
  if(r.status===404)return null;
  if(!r.ok||r.status===202)throw fail();
  const chunks=[];let count=0;for await(const chunk of r.body){count+=chunk.length;if(count>65536)throw fail();chunks.push(chunk);}
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
 }
 async discover(device,token){
  const status=await this.json(this.backend,'/api/backends',token);if(!status?.fleet)return;
  const fleet=fleetDescriptor(status.fleet);if(!fleet.enrollmentPath)return;
  const session=fleetSession(await this.json(this.backend,'/api/fleet/session',token,{}),fleet,this.now());
  let state=this.load(device);
  if(state.fleetId&&(state.fleetId!==fleet.id||state.accountId!==fleet.accountId||state.ownerDeviceId!==session.deviceId))throw fail();
  state={...state,fleetId:fleet.id,accountId:fleet.accountId,ownerDeviceId:session.deviceId};
  const liveIds=new Set(fleet.members.map(m=>m.id));
  // Removed enrollment is not silently routed forever. Its saved data stays on disk.
  state={...state,profiles:state.profiles.map(p=>({...p,retired:!liveIds.has(p.nodeId)}))};
  this.save(device,state);
  let changed=false,failed=false;
  for(const member of fleet.members){
   if(member.id===fleet.nodeId||state.profiles.some(p=>p.nodeId===member.id&&p.base===member.origin&&!p.retired))continue;
   try{
    const previous=state.pending[member.id],operationId=previous?.operationId||randomUUID();
    if(previous&&previous.origin!==member.origin)throw fail();
    if(!previous){state={...state,pending:{...state.pending,[member.id]:{operationId,origin:member.origin}}};this.save(device,state);}
    let access=previous?await this.json(member.origin,'/api/fleet/enrollments/'+operationId,session.token):null;
    if(!access)access=await this.json(member.origin,fleet.enrollmentPath,session.token,{operationId});
    access=access?.enrollment||access;
    if(access?.version!==1||access.fleetId!==fleet.id||access.accountId!==fleet.accountId||access.nodeId!==member.id||
       access.ownerDeviceId!==session.deviceId||!uuid.test(access.deviceId)||typeof access.token!=='string'||access.token.length<32||access.token.length>16384||/\s/.test(access.token))throw fail();
    const id='f-'+hash(member.id).slice(0,24);
    if(state.profiles.some(p=>p.id===id&&p.nodeId!==member.id))throw fail();
    if(state.profiles.length>=128&&!state.profiles.some(p=>p.nodeId===member.id))throw fail();
    const profile={id,name:member.name,base:member.origin,token:access.token,deviceId:access.deviceId,nodeId:member.id,
     fleetId:fleet.id,accountId:fleet.accountId,ownerDeviceId:session.deviceId};
    const pending={...state.pending};delete pending[member.id];
    state={...state,pending,profiles:[...state.profiles.filter(p=>p.nodeId!==member.id),profile]};this.save(device,state);changed=true;
   }catch{failed=true;}
  }
  if(changed)for(const listener of this.listeners.get(device)||[])listener();
  if(failed)throw fail();
 }
}
