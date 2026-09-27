import {createHmac,createHash,timingSafeEqual} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';
import {createServer} from 'node:http';

export function validSignature(raw,header,secret){
 if(typeof header!=='string'||!/^sha256=[0-9a-f]{64}$/.test(header))return false;
 return timingSafeEqual(Buffer.from(header.slice(7),'hex'),createHmac('sha256',secret).update(raw).digest());
}

// Event content supplies identifiers only. All authority and review inputs are
// fetched again from GitHub; webhook text can never become commands or prompts.
export function eventJobs(event,payload,repositoryId){
 if(payload.repository?.id!==repositoryId)return [];
 if(event==='pull_request'){
  if(!['opened','synchronize','reopened','edited','ready_for_review','converted_to_draft','closed','labeled','unlabeled'].includes(payload.action))return [];
  if(['labeled','unlabeled'].includes(payload.action)&&payload.label?.name!=='maintainer:hold')return [];
  const n=payload.number;
  return payload.pull_request?.base?.ref==='main'&&Number.isSafeInteger(n)&&n>0?[payload.action==='closed'&&payload.pull_request.merged?`postmerge:${n}`:`pr:${n}`]:[];
 }
 if(event==='issue_comment'&&['created','edited'].includes(payload.action)&&payload.issue?.pull_request&&Number.isSafeInteger(payload.comment?.id)&&/^\/pocket reconsider\s+\S/.test((payload.comment.body||'').trim()))return [`comment:${payload.comment.id}`];
 if(event==='workflow_run'&&payload.action==='completed'){
  const run=payload.workflow_run;
  const path=payload.workflow?.path||run?.path;
  if(path==='.github/workflows/maintainer.yml'&&run.event==='workflow_dispatch')return ['reconcile'];
  if(path!=='.github/workflows/check.yml')return [];
  if(Number.isSafeInteger(run.id)&&run.id>0)return [`run:${run.id}`];
  if(run.event!=='pull_request')return [];
  const prs=(run.pull_requests||[]).filter(p=>Number.isSafeInteger(p.number)&&p.number>0).map(p=>`pr:${p.number}`);
  return prs.length?prs:/^[a-f0-9]{40}$/.test(run.head_sha)?[`commit:${run.head_sha}`]:[];
 }
 if(event==='push'&&payload.ref==='refs/heads/main')return ['reconcile'];
 return [];
}

