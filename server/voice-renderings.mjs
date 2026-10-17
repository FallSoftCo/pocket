import {createHash,randomUUID} from 'node:crypto';
import {mkdir,readFile,rename,open,rm,statfs} from 'node:fs/promises';
import {join} from 'node:path';
const hash=v=>createHash('sha256').update(v).digest('hex');
const fail=(message,status=400)=>Object.assign(Error(message),{status});
const token=v=>typeof v==='string'&&/^[a-zA-Z0-9_-]{8,100}$/.test(v);
export const SPEECH_POLICY='native-account-file-v1';

/** Audio assets, never task input. An attempt ID deduplicates lost POST acknowledgements. */
export class VoiceRenderings {
 constructor({db,root,render,minFreeBytes=80*1024**3}){
  Object.assign(this,{db,root,render,minFreeBytes});this.jobs=new Map();this.waiting=[];this.active=null;
  db.exec(`CREATE TABLE IF NOT EXISTS voice_renderings(device TEXT,id TEXT,digest TEXT,text TEXT,attempt TEXT,
   phase TEXT,error TEXT,sha256 TEXT,bytes INTEGER,duration_ms INTEGER,proof TEXT,updated_at INTEGER,
   PRIMARY KEY(device,id));
   CREATE TABLE IF NOT EXISTS voice_rendering_attempts(device TEXT,id TEXT,attempt TEXT,phase TEXT,proof TEXT,error TEXT,started_at INTEGER,finished_at INTEGER,PRIMARY KEY(device,id,attempt));
   CREATE INDEX IF NOT EXISTS voice_rendering_cache ON voice_renderings(device,phase,updated_at);`);
  // Audio-only recovery waits for an explicit listening request, not background model wakeups.
  db.prepare("UPDATE voice_renderings SET phase='interrupted',error='Audio generation was interrupted; text is retained' WHERE phase IN ('queued','rendering')").run();
 }
 row(device,id){return this.db.prepare('SELECT * FROM voice_renderings WHERE device=? AND id=?').get(device,id);}
 key(device,id){return hash(JSON.stringify([device,id]));}
 jobKey(device,id,attempt){return this.key(device,id)+':'+attempt;}
 path(row){return join(this.root,this.key(row.device,row.id)+'.wav');}
 projection(row,cacheHit=false){return {id:row.id,attemptId:row.attempt,phase:row.phase,error:row.error||null,
  audioSha256:row.sha256||null,bytes:row.bytes||0,durationMs:row.duration_ms||null,cacheHit,
  ...(row.phase==='completed'?{audioUrl:'/api/voice/renderings/'+row.id+'/audio'}:{})};}
 async create(device,id,{text,attemptId}){
  if(!token(id)||!token(attemptId)||typeof text!=='string'||!text.trim()||text.length>2000||Buffer.byteLength(text)>6000)
   throw fail('Choose a bounded speech passage and stable playback identity.');
  const digest=hash(SPEECH_POLICY+':'+text),old=this.row(device,id);
  if(old&&old.digest!==digest)throw fail('This audio ID belongs to another passage.',409);
  if(id!==digest)throw fail('Speech policy or passage identity changed. Refresh before listening.',409);
  if(old?.phase==='completed')return this.projection(old,true);
  if(old&&(old.attempt===attemptId||['queued','rendering'].includes(old.phase)))return this.projection(old);
  if(this.waiting.length>=32)throw fail('Speech is busy. Text remains available.',429);
  await mkdir(this.root,{recursive:true,mode:0o700});await this.prune(device,id);const space=await statfs(this.root);
  if(space.bavail*space.bsize<this.minFreeBytes+32*1024**2)throw fail('Storage reserve prevents generating speech. Text remains available.',507);
  this.db.prepare(`INSERT INTO voice_renderings(device,id,digest,text,attempt,phase,updated_at) VALUES(?,?,?,?,?,'queued',?)
   ON CONFLICT(device,id) DO UPDATE SET attempt=excluded.attempt,phase='queued',error=NULL,sha256=NULL,bytes=NULL,duration_ms=NULL,proof=NULL,updated_at=excluded.updated_at
   WHERE voice_renderings.phase NOT IN ('queued','rendering','completed')`).run(device,id,digest,text,attemptId,Date.now());
  const row=this.row(device,id),key=this.jobKey(device,id,this.row(device,id).attempt);
  if(!this.jobs.has(key)&&row.phase==='queued'){
   const control=new AbortController();this.jobs.set(key,{control,attempt:row.attempt});this.waiting.push({device,id,attempt:row.attempt});this.kick();
  }
  return this.projection(this.row(device,id));
 }
 kick(){
  if(this.active||!this.waiting.length)return;
  const request=this.waiting.shift(),key=this.jobKey(request.device,request.id,request.attempt),job=this.jobs.get(key);
  if(!job){this.kick();return;}
  this.active=key;
  void this.work(request,job).finally(()=>{this.jobs.delete(key);this.active=null;this.kick();}).catch(()=>{});
 }
 async work(request,job){
  const {device,id,attempt}=request,row=this.row(device,id),temp=join(this.root,this.key(device,id)+'.'+randomUUID()+'.preparing.wav');
  try{
   if(!this.db.prepare("UPDATE voice_renderings SET phase='rendering' WHERE device=? AND id=? AND attempt=? AND phase='queued'").run(device,id,attempt).changes)return;
   this.db.prepare("INSERT OR IGNORE INTO voice_rendering_attempts(device,id,attempt,phase,started_at) VALUES(?,?,?,'rendering',?)").run(device,id,attempt,Date.now());
   const result=await this.render({device,id,text:row.text,file:temp,signal:job.control.signal});
   const audio=await readFile(temp);
   if(result.sha256!==hash(audio)||result.proof?.textSha256!==hash(row.text)||result.proof?.transcriptMatches!==true||result.proof?.boundary!=='turn.done+session.closed')throw fail('Rendered audio did not confirm the original passage.',503);
   this.db.prepare("UPDATE voice_rendering_attempts SET phase='rendered',proof=?,finished_at=? WHERE device=? AND id=? AND attempt=?").run(JSON.stringify(result.proof),Date.now(),device,id,attempt);
   // A verified completed clip remains reusable even if its original listener stopped.
   // Cancellation of listening never becomes cancellation or replay of task work.
   const file=await open(temp,'r');try{await file.sync();}finally{await file.close();}
   await rename(temp,this.path(row));const dir=await open(this.root,'r');try{await dir.sync();}finally{await dir.close();}
   this.db.prepare("UPDATE voice_renderings SET phase='completed',sha256=?,bytes=?,duration_ms=?,proof=?,error=NULL,updated_at=? WHERE device=? AND id=? AND digest=? AND phase IN ('queued','rendering','cancelled')")
    .run(result.sha256,audio.length,result.durationMs,JSON.stringify(result.proof),Date.now(),device,id,row.digest);
  }catch(error){
   this.db.prepare("UPDATE voice_rendering_attempts SET phase='failed',error=?,finished_at=? WHERE device=? AND id=? AND attempt=? AND phase='rendering'").run(String(error.message).slice(0,300),Date.now(),device,id,attempt);
   this.db.prepare("UPDATE voice_renderings SET phase='failed',error=?,updated_at=? WHERE device=? AND id=? AND attempt=? AND phase='rendering'")
    .run(String(error.message).slice(0,300),Date.now(),device,id,attempt);
  }finally{await rm(temp,{force:true}).catch(()=>{});}
 }
 async prune(device,keep){
  const rows=this.db.prepare("SELECT * FROM voice_renderings WHERE device=? AND phase='completed' ORDER BY updated_at DESC").all(device);
  let bytes=0,count=0;
  for(const row of rows){
   bytes+=row.bytes||0;count++;
   if(row.id===keep||count<=128&&bytes<=64*1024**2)continue;
   const removed=this.db.prepare("UPDATE voice_renderings SET phase='evicted',error='Speech cache expired; original text is retained' WHERE device=? AND id=? AND phase='completed' AND sha256=?").run(device,row.id,row.sha256);
   if(removed.changes)await rm(this.path(row),{force:true});
  }
 }
 get(device,id){const row=this.row(device,id);return row?this.projection(row):null;}
 async audio(device,id){
  const row=this.row(device,id);if(!row||row.phase!=='completed')throw fail('Speech audio is not ready.',404);
  let audio;
  try{audio=await readFile(this.path(row));}catch(error){
   if(error.code!=='ENOENT')throw error;
   this.db.prepare("UPDATE voice_renderings SET phase='corrupt',error='Saved audio is missing; retry speech only' WHERE device=? AND id=? AND phase='completed'").run(device,id);
   throw fail('Saved speech is missing. Text is retained; retry speech only.',503);
  }
  if(audio.length!==row.bytes||hash(audio)!==row.sha256){
   this.db.prepare("UPDATE voice_renderings SET phase='corrupt',error='Saved audio failed verification; retry speech only' WHERE device=? AND id=? AND phase='completed'").run(device,id);
   throw fail('Saved speech failed integrity verification. Text is retained; retry speech only.',503);
  }
  this.db.prepare("UPDATE voice_renderings SET updated_at=? WHERE device=? AND id=? AND phase='completed'").run(Date.now(),device,id);
  return {audio,sha256:row.sha256};
 }
 cancel(device,id,attempt){
  const row=this.row(device,id);if(!row)throw fail('Speech request not found.',404);
  if(row.attempt!==attempt)throw fail('This cancellation belongs to an older playback request.',409);
  if(['queued','rendering'].includes(row.phase)){
   this.db.prepare("UPDATE voice_renderings SET phase='cancelled',error=NULL,updated_at=? WHERE device=? AND id=? AND attempt=?").run(Date.now(),device,id,attempt);
   this.jobs.get(this.jobKey(device,id,attempt))?.control.abort();
  }
  return this.projection(this.row(device,id));
 }
}
