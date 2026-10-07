import {RecoveryQueue} from './recovery-queue.mjs';
import {TurnRecovery,permissionRecoveryAudit} from './turn-recovery.mjs';
import {SessionDiscovery,INTERACTIVE_SOURCES} from './session-discovery.mjs';
import {SessionCatchup} from './session-catchup.mjs';
import {CoordinatorReports} from './coordinator-reports.mjs';
import {recoverReply} from './reply-recovery.mjs';
import {mountLosangelex} from './losangelex.mjs';
import {computerUseStatus} from './computer-use.mjs';
import {NotificationTitles} from './notification-titles.mjs';
import {NotificationReads} from './notification-reads.mjs';
import {ImmersionWorker} from './immersion-worker.mjs';
import {QuestionActions} from './question-actions.mjs';
import {ConversationNotes} from "./conversation-notes.mjs";
import {LiveActivityDelivery} from "./live-activity-delivery.mjs";
import {ActivityBoard} from './activity-board.mjs';
import {NativeVoice} from './native-voice.mjs';
import {ThreadPreviews,sessionInteraction} from './thread-previews.mjs';
import express from 'express';
import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { randomUUID, randomBytes, timingSafeEqual } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, basename, extname } from 'node:path';
import { readFileSync, writeFileSync, mkdirSync, statSync, realpathSync, existsSync, chmodSync } from 'node:fs';
import { hostname } from 'node:os';
import { Codex } from './codex.mjs';
import { openStore, hash } from './store.mjs';
import { loadPush } from './push.mjs';
import { AppUpdates,mountAppUpdates } from './app-updates.mjs';
import { SessionStarts,recentProjects,permissionOptions } from './sessions.mjs';
import { CompletionRecovery } from './completions.mjs';
import { spokenSummary,spokenText,promptContext,turnPrompt } from './speech.mjs';
import { LiveTimeline,timelinePage } from './timeline.mjs';
import { ThreadHistory } from './history.mjs';
import { ambiguousDelivery } from './connection-errors.mjs';
import { AccountRateLimits } from './rate-limits.mjs';
import { modelCatalogue,validateTurnSettings,turnOverrides } from './turn-settings.mjs';
import { VoiceSpeech } from './voice-speech.mjs';
import { VoiceController } from './voice-controller.mjs';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const dir=process.env.POCKET_DATA || resolve(root,'data');
const appVersion=JSON.parse(readFileSync(resolve(root,'package.json'),'utf8')).version;
const hostName=process.env.POCKET_HOST_NAME||hostname();
const localMode=process.env.POCKET_LOCAL==='1';
const defaultCwd=process.env.POCKET_DEFAULT_CWD||process.cwd();
const {db,secrets}=openStore(dir);
const notificationReads=new NotificationReads(db);
const notificationTitles=new NotificationTitles(db);
const voiceKeyPath=resolve(dir,'voice-key');
const voiceSpeech=new VoiceSpeech({apiKey:process.env.POCKET_VOICE_API_KEY||(existsSync(voiceKeyPath)?readFileSync(voiceKeyPath,'utf8').trim():'')});
let speechRequests=0;
const automationPath=resolve(dir,'automation.json');
const automation=localMode?(existsSync(automationPath)?JSON.parse(readFileSync(automationPath)):{secret:randomBytes(32).toString('hex')}):null;
if(automation&&!existsSync(automationPath))writeFileSync(automationPath,JSON.stringify(automation),{mode:0o600});
if(automation)chmodSync(automationPath,0o600);
const push=loadPush(db,dir);
setInterval(()=>void push.flush().catch(e=>console.error('Push retry',e.message)),5000).unref();
const codex=new Codex();
const app=express(); app.disable('x-powered-by'); app.use(express.json({limit:'1mb'}));
const server=http.createServer(app), sockets=new WebSocketServer({noServer:true,maxPayload:16384});
const attached=new Set(), pending=new Map(), syncing=new Set();
db.exec('CREATE TABLE IF NOT EXISTS immersion_profiles(device_id TEXT PRIMARY KEY,state TEXT NOT NULL)');
const immersionCwd=resolve(dir,'immersion');mkdirSync(immersionCwd,{recursive:true,mode:0o700});
const immersion=new ImmersionWorker({codex:new Codex(),cwd:immersionCwd,load:id=>{const row=db.prepare('SELECT state FROM immersion_profiles WHERE device_id=?').get(id);return row?JSON.parse(row.state):null;},save:(id,state)=>db.prepare('INSERT INTO immersion_profiles VALUES(?,?) ON CONFLICT(device_id) DO UPDATE SET state=excluded.state').run(id,JSON.stringify(state)),publish:(id,event)=>{const message=JSON.stringify(event);for(const ws of sockets.clients)if(ws.deviceId===id&&ws.readyState===WebSocket.OPEN)ws.send(message);}});
const voiceCodex=new Codex();
const voiceController=new VoiceController({db,codex:voiceCodex,cwd:defaultCwd,host:hostName,
  api:async(path,body)=>{const r=await fetch(`http://127.0.0.1:${server.address().port}${path}`,{headers:{Authorization:`Bearer ${secrets.adminToken}`,'Content-Type':'application/json'},...(body===undefined?{}:{method:'POST',body:JSON.stringify(body)}),signal:AbortSignal.timeout(35000)});const data=await r.json();if(!r.ok)throw Error(data.error||'Pocket control failed.');return data;},
  transcribe:async audio=>{
    if(!voiceSpeech.apiKey)throw Object.assign(Error('Voice is not configured on this server.'),{status:503});
    const form=new FormData();form.append('model','gpt-transcribe');form.append('file',new Blob([audio],{type:'audio/wav'}),'turn.wav');form.append('response_format','json');form.append('prompt','Pocket, Codex, steer, queue, workstation, session, full permissions.');
    const r=await fetch('https://api.openai.com/v1/audio/transcriptions',{method:'POST',headers:{Authorization:`Bearer ${voiceSpeech.apiKey}`},body:form,signal:AbortSignal.timeout(60000)});
    if(!r.ok)throw Error(`Transcription provider returned HTTP ${r.status}.`);return (await r.json()).text||'';
  }});

