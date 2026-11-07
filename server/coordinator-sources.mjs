import {createCipheriv,createDecipheriv,createHash,randomBytes} from 'node:crypto';
import {coordinatorRoomPath} from './coordinator-room-paths.mjs';
import {ensureRoomReconciliations,noAdmission} from './coordinator-room-reconciliation.mjs';
import {definitiveRoomRefusal} from './coordinator-room-errors.mjs';
const fail=(message,status=400)=>Object.assign(Error(message),{status});
const id=value=>typeof value==='string'&&/^[a-zA-Z0-9_-]{1,100}$/.test(value);
/** Human-device delegated environment credentials. Model arguments never contain
 * credentials or URLs; all egress and mutations resolve a registered source ID. */
export class CoordinatorSources {
 constructor({db,key,fetchImpl=fetch,localApi,authorize=()=>false,sourceAllowed=()=>true,clock=Date.now}){
  Object.assign(this,{db,fetchImpl,localApi,authorize,sourceAllowed,clock});this.key=createHash('sha256').update('nextcomp-coordinator-source-v1\0'+key).digest();
  ensureRoomReconciliations(db);
  db.exec(`CREATE TABLE IF NOT EXISTS coordinator_remote_commands(id TEXT PRIMARY KEY,device_id TEXT NOT NULL,source_id TEXT NOT NULL,voice_turn TEXT NOT NULL,path TEXT NOT NULL,body_hash TEXT NOT NULL,state TEXT NOT NULL,result TEXT,at INTEGER NOT NULL)`);
  db.prepare("UPDATE coordinator_remote_commands SET state='unknown' WHERE state='sending'").run();
  if(!db.prepare('PRAGMA table_info(coordinator_remote_commands)').all().some(c=>c.name==='request_id'))db.exec('ALTER TABLE coordinator_remote_commands ADD COLUMN request_id TEXT');
  db.exec(`CREATE TABLE IF NOT EXISTS coordinator_sources(device_id TEXT NOT NULL,source_id TEXT NOT NULL,name TEXT NOT NULL,base TEXT NOT NULL,sealed TEXT NOT NULL,credential_hash TEXT NOT NULL,verified_at INTEGER,problem TEXT,PRIMARY KEY(device_id,source_id));`);
 }
 seal(device,source,credential){const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',this.key,iv);cipher.setAAD(Buffer.from(JSON.stringify([device,source])));const data=Buffer.concat([cipher.update(credential,'utf8'),cipher.final()]);return Buffer.concat([iv,cipher.getAuthTag(),data]).toString('base64');}
 credential(row){const raw=Buffer.from(row.sealed,'base64'),cipher=createDecipheriv('aes-256-gcm',this.key,raw.subarray(0,12));cipher.setAAD(Buffer.from(JSON.stringify([row.device_id,row.source_id])));cipher.setAuthTag(raw.subarray(12,28));return Buffer.concat([cipher.update(raw.subarray(28)),cipher.final()]).toString('utf8');}
 list(device){return this.db.prepare('SELECT source_id AS environmentId,name,base,verified_at AS verifiedAt,problem FROM coordinator_sources WHERE device_id=? ORDER BY source_id').all(device).filter(row=>this.sourceAllowed(row.base)).map(({base,...row})=>row);}
 async register(device,sources){
  if(!Array.isArray(sources)||sources.length>12)throw fail('Choose up to twelve paired environments.');
  const seen=new Set();
  for(const s of sources){let u;try{u=new URL(s.base)}catch{throw fail('Invalid paired environment origin.')}
   if(!id(s.environmentId)||s.environmentId==='current'||seen.has(s.environmentId)||typeof s.name!=='string'||!s.name.trim()||s.name.length>100||typeof s.token!=='string'||s.token.length<12||s.token.length>4096||u.protocol!=='https:'||u.username||u.password||u.search||u.hash||!u.hostname.endsWith('.ts.net')||u.pathname!=='/')throw fail('Use the private HTTPS origin and original paired-device credential.');seen.add(s.environmentId);
  }
  sources=sources.filter(s=>this.sourceAllowed(s.base.replace(/\/$/,'')));
  // Credential replacement is atomic per source, before verification. A revoked
  // old credential is never used as a fallback for a changed pairing.
  for(const s of sources)this.db.prepare('INSERT INTO coordinator_sources VALUES(?,?,?,?,?,?,NULL,NULL) ON CONFLICT(device_id,source_id) DO UPDATE SET name=excluded.name,base=excluded.base,sealed=excluded.sealed,credential_hash=excluded.credential_hash,verified_at=NULL,problem=NULL').run(device,s.environmentId,s.name,s.base.replace(/\/$/,''),this.seal(device,s.environmentId,s.token),createHash('sha256').update(s.token).digest('hex'));
  for(const old of this.list(device))if(!seen.has(old.environmentId))this.db.prepare('DELETE FROM coordinator_sources WHERE device_id=? AND source_id=?').run(device,old.environmentId);
  let next=0;await Promise.all(Array.from({length:Math.min(3,sources.length)},async()=>{while(next<sources.length){const s=sources[next++];try{
   const status=await this.call(device,s.environmentId,'/api/status');
   if(!status.deviceId||status.deviceId==='owner')throw fail('Use the original paired-device credential.',403);
   this.db.prepare('UPDATE coordinator_sources SET verified_at=?,problem=NULL WHERE device_id=? AND source_id=?').run(this.clock(),device,s.environmentId);
  }catch{this.db.prepare('UPDATE coordinator_sources SET problem=? WHERE device_id=? AND source_id=?').run('Paired source unavailable; no work submitted.',device,s.environmentId);}}}));return {sources:this.list(device)};
 }
 async call(device,sourceId,path,body,actor,options={}){
  if(!sourceId||sourceId==='current')return this.localApi(path,body,actor,options);
  const row=this.db.prepare('SELECT * FROM coordinator_sources WHERE device_id=? AND source_id=?').get(device,sourceId);if(!row||!this.sourceAllowed(row.base))throw fail('This environment is not delegated to this human device.',403);
  // No arbitrary proxy, pairing/admin/credential endpoints or nested federation.
  const roomRead=coordinatorRoomPath(path);
  const roomMessage=/^\/api\/hollywood\/v3\/rooms\/[0-9a-f-]{36}\/messages$/.test(path);
  const read=roomRead||path==='/api/status'||/^\/api\/(?:threads(?:\?|\/[a-zA-Z0-9_-]{5,100}(?:\/(?:native-timeline|head))?(?:\?|$))|session-starts\/[a-zA-Z0-9_-]{8,120}$|projects$|models$|attention$|notifications(?:\?|$)|coordinator\/(?:evidence|voice-updates|catchup|receipt)(?:\?|$))/.test(path);
  const controlPlane=/^\/api\/coordinator\/(?:listening|voice-updates\/presented|catchup\/presented)$/.test(path);
  const write=coordinatorRoomPath(path,'POST')||controlPlane||/^\/api\/threads(?:$|\/[a-zA-Z0-9_-]{5,100}\/(?:reply|interrupt|rename|archive|unarchive|watch|settings|queue\/resume|replies\/[a-zA-Z0-9_-]{8,120})$)|^\/api\/requests\/[a-zA-Z0-9_-]+\/answer$/.test(path);
  if(typeof path!=='string'||path.includes('://')||path.includes('..')||(body===undefined?!read:!write))throw fail('Unsupported delegated coordinator operation.',403);
  if(body!==undefined&&!controlPlane&&(!actor||actor.deviceId!==device||!actor.voiceTurnId||!this.authorize(actor)))throw fail('A remote mutation needs its original human voice command.',403);
  if(path!=='/api/status'&&!row.verified_at){
   const status=await this.call(device,sourceId,'/api/status');
   if(!status.deviceId||status.deviceId==='owner')throw fail('This source requires its original paired-device credential.',403);
   this.db.prepare('UPDATE coordinator_sources SET verified_at=?,problem=NULL WHERE device_id=? AND source_id=?').run(this.clock(),device,sourceId);
  }
  let command=null;
  if(body!==undefined&&!controlPlane){
   const bodyHash=createHash('sha256').update(JSON.stringify(body)).digest('hex');
   const legacyId=createHash('sha256').update(JSON.stringify([device,sourceId,actor.voiceTurnId,actor.voiceCallId||body.id||path])).digest('hex');
   // One coordinator tool call may create a room AND submit its kickoff.
   // Preserve exact legacy receipts, but give distinct room operations distinct
   // durable identities. An existing unknown for the SAME operation still fences.
   const legacy=this.db.prepare('SELECT * FROM coordinator_remote_commands WHERE id=?').get(legacyId);
   const exactLegacy=legacy&&legacy.path===path&&legacy.body_hash===bodyHash;
   const commandId=coordinatorRoomPath(path,'POST')&&!exactLegacy?
    createHash('sha256').update(JSON.stringify(['room-command-v2',device,sourceId,actor.voiceTurnId,actor.voiceCallId||null,path,body.operationId,body.id||null])).digest('hex'):legacyId;
   const unresolved=this.db.prepare("SELECT * FROM coordinator_remote_commands WHERE device_id=? AND source_id=? AND path=? AND state IN ('sending','unknown') LIMIT 8").all(device,sourceId,path);
   for(const old of unresolved){
    if(noAdmission(this.db,{remoteId:old.id}))continue;
    let receipt=null;
    if(old.request_id)try{receipt=await this.call(device,sourceId,path==='/api/hollywood/v3/rooms'?'/api/hollywood/v3/rooms/'+encodeURIComponent(old.request_id):roomMessage?'/api/hollywood/v3/commands/'+encodeURIComponent(old.request_id):'/api/coordinator/receipt?id='+encodeURIComponent(old.request_id),undefined,actor);}catch{}
    const roomAccepted=path==='/api/hollywood/v3/rooms'&&receipt?.room?.id===old.request_id;
    const e=receipt?.event;
    const originalMessage={operationId:old.request_id,kind:e?.kind,body:e?.body,audience:e?.audience,...(e?.recipient?{recipientId:e.recipient}:{}),...(e?.source?{sourceEventId:e.source}:{})};
    const messageAccepted=roomMessage&&receipt?.accepted===true&&e?.room===path.split('/')[5]&&e.data?.authorKind==='human'&&e.data?.conversationVersion===1&&
     receipt.binding?.bindingId===actor?.hollywoodBinding&&createHash('sha256').update(JSON.stringify(originalMessage)).digest('hex')===old.body_hash;
    if(roomAccepted||messageAccepted||receipt?.id===old.request_id&&['queued','sent','steered','accepted','cancelled','failed'].includes(receipt.state)){
     this.db.prepare("UPDATE coordinator_remote_commands SET state='responded',result=? WHERE id=?").run(JSON.stringify(receipt),old.id);
     if(old.id===commandId)return {...receipt,reconciledOriginal:true,notSubmittedNewCommand:true};
    }else throw fail('Original remote delivery is uncertain. Read its original source receipt; no further command was sent.',409);
   }
   const previous=this.db.prepare('SELECT * FROM coordinator_remote_commands WHERE id=?').get(commandId);
   if(previous){
    if(previous.body_hash!==bodyHash||previous.path!==path)throw fail('Original delegated command identity conflicts; nothing was resent.',409);
    const disposition=noAdmission(this.db,{remoteId:previous.id});
    if(disposition)throw Object.assign(fail('Original gateway rejected this operation before canonical admission.',404),{code:'HOLLYWOOD_REQUEST_REJECTED',admission:'no_admission',evidence:disposition});
    if(previous.state==='responded')return JSON.parse(previous.result);
    if(previous.state==='rejected'){const value=JSON.parse(previous.result);throw Object.assign(fail(value.error,value.status),{code:value.code,admission:'no_admission',evidence:value});}
    throw fail('Original remote delivery is uncertain. Inspect its source receipt before any further send; do not repeat this operation.',409);
   }
   this.db.prepare("INSERT INTO coordinator_remote_commands(id,device_id,source_id,voice_turn,path,body_hash,state,result,at,request_id) VALUES(?,?,?,?,?,?,'sending',NULL,?,?)").run(commandId,device,sourceId,actor.voiceTurnId,path,bodyHash,this.clock(),roomMessage?body.operationId:typeof body.id==='string'?body.id:null);command=commandId;
  }
  let observedHttp=null;
  try{
  const response=await this.fetchImpl(row.base+path,{redirect:'error',headers:{Authorization:'Bearer '+this.credential(row),'Content-Type':'application/json',...(actor?.hollywoodBinding?{'X-Hollywood-Binding':actor.hollywoodBinding}:{}),'X-NextComp-Coordinator-Hop':'1',...(body===undefined||controlPlane?{}:{'X-NextComp-Coordinator-Turn':actor.voiceTurnId,'X-NextComp-Coordinator-Origin':createHash('sha256').update(this.key).digest('hex').slice(0,24)})},...(body===undefined?{}:{method:'POST',body:JSON.stringify(body)}),signal:options.signal?AbortSignal.any([AbortSignal.timeout(body===undefined||controlPlane?2500:15000),options.signal]):AbortSignal.timeout(body===undefined||controlPlane?2500:15000)});
  let value;try{value=await response.json()}catch{throw fail('The delegated source returned an invalid response.',502)}
  if(!response.ok){
   observedHttp={status:response.status,code:typeof value.code==='string'?value.code.slice(0,100):null,error:typeof value.error==='string'?value.error.slice(0,500):'Delegated source refused the request.'};
   const error=Object.assign(fail(observedHttp.error,response.status),{code:observedHttp.code,evidence:observedHttp});
   // Typed authentication/admission refusal is not unknown command delivery.
   // Other failures stay uncertain and are never automatically repeated.
   if(command&&typeof value.error==='string'&&definitiveRoomRefusal(error)){
    this.db.prepare("UPDATE coordinator_remote_commands SET state='rejected',result=? WHERE id=?").run(JSON.stringify({...observedHttp,admission:'no_admission'}),command);
    error.definitiveRejection=true;
    error.admission='no_admission';
   }
   throw error;
  }
  if(command)this.db.prepare("UPDATE coordinator_remote_commands SET state='responded',result=? WHERE id=?").run(JSON.stringify(value),command);
  return value;
  }catch(error){
   if(error.definitiveRejection)throw error;
   if(command){this.db.prepare("UPDATE coordinator_remote_commands SET state='unknown',result=? WHERE id=?").run(observedHttp?JSON.stringify(observedHttp):null,command);throw Object.assign(fail('Original remote admission is inaccessible or unconfirmed. Inspect its exact receipt; do not resend or ask for duplicate-risk consent before admission is known.',409),{code:'REMOTE_DELIVERY_UNKNOWN',admission:'inaccessible_unknown',evidence:observedHttp});}
   throw error;
  }
 }
 async discover(device,query,local){
  const entries=this.list(device),results=[{environmentId:'current',name:'Current environment',...await local()}];
  let next=0;await Promise.all(Array.from({length:Math.min(3,entries.length)},async()=>{while(next<entries.length){const s=entries[next++];try{
   const value=await this.call(device,s.environmentId,'/api/coordinator/evidence?query='+encodeURIComponent(query||''));results.push({...s,...value});
  }catch{results.push({...s,availability:'unavailable',sessions:[],catalog:[],error:'Public evidence unavailable; do not guess a target.'});}}}));
  return {sources:results,sessions:results.flatMap(s=>(s.sessions||[]).map(t=>({...t,environmentId:s.environmentId,environmentName:s.name}))),catalog:results.flatMap(s=>(s.catalog||[]).slice(0,8).map(t=>({...t,environmentId:s.environmentId,environmentName:s.name}))),scope:{complete:false,kind:'device-delegated-environments'}};
 }
}