export class EventQueue{
 constructor(path){
  this.db=new DatabaseSync(path);
  this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
   CREATE TABLE IF NOT EXISTS deliveries(id TEXT PRIMARY KEY, digest TEXT UNIQUE NOT NULL, received INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS jobs(key TEXT PRIMARY KEY, version INTEGER NOT NULL DEFAULT 1, attempts INTEGER NOT NULL DEFAULT 0, not_before INTEGER, error TEXT);
   CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
  `);
 }
 enqueue(key,at=Date.now()){
  if(!/^(?:reconcile|(?:pr|comment|run|postmerge):[1-9][0-9]*|commit:[a-f0-9]{40})$/.test(key))throw Error('Invalid event work key');
  this.db.prepare(`INSERT INTO jobs(key,not_before) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET version=version+1,attempts=0,not_before=excluded.not_before,error=NULL`).run(key,at);
 }
 accept(id,digest,jobs,at=Date.now()){
  this.db.exec('BEGIN IMMEDIATE');
  try{
   const inserted=this.db.prepare('INSERT OR IGNORE INTO deliveries(id,digest,received) VALUES(?,?,?)').run(id,digest,at).changes;
   if(inserted)for(const key of new Set(jobs))this.enqueue(key,at);
   this.db.prepare('DELETE FROM deliveries WHERE received < ?').run(at-30*86400000);
   this.db.exec('COMMIT');return !!inserted;
  }catch(e){this.db.exec('ROLLBACK');throw e;}
 }
 next(){return this.db.prepare('SELECT * FROM jobs WHERE not_before IS NOT NULL ORDER BY not_before,key LIMIT 1').get();}
 complete(job,retryAt=null){
  if(retryAt===null)this.db.prepare('DELETE FROM jobs WHERE key=? AND version=?').run(job.key,job.version);
  else this.db.prepare('UPDATE jobs SET not_before=? WHERE key=? AND version=?').run(retryAt,job.key,job.version);
 }
 fail(job,now=Date.now()){
  const delays=[60000,300000,1800000,7200000,21600000,86400000];
  const at=job.attempts<delays.length?now+delays[job.attempts]:null;
  this.db.prepare('UPDATE jobs SET attempts=attempts+1,not_before=?,error=? WHERE key=? AND version=?').run(at,'Processing failed; inspect the service log.',job.key,job.version);
  return at;
 }
 close(){this.db.close();}
 get(key){const row=this.db.prepare('SELECT value FROM metadata WHERE key=?').get(key);return row?JSON.parse(row.value):null;}
 set(key,value){this.db.prepare('INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value').run(key,JSON.stringify(value));}
 healthy(now=Date.now()){
  const recovery=this.get('recovery-health');
  if(recovery&&(!recovery.ok||now-recovery.at>45*60000))return false;
  return !this.db.prepare('SELECT key FROM jobs WHERE not_before IS NULL OR attempts>=3 OR not_before < ? LIMIT 1').get(now-20*60000);
 }
}

export function webhookServer({secret,repositoryId,queue,wake=()=>{}}){
 if(typeof secret!=='string'||secret.length<32||!Number.isSafeInteger(repositoryId))throw Error('Webhook secret and repository ID are required');
 return createServer({requestTimeout:15000,headersTimeout:10000},async(req,res)=>{
  const respond=(code,text)=>{res.writeHead(code,{'Content-Type':'text/plain','Cache-Control':'no-store'});res.end(text);};
  if(req.url==='/healthz'&&req.method==='GET'){respond(queue.healthy()?200:503,queue.healthy()?'Healthy':'Needs attention');return;}
  if(req.url!=='/github/events'||req.method!=='POST'){respond(404,'Not found');return;}
  if(req.headers['content-encoding']&&req.headers['content-encoding']!=='identity'){respond(415,'Unsupported encoding');return;}
  try{
   const chunks=[];let size=0;
   for await(const b of req){size+=b.length;if(size>2*1024*1024){respond(413,'Payload too large');return;}chunks.push(b);}
   const raw=Buffer.concat(chunks);
   if(!validSignature(raw,req.headers['x-hub-signature-256'],secret)){respond(401,'Invalid signature');return;}
   const id=req.headers['x-github-delivery'],event=req.headers['x-github-event'];
   if(typeof id!=='string'||!/^[a-zA-Z0-9-]{1,100}$/.test(id)||typeof event!=='string'||event.length>50){respond(400,'Invalid delivery headers');return;}
   let payload;try{payload=JSON.parse(raw);}catch{respond(400,'Invalid JSON');return;}
   if(!payload||typeof payload!=='object'){respond(400,'Invalid payload');return;}
   if(payload.repository?.id!==repositoryId){respond(403,'Wrong repository');return;}
   const jobs=eventJobs(event,payload,repositoryId);
   const digest=createHash('sha256').update(event).update(raw).digest('hex');
   const inserted=queue.accept(id,digest,jobs);
   respond(202,inserted?'Accepted':'Already accepted');
   if(inserted&&jobs.length){console.log(JSON.stringify({event,delivery:id,jobs}));wake();}
  }catch{if(!res.headersSent)respond(503,'Delivery not accepted');}
 });
}

// One timeout exists only while queued work has a future retry deadline.
// An empty queue has no timer and makes no API calls.
export class EventPump{
 constructor(queue,processJob,{log=console.error,onFailure=async()=>{}}={}){this.queue=queue;this.processJob=processJob;this.log=log;this.onFailure=onFailure;this.busy=false;this.timer=null;this.stopped=false;}
 wake(){
  if(this.stopped||this.busy)return;
  clearTimeout(this.timer);this.timer=null;
  const job=this.queue.next();if(!job)return;
  const delay=job.not_before-Date.now();
  if(delay>0){this.timer=setTimeout(()=>this.wake(),Math.min(delay,2147483647));return;}
  this.busy=true;
  Promise.resolve().then(()=>this.processJob(job)).then(result=>this.queue.complete(job,result?.retryAt??null)).catch(async e=>{
   const retryAt=this.queue.fail(job);this.log(JSON.stringify({job:job.key,error:e.message,retryAt}));
   try{await this.onFailure(job,retryAt);}catch{this.log('Failure alert could not be delivered');}
  }).finally(()=>{this.busy=false;this.wake();});
 }
 stop(){this.stopped=true;clearTimeout(this.timer);this.timer=null;}
}
