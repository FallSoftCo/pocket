import {createHash,randomUUID} from 'node:crypto';
import {mkdirSync} from 'node:fs';
import {open,rename,readFile,stat,statfs} from 'node:fs/promises';
import {join} from 'node:path';
import {validateVoiceAudio} from './voice-controller.mjs';

const fail=(message,status=400)=>Object.assign(Error(message),{status});
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const canonical=value=>JSON.stringify(value,(_key,v)=>v&&typeof v==='object'&&!Array.isArray(v)?Object.fromEntries(Object.keys(v).sort().map(k=>[k,v[k]])):v);
const phases={receiving:'uploading',sealing:'uploading',ready:'queued',transcribing:'transcribing',transcribed:'transcribing',dispatching:'submitting',accepted:'thinking',unknown:'unknown',failed:'failed',cancelled:'cancelled',blocked:'blocked'};
const token=v=>typeof v==='string'&&/^[a-zA-Z0-9_-]{8,100}$/.test(v);

function admissionHash(device,{id,destination,audioSha256,bytes},principal){
 if(!token(id)||!token(device)||!/^[a-f0-9]{64}$/.test(audioSha256)||!Number.isSafeInteger(bytes)||bytes<3244||bytes>3840044||!destination||typeof destination!=='object'||Array.isArray(destination))
  throw fail('Invalid recorded turn identity, destination, digest or size.');
 if(!principal||principal.deviceId!==device||!['paired-device','voice'].includes(principal.kind))
  throw fail('A recording requires its original authenticated human device.',403);
 return hash(canonical({destination,audioSha256,bytes}));
}

