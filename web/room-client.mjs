import {RoomHistory} from './room-model.mjs';
import {bindingMatches,jsonEqual} from './receipt-policy.mjs';
export const uuid=v=>typeof v==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v);
export const isRoom=id=>typeof id==='string'&&id.startsWith('room:');
const identityKeys=['bindingId','installationId','authorityId','principalId'];
const roomKey=(identity,id)=>'room:'+identity.authorityId+':'+identity.principalId+':'+id;
const cancelled=signal=>Object.assign(Error(signal.reason?.name==='TimeoutError'?'Room read timed out; saved state retained.':'Room read or discovery superseded.'),{name:signal.reason?.name||'AbortError'});
function abortable(promise,signal){
 if(signal.aborted)return Promise.reject(cancelled(signal));
 return new Promise((resolve,reject)=>{const abort=()=>reject(cancelled(signal));signal.addEventListener('abort',abort,{once:true});Promise.resolve(promise).then(resolve,reject).finally(()=>signal.removeEventListener('abort',abort));});
}
export function sourcePath(environment,path){return environment&&environment!=='current'?'/api/environments/'+environment+path.slice(4):path;}
export function verifiedIdentity(value){
 if(!value||!value.bindingId?.match(/^[0-9a-f]{64}$/)||!['installationId','authorityId','principalId','defaultRoomId'].every(k=>uuid(value[k])))throw Error('Hollywood human binding is unconfirmed.');
 return Object.fromEntries([...identityKeys,'defaultRoomId'].map(k=>[k,value[k]]));
}
export function canAnswer(value){
 const a=value?.attention,s=value?.scope;
 if(a?.state!=='pending'||!s||s.attentionId!==a.id||s.authorityId!==a.authority||s.roomId!==a.room||s.agentId!==a.agent||
  s.nativeThread!==a.native_thread||s.nativeTurn!==a.native_turn||s.requestDigest!==a.digest||!s.requestDigest?.match(/^[0-9a-f]{64}$/)||
  !['authorityId','attentionId','roomId','agentId'].every(k=>uuid(s[k]))||!['nativeThread','nativeTurn'].every(k=>typeof s[k]==='string'&&s[k].length>0&&s[k].length<=128))return false;
 if(s.conversationVersion!==undefined)return s.conversationVersion===1&&value.canRespond===true&&uuid(s.sourceEventId)&&s.sourceEventId===a.event;
 return uuid(s.deliveryId)&&uuid(s.hostId)&&Number.isSafeInteger(s.generation)&&s.generation>0&&
  (['claimed','running'].includes(value.deliveryState)||value.responseMode==='same-agent-context-input');
}
/** API calls carry their captured physical binding; history uses logical room
 * identity. Gateway switching can re-read events, never retarget saved writes. */