const nativeVoiceCodex=new Codex();
const nativeVoice=new NativeVoice({codex:nativeVoiceCodex,controller:voiceController,cwd:defaultCwd});
const sessionDiscovery=new SessionDiscovery({db,codex,hidden:id=>voiceController.owns(id)||nativeVoice.owns(id)||immersion.ownsThread(id)});
const catchupHidden=id=>voiceController.owns(id)||nativeVoice.owns(id)||immersion.ownsThread(id)||!!db.prepare('SELECT archived FROM pocket_discovered_threads WHERE thread_id=?').get(id)?.archived;
const sessionCatchup=new SessionCatchup({db,hidden:catchupHidden,resolvePending:(requestId,threadId)=>{
  if(!requestId)return undefined;const live=pending.get(String(requestId));if(live?.params?.threadId===threadId)return true;
  const attention=db.prepare('SELECT a.resolved_at FROM notification_attention a JOIN notifications n ON n.id=a.notification_id WHERE a.request_id=? AND n.thread_id=? ORDER BY n.id DESC LIMIT 1').get(String(requestId),threadId);
  return attention?.resolved_at!=null?false:undefined;
}});
sessionCatchup.bootstrap();
voiceController.catchup=sessionCatchup;
voiceController.hidden=catchupHidden;
db.exec('CREATE TABLE IF NOT EXISTS coordinator_report_notifications(report_id TEXT PRIMARY KEY,device_id TEXT NOT NULL,notification_id INTEGER UNIQUE NOT NULL)');
const coordinatorReports=new CoordinatorReports({db,catchup:sessionCatchup,hidden:catchupHidden,
  readThread:async threadId=>{await codex.connect();return codex.call('thread/read',{threadId,includeTurns:false});},
  refreshRoster:async()=>{
    await codex.connect();const threads=[],visited=new Set();let cursor=null;
    for(let page=0;page<6;page++){
      const result=await codex.call('thread/list',{limit:100,sortKey:'updated_at',sortDirection:'desc',archived:false,useStateDbOnly:true,sourceKinds:[...INTERACTIVE_SOURCES,'subAgent'],...(cursor?{cursor}:{})});
      for(const thread of result.data||[]){if(catchupHidden(thread.id))continue;sessionDiscovery.remember(thread,{archived:false});threads.push(thread);}
      const next=result.nextCursor||null;if(!next)return {threads,threadCount:threads.length,complete:true,truncated:false};
      if(visited.has(next))return {threads,threadCount:threads.length,complete:false,truncated:true,reason:'The inventory returned a repeated cursor; coverage is incomplete.'};visited.add(next);cursor=next;
    }
    return {threads,threadCount:threads.length,complete:false,truncated:true,nextCursor:cursor,reason:'More sessions remain beyond the 600-session inventory limit.'};
  },
  publish:async report=>{
    let saved=db.prepare('SELECT notification_id FROM coordinator_report_notifications WHERE report_id=? AND device_id=?').get(report.id,report.device);
    if(!saved){db.exec('BEGIN IMMEDIATE');try{
      const row=db.prepare("INSERT INTO notifications(thread_id,title,body,kind,attachments,created_at,spoken_summary,spoken_text) VALUES(NULL,?,?,'coordinator_report','[]',?,'','')").run('Work updates',report.response,report.createdAt);
      saved={notification_id:Number(row.lastInsertRowid)};db.prepare('INSERT INTO coordinator_report_notifications(report_id,device_id,notification_id) VALUES(?,?,?)').run(report.id,report.device,saved.notification_id);
      if(push.enabled)db.prepare("INSERT OR IGNORE INTO push_deliveries(notification_id,device_id,state,updated_at) SELECT ?,device_id,'queued',? FROM push_tokens WHERE device_id=? AND project_id=?").run(saved.notification_id,Date.now(),report.device,push.config.projectId);
      db.exec('COMMIT');
    }catch(error){db.exec('ROLLBACK');throw error;}}
    const notification=notificationRow(db.prepare('SELECT * FROM notifications WHERE id=?').get(saved.notification_id));
    const message=JSON.stringify({type:'coordinatorReport',reportId:report.id,notification});
    for(const ws of sockets.clients)if(ws.deviceId===report.device&&ws.readyState===WebSocket.OPEN)ws.send(message);
    void push.flush().catch(error=>console.error('Report push retry',error.message));
  }});
voiceController.reports=coordinatorReports;
setInterval(()=>void coordinatorReports.tick().catch(error=>console.error('Coordinator report',error.message)),60000).unref();

