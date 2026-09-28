import express from 'express';
import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { randomUUID, randomBytes, timingSafeEqual } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, basename, extname } from 'node:path';
import { readFileSync, writeFileSync, mkdirSync, statSync, realpathSync } from 'node:fs';
import { hostname } from 'node:os';
import { Codex } from './codex.mjs';
import { openStore, hash } from './store.mjs';
import { loadPush } from './push.mjs';
import { SessionStarts,recentProjects } from './sessions.mjs';
import { CompletionRecovery } from './completions.mjs';
import { spokenSummary } from './speech.mjs';
import { LiveTimeline,timelinePage } from './timeline.mjs';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const dir=process.env.POCKET_DATA || resolve(root,'data');
const {db,secrets}=openStore(dir);
const push=loadPush(db,dir);
setInterval(()=>void push.flush().catch(e=>console.error('Push retry',e.message)),5000).unref();
const codex=new Codex();
const app=express(); app.disable('x-powered-by'); app.use(express.json({limit:'1mb'}));
const server=http.createServer(app), sockets=new WebSocketServer({noServer:true,maxPayload:16384});
const attached=new Set(), pending=new Map(), syncing=new Set();
const timeline=new LiveTimeline();
const sessionStarts=new SessionStarts(db,codex,(thread,row)=>{attached.add(thread.id);timeline.seed(thread);completions.follow(thread.id,thread);emit('sessionStarted',{threadId:thread.id});void sendOutgoing(row,thread);});
const now=()=>Date.now();
const emit=(type,payload)=>{const m=JSON.stringify({type,...payload}); for(const s of sockets.clients)if(s.readyState===WebSocket.OPEN)s.send(m);};
const safeEqual=(a,b)=>{const x=Buffer.from(a),y=Buffer.from(b);return x.length===y.length&&timingSafeEqual(x,y);};
const getToken=req=>(req.headers.authorization||'').replace(/^Bearer /,'');
const identity=req=>{const t=getToken(req); if(t&&safeEqual(t,secrets.adminToken))return {id:'owner',name:'Local owner'};return db.prepare('SELECT id,name FROM devices WHERE token_hash=?').get(hash(t));};
const requireAuth=(req,res,next)=>{req.device=identity(req); if(!req.device)return res.status(401).json({error:'Pair this device to continue.'});next();};
const owner=(req,res,next)=>{if(req.device.id!=='owner')return res.status(403).json({error:'Local owner access required'});next();};
const route=fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next);
const requireId=id=>{if(typeof id!=='string'||!/^[a-zA-Z0-9_-]{5,100}$/.test(id))throw new Error('Invalid thread identifier');return id;};
const notificationRow=r=>({...r,attachments:JSON.parse(r.attachments||'[]')});
const completions=new CompletionRecovery(db,(threadId,title,body,kind,turnId)=>notify(threadId,title,body,kind,[],null,null,turnId));
function notify(threadId,title,body,kind='update',attachments=[],requestId=null,speech=null,turnId=null) {
  const at=now();
  let n;
  db.exec('BEGIN IMMEDIATE');
  try{
    const r=db.prepare('INSERT OR IGNORE INTO notifications(thread_id,title,body,kind,attachments,created_at,spoken_summary,source_turn_id) VALUES(?,?,?,?,?,?,?,?)').run(threadId,title,body,kind,JSON.stringify(attachments),at,spokenSummary(title,body,speech),turnId);
    if(!r.changes){db.exec('COMMIT');return null;}
    n={id:Number(r.lastInsertRowid),thread_id:threadId,title,body,kind,attachments,created_at:at,spoken_summary:spokenSummary(title,body,speech)};
    if(threadId&&['question','approval','error'].includes(kind))db.prepare('INSERT INTO notification_attention(notification_id,request_id) VALUES(?,?)').run(n.id,requestId);
    push.enqueue(n.id,{flush:false});db.exec('COMMIT');
  }catch(e){db.exec('ROLLBACK');throw e;}
  emit('notification',{notification:n});void push.flush().catch(e=>console.error('Push retry',e.message));return n;
}
function resolveAttention(where,...args){
  const ids=db.prepare(`SELECT notification_id FROM notification_attention WHERE resolved_at IS NULL AND (${where})`).all(...args).map(x=>x.notification_id);
  if(!ids.length)return;
  db.prepare(`UPDATE notification_attention SET resolved_at=? WHERE notification_id IN (${ids.map(()=>'?').join(',')})`).run(now(),...ids);
  emit('attentionResolved',{ids});
}
// Native request IDs belong to the current bridge connection and cannot be answered after restart.
resolveAttention('request_id IS NOT NULL');
async function attach(threadId){
  requireId(threadId); await codex.connect();
  if(!attached.has(threadId)){
    const r=await codex.call('thread/resume',{threadId});timeline.seed(r.thread);completions.observe(r.thread);attached.add(threadId); return r.thread;
  }
  return (await codex.call('thread/read',{threadId,includeTurns:true})).thread;
}
const isActive=t=>t.status?.type==='active';
async function sendOutgoing(row,initialThread=null){
  if(syncing.has(row.thread_id))return;
  syncing.add(row.thread_id);
  try{
    const t=initialThread||await attach(row.thread_id);
    db.prepare("UPDATE outgoing SET state='sending',updated_at=? WHERE id=?").run(now(),row.id);
    const input=[{type:'text',text:row.text}];
    let result;
    const active=t.turns?.findLast(x=>x.status==='inProgress');
    if(isActive(t)&&active) {
      try {result=await codex.call('turn/steer',{threadId:t.id,expectedTurnId:active.id,input});}
      catch(e){
        // Retry only when Codex explicitly reports that the turn has finished.
        if(e.rpc && /no active turn|no turn in progress/i.test(e.message))result=await codex.call('turn/start',{threadId:t.id,input});else throw e;
      }
    }else if(isActive(t))throw new Error('The active turn is not ready for replies. Try again shortly.');
    else result=await codex.call('turn/start',{threadId:t.id,input,clientUserMessageId:row.id});
    db.prepare("UPDATE outgoing SET state='accepted',result=?,updated_at=? WHERE id=?").run(JSON.stringify(result),now(),row.id);
    resolveAttention('request_id IS NULL AND notification_id IN (SELECT id FROM notifications WHERE thread_id=? AND created_at<=?)',row.thread_id,row.created_at||now());
    emit('reply',{id:row.id,threadId:row.thread_id,state:'accepted'});
  }catch(e){
    const state=/timed out|disconnected/.test(e.message)?'unknown':'failed';
    db.prepare('UPDATE outgoing SET state=?,result=?,updated_at=? WHERE id=?').run(state,e.message,now(),row.id);
    emit('reply',{id:row.id,threadId:row.thread_id,state,error:e.message});
  }finally{syncing.delete(row.thread_id);}
}
async function flush(){if(!codex.ready)return;for(const row of db.prepare("SELECT * FROM outgoing WHERE state='queued' ORDER BY created_at").all())await sendOutgoing(row);}
db.prepare("UPDATE outgoing SET state='unknown',result='Server restarted during delivery; check the conversation before resending.' WHERE state='sending'").run();