/** Durable authenticated audio admission. Media processing cannot dispatch tasks. */
export class VoiceRecordings {
 constructor({db,root,resolveDestination,transcribe,submit,getReceipt,authorize,cancelTask=null,minFreeBytes=80*1024**3}){
  Object.assign(this,{db,root,resolveDestination,transcribe,submit,getReceipt,authorize,cancelTask,minFreeBytes});
  if([resolveDestination,transcribe,submit,getReceipt,authorize].some(value=>typeof value!=='function'))throw Error('Voice admission requires all authority and execution adapters');
  this.owner=randomUUID();this.cancels=new Map();
  mkdirSync(root,{recursive:true,mode:0o700});this.uploads=new Map();this.running=new Map();this.enabled=false;
  db.exec(`CREATE TABLE IF NOT EXISTS voice_recordings(
   device TEXT NOT NULL,id TEXT NOT NULL,request_hash TEXT NOT NULL,destination TEXT NOT NULL,
   principal TEXT NOT NULL,audio_sha256 TEXT NOT NULL,bytes INTEGER NOT NULL,phase TEXT NOT NULL,
   transcript TEXT,proof TEXT,result TEXT,error TEXT,worker TEXT,created_at INTEGER NOT NULL,
   PRIMARY KEY(device,id));
   CREATE INDEX IF NOT EXISTS voice_recordings_ready ON voice_recordings(phase,created_at);`);
 }
 key(device,id){return hash(JSON.stringify([device,id]));}
 path(row){return join(this.root,this.key(row.device,row.id)+'.wav');}
 row(device,id){return this.db.prepare('SELECT * FROM voice_recordings WHERE device=? AND id=?').get(device,id);}
 projection(row){
  if(!row)return null;
  const result=row.result?JSON.parse(row.result):null;
  return {...(result||{}),id:row.id,phase:row.phase,state:result?.state||phases[row.phase]||'unknown',
   audioSha256:row.audio_sha256,bytes:row.bytes,canRetryAudio:row.phase==='failed'&&!row.result,transcript:result?.transcript||row.transcript||'',
   response:result?.response||'',actions:result?.actions||[],error:row.error||result?.error||null};
 }
 async create(device,{id,destination,audioSha256,bytes},principal){
  const requestHash=admissionHash(device,{id,destination,audioSha256,bytes},principal);
  const old=this.row(device,id);
  if(old){if(old.request_hash!==requestHash)throw fail('This turn ID belongs to another recording or destination.',409);return this.projection(old);}
  if(this.db.prepare("SELECT count(*) AS n FROM voice_recordings WHERE device=? AND phase NOT IN ('accepted','failed','cancelled')").get(device).n>=64)
   throw fail('This environment has too many unsettled recordings. New audio remains saved on the device.',429);
  const resolved=await this.resolveDestination(device,destination);
  if(await this.getReceipt(device,id))throw fail('This ID already belongs to existing work. Inspect its original receipt.',409);
  // Validation awaits above can interleave with the same request; SQLite owns final admission.
  const values=[device,id,requestHash,JSON.stringify(resolved),JSON.stringify(principal),audioSha256,bytes,Date.now()];
  this.db.prepare("INSERT OR IGNORE INTO voice_recordings(device,id,request_hash,destination,principal,audio_sha256,bytes,phase,created_at) VALUES(?,?,?,?,?,?,?,'receiving',?)").run(...values);
  const saved=this.row(device,id);
  if(saved.request_hash!==requestHash)throw fail('This turn ID was admitted with a different payload.',409);
  return this.projection(saved);
 }
 async upload(device,id,audio){
  const row=this.row(device,id);if(!row)throw fail('Create the original recording admission first.',404);
  validateVoiceAudio(audio);
  if(audio.length!==row.bytes||hash(audio)!==row.audio_sha256)throw fail('Uploaded audio does not match the admitted recording.',409);
  const key=this.key(device,id);
  if(this.uploads.has(key))return this.uploads.get(key);
  if(row.phase!=='receiving'&&row.phase!=='sealing')return this.projection(row);
  const operation=this.seal(row,audio).finally(()=>this.uploads.delete(key));
  this.uploads.set(key,operation);return operation;
 }
 async seal(row,audio){
  const space=await statfs(this.root);
  if(space.bavail*space.bsize<this.minFreeBytes+audio.length+16*1024**2)throw fail('Server storage reserve prevents accepting audio. It remains saved on the device.',507);
  this.db.prepare("UPDATE voice_recordings SET phase='sealing' WHERE device=? AND id=? AND phase='receiving'").run(row.device,row.id);
  const current=this.row(row.device,row.id);
  if(current.phase!=='sealing')return this.projection(current);
  if(await this.recoverFile(current)){this.kick();return this.projection(this.row(row.device,row.id));}
  // Every retry writes a new private temporary. Concurrent identical uploads may
  // publish the same immutable bytes; neither can replace them with other content.
  const target=this.path(row),temp=target+'.'+randomUUID()+'.preparing';
  const file=await open(temp,'wx',0o600);
  try{await file.writeFile(audio);await file.sync();}finally{await file.close();}
  await rename(temp,target);
  const directory=await open(this.root,'r');try{await directory.sync();}finally{await directory.close();}
  this.db.prepare("UPDATE voice_recordings SET phase='ready',error=NULL WHERE device=? AND id=? AND phase='sealing'").run(row.device,row.id);
  const result=this.projection(this.row(row.device,row.id));this.kick();return result;
 }
 async recoverFile(row){
  if(row.phase!=='sealing')return false;
  const file=this.path(row);
  try{
   const info=await stat(file);if(info.size!==row.bytes)return false;
   const audio=await readFile(file);validateVoiceAudio(audio);if(hash(audio)!==row.audio_sha256)return false;
  }catch(e){if(e.code==='ENOENT'||e.status)return false;throw e;}
  const directory=await open(this.root,'r');try{await directory.sync();}finally{await directory.close();}
  this.db.prepare("UPDATE voice_recordings SET phase='ready',error=NULL WHERE device=? AND id=? AND phase='sealing'").run(row.device,row.id);
  return true;
 }
 async start(){
  if(this.enabled)return;
  this.db.prepare("UPDATE voice_recordings SET phase='unknown',error='Server restarted after task dispatch began; original receipt required' WHERE phase='dispatching'").run();
  // Transcription has no task authority. A restarted audio-only attempt may be reconstructed from the sealed file.
  this.db.prepare("UPDATE voice_recordings SET phase='ready',worker=NULL WHERE phase='transcribing'").run();
  this.db.prepare("UPDATE voice_recordings SET worker=? WHERE phase='transcribed'").run(this.owner);
  for(const row of this.db.prepare("SELECT * FROM voice_recordings WHERE phase='sealing'").all())await this.recoverFile(row);
  this.enabled=true;this.kick();
 }
 kick(){
  if(!this.enabled||this.running.size)return;
  const row=this.db.prepare("SELECT * FROM voice_recordings WHERE phase IN ('ready','transcribed') ORDER BY created_at,id LIMIT 1").get();
  if(!row)return;
  const key=this.key(row.device,row.id),job=this.work(row).finally(()=>{this.running.delete(key);this.cancels.delete(key);this.kick();});
  this.running.set(key,job);void job.catch(()=>{});
 }
 async work(original){
  let row=this.row(original.device,original.id);
  try{
   if(row.phase==='ready'){
    const changed=this.db.prepare("UPDATE voice_recordings SET phase='transcribing',worker=? WHERE device=? AND id=? AND phase='ready'").run(this.owner,row.device,row.id);
    if(!changed.changes)return;
    await this.authorize(row.device,JSON.parse(row.principal),JSON.parse(row.destination));
    const audio=await readFile(this.path(row));validateVoiceAudio(audio);
    if(hash(audio)!==row.audio_sha256)throw fail('Saved server audio failed integrity verification.',409);
    const cancellation=new AbortController();this.cancels.set(this.key(row.device,row.id),cancellation);
    const result=await this.transcribe({device:row.device,id:row.id,file:this.path(row),sha256:row.audio_sha256,signal:cancellation.signal});
    if(typeof result.text!=='string'||!result.text.trim()||result.text.length>24000||result.proof?.confirmed!==true||result.proof.version!==1||result.proof.boundary!=='session.closed'||!['client_request','close_requested'].includes(result.proof.reason)||result.proof.audioSha256!==row.audio_sha256)
     throw fail('Audio did not return a confirmed, source-bound transcript. Recording retained.',503);
    const committed=this.db.prepare("UPDATE voice_recordings SET phase='transcribed',transcript=?,proof=? WHERE device=? AND id=? AND phase='transcribing' AND worker=?")
     .run(result.text,JSON.stringify({...result.proof,mediaCacheHit:result.mediaCacheHit===true}),row.device,row.id,this.owner);
    if(!committed.changes)return;
    row=this.row(row.device,row.id);
   }
   if(row.phase!=='transcribed')return;
   await this.authorize(row.device,JSON.parse(row.principal),JSON.parse(row.destination));
   const changed=this.db.prepare("UPDATE voice_recordings SET phase='dispatching',worker=? WHERE device=? AND id=? AND phase='transcribed'").run(this.owner,row.device,row.id);
   if(!changed.changes)return;
   const receipt=await this.submit(row.device,row.id,row.transcript,JSON.parse(row.destination),JSON.parse(row.principal));
   if(receipt?.id!==row.id)throw fail('Destination did not confirm the original task identity.',502);
   this.db.prepare("UPDATE voice_recordings SET phase='accepted',result=?,error=NULL WHERE device=? AND id=? AND worker=?").run(JSON.stringify(receipt),row.device,row.id,this.owner);
  }catch(error){
   const current=this.row(row.device,row.id);
   if(current.phase==='cancelled')return;
   this.db.prepare('UPDATE voice_recordings SET phase=?,error=? WHERE device=? AND id=? AND worker=?')
    .run(current.phase==='dispatching'&&error.notAdmitted!==true?'unknown':error.status===403?'blocked':'failed',String(error.message).slice(0,500),row.device,row.id,this.owner);
  }
 }
 async inspect(device,id){
  let row=this.row(device,id);if(!row)return null;
  if(['accepted','unknown','dispatching'].includes(row.phase)){
   const receipt=await this.getReceipt(device,id);
   if(receipt){
    if(receipt.id!==id)throw fail('Original receipt identity changed.',502);
    this.db.prepare("UPDATE voice_recordings SET phase='accepted',result=?,error=NULL WHERE device=? AND id=?").run(JSON.stringify(receipt),device,id);
    row=this.row(device,id);
   }
  }
  return this.projection(row);
 }
 async cancelRequest(device,id){
  const row=this.row(device,id);if(!row||row.phase!=='accepted'||!this.cancelTask)throw fail('Use the original work controls for this request.',409);
  const result=await this.cancelTask(device,id);
  if(result?.id!==id||result.state!=='cancelled')throw fail('Original request cancellation was not confirmed.',409);
  this.db.prepare("UPDATE voice_recordings SET result=?,error=NULL WHERE device=? AND id=? AND phase='accepted'").run(JSON.stringify(result),device,id);
  return this.projection(this.row(device,id));
 }
 async retryAudio(device,id){
  const row=this.row(device,id);if(!row)throw fail('Original recording not found.',404);
  // Task failures, unknown dispatch and revoked authority are not audio retries.
  if(row.phase!=='failed'||row.result)throw fail('Only a confirmed pre-dispatch audio failure may be retried. Inspect the original work receipt.',409);
  await this.authorize(device,JSON.parse(row.principal),JSON.parse(row.destination));
  const audio=await readFile(this.path(row));validateVoiceAudio(audio);
  if(audio.length!==row.bytes||hash(audio)!==row.audio_sha256)throw fail('Saved audio failed verification. The request was not sent.',409);
  const changed=this.db.prepare("UPDATE voice_recordings SET phase=?,error=NULL,worker=? WHERE device=? AND id=? AND phase='failed' AND result IS NULL")
   .run(row.proof&&row.transcript?'transcribed':'ready',this.owner,device,id);
  if(!changed.changes)throw fail('Recording state changed. Inspect its original receipt.',409);
  this.kick();return this.projection(this.row(device,id));
 }
 async cancel(device,id,metadata=null,principal=null){
  let row=this.row(device,id);
  const fingerprint=metadata?.audioSha256?admissionHash(device,{...metadata,id},principal):null;
  if(!row){
   if(!fingerprint)throw fail('Original recording admission not found. Preserve its identity before cancelling.',404);
   if(await this.getReceipt(device,id))throw fail('This ID already belongs to work. Use its original controls.',409);
   // Cancellation races admission through the same unique key. No destination
   // resolution, transcription or task dispatch is needed to reject future upload.
   this.db.prepare("INSERT OR IGNORE INTO voice_recordings(device,id,request_hash,destination,principal,audio_sha256,bytes,phase,created_at) VALUES(?,?,?,?,?,?,?,'cancelled',?)")
    .run(device,id,fingerprint,JSON.stringify(metadata.destination),JSON.stringify(principal),metadata.audioSha256,metadata.bytes,Date.now());
   row=this.row(device,id);
  }
  if(fingerprint&&row.request_hash!==fingerprint)throw fail('Cancellation belongs to another recording or destination.',409);
  if(row.phase==='cancelled')return this.projection(row);
  const changed=this.db.prepare("UPDATE voice_recordings SET phase='cancelled',error=NULL WHERE device=? AND id=? AND phase IN ('receiving','sealing','ready','transcribing','transcribed','failed','blocked')").run(device,id);
  if(!changed.changes)throw fail('This recording already reached task dispatch. Use the original work controls.',409);
  this.cancels.get(this.key(device,id))?.abort();
  return this.projection(this.row(device,id));
 }
 async stop(){this.enabled=false;await Promise.allSettled(this.running.values());}
}