const timeline=new LiveTimeline();
setInterval(()=>timeline.prune(),60000).unref();
const conversationNotes=new ConversationNotes(db,(threadId,notes)=>emit("contextNotes",{threadId,notes}));
// Notes hydrate when their conversation opens, not while hundreds of list cards load.
const history=new ThreadHistory(codex);
const activityBoard=new ActivityBoard();
const liveDelivery=new LiveActivityDelivery((type,payload)=>emit(type,payload));
const threadPreviews=new ThreadPreviews(history,(threadId,preview)=>liveDelivery.preview(threadId,preview));
const sessionStarts=new SessionStarts(db,codex,(thread,row)=>{const known={...thread,activityAt:Date.now(),name:thread.name||row.text?.slice(0,90)||'New task',status:thread.status?.type==='active'?thread.status:{type:'pending'},discoveryPending:true};sessionDiscovery.remember(known);attached.add(thread.id);timeline.seed(thread);completions.follow(thread.id,thread);emit('sessionStarted',{threadId:thread.id,thread:known});void sendOutgoing(row,thread);});
const now=()=>Date.now();
const emit=(type,payload)=>{const m=JSON.stringify({type,...payload}); for(const s of sockets.clients)if(s.readyState===WebSocket.OPEN)s.send(m);};
const rateLimits=new AccountRateLimits(codex,usage=>emit('rateLimits',{usage}));
setInterval(()=>{if(sockets.clients.size)void rateLimits.refresh();},60000).unref();
const safeEqual=(a,b)=>{const x=Buffer.from(a),y=Buffer.from(b);return x.length===y.length&&timingSafeEqual(x,y);};
const getToken=req=>(req.headers.authorization||'').replace(/^Bearer /,'');
const identity=req=>{const t=getToken(req); if(t&&safeEqual(t,secrets.adminToken))return {id:'owner',name:'Local owner'};return db.prepare('SELECT id,name FROM devices WHERE token_hash=?').get(hash(t));};
const requireAuth=(req,res,next)=>{req.device=identity(req); if(!req.device)return res.status(401).json({error:'Pair this device to continue.'});next();};
const owner=(req,res,next)=>{if(req.device.id!=='owner')return res.status(403).json({error:'Local owner access required'});next();};
const route=fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next);
const requireId=id=>{if(typeof id!=='string'||!/^[a-zA-Z0-9_-]{5,100}$/.test(id))throw new Error('Invalid thread identifier');return id;};
const notificationRow=r=>({...r,title_revision:notificationTitles.revision(r.thread_id),spoken_text:r.spoken_text||spokenText(r.title,r.body),attachments:JSON.parse(r.attachments||'[]')});
function rememberSpeechContext(threadId,turn){
  const prompt=turnPrompt(turn);if(!threadId||!prompt)return;
  db.prepare('INSERT INTO speech_contexts(thread_id,turn_id,context) VALUES(?,?,?) ON CONFLICT(thread_id) DO UPDATE SET turn_id=excluded.turn_id,context=excluded.context').run(threadId,turn.id||null,promptContext(prompt));
}
const recoveryQueue=new RecoveryQueue(db);
const turnRecovery=new TurnRecovery({db,
  read:async threadId=>{await codex.connect();const {thread}=await codex.call('thread/read',{threadId,includeTurns:false});return history.read(thread,{summary:true});},
  health:async(threadId,kind,thread)=>{
    if(!codex.ready||syncing.has(threadId))return false;
    if(thread?.goal&&['paused','budgetLimited','usageLimited','tokenLimited'].includes(thread.goal.status))return {ok:false,blocked:true,reason:'The task’s usage-stop policy is paused. Recovery will not override it.'};
    if([...pending.values()].some(m=>m.params?.threadId===threadId))return {ok:false,blocked:true,reason:'This task has an unresolved request that needs your answer before continuing.'};
    if(db.prepare("SELECT 1 FROM outgoing WHERE thread_id=? AND state='unknown'").get(threadId))return {ok:false,blocked:true,reason:'Earlier message delivery is uncertain. Reconcile that message before continuing; it has not been sent again.'};
    if(db.prepare("SELECT 1 FROM outgoing WHERE thread_id=? AND state IN ('sending','queued')").get(threadId))return false;
    await rateLimits.refresh();if(rateLimits.failed)return false;
    const quota=rateLimits.buckets.codex||rateLimits.defaultBucket;
    const stops=[quota?.primary,quota?.secondary].filter(window=>window&&window.usedPercent>=100);
    if(stops.length)return {ok:false,nextAt:Math.max(...stops.map(window=>window.resetsAt>0?window.resetsAt*1000:Date.now()+4*3600000))};
    if(db.prepare('SELECT archived FROM pocket_discovered_threads WHERE thread_id=?').get(threadId)?.archived)return false;
    if(kind==='permission'){
      const intended=db.prepare('SELECT permissions FROM thread_permissions WHERE thread_id=?').get(threadId)||db.prepare('SELECT permissions FROM session_starts WHERE thread_id=?').get(threadId);
      if(intended?.permissions&&intended.permissions!=='full')return {ok:false,blocked:true,reason:'The task’s intended permissions have changed. Recovery will not restore full access.'};
      // Audit inherited permissions, never reapply a saved/full profile over a revocation.
      const inherited=await codex.call('thread/resume',{threadId,excludeTurns:true});
      const audit=permissionRecoveryAudit(inherited);
      if(audit==='waiting')return false; // An incomplete audit is not proof of revocation.
      if(audit==='blocked')return {ok:false,blocked:true,reason:'The runtime no longer grants the task’s intended full permissions. Recovery will not override that change.'};
      const probe=await fetch('https://chatgpt.com/backend-api/codex/responses',{method:'HEAD',signal:AbortSignal.timeout(8000)});
      if(probe.status>=500)return false;
    }
    return true;
  },
  stop:async(threadId,turnId)=>codex.call('turn/interrupt',{threadId,turnId}),
  start:async(threadId,requestId,text)=>{
    // Inherit the runtime's exact model and permission settings. No original task replay.
    await codex.call('thread/resume',{threadId,excludeTurns:true});attached.add(threadId);
    const recovery=turnRecovery.get(threadId);if(recovery?.state!=='dispatching'||recovery.request_id!==requestId)throw Error('Recovery was cancelled before delivery.');
    return codex.call('turn/start',{threadId,clientUserMessageId:requestId,input:[{type:'text',text}]});
  },
  publish:(threadId,recovery)=>{if(recovery.state==='completed'&&recoveryQueue.release(threadId,recovery.source_turn)){emit('reply',{threadId,state:'queued'});void flush();}if(recovery.state==='cancelled')recoveryQueue.forget(threadId,recovery.source_turn);emit('turnRecovery',{threadId,recovery});if(recovery.message){sessionCatchup.put(threadId,{key:'recovery:'+recovery.source_turn,kind:'blocked',text:recovery.message,turnId:recovery.current_turn});notify(threadId,'Task recovery',recovery.message,'update');}}
});
sessionCatchup.recoverableFailure=(threadId,turnId)=>turnRecovery.recoverableFailure(threadId,turnId);
setInterval(()=>void turnRecovery.tick().catch(error=>console.error('Turn recovery',error.message)),10000).unref();
const completions=new CompletionRecovery(db,(threadId,title,body,kind,turnId,prompt)=>notify(threadId,title,body,kind,[],null,null,turnId,prompt?promptContext(prompt):null));
function notify(threadId,title,body,kind='update',attachments=[],requestId=null,speech=null,turnId=null,context=null,onPersist=null) {
  title=notificationTitles.title(threadId,title);
  const saved=threadId?db.prepare('SELECT * FROM speech_contexts WHERE thread_id=?').get(threadId):null;
  const speechContext=context||(saved&&(!turnId||saved.turn_id===turnId)?saved.context:'Task update');
  const at=now();
  let n;
  db.exec('BEGIN IMMEDIATE');
  try{
    const r=db.prepare('INSERT OR IGNORE INTO notifications(thread_id,title,body,kind,attachments,created_at,spoken_summary,source_turn_id,spoken_text) VALUES(?,?,?,?,?,?,?,?,?)').run(threadId,title,body,kind,JSON.stringify(attachments),at,spokenSummary(title,body,speech,speechContext),turnId,spokenText(title,body,speech,speechContext));
    if(!r.changes){db.exec('COMMIT');return null;}
    n={id:Number(r.lastInsertRowid),thread_id:threadId,title_revision:notificationTitles.revision(threadId),title,body,kind,attachments,created_at:at,spoken_summary:spokenSummary(title,body,speech,speechContext),spoken_text:spokenText(title,body,speech,speechContext)};
    if(threadId&&['question','approval','error'].includes(kind))db.prepare('INSERT INTO notification_attention(notification_id,request_id) VALUES(?,?)').run(n.id,requestId);
    sessionCatchup.recordNotification({...n,turnId,requestId});onPersist?.(n);push.enqueue(n.id,{flush:false});db.exec('COMMIT');
  }catch(e){db.exec('ROLLBACK');throw e;}
  emit('notification',{notification:n});void push.flush().catch(e=>console.error('Push retry',e.message));return n;
}
function resolveAttention(where,...args){
  const resolved=db.prepare(`SELECT notification_id,request_id FROM notification_attention WHERE resolved_at IS NULL AND (${where})`).all(...args);
  const ids=resolved.map(x=>x.notification_id);for(const row of resolved)if(row.request_id)sessionCatchup.resolve(row.request_id);
  if(!ids.length)return;
  db.prepare(`UPDATE notification_attention SET resolved_at=? WHERE notification_id IN (${ids.map(()=>'?').join(',')})`).run(now(),...ids);
  emit('attentionResolved',{ids});
}
// Native request IDs belong to the current bridge connection and cannot be answered after restart.
resolveAttention('request_id IS NOT NULL');
const questionActions=new QuestionActions({pending,codex,db,resolve:resolveAttention});
const historyRecovery=new Map();
function observeCompletionSnapshot(thread,{latest=false}={}){
  const turn=thread.turns?.at(-1);
  if(latest&&turn?.id&&db.prepare('SELECT 1 FROM watches WHERE thread_id=? AND enabled=1').get(thread.id)){
    const checkpoint=db.prepare('SELECT * FROM completion_watches WHERE thread_id=?').get(thread.id);
    const recovered=turnRecovery.observeMissed(thread,checkpoint,!!completions.seen(thread.id,turn.id));
    const recovery=turnRecovery.get(thread.id);
    if(recovered||(recovery?.state==='waiting'&&recovery.current_turn===turn.id&&turn.status==='failed'))recoveryQueue.hold(thread.id,recovery.source_turn);
    if(recovered)completions.mark(thread.id,turn.id);
  }
  completions.observe(thread);
}
function recoverHistory(metadata){
  if(historyRecovery.has(metadata.id))return historyRecovery.get(metadata.id);
  const work=(async()=>{
    const positions=[],seen=new Set();let cursor=null;
    while(true){
      const page=await history.read(metadata,{before:cursor,summary:true});if(cursor===null)observeCompletionSnapshot(page,{latest:true});positions.push(cursor);
      if(!completions.needsEarlier(page)||!page._pocketPage?.before)break;
      cursor=page._pocketPage.before;if(seen.has(cursor))throw Error('Codex returned a repeated history cursor.');seen.add(cursor);
    }
    for(const position of positions.reverse())completions.observe(await history.read(metadata,{before:position,summary:true}));
  })();
  historyRecovery.set(metadata.id,work);void work.finally(()=>historyRecovery.delete(metadata.id)).catch(()=>{});
  return work;
}
async function attach(threadId,{before=null,recent=false}={}){
  requireId(threadId); await codex.connect();
  let metadata=(await codex.call('thread/read',{threadId,includeTurns:false})).thread,initial=null;sessionDiscovery.remember(metadata);
  if(!attached.has(threadId)){
    // Opening history inherits the runtime profile; it must not undo an external permission change.
    metadata=(await codex.call('thread/resume',{threadId,excludeTurns:true})).thread;attached.add(threadId);
    if(metadata.historyMode!=='paginated'&&!recent){initial=await history.read(metadata);observeCompletionSnapshot(initial,{latest:true});}
    else if(metadata.historyMode==='paginated'&&db.prepare('SELECT 1 FROM watches WHERE thread_id=? AND enabled=1').get(threadId)){
      if(recent)void recoverHistory(metadata).catch(e=>console.error('History recovery',e.message));
      else await recoverHistory(metadata);
    }
  }
  const latestTurn=initial?.turns?.at(-1);if(latestTurn)rememberSpeechContext(threadId,latestTurn);
  const snapshotRevision=timeline.version;
  const thread=initial||await history.read(metadata,{before,...(recent?{maxItems:8,maxBytes:512000,maxTurns:1,preferPaging:true}:{})});if(!before){timeline.seed(thread,{throughVersion:snapshotRevision});rememberSpeechContext(threadId,thread.turns?.at(-1));}return {...thread,_pocketSnapshotRevision:snapshotRevision};
}
const isActive=t=>t.status?.type==='active';
const savedTurnSettings=id=>{const row=db.prepare('SELECT settings FROM turn_settings WHERE thread_id=?').get(id);return row?JSON.parse(row.settings):null;};
async function sendOutgoing(row,initialThread=null){
  const threadId=row.thread_id,id=row.id;
  if(syncing.has(threadId))return;
  syncing.add(threadId);
  try{
    const t=initialThread||await attach(row.thread_id);
    // Re-read after attachment: the user may have edited or removed the waiting message.
    row=db.prepare('SELECT * FROM outgoing WHERE id=?').get(row.id);
    if(!row||row.state!=='queued')return;
    if(row.mode==='queue'){
      if(isActive(t)||t.turns?.some(x=>x.status==='inProgress'))return;
      const earlier=db.prepare("SELECT 1 FROM outgoing WHERE thread_id=? AND mode='queue' AND state IN ('queued','held','sending') AND rowid<(SELECT rowid FROM outgoing WHERE id=?)").get(row.thread_id,row.id);
      if(earlier)return;
    }
    if(!db.prepare("UPDATE outgoing SET state='sending',updated_at=? WHERE id=? AND state='queued'").run(now(),row.id).changes)return;
    const input=[{type:'text',text:row.text}],overrides=turnOverrides(savedTurnSettings(t.id));
    let result;
    const active=t.turns?.findLast(x=>x.status==='inProgress');
    if(row.mode!=='queue'&&isActive(t)&&active) {
      try {result=await codex.call('turn/steer',{threadId:t.id,expectedTurnId:active.id,input});}
      catch(e){
        // Retry only when Codex explicitly reports that the turn has finished.
        if(e.rpc && /no active turn|no turn in progress/i.test(e.message))result=await codex.call('turn/start',{threadId:t.id,input,clientUserMessageId:row.id,...overrides});else throw e;
      }
    }else if(isActive(t))throw new Error('The active turn is not ready for replies. Try again shortly.');
    else result=await codex.call('turn/start',{threadId:t.id,input,clientUserMessageId:row.id,...overrides});
    db.prepare("UPDATE outgoing SET state='accepted',result=?,updated_at=? WHERE id=?").run(JSON.stringify(result),now(),row.id);
    resolveAttention('request_id IS NULL AND notification_id IN (SELECT id FROM notifications WHERE thread_id=? AND created_at<=?)',row.thread_id,row.created_at||now());
    emit('reply',{id:row.id,threadId:row.thread_id,state:'accepted'});
  }catch(e){
    // A definite busy rejection is safe to retry as a queued next turn; lost acknowledgements aren't.
    const state=row?.mode==='queue'&&e.rpc&&/already active|turn.*in progress|active turn/i.test(e.message)?'queued':ambiguousDelivery(e)?'unknown':'failed';
    // Attachment may fail after a waiting reply was removed. Never resurrect
    // that cancelled row, or overwrite a later accepted outcome.
    const changed=db.prepare("UPDATE outgoing SET state=?,result=?,updated_at=? WHERE id=? AND state IN ('queued','sending')").run(state,e.message,now(),id);
    if(changed.changes)emit('reply',{id,threadId,state,error:e.message});
  }finally{syncing.delete(threadId);}
}
async function flush(){if(!codex.ready)return;for(const row of db.prepare("SELECT * FROM outgoing WHERE state='queued' ORDER BY rowid").all())await sendOutgoing(row);}
db.prepare("UPDATE outgoing SET state='unknown',result='Server restarted during delivery; check the conversation before resending.' WHERE state='sending'").run();