codex.on('event',m=>{
  const p=m.params||{}, threadId=p.threadId || p.thread?.id;
  if(threadId&&attached.has(threadId)){const update=timeline.ingest(m);if(update)emit('timeline',update);}
  if(m.method==='serverRequest/resolved'){pending.delete(String(p.requestId));resolveAttention('request_id=?',String(p.requestId));}
  if(m.method==='turn/started'&&threadId)resolveAttention('request_id IS NULL AND notification_id IN (SELECT id FROM notifications WHERE thread_id=?)',threadId);
  if(m.id!==undefined && threadId){
    const duplicate=pending.has(String(m.id));
    pending.set(String(m.id),m);
    const question=/requestUserInput/.test(m.method);
    if(!duplicate)notify(threadId,question?'Codex has a question':'Codex needs your attention',p.questions?.map(q=>q.question).join('\n')||p.reason||p.command||p.message||'Open the task to review the request.',question?'question':'approval',[],String(m.id));
  }
  if(m.method==='item/completed' && p.item?.type==='agentMessage')emit('message',{threadId,item:p.item});
  if(m.method==='turn/completed')completions.complete(threadId,p.turn);
  // Only forward subscribed task events, never unrelated global messages or credentials.
  if(threadId && attached.has(threadId))emit('codex',{event:{method:m.method,...(m.id!==undefined?{id:m.id}:{}),params:{threadId,turnId:p.turnId}}});
});
codex.on('connected',()=>emit('status',{connected:true}));
codex.on('disconnected',()=>{attached.clear();pending.clear();resolveAttention('request_id IS NOT NULL');emit('status',{connected:false});});
let reconnecting=false;
setInterval(async()=>{
  if(reconnecting)return;reconnecting=true;
  try{
    await codex.connect();
    await sessionStarts.flush();
    for(const w of db.prepare('SELECT thread_id FROM watches').all())if(!attached.has(w.thread_id)){try{await attach(w.thread_id);}catch{if(!codex.ready)break;}}
    await flush();
  }catch(e){ /* Reconnect without changing the running Codex process. */ }finally{reconnecting=false;}
},5000).unref();
codex.connect().catch(()=>{});

