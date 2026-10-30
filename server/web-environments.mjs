import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
const execute=promisify(execFile);
import {readFileSync,statSync} from 'node:fs';
const fail=(text,status=409)=>Object.assign(Error(text),{status});
const idPattern=/^[a-z][a-z0-9-]{1,30}$/;
export const scopedId=(environment,id)=>'env_'+environment+'__'+Buffer.from(String(id)).toString('base64url');
export function splitScopedId(id){
 const m=/^env_([a-z][a-z0-9-]{1,30})__([A-Za-z0-9_-]+)$/.exec(String(id));if(!m)return null;
 const raw=Buffer.from(m[2],'base64url').toString('utf8');
 if(!raw||Buffer.from(raw).toString('base64url')!==m[2]||raw.length>512)throw fail('Invalid environment identity.',400);
 return {environmentId:m[1],id:raw};
}
export function qualifyPayload(value,environment,key=''){
 if(Array.isArray(value))return value.map(v=>qualifyPayload(v,environment,key));
 if(!value||typeof value!=='object')return value;
 const result={};for(const [k,v]of Object.entries(value)){
  if(['text','content','arguments','output','detail','command','response','result'].includes(k)){result[k]=v;continue;}
  if(['threadId','thread_id','parentThreadId','parent_thread_id','forkedFromId','forked_from_id','selectedThreadId'].includes(k)&&typeof v==='string'&&v)result[k]=scopedId(environment,v);
  else if(['url','href','apkUrl'].includes(k)&&typeof v==='string'&&v.startsWith('/api/'))result[k]='/api/environments/'+environment+v.slice(4);
  else result[k]=qualifyPayload(v,environment,k);
 }
 if(value.id!=null&&(key==='thread'||key==='threads'||key==='notification'||key==='notifications'||value.method&&value.params))result.id=scopedId(environment,value.id);
 return result;
}
export function unqualifyPayload(value,environment){
 if(Array.isArray(value))return value.map(v=>unqualifyPayload(v,environment));
 if(!value||typeof value!=='object')return value;
 return Object.fromEntries(Object.entries(value).map(([k,v])=>{
  if(['id','threadId','thread_id','parentThreadId','parent_thread_id','requestId'].includes(k)&&typeof v==='string'){
   const scope=splitScopedId(v);if(scope){if(scope.environmentId!==environment)throw fail('This action references a different environment.');return [k,scope.id];}
  }
  return [k,unqualifyPayload(v,environment)];
 }));
}
export function environmentRoute(path){
 const url=new URL(path,'http://gateway.invalid');let environmentId=null;
 const explicit=/^\/api\/environments\/([a-z][a-z0-9-]{1,30})(\/.*)?$/.exec(url.pathname);
 if(explicit){environmentId=explicit[1];url.pathname='/api'+(explicit[2]||'');}
 else{
  const match=/^\/api\/(threads|requests|notifications|files)\/([^/]+)(.*)$/.exec(url.pathname);
  if(match){const scope=splitScopedId(decodeURIComponent(match[2]));if(scope){environmentId=scope.environmentId;url.pathname='/api/'+match[1]+'/'+encodeURIComponent(scope.id)+match[3];}}
 }
 return environmentId?{environmentId,path:url.pathname+url.search}:null;
}
export class WebEnvironments {
 constructor({profiles={},fetchImpl=fetch,secure=true,fleet=null}){this.fleet=fleet;this.profiles=profiles;this.fetch=fetchImpl;this.secure=secure;this.lastCatalog=new Map();this.catalogFlights=new Map();this.verified=new Map();this.presentation=new CatalogPresentation();this.provisioning=new Map();this.provisionRetry=new Map();}
 static fromFile(file,options={}){
  const result=new WebEnvironments(options);let stamp;
  result.refresh=()=>{
   const st=statSync(file);if(st.mode&0o077)throw Error('Environment credential file must be private.');
   const next=st.ino+':'+st.mtimeMs+':'+st.size;if(next===stamp)return;
   const config=JSON.parse(readFileSync(file,'utf8'));if(config.version!==1||!config.profiles||typeof config.profiles!=='object')throw Error('Invalid environment profiles.');
   result.profiles=config.profiles;result.provisionCommand=config.provisionCommand;result.verified.clear();stamp=next;
  };result.refresh();return result;
 }
 async ensure(device,token){
  this.fleet?.observe(device,token);
  this.refresh?.();if(device==='owner'||!device)throw fail('Use the original paired browser.',403);
  if(this.profiles[device]||!this.provisionCommand)return;
  if((this.provisionRetry.get(device)||0)>Date.now())return;
  if(!this.provisioning.has(device)){
   if(this.provisioning.size>=2)throw fail('Environment connections are being prepared.',503);
   const command=this.provisionCommand;if(!Array.isArray(command)||!command.length||command.some(x=>typeof x!=='string'))throw Error('Invalid environment provisioning command.');
   const work=execute(command[0],[...command.slice(1),device],{timeout:10000,maxBuffer:16384}).then(()=>this.refresh?.()).catch(()=>{this.provisionRetry.set(device,Date.now()+60000);throw fail('Environment connection is being prepared. Existing pairings are unchanged.',503);});
   this.provisioning.set(device,work);work.finally(()=>this.provisioning.delete(device)).catch(()=>{});
  }
  return this.provisioning.get(device);
 }
 list(device){
  this.refresh?.();const saved=this.profiles[device]||[];
  if(!Array.isArray(saved))throw Error('Invalid environment roster.');
  const imported=this.fleet?.list(device)||[];
  const roster=[...saved,...imported.filter(source=>!saved.some(p=>p.id===source.id||p.base===source.base))];
  if(!Array.isArray(roster)||roster.length>128||new Set(roster.map(s=>s.id)).size!==roster.length)throw Error('Invalid environment roster.');
  return roster.map(source=>{
   const url=new URL(source.base);
   if(!idPattern.test(source.id)||source.id==='current'||typeof source.name!=='string'||!source.name||typeof source.token!=='string'||source.token.length<12||url.username||url.password||url.pathname!=='/'||url.search||url.hash||
    (this.secure&&(url.protocol!=='https:'||!url.hostname.endsWith('.ts.net'))))throw Error('Invalid private environment profile.');
   return source;
  });
 }
 source(device,id){const source=this.list(device).find(s=>s.id===id);if(!source)throw fail('This environment is not connected to this browser.',403);if(source.retired)throw fail('This environment is no longer enrolled in the fleet.',409);return source;}
 async forward(device,environment,path,method='GET',body,headers={}){
  const source=this.source(device,environment);
  if(!path.startsWith('/api/')||path.includes('://')||path.includes('..')||/^\/api\/(pair(?:ing)?|devices|notify)(?:\/|\?|$)/.test(path))throw fail('This environment route is unavailable.',403);
  const key=device+'/'+environment;
  if((this.verified.get(key)||0)<Date.now()){
   const check=await this.fetch(new URL('/api/status',source.base),{redirect:'error',headers:{Authorization:'Bearer '+source.token},signal:AbortSignal.timeout(2500)});
   const identity=check.ok?await check.json():null;
   if(!identity?.deviceId||identity.deviceId==='owner'||source.deviceId&&identity.deviceId!==source.deviceId)throw fail('The original paired environment credential is unavailable.',403);
   this.verified.set(key,Date.now()+15000);
  }
  const response=await this.fetch(new URL(path,source.base),{method,redirect:'error',headers:{Authorization:'Bearer '+source.token,'Content-Type':Buffer.isBuffer(body)?'audio/wav':'application/json',...headers},...(body===undefined?{}:{body:Buffer.isBuffer(body)?body:JSON.stringify(unqualifyPayload(body,environment))}),signal:AbortSignal.timeout(method==='GET'?8000:45000)});
  return response;
 }
 async catalog(device,path){
  const sources=this.list(device),environments=[],rows=[];let next=0;
  await Promise.all(Array.from({length:Math.min(3,sources.length)},async()=>{while(next<sources.length){
   const source=sources[next++],key=device+'/'+source.id+'/'+path;
   try{
    let work=this.catalogFlights.get(key);
    if(!work){work=(async()=>{const response=await this.forward(device,source.id,path);if(!response.ok)throw Error('Remote catalog unavailable');const data=await response.json();if(!Array.isArray(data.threads))throw Error('Invalid remote catalog');await this.presentation.annotate(data.threads,{key:device+'/'+source.id,read:async id=>{const response=await this.forward(device,source.id,'/api/threads/'+encodeURIComponent(id)+'/native-timeline?observeOnly=1');if(!response.ok)throw Error('Observation unavailable');return response.json();}});return data;})();this.catalogFlights.set(key,work);work.finally(()=>this.catalogFlights.delete(key)).catch(()=>{});}
    const data=await work;this.lastCatalog.set(key,data.threads);while(this.lastCatalog.size>32)this.lastCatalog.delete(this.lastCatalog.keys().next().value);environments.push({id:source.id,name:source.name,available:true,partial:!!data.nextCursor});
    for(const thread of data.threads)rows.push({...qualifyPayload(thread,source.id,'thread'),environmentId:source.id,environmentName:source.name,environmentUnavailable:false});
   }catch{
    environments.push({id:source.id,name:source.name,available:false});
    for(const thread of this.lastCatalog.get(key)||[])rows.push({...qualifyPayload(thread,source.id,'thread'),environmentId:source.id,environmentName:source.name,environmentUnavailable:true});
   }
  }}));
  return {threads:rows,environments};
 }
}
export class CatalogPresentation {
 constructor(){this.temporary=new Set();this.cache=new Map();this.pending=new Map();this.queue=[];this.running=0;}
 pump(){while(this.running<2&&this.queue.length){const task=this.queue.shift();this.running++;task().finally(()=>{this.running--;this.pump()}).catch(()=>{});}}
 async annotate(threads,{key='current',read,metadata}={}){
  let budget=4;const waits=[];
  for(const thread of threads){
   thread.catalogEmpty=false;thread.displayName=null;thread.ephemeral=thread.ephemeral===true||this.temporary.has(key+'/'+thread.id);
   if(thread.ephemeral||thread.preview?.trim()||! /^(?:untitled(?: task| work)?|unnamed(?: session)?|new task)?$/i.test(String(thread.name||'').trim())||['active','pending'].includes(thread.status?.type))continue;
   const id=key+'/'+thread.id,signature=JSON.stringify([thread.recencyAt,thread.activityAt,thread.status?.type,thread.name,thread.preview]);
   const old=this.cache.get(id);
   if(old&&old.signature===signature&&Date.now()-old.at<60000){Object.assign(thread,old.fields);continue;}
   let pending=this.pending.get(id);
   if(pending&&pending.signature!==signature)continue;
   if(!pending){
    if(budget<=0||this.pending.size>=4)continue;budget--;
    let resolve;const promise=new Promise(r=>resolve=r);pending={signature,promise};this.pending.set(id,pending);
    this.queue.push(async()=>{
     const fields={};
     try{
      const info=metadata?await metadata(thread.id):null;
      if(info?.ephemeral===true){fields.ephemeral=true;this.temporary.add(id);while(this.temporary.size>512)this.temporary.delete(this.temporary.values().next().value);}
      else{
       const page=await read(thread.id);
       if(page?.thread?.id===thread.id&&Array.isArray(page.timeline?.rows)){
        fields.ephemeral=page.thread.ephemeral===true;
        const hasWork=(page.outgoing||[]).some(o=>['sending','queued','held','unknown'].includes(o.state))||(page.pending||[]).length>0;
        if(!page.timeline.rows.length&&page.timeline.hasEarlier===false&&Array.isArray(page.pending)&&Array.isArray(page.outgoing)&&!['active','pending'].includes(page.thread.status?.type)&&!hasWork)fields.catalogEmpty=true;
        else{const row=page.timeline.rows.findLast(r=>r.type==='userMessage'&&r.text?.trim());if(row)fields.displayName=row.text.replace(/\s+/g,' ').trim().slice(0,90);}
       }
      }
     }catch{/* Unknown is never classified as empty. */}
     this.cache.set(id,{signature,fields,at:Date.now()});while(this.cache.size>512)this.cache.delete(this.cache.keys().next().value);
     this.pending.delete(id);resolve(fields);
    });this.pump();
   }
   waits.push(pending.promise.then(fields=>Object.assign(thread,fields)));
  }
  let timer;try{await Promise.race([Promise.all(waits),new Promise(r=>timer=setTimeout(r,350))]);}finally{clearTimeout(timer);}
  return threads;
 }
}