export class RoomClient{
 constructor(api,{clock=()=>Date.now()}={}){this.api=api;this.clock=clock;this.entries=new Map();this.histories=new Map();this.sources=[];this.errors=[];this.sourceCache=new Map();this.discoveryEpoch=0;this.readFlights=new Map();this.entryWaiters=new Set();}
 entry(id){const entry=this.entries.get(id);if(!entry)throw Error('Original room is not available in this browser.');return entry;}
 route(id,{requireAvailable=true}={}){const entry=this.entry(id),route=entry.routes.find(r=>r.environment===entry.selected);if(!route||requireAvailable&&!route.available)throw Error('Original room gateway is unavailable. Draft retained.');return route;}
 get(path,options={}){const signal=AbortSignal.any([AbortSignal.timeout(options.timeout||4000),...(options.signal?[options.signal]:[])]);if(signal.aborted)return Promise.reject(cancelled(signal));return abortable(this.api(path,undefined,{...options,signal,timeout:options.timeout||4000}),signal);}
 async call(route,tail,body,options={}){
  const path=sourcePath(route.environment,'/api/hollywood/v3/'+tail),headers={'X-Hollywood-Binding':route.binding.bindingId};
  const value=body===undefined?await this.get(path,{...options,headers}):await this.api(path,body,{headers});
  if(!bindingMatches(route.binding,value.binding))throw Object.assign(Error('Hollywood reply belongs to another environment or human binding.'),{status:409,code:'HOLLYWOOD_BINDING_MISMATCH'});
  return value;
 }
 publishCatalog(pass){
  if(pass.epoch!==this.discoveryEpoch)return;
  const found=new Map();this.errors=[...(pass.failures?.values()||[])];
  for(const source of pass.sources){const cached=this.sourceCache.get(source.id);if(!cached)continue;
   if(cached.error)this.errors.push(source.name+': '+cached.error);
   for(const room of cached.rooms||[]){const binding=cached.binding,id=roomKey(binding,room.id),old=this.entries.get(id);
    const entry=found.get(id)||{...old,id,roomId:room.id,binding,room,routes:[],selected:old?.selected||source.id};
    entry.routes.push({environment:source.id,name:source.name,binding,room,available:cached.available});found.set(id,entry);
   }
  }
  for(const e of found.values()){
   const old=this.entries.get(e.id),selected=e.routes.find(r=>r.environment===e.selected);
   if(!selected){const previous=old?.routes.find(r=>r.environment===old.selected);if(previous)e.routes.push({...previous,available:false,retired:true});}
   e.room=(e.routes.find(r=>r.environment===e.readEnvironment&&r.available)||e.routes.find(r=>r.environment===e.selected&&r.available)||e.routes[0]).room;
  }
  this.entries=found;
  for(const waiter of [...this.entryWaiters])if(found.has(waiter.id)){waiter.resolve(found.get(waiter.id));this.entryWaiters.delete(waiter);}
  const snapshot=[...found.values()];for(const callback of pass.listeners)callback(snapshot);
  if(snapshot.some(e=>e.routes.some(r=>r.available)))pass.resolveReady(snapshot);
 }
 waitForEntry(id){
  if(this.entries.has(id))return Promise.resolve(this.entries.get(id));
  return new Promise((resolve,reject)=>{const waiter={id,resolve:value=>{clearTimeout(timer);resolve(value);}},timer=setTimeout(()=>{this.entryWaiters.delete(waiter);reject(Error('Original room discovery is unavailable. Saved draft retained.'));},12000);this.entryWaiters.add(waiter);});
 }
 discover(sources=[{id:'current',name:'Workstation'}],{onUpdate,force=false}={}){
  const signature=JSON.stringify(sources.map(s=>[s.id,s.name]));
  if(this.discovery?.signature===signature&&!force){
   if(!this.discovery.finished){if(onUpdate)this.discovery.listeners.add(onUpdate);onUpdate?.([...this.entries.values()]);return this.discovery.ready;}
   if(this.clock()-this.discovery.completedAt<15000){onUpdate?.([...this.entries.values()]);return Promise.resolve([...this.entries.values()]);}
  }
  this.discovery?.controller.abort();this.discovery?.resolveReady([...this.entries.values()]);
  const controller=new AbortController(),pass={epoch:++this.discoveryEpoch,signature,controller,sources:sources.slice(0,128),listeners:new Set(onUpdate?[onUpdate]:[]),failures:new Map(),finished:false};
  pass.ready=new Promise(resolve=>{pass.resolveReady=resolve;});this.discovery=pass;this.sources=pass.sources;
  this.publishCatalog(pass); // Cached navigation/head is never blocked by discovery.
  const ordered=[...pass.sources].sort((a,b)=>Number(!!this.sourceCache.get(b.id)?.available)-Number(!!this.sourceCache.get(a.id)?.available));
  let cursor=0,healthy=false;const deadline=setTimeout(()=>controller.abort(),10000);
  const probe=async source=>{
   const old=this.sourceCache.get(source.id);let binding=old?.binding;
   try{
    if(!binding||old.identityUntil<=this.clock())binding=verifiedIdentity(await this.get(sourcePath(source.id,'/api/hollywood/v3/identity'),{signal:controller.signal}));
    const value=await this.call({environment:source.id,binding},'rooms',undefined,{signal:controller.signal});
    if(value.authorityId!==binding.authorityId||!Array.isArray(value.rooms)||value.rooms.some(room=>!uuid(room.id)||room.authority!==binding.authorityId))throw Error('Room catalog scope is unconfirmed.');
    if(pass.epoch!==this.discoveryEpoch||controller.signal.aborted)return;
    this.sourceCache.set(source.id,{binding,identityUntil:old?.binding===binding?old.identityUntil:this.clock()+60000,rooms:value.rooms,available:true});healthy=true;
   }catch(error){
    if(pass.epoch!==this.discoveryEpoch||controller.signal.aborted)return;
    if(old)this.sourceCache.set(source.id,{...old,available:false,identityUntil:[401,403,409].includes(error.status)?0:old.identityUntil,error:error.message});
    else if(![403,404].includes(error.status))pass.failures.set(source.id,source.name+': '+error.message);
   }
   this.publishCatalog(pass);
  };
  // One verified Hollywood ingress federates its rooms. Explore only a bounded
  // first wave of alternates; continue past failures only until one is healthy.
  pass.done=Promise.all(Array.from({length:Math.min(3,ordered.length)},async()=>{while(cursor<ordered.length&&!healthy&&!controller.signal.aborted){const source=ordered[cursor++];await probe(source);}})).finally(()=>{
   clearTimeout(deadline);pass.finished=true;pass.completedAt=this.clock();
   if(pass.epoch===this.discoveryEpoch){this.publishCatalog(pass);pass.resolveReady([...this.entries.values()]);}
   pass.listeners.clear();
  });
  return pass.ready;
 }
 readCall(id,tail){
  const key=id+'/'+tail;if(this.readFlights.has(key))return this.readFlights.get(key);
  const e=this.entry(id),routes=e.routes.filter(r=>!r.retired&&r.binding.authorityId===e.binding.authorityId&&r.binding.principalId===e.binding.principalId)
   .sort((a,b)=>Number(b.available)-Number(a.available)||Number(b.environment===e.readEnvironment)-Number(a.environment===e.readEnvironment)).slice(0,3);
  const controller=new AbortController();
  const jobs=routes.map(async route=>{try{const value=await this.call(route,tail,undefined,{signal:controller.signal});route.available=true;e.readEnvironment=route.environment;const cached=this.sourceCache.get(route.environment);if(bindingMatches(route.binding,cached?.binding))cached.available=true;return value;}
   catch(error){if(!controller.signal.aborted){route.available=false;const cached=this.sourceCache.get(route.environment);if(bindingMatches(route.binding,cached?.binding)&&(!error.status||error.status>=500))cached.available=false;}throw error;}});
  // Hedge GET only. The selected physical write/draft route is never modified.
  const work=Promise.any(jobs).catch(error=>{throw error.errors?.[0]||error;}).finally(()=>{controller.abort();this.readFlights.delete(key);});
  this.readFlights.set(key,work);return work;
 }
 select(id,environment){const e=this.entry(id);if(!e.routes.some(r=>r.environment===environment&&r.available))throw Error('Gateway unavailable.');e.selected=environment;e.room=e.routes.find(r=>r.environment===environment).room;}
 async detail(id){
  const entry=this.entry(id),value=await this.readCall(id,'rooms/'+entry.roomId);
  if(value.room?.id!==entry.roomId||value.room.authority!==entry.binding.authorityId||!Array.isArray(value.actors)||!value.permissions)throw Error('Room detail scope is unconfirmed.');
  entry.detail=value;entry.room=value.room;return value;
 }
 history(id,view){
  const e=this.entry(id),key=id+'/'+view;
  if(!this.histories.has(key))this.histories.set(key,new RoomHistory({authorityId:e.binding.authorityId,principalId:e.binding.principalId,roomId:e.roomId},{view}));
  return this.histories.get(key);
 }
 async read(id,view='all',before=null){
  const entry=this.entry(id),history=this.history(id,view);
  const query=new URLSearchParams({view,limit:'100',...(before!==null?{before:String(before)}:history.latestSeq!==null?{after:String(history.latestSeq)}:{before:String(Number.MAX_SAFE_INTEGER)})});
  const page=await this.readCall(id,'rooms/'+entry.roomId+'/history?'+query);
  history.apply(page,{older:before!==null});
  if(before===null&&page.events.length===100){
   const tail=await this.readCall(id,'rooms/'+entry.roomId+'/history?view='+view+'&limit=100&before='+Number.MAX_SAFE_INTEGER);
   const end=page.events.at(-1)?.seq,first=tail.events?.[0]?.seq;
   history.apply(tail);
   if(end<first)(history.gaps??=[]).push({after:end,before:first});
  }
  if(before===null&&history.gaps?.length){
   const gap=history.gaps[0],fill=await this.readCall(id,'rooms/'+entry.roomId+'/history?view='+view+'&limit=100&after='+gap.after);
   history.apply(fill);
   const next=fill.events.at(-1)?.seq;
   if(fill.events.length<100||next>=gap.before)history.gaps.shift();
   else if(next>gap.after)gap.after=next;
  }
  return {rows:history.rows(),hasEarlier:history.olderCursor!==null,before:history.olderCursor,catchingUp:!!history.gaps?.length};
 }
 async attention(id){
  const e=this.entry(id),page=await this.readCall(id,'rooms/'+e.roomId+'/attention?limit=100');
  if(page.authorityId!=null&&page.authorityId!==e.binding.authorityId||page.roomId!=null&&page.roomId!==e.roomId||!Array.isArray(page.attention)||page.attention.some(v=>v.attention?.room!==e.roomId||v.attention?.authority!==e.binding.authorityId))throw Error('Room attention scope is unconfirmed.');
  return page.attention;
 }
 attentionAccepted(intent,receipt){return bindingMatches(intent.route.binding,receipt.binding)&&jsonEqual(receipt.scope,intent.body.scope)&&
  receipt.attention?.id===intent.body.scope.attentionId&&receipt.attention.response_author===intent.route.binding.principalId&&jsonEqual(receipt.attention.response,intent.body.response);}
}