app.use((req,res,next)=>{res.set('Cache-Control','no-store');res.set('X-Content-Type-Options','nosniff');next();});
app.get('/health',(_req,res)=>res.json({ok:true,codex:codex.ready,push:push.enabled?'fcm':'unconfigured',version:'0.4.3-alpha.3'}));
const pairAttempts=new Map();
setInterval(()=>{for(const [ip,v] of pairAttempts)if(now()-v.start>60000)pairAttempts.delete(ip);},60000).unref();
app.post('/api/pair',(req,res)=>{
  const ip=req.socket.remoteAddress, v=pairAttempts.get(ip)||{count:0,start:now()};
  if(now()-v.start>60000){v.count=0;v.start=now();}v.count++;pairAttempts.set(ip,v);
  if(v.count>10)return res.status(429).json({error:'Too many pairing attempts. Wait one minute.'});
  const code=String(req.body.code||'').replace(/[-\s]/g,'').toUpperCase();
  const row=db.prepare('SELECT * FROM pairing WHERE code_hash=? AND expires>?').get(hash(code),now());
  if(!row)return res.status(401).json({error:'Pairing code expired or incorrect.'});
  db.prepare('DELETE FROM pairing WHERE code_hash=?').run(row.code_hash);
  const token=randomBytes(32).toString('hex'), id=randomUUID();
  db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run(id,String(req.body.name||'Android phone').slice(0,100),hash(token),now());
  res.json({token,id,host:hostname(),firebase:push.config});
});
app.use('/api',requireAuth);
app.post('/api/pairing',owner,(_req,res)=>{
  const code=randomBytes(5).toString('hex').toUpperCase();
  db.prepare('INSERT INTO pairing VALUES(?,?)').run(hash(code),now()+15*60000);res.json({code,expires:now()+15*60000});
});
app.get('/api/status',(req,res)=>res.json({connected:codex.ready,host:hostname(),version:'0.4.3-alpha.3',device:req.device.name,deviceId:req.device.id,firebase:push.config,push:{enabled:push.enabled,registered:!!db.prepare('SELECT 1 FROM push_tokens WHERE device_id=?').get(req.device.id)}}));
app.post('/api/device/push',(req,res)=>{
  if(req.device.id==='owner')return res.status(403).json({error:'Pair a phone before registering push.'});
  if(!push.enabled)return res.status(503).json({error:'Configure Firebase on this server first.'});
  const {token,projectId}=req.body;
  if(typeof token!=='string'||!/^[-_A-Za-z0-9:]{20,4096}$/.test(token)||projectId!==push.config.projectId)return res.status(400).json({error:'Invalid push registration for this server.'});
  db.prepare('DELETE FROM push_tokens WHERE token=? AND device_id<>?').run(token,req.device.id);
  db.prepare('INSERT INTO push_tokens VALUES(?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET token=excluded.token,project_id=excluded.project_id,updated_at=excluded.updated_at').run(req.device.id,token,projectId,now());
  res.json({ok:true,transport:'fcm'});
});
app.post('/api/device/disconnect',(req,res)=>{
  if(req.device.id==='owner')return res.status(403).json({error:'Device access required.'});
  db.prepare('DELETE FROM devices WHERE id=?').run(req.device.id);
  for(const s of sockets.clients)if(s.deviceId===req.device.id)s.close();
  res.json({ok:true});
});
app.get('/api/notifications/:id/delivery',owner,(req,res)=>res.json({deliveries:db.prepare('SELECT p.device_id,d.name,p.state,p.attempts,p.message_id,p.error,p.updated_at FROM push_deliveries p JOIN devices d ON d.id=p.device_id WHERE p.notification_id=?').all(req.params.id)}));
app.get('/api/projects',route(async(_req,res)=>{
  await codex.connect();const r=await codex.call('thread/list',{limit:100,sortKey:'updated_at',sortDirection:'desc',archived:false});
  res.json({projects:recentProjects(r.data||[])});
}));
app.post('/api/threads',(req,res)=>{
  try{const row=sessionStarts.enqueue(req.body);res.status(row.state==='started'?200:202).json(row);}
  catch(e){res.status(e.status||500).json({error:e.message});}
});
app.get('/api/session-starts/:id',(req,res)=>{const row=sessionStarts.get(req.params.id);if(!row)return res.status(404).json({error:'Task request not found.'});res.json(row);});
app.get('/api/threads',route(async(req,res)=>{
  await codex.connect();
  const r=await codex.call('thread/list',{limit:70,sortKey:'updated_at',sortDirection:'desc',archived:false});
  const watches=db.prepare('SELECT * FROM watches').all();
  res.json({threads:(r.data||[]).map(t=>({id:t.id,name:t.name||t.preview?.slice(0,90)||'Untitled task',preview:t.preview?.slice(0,200),cwd:t.cwd,status:t.status,updatedAt:t.updatedAt,watched:!!watches.find(w=>w.thread_id===t.id&&w.enabled)}))});
}));
app.get('/api/threads/:id',route(async(req,res)=>{
  const t=timeline.merge(await attach(req.params.id));
  const requests=[...pending.values()].filter(m=>m.params.threadId===t.id);
  const page=timelinePage(t,requests,{before:req.query.before||null,limit:8,notifications:db.prepare('SELECT * FROM notifications WHERE thread_id=?').all(t.id).map(notificationRow)});
  // Paging is bounded on the wire; retain the original thread on the Codex host.
  const turns=(t.turns||[]).slice(-25).map(turn=>({...turn,items:(turn.items||[]).filter(x=>['userMessage','agentMessage','imageGeneration','fileChange','commandExecution'].includes(x.type)).map(x=>x.type==='commandExecution'?{...x,aggregatedOutput:x.aggregatedOutput?.slice(-12000)}:x)}));
  res.json({thread:{...t,turns:req.query.view==='timeline'?[]:turns},timeline:page,revision:timeline.version,pending:requests,notifications:db.prepare('SELECT * FROM notifications WHERE thread_id=? ORDER BY id DESC LIMIT 30').all(t.id).map(notificationRow),outgoing:db.prepare('SELECT id,text,state,result,created_at FROM outgoing WHERE thread_id=? ORDER BY created_at').all(t.id),watched:!!db.prepare('SELECT * FROM watches WHERE thread_id=? AND enabled=1').get(t.id)});
}));
app.post('/api/threads/:id/interrupt',route(async(req,res)=>{
  const t=await attach(req.params.id);const active=t.turns?.findLast(x=>x.status==='inProgress');
  if(!active)return res.status(409).json({error:'This task has already stopped.'});
  await codex.call('turn/interrupt',{threadId:t.id,turnId:active.id});res.json({ok:true});
}));
app.post('/api/threads/:id/watch',route(async(req,res)=>{
  const t=await attach(req.params.id);
  const wasFollowing=!!db.prepare('SELECT 1 FROM watches WHERE thread_id=? AND enabled=1').get(t.id);
  db.prepare('INSERT INTO watches(thread_id,name,enabled) VALUES(?,?,?) ON CONFLICT(thread_id) DO UPDATE SET name=excluded.name,enabled=excluded.enabled').run(t.id,t.name||t.preview?.slice(0,60)||'Codex',req.body.enabled?1:0);
  if(req.body.enabled&&!wasFollowing)completions.follow(t.id,t);
  res.json({ok:true});
}));
app.post('/api/threads/:id/reply',route(async(req,res)=>{
  const threadId=requireId(req.params.id), text=String(req.body.text||'').trim(), id=String(req.body.id||randomUUID());
  if(!text||text.length>32000||id.length>100)return res.status(400).json({error:'Reply must be 1–32000 characters.'});
  const existing=db.prepare('SELECT * FROM outgoing WHERE id=?').get(id);
  if(existing){if(existing.thread_id!==threadId||existing.text!==text)return res.status(409).json({error:'Reply id already belongs to a different message.'});return res.json({id,state:existing.state});}
  db.prepare('INSERT INTO outgoing(id,thread_id,text,state,created_at,updated_at) VALUES(?,?,?,?,?,?)').run(id,threadId,text,'queued',now(),now());
  const wasFollowing=!!db.prepare('SELECT 1 FROM watches WHERE thread_id=? AND enabled=1').get(threadId);
  // A phone reply explicitly opts this task into a completion notification.
  db.prepare('INSERT INTO watches(thread_id,name,enabled) VALUES(?,?,1) ON CONFLICT(thread_id) DO UPDATE SET enabled=1').run(threadId,'Codex replied');
  if(!wasFollowing)completions.follow(threadId);
  res.status(202).json({id,state:'queued'}); if(codex.ready)void sendOutgoing({id,thread_id:threadId,text,created_at:now()});
}));
app.get('/api/replies/:id',(req,res)=>{
  const row=db.prepare('SELECT id,thread_id,state,result FROM outgoing WHERE id=?').get(req.params.id);
  if(!row)return res.status(404).json({error:'Reply not found.'});
  res.json(row);
});
app.post('/api/requests/:id/answer',route(async(req,res)=>{
  const m=pending.get(req.params.id);
  if(!m)return res.status(409).json({error:'This request has already been answered or expired.'});
  let result;
  if(/requestUserInput/.test(m.method)){
    const answers={};
    for(const q of m.params.questions||[]){const answer=req.body.answers?.[q.id];if(typeof answer!=='string'||!answer.trim())return res.status(400).json({error:`Answer ${q.header||q.id} first.`});answers[q.id]={answers:[answer.slice(0,8000)]};}
    result={answers};
  }else if(/item\/(commandExecution|fileChange)\/requestApproval/.test(m.method)){
    if(!['accept','decline','cancel'].includes(req.body.decision))return res.status(400).json({error:'Invalid approval decision'});
    if(m.params.availableDecisions && !m.params.availableDecisions.includes(req.body.decision))return res.status(400).json({error:'This decision is not offered by Codex.'});
    result={decision:req.body.decision};
  }else return res.status(400).json({error:'Please answer this request in the terminal; this request type is not supported yet.'});
  codex.answer(m.id,result);pending.delete(req.params.id);resolveAttention('request_id=?',req.params.id);res.json({ok:true});
}));
app.get('/api/attention',(_req,res)=>res.json({notifications:db.prepare('SELECT n.* FROM notifications n JOIN notification_attention a ON a.notification_id=n.id WHERE a.resolved_at IS NULL ORDER BY n.id DESC LIMIT 100').all().map(notificationRow)}));
app.get('/api/notifications/:id/attention',(req,res)=>{
  const row=db.prepare('SELECT * FROM notification_attention WHERE notification_id=?').get(req.params.id);
  res.json({needsAttention:!!row&&row.resolved_at===null});
});
app.get('/api/notifications',(req,res)=>{const after=Math.max(0,Number(req.query.after)||0);res.json({notifications:db.prepare('SELECT * FROM notifications WHERE id>? ORDER BY id DESC LIMIT 100').all(after).reverse().map(notificationRow)});});
app.post('/api/test-notification',(req,res)=>res.json(notify(null,'Your work, within reach.','Pocket is connected. Updates from Codex will arrive here, with a direct route back to your task.','test')));
const mimeFor=n=>({'.png':'image/png','.jpg':'image/jpeg','.jpeg':'image/jpeg','.webp':'image/webp','.pdf':'application/pdf','.mp4':'video/mp4','.txt':'text/plain','.md':'text/plain'})[extname(n).toLowerCase()]||'application/octet-stream';
function saveAttachment(file,threadId){
  const actual=realpathSync(file), st=statSync(actual);if(!st.isFile()||st.size>12*1024*1024)throw new Error('Attachment must be a regular file under 12 MB.');
  const id=randomUUID(), name=basename(actual), mime=mimeFor(name), folder=resolve(dir,'files');mkdirSync(folder,{recursive:true,mode:0o700});
  const dest=resolve(folder,id);writeFileSync(dest,readFileSync(actual),{mode:0o600});db.prepare('INSERT INTO files VALUES(?,?,?,?,?)').run(id,name,mime,dest,threadId);
  return {id,name,mime,url:`/api/files/${id}`,size:st.size};
}
app.post('/api/notify',owner,route(async(req,res)=>{
  const b=req.body, threadId=requireId(b.thread_id);
  if(!b.message||String(b.message).length>32000)return res.status(400).json({error:'A message up to 32000 characters is required.'});
  if(b.spoken_summary!==undefined&&(typeof b.spoken_summary!=='string'||b.spoken_summary.length>240))return res.status(400).json({error:'Spoken summary must be at most 240 characters.'});
  const t=await attach(threadId);
  db.prepare('INSERT OR IGNORE INTO watches(thread_id,name,enabled) VALUES(?,?,0)').run(threadId,t.name||'Codex');
  const attachments=(b.files||[]).slice(0,5).map(f=>saveAttachment(f,threadId));
  res.json({notification:notify(threadId,String(b.title||t.name||'Codex update').slice(0,180),String(b.message),['update','complete','question','error'].includes(b.kind)?b.kind:'update',attachments,null,b.spoken_summary)});
}));
app.get('/api/files/:id',(req,res)=>{const f=db.prepare('SELECT * FROM files WHERE id=?').get(req.params.id);if(!f)return res.sendStatus(404);res.set('Content-Type',f.mime);res.set('Content-Disposition',`inline; filename*=UTF-8''${encodeURIComponent(f.name)}`);res.sendFile(f.disk_path);});
app.get('/api/devices',owner,(_req,res)=>res.json({devices:db.prepare('SELECT id,name,created_at,last_seen FROM devices').all()}));
app.delete('/api/devices/:id',owner,(req,res)=>{db.prepare('DELETE FROM devices WHERE id=?').run(req.params.id);for(const s of sockets.clients)if(s.deviceId===req.params.id)s.close();res.json({ok:true});});
server.on('upgrade',(req,socket,head)=>{
  const device=identity(req);if(!device||req.url!=='/events'){socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');socket.destroy();return;}
  sockets.handleUpgrade(req,socket,head,ws=>{ws.deviceId=device.id;ws.alive=true;ws.on('pong',()=>ws.alive=true);ws.on('error',()=>{});db.prepare('UPDATE devices SET last_seen=? WHERE id=?').run(now(),device.id);ws.send(JSON.stringify({type:'status',connected:codex.ready}));sockets.emit('connection',ws,req);});
});
setInterval(()=>{for(const ws of sockets.clients){if(!ws.alive){ws.terminate();continue;}ws.alive=false;ws.ping();}},25000).unref();
app.use((err,req,res,next)=>{console.error(req.method,req.path,err.message);res.status(400).json({error:err.message});});
const port=Number(process.env.PORT||18880);
server.listen(port,'127.0.0.1',()=>console.log(`Pocket listening on 127.0.0.1:${port}`));
