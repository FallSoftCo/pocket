import {createHash} from 'node:crypto';
const failure=(message,status=409)=>Object.assign(Error(message),{status});
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;
// This journal covers only a local notification transaction, not arbitrary
// commands or external actions. Its receipt commits with the notification and
// durable push queue. An unfinished journal row therefore proves no such commit.
export class NotificationRequests {
 constructor({db,clock=Date.now}) {
  Object.assign(this,{db,clock});this.active=new Map();
  db.exec(`CREATE TABLE IF NOT EXISTS notification_requests(
   request_key TEXT PRIMARY KEY,body_hash TEXT NOT NULL,state TEXT NOT NULL,
   result TEXT,error TEXT,status INTEGER,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)`);
 }
 run(key,body,publish){
  if(key==null)return publish(()=>{});
  if(typeof key!=='string'||!/^[a-zA-Z0-9][a-zA-Z0-9._:-]{7,159}$/.test(key))throw failure('Use a stable notification request identity of 8–160 characters.',400);
  const hash=createHash('sha256').update(JSON.stringify(canonical(body))).digest('hex');
  const old=this.db.prepare('SELECT * FROM notification_requests WHERE request_key=?').get(key);
  if(old&&old.body_hash!==hash)throw failure('This notification identity already belongs to different content. Nothing was sent.');
  if(old?.state==='persisted')return Promise.resolve(JSON.parse(old.result));
  if(old?.state==='failed')throw failure(old.error,old.status);
  if(this.active.has(key))return this.active.get(key);
  const at=this.clock();
  if(!old)this.db.prepare("INSERT INTO notification_requests VALUES(?,?,'preparing',NULL,NULL,NULL,?,?)").run(key,hash,at,at);
  const job=(async()=>{
   try{
    await publish(result=>{
     if(this.db.isTransaction!==true)throw failure('Notification receipts must commit with the notification.',500);
     const n=this.db.prepare("UPDATE notification_requests SET state='persisted',result=?,updated_at=? WHERE request_key=? AND state='preparing'").run(JSON.stringify(result),this.clock(),key);
     if(n.changes!==1)throw failure('Notification receipt state changed.',500);
    });
    const row=this.db.prepare('SELECT * FROM notification_requests WHERE request_key=?').get(key);
    if(row.state!=='persisted')throw failure('Notification was not committed with a receipt.',500);
    return JSON.parse(row.result);
   }catch(error){
    const row=this.db.prepare('SELECT * FROM notification_requests WHERE request_key=?').get(key);
    // A failure after commit must return the original accepted receipt.
    if(row.state==='persisted')return JSON.parse(row.result);
    const status=Number.isInteger(error.status)&&error.status>=400&&error.status<=599?error.status:500;
    const message=status>=500?'Notification could not be prepared; no notification was committed.':String(error.message).slice(0,500);
    this.db.prepare("UPDATE notification_requests SET state='failed',error=?,status=?,updated_at=? WHERE request_key=? AND state='preparing'").run(message,status,this.clock(),key);
    throw failure(message,status);
   }
  })();
  this.active.set(key,job);job.finally(()=>{if(this.active.get(key)===job)this.active.delete(key)}).catch(()=>{});return job;
 }
}

/** Existing operator-only ingress; this does not grant agent or human task authority. */
export function mountOperatorNotifications(app,{db,codex,owner,route,requireId,saveAttachment,notify,promptContext}){
 const notificationRequests=new NotificationRequests({db});
 app.post('/api/notify',owner,route(async(req,res)=>{
  const b=req.body;
  if(!b||typeof b!=='object'||Array.isArray(b))return res.status(400).json({error:'Provide a notification object.'});
  const threadId=requireId(b.thread_id);
  if(typeof b.message!=='string'||!b.message.trim()||b.message.length>32000)return res.status(400).json({error:'A message up to 32000 characters is required.'});
  if(b.spoken_summary!==undefined&&(typeof b.spoken_summary!=='string'||b.spoken_summary.length>32000))return res.status(400).json({error:'Spoken text must be at most 32000 characters.'});
  if(b.spoken_context!==undefined&&(typeof b.spoken_context!=='string'||b.spoken_context.length>120))return res.status(400).json({error:'Spoken context must be a short prompt summary.'});
  if(b.files!==undefined&&(!Array.isArray(b.files)||b.files.length>5||b.files.some(f=>typeof f!=='string'||!f||f.length>4096)))return res.status(400).json({error:'Provide up to five explicit local attachment paths.'});
  const key=req.get('Idempotency-Key');
  res.json(await notificationRequests.run(key,b,async persist=>{
   await codex.connect();const {thread:t}=await codex.call('thread/read',{threadId,includeTurns:false});
   if(t?.id!==threadId)throw Object.assign(Error('Notification source identity changed.'),{status:409});
   db.prepare('INSERT OR IGNORE INTO watches(thread_id,name,enabled) VALUES(?,?,0)').run(threadId,t.name||'Codex');
   const attachments=(b.files||[]).slice(0,5).map(f=>saveAttachment(f,threadId));
   return {notification:notify(threadId,String(b.title||t.name||'Codex update').slice(0,180),String(b.message),['update','complete','question','error'].includes(b.kind)?b.kind:'update',attachments,null,b.spoken_summary,null,b.spoken_context?promptContext(null,b.spoken_context):null,n=>persist({notification:n}))};
  }));
}));
 return notificationRequests;
}