codex.on('event',m=>{
  const p=m.params||{}, threadId=p.threadId || p.thread?.id;
  if(threadId&&(voiceController.owns(threadId)||nativeVoice.owns(threadId)||immersion.ownsThread(threadId)))return;
  const activity=sessionDiscovery.observe(m);
  if(activity){
    if(activity.status||m.method==='item/started'&&p.item?.type==='userMessage')emit('sessionActivity',activity);
    if(!db.prepare('SELECT 1 FROM pocket_discovered_threads WHERE thread_id=?').get(threadId))void sessionDiscovery.discoverLive(threadId).then(thread=>{if(thread)emit('sessionStarted',{threadId,thread});});
  }
  if(m.method==='thread/started'&&p.thread)emit('sessionStarted',{threadId,thread:p.thread});
  const interaction=sessionInteraction(m);if(interaction)emit('sessionInteraction',{...interaction,activityAt:activity?.activityAt});
  threadPreviews.observe(m);activityBoard.observe(m);
  if(m.method==="item/completed"||p.item?.type==="userMessage")conversationNotes.observe(threadId,p.turnId,p.item);
  if(m.method==="turn/completed")for(const item of p.turn?.items||[])conversationNotes.observe(threadId,p.turn.id,item);
  if(threadId&&/^(item\/|turn\/completed)/.test(m.method))liveDelivery.activity(()=>activityBoard.snapshot());
  if(m.method==='account/rateLimits/updated')rateLimits.liveUpdate(p);
  if(m.method==='account/updated'){rateLimits.clear();void rateLimits.refresh({force:true});}
  if(threadId&&attached.has(threadId)){const update=timeline.ingest(m);if(update)emit('timeline',update);}
  if(m.method==='serverRequest/resolved'){pending.delete(String(p.requestId));resolveAttention('request_id=?',String(p.requestId));}
  if(threadId&&attached.has(threadId)&&m.method==='turn/started'){
    if(p.turn?.id)db.prepare('DELETE FROM speech_contexts WHERE thread_id=? AND turn_id IS NOT ?').run(threadId,p.turn.id);
    rememberSpeechContext(threadId,p.turn);
  }
  if(threadId&&attached.has(threadId)&&p.item?.type==='userMessage')rememberSpeechContext(threadId,{id:p.turnId,items:[p.item]});
  if(m.method==='turn/started'&&threadId)resolveAttention('request_id IS NULL AND notification_id IN (SELECT id FROM notifications WHERE thread_id=?)',threadId);
  if(m.id!==undefined && threadId){
    const duplicate=pending.has(String(m.id));
    pending.set(String(m.id),m);
    const question=/requestUserInput/.test(m.method);
    if(!duplicate)notify(threadId,question?'Codex has a question':'Codex needs your attention',p.questions?.map(q=>q.question).join('\n')||p.reason||p.command||p.message||'Open the task to review the request.',question?'question':'approval',[],String(m.id),null,p.turnId);
  }
  const recovering=m.method==='turn/completed'?turnRecovery.observe(threadId,p.turn):false;
  sessionCatchup.observe(m);
  if(m.method==='item/completed' && p.item?.type==='agentMessage')emit('message',{threadId,item:p.item});
  if(m.method==='turn/started'){const recovery=turnRecovery.get(threadId);if(recovery&&['waiting','running'].includes(recovery.state)&&recovery.current_turn!==p.turn?.id)turnRecovery.cancel(threadId);}
  if(m.method==='turn/completed'){
    if(recovering)completions.mark(threadId,p.turn.id);else completions.complete(threadId,p.turn);
    if(['interrupted','failed'].includes(p.turn?.status)){
      if(recovering)recoveryQueue.hold(threadId,turnRecovery.get(threadId).source_turn);
      db.prepare("UPDATE outgoing SET state='held',updated_at=? WHERE thread_id=? AND mode='queue' AND state='queued'").run(now(),threadId);
      emit('reply',{threadId,state:'held'});
    }else if(p.turn?.status==='completed')void flush();
  }
  // Only forward subscribed task events, never unrelated global messages or credentials.
  if(threadId && attached.has(threadId))emit('codex',{event:{method:m.method,...(m.id!==undefined?{id:m.id}:{}),params:{threadId,turnId:p.turnId,...(m.method==='thread/status/changed'?{status:p.status}:{})}}});
});
codex.on('connected',()=>{emit('status',codex.status());void rateLimits.refresh({force:true});});
codex.on('status',status=>emit('status',status));
codex.on('disconnected',error=>{rateLimits.disconnected();console.error('Codex connection',error.code,error.cause?.message||'Socket closed');attached.clear();sessionDiscovery.clearLiveStatus();timeline.clear();pending.clear();resolveAttention('request_id IS NOT NULL');emit('status',codex.status());});
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
app.get('/health',(_req,res)=>res.json({ok:true,codex:codex.ready,push:push.enabled?'fcm':'unconfigured',version:appVersion}));
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
  res.json({token,id,host:hostName,firebase:push.config,local:localMode,...(automation?{automationSecret:automation.secret}:{})});
});
app.use('/api',requireAuth);
mountLosangelex(app,{dir,codex,db,publish:(thread,onPersist)=>notify(thread,'Losangelex needs you','Open the team conversation to review its request.','question',[],null,null,null,null,onPersist),resolve:resolveAttention});
app.get('/api/computer-use',route(async(_req,res)=>res.json(await computerUseStatus())));
mountAppUpdates(app,{updates:new AppUpdates({dir,db,push}),owner,route,onAnnounce:notification=>emit('notification',{notification})});
app.get('/api/coordinator/reporting',(req,res)=>res.json(coordinatorReports.settings(req.device.id)));
app.post('/api/coordinator/reporting',route(async(req,res)=>res.json(coordinatorReports.configure(req.device.id,req.body))));
app.get('/api/coordinator/reporting/:deviceId',owner,(req,res)=>res.json(coordinatorReports.settings(req.params.deviceId)));
app.post('/api/coordinator/reporting/:deviceId',owner,route(async(req,res)=>res.json(coordinatorReports.configure(req.params.deviceId,req.body))));
app.get('/api/coordinator/reports',(req,res)=>{try{res.json(coordinatorReports.inbox(req.device.id,{before:Number(req.query.before||Number.MAX_SAFE_INTEGER),limit:Number(req.query.limit||40)}));}catch(e){res.status(e.status||500).json({error:e.message});}});
app.get('/api/voice/history',(req,res)=>{const before=Number(req.query.before||Number.MAX_SAFE_INTEGER);if(!Number.isSafeInteger(before)||before<=0)return res.status(400).json({error:'Invalid history cursor.'});res.json(voiceController.history(req.device.id,before,{conversationOnly:req.query.conversationOnly==='1'}));});
app.post('/api/voice/start',route(async(req,res)=>{
  await voiceController.ensure(req.device.id);
  let threadId=null;
  if(req.body.threadId){threadId=requireId(req.body.threadId);if(catchupHidden(threadId))return res.status(400).json({error:'Choose a work session.'});await codex.connect();const result=await codex.call('thread/read',{threadId,includeTurns:false});if(result.thread?.id!==threadId)return res.status(409).json({error:'Session identity changed. Choose the intended session again.'});}
  const s=voiceController.setFocus(req.device.id,threadId,{mode:threadId?'direct':'coordinator'});
  if(typeof req.body.fullPermissions==='boolean')db.prepare('UPDATE voice_sessions SET full=? WHERE device=?').run(req.body.fullPermissions?1:0,req.device.id);
  res.json({threadId:s.thread_id,selected:s.selected,focus:s.focus,host:hostName,speech:voiceSpeech.status(),native:true});
}));
app.post('/api/voice/native/start',route(async(req,res)=>res.json(await nativeVoice.start(req.device.id,req.body.sdp,req.body.purpose))));
app.post('/api/voice/native/stop',route(async(req,res)=>{await nativeVoice.stop(req.device.id,req.body.connectionId);res.json({ok:true});}));
app.post('/api/voice/native/input',route(async(req,res)=>res.json(nativeVoice.begin(req.device.id,req.body.connectionId,req.body.turnId))));
app.post('/api/voice/native/commit',route(async(req,res)=>res.json(await nativeVoice.commit(req.device.id,req.body.connectionId,req.body.turnId))));
app.post('/api/voice/native/speak',route(async(req,res)=>res.json(await nativeVoice.speak(req.device.id,req.body.connectionId,req.body.text))));
app.post('/api/voice/native/heartbeat',route(async(req,res)=>{nativeVoice.session(req.device.id,req.body.connectionId);res.json({ok:true});}));
app.post('/api/voice/turns/:id',express.raw({type:'audio/wav',limit:'4mb'}),(req,res,next)=>{try{if(!voiceSpeech.status().configured)return res.status(503).json({error:'Voice is not configured.'});res.status(202).json(voiceController.submit(req.device.id,req.params.id,req.body));}catch(e){next(e);}});
app.post('/api/voice/text',route(async(req,res)=>{res.status(202).json(voiceController.submitText(req.device.id,req.body.turnId,req.body.text,req.body.correctionOf));}));
app.get('/api/voice/turns/:id',(req,res)=>{const row=voiceController.get(req.device.id,req.params.id);if(!row)return res.status(404).json({error:'Voice turn not found.'});res.json(row);});
app.post('/api/voice/turns/:id/presented',route(async(req,res)=>res.json(voiceController.presented(req.device.id,req.params.id))));
app.get('/api/voice/speech',(_req,res)=>res.json(voiceSpeech.status()));
app.post('/api/voice/speech',route(async(req,res)=>{
  if(speechRequests>=2)return res.status(429).json({error:'Speech is busy. Retry shortly.'});
  const controller=new AbortController();
  const cancel=()=>{if(!res.writableEnded)controller.abort();};
  res.on('close',cancel);speechRequests++;
  try{
    const result=await voiceSpeech.synthesize(req.body.text,{signal:controller.signal});
    if(!res.destroyed)res.set({'Content-Type':'audio/wav','Cache-Control':'no-store','X-Pocket-Speech-Model':result.model}).send(result.audio);
  }finally{speechRequests--;res.off('close',cancel);}
}));
app.post('/api/pairing',owner,(_req,res)=>{
  const code=randomBytes(5).toString('hex').toUpperCase();
  db.prepare('INSERT INTO pairing VALUES(?,?)').run(hash(code),now()+15*60000);res.json({code,expires:now()+15*60000});
});
app.get('/api/status',(req,res)=>{void rateLimits.refresh();res.json({...codex.status(),usage:rateLimits.snapshot(),host:hostName,local:localMode,defaultCwd,version:appVersion,device:req.device.name,deviceId:req.device.id,firebase:push.config,push:{enabled:push.enabled,registered:!!db.prepare('SELECT 1 FROM push_tokens WHERE device_id=?').get(req.device.id)}});});
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
  await codex.connect();const r=await codex.call('thread/list',{limit:100,sortKey:'updated_at',sortDirection:'desc',archived:false,useStateDbOnly:true});
  res.json({projects:recentProjects(r.data||[])});
}));
app.post('/api/threads',(req,res)=>{
  try{const row=sessionStarts.enqueue(req.body);res.status(row.state==='started'?200:202).json(row);}
  catch(e){res.status(e.status||500).json({error:e.message});}
});
app.get('/api/session-starts/:id',(req,res)=>{const row=sessionStarts.get(req.params.id);if(!row)return res.status(404).json({error:'Task request not found.'});res.json(row);});
app.get('/api/activity',(_req,res)=>res.json({items:activityBoard.snapshot()}));
app.get('/api/threads',route(async(req,res)=>{
  await codex.connect();
  if(req.query.view==='coordinator'){
    const cursor=typeof req.query.cursor==='string'?req.query.cursor:null;
    if(cursor&&cursor.length>4096)return res.status(400).json({error:'Invalid discovery cursor.'});
    const result=await codex.call('thread/list',{limit:100,sortKey:'updated_at',sortDirection:'desc',archived:false,useStateDbOnly:true,sourceKinds:INTERACTIVE_SOURCES,...(cursor?{cursor}:{})});
    const found=new Map();for(const thread of result.data||[]){if(catchupHidden(thread.id))continue;sessionDiscovery.remember(thread,{archived:false});found.set(thread.id,thread);}
    if(!cursor)for(const row of db.prepare("SELECT d.metadata FROM session_starts s JOIN pocket_discovered_threads d ON d.thread_id=s.thread_id WHERE s.state='started' AND d.archived=0 ORDER BY s.updated_at DESC LIMIT 100").all()){
      const thread=JSON.parse(row.metadata);if(!found.has(thread.id)&&!catchupHidden(thread.id))found.set(thread.id,{...thread,...sessionDiscovery.live.get(thread.id)});
    }
    return res.json({threads:[...found.values()].map(thread=>({id:thread.id,name:thread.name||thread.preview?.slice(0,90)||'Untitled task',preview:thread.preview||'',cwd:thread.cwd,status:thread.status,updatedAt:thread.updatedAt,createdAt:thread.createdAt,activityAt:thread.activityAt,source:thread.source,parentThreadId:thread.parentThreadId,discoveryPending:!!thread.discoveryPending})),nextCursor:result.nextCursor||null,refreshPending:false});
  }
  const r=await sessionDiscovery.list({archived:req.query.archived==='true'});
  const watches=db.prepare('SELECT * FROM watches').all();
  for(const renamed of notificationTitles.reconcile(r.data||[])){emit('threadRenamed',renamed);void push.renameThread(renamed).catch(error=>console.error('Rename push',error.message));}
  res.json({threads:(r.data||[]).filter(t=>!voiceController.owns(t.id)&&!nativeVoice.owns(t.id)&&!immersion.ownsThread(t.id)).map(t=>{
    const followed=!!watches.find(w=>w.thread_id===t.id&&w.enabled);
    const updatedMs=t.updatedAt<1e11?t.updatedAt*1000:t.updatedAt;
    const preview=threadPreviews.get(t,{hydrate:followed||t.status?.type==='active'||Date.now()-updatedMs<15*60000});
    const start=db.prepare("SELECT state FROM outgoing WHERE thread_id=? AND id LIKE 'start-%' ORDER BY created_at DESC LIMIT 1").get(t.id);
    const awaitingStart=start&&['queued','sending'].includes(start.state)&&['idle','notLoaded','pending',undefined].includes(t.status?.type);
    return {id:t.id,name:t.name||t.preview?.slice(0,90)||'Untitled task',...preview,...(awaitingStart&&!preview.preview?{preview:'Starting…',previewRole:'activity',previewKind:'pending'}:{}),cwd:t.cwd,status:awaitingStart?{type:'pending'}:t.status,discoveryPending:!!t.discoveryPending||!!awaitingStart,activityAt:t.activityAt,updatedAt:t.updatedAt,archived:req.query.archived==='true',watched:followed};
  }),refreshPending:!!r.refreshPending});
}));
app.post('/api/threads/:id/recovery',route(async(req,res)=>{
 const threadId=requireId(req.params.id);
 if(req.body.cancel){turnRecovery.cancel(threadId);return res.json({recovery:turnRecovery.get(threadId)});}
 if(req.device.id!=='owner')return res.status(403).json({error:'Only the local owner can schedule recovery of an older failed turn.'});
 const {thread}=await codex.call('thread/read',{threadId,includeTurns:false});
 const snapshot=await history.read(thread,{summary:true});const latest=snapshot.turns?.at(-1);
 if(!latest||latest.id!==req.body.turnId)return res.status(409).json({error:'The failed turn is no longer the latest turn.'});
 const scheduled=turnRecovery.observe(threadId,latest);res.json({scheduled,recovery:turnRecovery.get(threadId)});
}));
app.get('/api/threads/:id',route(async(req,res)=>{
  const raw=await attach(req.params.id,{before:req.query.before||null,recent:req.query.view==='timeline'});
  const t=raw._pocketPage&&req.query.before?raw:timeline.merge(raw,{includeMissing:!raw._pocketPage,includeMissingItems:!raw._pocketPage});
  const requests=[...pending.values()].filter(m=>m.params.threadId===t.id);
  const page=timelinePage(t,requests,{before:raw._pocketPage?null:req.query.before||null,limit:8,notifications:db.prepare('SELECT * FROM notifications WHERE thread_id=?').all(t.id).map(notificationRow)});
  if(raw._pocketPage)Object.assign(page,raw._pocketPage);
  // Paging is bounded on the wire; retain the original thread on the Codex host.
  const turns=(t.turns||[]).slice(-25).map(turn=>({...turn,items:(turn.items||[]).filter(x=>['userMessage','agentMessage','imageGeneration','fileChange','commandExecution'].includes(x.type)).map(x=>x.type==='commandExecution'?{...x,aggregatedOutput:x.aggregatedOutput?.slice(-12000)}:x)}));
  conversationNotes.remember(t);
  const response={recovery:turnRecovery.get(t.id)||null,notes:conversationNotes.list(t.id),turnSettings:savedTurnSettings(t.id),thread:{...t,turns:req.query.view==='timeline'?[]:turns},timeline:page,revision:raw._pocketSnapshotRevision??timeline.version,pending:requests,notifications:notificationReads.decorate(db.prepare('SELECT * FROM notifications WHERE thread_id=? ORDER BY id DESC LIMIT 30').all(t.id).map(notificationRow),req.device.id),outgoing:db.prepare("SELECT id,text,mode,state,result,created_at FROM outgoing WHERE thread_id=? AND state!='cancelled' ORDER BY created_at,rowid").all(t.id),watched:!!db.prepare('SELECT * FROM watches WHERE thread_id=? AND enabled=1').get(t.id)};
  if(!req.query.before)response.catchup=sessionCatchup.capture(req.device.id,t.id,response);
  res.json(response);
}));
app.post('/api/threads/:id/catchup/read',route(async(req,res)=>res.json(sessionCatchup.acknowledgeCapture(req.device.id,requireId(req.params.id),req.body.token))));
app.get('/api/models',route(async(_req,res)=>{
  await codex.connect();res.json({models:(await modelCatalogue(codex)).map(m=>({model:m.model,name:m.displayName,defaultEffort:m.defaultReasoningEffort,efforts:m.supportedReasoningEfforts}))});
}));
app.post('/api/threads/:id/notes',(req,res)=>{try{const id=requireId(req.params.id);conversationNotes.put(id,{...req.body,manual:true});res.json({notes:conversationNotes.list(id)});}catch(e){res.status(e.status||400).json({error:e.message});}});
app.post('/api/threads/:id/notes/remove',(req,res)=>{const id=requireId(req.params.id);conversationNotes.remove(id,String(req.body.id||''));res.json({notes:conversationNotes.list(id)});});
app.post('/api/threads/:id/settings',route(async(req,res)=>{
  const threadId=requireId(req.params.id);await codex.connect();
  const settings=validateTurnSettings(req.body,await modelCatalogue(codex));
  db.prepare('INSERT INTO turn_settings(thread_id,settings) VALUES(?,?) ON CONFLICT(thread_id) DO UPDATE SET settings=excluded.settings').run(threadId,JSON.stringify(settings));
  res.json({settings});
}));
app.post('/api/threads/:id/rename',route(async(req,res)=>{
  const threadId=requireId(req.params.id),name=String(req.body.name||'').trim();
  if(!name||name.length>120)return res.status(400).json({error:'Name must be 1–120 characters.'});
  await codex.connect();await codex.call('thread/name/set',{threadId,name});
  const renamed=notificationTitles.rename(threadId,name);
  emit('threadRenamed',renamed);void push.renameThread(renamed).catch(error=>console.error('Rename push',error.message));
  res.json({ok:true,...renamed});
}));
app.post('/api/threads/:id/unarchive',route(async(req,res)=>{
  const threadId=requireId(req.params.id);await codex.connect();
  await codex.call('thread/unarchive',{threadId});sessionDiscovery.markArchived(threadId,false);res.json({ok:true});
}));
app.post('/api/threads/:id/archive',route(async(req,res)=>{
  const t=await attach(req.params.id);
  if(isActive(t)||t.turns?.some(x=>x.status==='inProgress'))return res.status(409).json({error:'Stop the running turn before archiving.'});
  turnRecovery.cancel(t.id);
  await codex.call('thread/archive',{threadId:t.id});sessionDiscovery.markArchived(t.id,true);
  db.prepare("UPDATE outgoing SET state='held',updated_at=? WHERE thread_id=? AND state='queued'").run(now(),t.id);
  db.prepare('UPDATE watches SET enabled=0 WHERE thread_id=?').run(t.id);attached.delete(t.id);res.json({ok:true});
}));
app.post('/api/threads/:id/interrupt',route(async(req,res)=>{
  turnRecovery.cancel(requireId(req.params.id));
  const t=await attach(req.params.id);const active=t.turns?.findLast(x=>x.status==='inProgress');
  if(!active)return res.status(409).json({error:'This task has already stopped.'});
  // Hold the queue before stopping so an idle snapshot cannot restart the task.
  db.prepare("UPDATE outgoing SET state='held',updated_at=? WHERE thread_id=? AND mode='queue' AND state='queued'").run(now(),t.id);
  emit('reply',{threadId:t.id,state:'held'});
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
  const threadId=requireId(req.params.id), text=String(req.body.text||'').trim(), id=String(req.body.id||randomUUID()), mode=req.body.mode||'auto';
  if(!text||text.length>32000||id.length>100)return res.status(400).json({error:'Reply must be 1–32000 characters.'});
  if(!['auto','steer','queue'].includes(mode))return res.status(400).json({error:'Choose steer or queue.'});
  const existing=db.prepare('SELECT * FROM outgoing WHERE id=?').get(id);
  if(existing){if(existing.thread_id!==threadId||existing.text!==text||existing.mode!==mode)return res.status(409).json({error:'Reply id already belongs to a different message.'});return res.json({id,state:existing.state});}
  turnRecovery.cancel(threadId);
  db.prepare('INSERT INTO outgoing(id,thread_id,text,mode,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?)').run(id,threadId,text,mode,'queued',now(),now());
  const wasFollowing=!!db.prepare('SELECT 1 FROM watches WHERE thread_id=? AND enabled=1').get(threadId);
  // A phone reply explicitly opts this task into a completion notification.
  db.prepare('INSERT INTO watches(thread_id,name,enabled) VALUES(?,?,1) ON CONFLICT(thread_id) DO UPDATE SET enabled=1').run(threadId,'Codex replied');
  if(!wasFollowing)completions.follow(threadId);
  // Record accepted user guidance without claiming older results were read.
  sessionCatchup.interact(req.device.id,threadId);
  res.status(202).json({id,state:'queued'}); if(codex.ready)void sendOutgoing({id,thread_id:threadId,text,mode,created_at:now()});
}));
app.post('/api/threads/:id/queue/resume',route(async(req,res)=>{
  const threadId=requireId(req.params.id);
  db.prepare("UPDATE outgoing SET state='queued',updated_at=? WHERE thread_id=? AND mode='queue' AND state='held'").run(now(),threadId);
  emit('reply',{threadId,state:'queued'});res.json({ok:true});void flush();
}));
app.post('/api/threads/:id/replies/:replyId',route(async(req,res)=>{
  const threadId=requireId(req.params.id),id=req.params.replyId;
  const outcome=recoverReply(db,threadId,id,req.body,now());
  if(outcome.error)return res.status(outcome.status).json({error:outcome.error});
  emit('reply',{threadId,id,state:outcome.state});res.json({id,state:outcome.state});if(outcome.dispatch)void sendOutgoing({id,thread_id:threadId});
}));
app.get('/api/replies/:id',(req,res)=>{
  const row=db.prepare('SELECT id,thread_id,state,result FROM outgoing WHERE id=?').get(req.params.id);
  if(!row)return res.status(404).json({error:'Reply not found.'});
  res.json(row);
});
app.post('/api/requests/:id/answer',route(async(req,res)=>res.json(questionActions.answer(req.params.id,req.body))));
app.post('/api/notifications/:id/presented',route(async(req,res)=>res.json(sessionCatchup.presentedNotification(req.device.id,req.params.id,requireId(req.body.threadId)))));
app.post('/api/notifications/:id/skip',route(async(req,res)=>res.json(questionActions.skipNotification(req.params.id))));
app.get('/api/immersion',(req,res)=>res.json(immersion.snapshot(req.device.id)));
app.post('/api/immersion',route(async(req,res)=>res.json(immersion.setEnabled(req.device.id,req.body.enabled,req.body.density))));
app.post('/api/immersion/translate',route(async(req,res)=>res.json(immersion.submit(req.device.id,req.body.sources))));
app.post('/api/threads/:id/notifications/read',route(async(req,res)=>res.json(notificationReads.ack(req.device.id,requireId(req.params.id),req.body.throughId))));
app.get('/api/attention',(req,res)=>res.json({notifications:notificationReads.decorate(db.prepare('SELECT n.* FROM notifications n JOIN notification_attention a ON a.notification_id=n.id WHERE a.resolved_at IS NULL ORDER BY n.id DESC LIMIT 100').all().map(notificationRow),req.device.id)}));
app.get('/api/notifications/:id/attention',(req,res)=>{
  const row=db.prepare('SELECT * FROM notification_attention WHERE notification_id=?').get(req.params.id);
  res.json({needsAttention:!!row&&row.resolved_at===null});
});
app.get('/api/notifications/:id',(req,res)=>{const n=db.prepare('SELECT * FROM notifications WHERE id=?').get(req.params.id);if(!n||n.kind==='coordinator_report'&&!db.prepare('SELECT 1 FROM coordinator_report_notifications WHERE notification_id=? AND device_id=?').get(n.id,req.device.id))return res.status(404).json({error:'Notification not found.'});res.json({notification:notificationReads.decorate([notificationRow(n)],req.device.id)[0]});});
app.get('/api/notifications',(req,res)=>{const after=Math.max(0,Number(req.query.after)||0);res.json({notifications:notificationReads.decorate(db.prepare("SELECT * FROM notifications WHERE id>? AND kind!='coordinator_report' ORDER BY id DESC LIMIT 100").all(after).reverse().map(notificationRow),req.device.id)});});
app.post('/api/test-notification',(req,res)=>res.json(notify(null,'Your work, within reach.','NextComp is connected. Updates from Codex will arrive here, with a direct route back to your task.','test')));
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
  if(b.spoken_summary!==undefined&&(typeof b.spoken_summary!=='string'||b.spoken_summary.length>32000))return res.status(400).json({error:'Spoken text must be at most 32000 characters.'});
  if(b.spoken_context!==undefined&&(typeof b.spoken_context!=='string'||b.spoken_context.length>120))return res.status(400).json({error:'Spoken context must be a short prompt summary.'});
  const t=await attach(threadId);
  db.prepare('INSERT OR IGNORE INTO watches(thread_id,name,enabled) VALUES(?,?,0)').run(threadId,t.name||'Codex');
  const attachments=(b.files||[]).slice(0,5).map(f=>saveAttachment(f,threadId));
  res.json({notification:notify(threadId,String(b.title||t.name||'Codex update').slice(0,180),String(b.message),['update','complete','question','error'].includes(b.kind)?b.kind:'update',attachments,null,b.spoken_summary,null,promptContext(turnPrompt(t.turns?.at(-1)),b.spoken_context))});
}));
app.get('/api/files/:id',(req,res)=>{const f=db.prepare('SELECT * FROM files WHERE id=?').get(req.params.id);if(!f)return res.sendStatus(404);res.set('Content-Type',f.mime);res.set('Content-Disposition',`inline; filename*=UTF-8''${encodeURIComponent(f.name)}`);res.sendFile(f.disk_path);});
app.get('/api/devices',owner,(_req,res)=>res.json({devices:db.prepare('SELECT id,name,created_at,last_seen FROM devices').all()}));
app.delete('/api/devices/:id',owner,(req,res)=>{db.prepare('DELETE FROM devices WHERE id=?').run(req.params.id);for(const s of sockets.clients)if(s.deviceId===req.params.id)s.close();res.json({ok:true});});
server.on('upgrade',(req,socket,head)=>{
  const device=identity(req);if(!device||req.url!=='/events'){socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');socket.destroy();return;}
  sockets.handleUpgrade(req,socket,head,ws=>{ws.deviceId=device.id;ws.alive=true;ws.on('pong',()=>ws.alive=true);ws.on('error',()=>{});db.prepare('UPDATE devices SET last_seen=? WHERE id=?').run(now(),device.id);ws.send(JSON.stringify({type:'status',...codex.status()}));ws.send(JSON.stringify({type:'rateLimits',usage:rateLimits.snapshot()}));void rateLimits.refresh();sockets.emit('connection',ws,req);});
});
setInterval(()=>{for(const ws of sockets.clients){if(!ws.alive){ws.terminate();continue;}ws.alive=false;ws.ping();}},25000).unref();
app.use((err,req,res,next)=>{console.error(req.method,req.path,err.code||'',err.message);if(res.destroyed||res.headersSent)return;res.status(err.status||400).json({error:err.message,...(err.code?{code:err.code}:{})});});
const port=Number(process.env.PORT||18880);
server.listen(port,'127.0.0.1',()=>console.log(`NextComp listening on 127.0.0.1:${port}`));
