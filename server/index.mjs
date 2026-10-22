import {CoordinatorCollector} from './coordinator-collector.mjs';
import {NativeLiveInput} from './native-live-input.mjs';
import {createCoordinatorHub} from './coordinator-hub.mjs';
import {NativeSubscriptions} from './native-subscriptions.mjs';
import {NativeTimelineReader,projectNativeTimeline} from './native-timeline.mjs';
import {nativeJsonCompression} from './json-compression.mjs';
import {readThreadProjection} from './thread-projection.mjs';
import {MAX_RECORDING_WAV_BYTES,MAX_RECORDING_SECONDS} from './audio-limits.mjs';
import {HumanIngress,admissionTransaction} from './human-ingress.mjs';
import {CreditObservations} from './credit-observations.mjs';
import {CatalogContext} from './catalog-context.mjs';
import {WorkObservations} from './work-observations.mjs';
import {InternalSessions} from './internal-sessions.mjs';
import {RecoveryQueue} from './recovery-queue.mjs';
import {TurnRecovery,permissionRecoveryAudit} from './turn-recovery.mjs';
import {recoveryStorageReady,recoveryUsagePolicy,recoveryAccountUsage} from './recovery-health.mjs';
import {SessionDiscovery,INTERACTIVE_SOURCES} from './session-discovery.mjs';
import {catalogFields,catalogName,sessionIdentity,sessionWorkTime,requireDirectSessionInput} from './session-catalog.mjs';
import {SessionCatchup} from './session-catchup.mjs';
import {CoordinatorReports} from './coordinator-reports.mjs';
import {recoverReply,pendingOutgoing} from './reply-recovery.mjs';
import {mountLosangelex} from './losangelex.mjs';
import {mountHollywood,HollywoodGateway,loadHollywood} from './hollywood.mjs';
import {teamNotificationFields} from './losangelex-attention.mjs';
import {computerUseStatus} from './computer-use.mjs';
import {NotificationTitles} from './notification-titles.mjs';
import {NotificationReads} from './notification-reads.mjs';
import {ImmersionWorker} from './immersion-worker.mjs';
import {transgrammaticContract} from './immersion-transgrammatic.mjs';
import {QuestionActions} from './question-actions.mjs';
import {ConversationNotes} from "./conversation-notes.mjs";
import {LiveActivityDelivery} from "./live-activity-delivery.mjs";
import {ActivityBoard} from './activity-board.mjs';
import {NativeVoice} from './native-voice.mjs';
import {NativeSpeechMedia} from './native-speech-media.mjs';
import {VoiceRenderings,SPEECH_POLICY} from './voice-renderings.mjs';
import {NativeAudioTranscriber} from './native-audio-transcriber.mjs';
import {VoiceRecordings} from './voice-recordings.mjs';
import {RemoteAudioTranscriber} from './remote-audio-transcriber.mjs';
import {RoomVoice} from './room-voice.mjs';
import {HollywoodVoice} from './hollywood-voice.mjs';
import {HollywoodPush} from './hollywood-push.mjs';
import {RoomProgress} from './room-progress.mjs';
import {Losangelex,loadLosangelex} from './losangelex.mjs';
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
import {HistoryHeads} from './history-heads.mjs';
import { ThreadHistory } from './history.mjs';
import {SendIdentity} from './send-identity.mjs';
import {TerminalForks,TerminalForkImports} from './terminal-forks.mjs';
import {sshTerminalFork} from './terminal-fork-transport.mjs';
import { WorkRecord } from './work-record.mjs';
import { ambiguousDelivery } from './connection-errors.mjs';
import { AccountRateLimits } from './rate-limits.mjs';
import { modelCatalogue,validateTurnSettings,turnOverrides } from './turn-settings.mjs';
import { VoiceSpeech } from './voice-speech.mjs';
import { VoiceController } from './voice-controller.mjs';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const dir=process.env.POCKET_DATA || resolve(root,'data');
const creditContinuationPath=resolve(dir,'existing-credit-continuation.json');
const creditContinuation=existsSync(creditContinuationPath)?JSON.parse(readFileSync(creditContinuationPath,'utf8')):null;
const appVersion=JSON.parse(readFileSync(resolve(root,'package.json'),'utf8')).version;
const hostName=process.env.POCKET_HOST_NAME||hostname();
const localMode=process.env.POCKET_LOCAL==='1';
const defaultCwd=process.env.POCKET_DEFAULT_CWD||process.cwd();
const {db,secrets}=openStore(dir);
const hollywood=new HollywoodGateway(loadHollywood(dir));
const nativeHollywood=process.env.POCKET_EXECUTION_BACKEND==='hollywood'||!!hollywood.config?.native;
const sendIdentity=new SendIdentity(db);
const workObservations=new WorkObservations(db);
const catalogContext=new CatalogContext(db);
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
if(nativeHollywood)push.allowedKinds=new Set(['app_update']);
setInterval(()=>void push.flush().catch(e=>console.error('Push retry',e.message)),5000).unref();
const codex=new Codex();
const nativeTimelineReader=new NativeTimelineReader(codex);
const nativeSubscriptions=new NativeSubscriptions(codex);
const app=express(); app.disable('x-powered-by'); app.use(express.json({limit:'1mb'}));
app.use('/api',nativeJsonCompression());
const server=http.createServer(app), sockets=new WebSocketServer({noServer:true,maxPayload:16384});
const hollywoodPush=nativeHollywood?new HollywoodPush({db,gateway:hollywood,push,publish:(device,event)=>{
 for(const ws of sockets.clients)if(ws.deviceId===device&&ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify(event));
}}):null;
if(hollywoodPush)setInterval(()=>void hollywoodPush.tick().catch(error=>console.error('Hollywood inbox',error.code||error.name)),5000).unref();
const attached=new Set(), pending=new Map(), syncing=new Set();
db.exec('CREATE TABLE IF NOT EXISTS immersion_profiles(device_id TEXT PRIMARY KEY,state TEXT NOT NULL)');
const immersionCwd=resolve(dir,'immersion');mkdirSync(immersionCwd,{recursive:true,mode:0o700});
const internalSessions=new InternalSessions(db,[{cwd:immersionCwd,kind:'immersion'}]);
// Session, coordinator and immersion RPCs share one multiplexed stock runtime.
// Separate threads retain their own instructions, model choices and routing.
const voiceCodex=codex;
const humanIngress=new HumanIngress(db);
const voiceController=new VoiceController({db,codex:voiceCodex,cwd:defaultCwd,host:hostName,
  api:async(path,body,actor)=>{const delegation=humanIngress.delegate(actor,body===undefined?'GET':'POST',path,body);try{const r=await fetch(`http://127.0.0.1:${server.address().port}${path}`,{headers:{Authorization:`Bearer ${secrets.adminToken}`,'Content-Type':'application/json',...delegation.headers},...(body===undefined?{}:{method:'POST',body:JSON.stringify(body)}),signal:AbortSignal.timeout(35000)});const data=await r.json();if(!r.ok)throw Error(data.error||'Pocket control failed.');return data;}finally{delegation.release();}},
  transcribe:async audio=>{
    if(!voiceSpeech.apiKey)throw Object.assign(Error('Voice is not configured on this server.'),{status:503});
    const form=new FormData();form.append('model','gpt-transcribe');form.append('file',new Blob([audio],{type:'audio/wav'}),'turn.wav');form.append('response_format','json');form.append('prompt','Pocket, Codex, steer, queue, workstation, session, full permissions.');
    const r=await fetch('https://api.openai.com/v1/audio/transcriptions',{method:'POST',headers:{Authorization:`Bearer ${voiceSpeech.apiKey}`},body:form,signal:AbortSignal.timeout(60000)});
    if(!r.ok)throw Error(`Transcription provider returned HTTP ${r.status}.`);return (await r.json()).text||'';
  }});

voiceController.authorizeQueued=async device=>{
 if(!db.prepare('SELECT 1 FROM devices WHERE id=?').get(device))throw Object.assign(Error('The original device is no longer authorized.'),{status:403});
};

const audioCommand=process.env.POCKET_LAX_AUDIO_COMMAND?JSON.parse(process.env.POCKET_LAX_AUDIO_COMMAND):null;
if(audioCommand&&(!Array.isArray(audioCommand)||!audioCommand.length||audioCommand.some(v=>typeof v!=='string'||!v)))throw Error('Invalid installation-owned native audio command.');
// Audio transport has no project workspace. Never inherit the backend checkout's local config or instructions.
const nativeAudioWorkspace=resolve(dir,'native-audio-workspace');
mkdirSync(nativeAudioWorkspace,{recursive:true,mode:0o700});
const nativeVoiceCodex=new Codex(undefined,{command:audioCommand,processCwd:nativeAudioWorkspace});
const immersion=new ImmersionWorker({codex:nativeHollywood?nativeVoiceCodex:codex,cwd:immersionCwd,registerThread:id=>internalSessions.register(id,'immersion'),load:id=>{const row=db.prepare('SELECT state FROM immersion_profiles WHERE device_id=?').get(id);return row?JSON.parse(row.state):null;},save:(id,state)=>db.prepare('INSERT INTO immersion_profiles VALUES(?,?) ON CONFLICT(device_id) DO UPDATE SET state=excluded.state').run(id,JSON.stringify(state)),publish:(id,event)=>{const message=JSON.stringify(event);for(const ws of sockets.clients)if(ws.deviceId===id&&ws.readyState===WebSocket.OPEN)ws.send(message);}});
// Existing devices retain their old endpoint/cache; reuse native authenticated transport.
db.exec('CREATE TABLE IF NOT EXISTS immersion_transgrammatic_profiles(device_id TEXT PRIMARY KEY,state TEXT NOT NULL)');
const transgrammaticImmersion=new ImmersionWorker({
 codex:nativeHollywood?nativeVoiceCodex:codex,cwd:immersionCwd,contract:transgrammaticContract,
 registerThread:id=>internalSessions.register(id,'immersion'),
 load:id=>{const row=db.prepare('SELECT state FROM immersion_transgrammatic_profiles WHERE device_id=?').get(id);if(row)return JSON.parse(row.state);const old=immersion.snapshot(id);return {enabled:old.enabled,density:old.density,translations:[]};},
 save:(id,state)=>db.prepare('INSERT INTO immersion_transgrammatic_profiles VALUES(?,?) ON CONFLICT(device_id) DO UPDATE SET state=excluded.state').run(id,JSON.stringify(state)),
 publish:(id,event)=>{const message=JSON.stringify(event);for(const ws of sockets.clients)if(ws.deviceId===id&&ws.readyState===WebSocket.OPEN)ws.send(message);}
});

const hollywoodVoice=new HollywoodVoice({db,gateway:hollywood});
const roomVoice=new RoomVoice({db,client:new Losangelex(loadLosangelex(dir))});
const roomVoiceFocus=new Map();
const audioController={db,
 capture:device=>roomVoiceFocus.get(device)?JSON.parse(JSON.stringify(roomVoiceFocus.get(device))):voiceController.capture(device),
 get:(device,id)=>nativeHollywood?hollywoodVoice.refresh(device,id):roomVoice.get(device,id)?roomVoice.refresh(device,id):voiceController.get(device,id),
 submitText:(device,id,text,destination)=>nativeHollywood?hollywoodVoice.submitText(device,id,text,destination):destination?.backend==='stock-codex-voice'?voiceController.submitText(device,id,text,null,destination):destination?roomVoice.submitText(device,id,text,destination):voiceController.submitText(device,id,text),
 mark:(device,id,...args)=>{if(nativeHollywood)return;if(!roomVoice.get(device,id))voiceController.mark(device,id,...args);}
};
const nativeVoice=new NativeVoice({codex:nativeVoiceCodex,controller:audioController,cwd:nativeAudioWorkspace,requireFinalTranscript:nativeHollywood,observe:process.env.POCKET_NATIVE_AUDIO_TRACE?event=>import('node:fs/promises').then(fs=>fs.appendFile(process.env.POCKET_NATIVE_AUDIO_TRACE,JSON.stringify(event)+'\n',{mode:0o600})):null});
const nativeLiveInput=new NativeLiveInput({db,voice:nativeVoice});
// Validate a per-turn destination without consulting or changing mutable device focus.
// Missing destination retains compatibility with already installed clients only.
async function voiceDestination(device,value){
 if(!value||typeof value!=='object'||Array.isArray(value))throw Object.assign(Error('Choose a voice destination.'),{status:400});
 if(value.room){
  if(value.threadId)throw Object.assign(Error('Choose one room or session destination.'),{status:400});
  return nativeHollywood?hollywoodVoice.destination(device,value.room):roomVoice.destination(value.room);
 }
 if(nativeHollywood)throw Object.assign(Error('Choose an exact Hollywood room before recording.'),{status:400});
 const threadId=value.threadId?requireId(value.threadId):null;
 if(threadId){
  if(catchupHidden(threadId))throw Object.assign(Error('Choose a work session.'),{status:400});
  await codex.connect();const result=await codex.call('thread/read',{threadId,includeTurns:false});
  if(result.thread?.id!==threadId)throw Object.assign(Error('Session identity changed.'),{status:409});
  requireDirectSessionInput(result.thread);
 }
 const current=voiceController.capture(device);
 return voiceController.validateOrigin({...current,focus:threadId?'direct':'coordinator',selected:threadId,
  full:typeof value.fullPermissions==='boolean'?value.fullPermissions:current.full});
}

const nativeAudioPython=process.env.POCKET_NATIVE_AUDIO_PYTHON||null;
const remoteAudioCommand=process.env.POCKET_NATIVE_AUDIO_REMOTE?JSON.parse(process.env.POCKET_NATIVE_AUDIO_REMOTE):null;
const nativeAudioIntake=!!(nativeAudioPython||remoteAudioCommand);
const nativeTranscriber=remoteAudioCommand?new RemoteAudioTranscriber(remoteAudioCommand):new NativeAudioTranscriber({voice:nativeVoice,python:nativeAudioPython});
const nativeSpeechEnabled=!!nativeAudioPython&&process.env.POCKET_NATIVE_SPEECH_ASSETS==='1';
const nativeSpeechMedia=new NativeSpeechMedia({voice:nativeVoice,python:nativeAudioPython});
const voiceRenderings=new VoiceRenderings({db,root:resolve(dir,'voice-renderings'),render:request=>nativeSpeechMedia.render(request)});
const voiceRecordings=new VoiceRecordings({
 db,root:resolve(dir,'voice-recordings'),
 resolveDestination:voiceDestination,
 transcribe:request=>nativeLiveInput.cached(request)||nativeTranscriber.transcribe(request),
 submit:(device,id,text,destination,principal)=>{
  humanIngress.record('voice-recording',voiceRecordings.key(device,id),principal);
  return audioController.submitText(device,id,text,destination);
 },
 getReceipt:(device,id)=>audioController.get(device,id),
 cancelTask:(device,id)=>{
  if(nativeHollywood||roomVoice.get(device,id))throw Object.assign(Error('Use the original room work controls.'),{status:409});
  return voiceController.cancelQueued(device,id);
 },
 authorize:async(device,principal)=>{
  if(principal.deviceId!==device||!db.prepare('SELECT 1 FROM devices WHERE id=?').get(device))
   throw Object.assign(Error('The original human device is no longer authorized. Recording retained.'),{status:403});
 }
});
const sessionDiscovery=new SessionDiscovery({db,codex,hidden:(id,thread)=>internalSessions.owns(id,thread)||voiceController.owns(id)||nativeVoice.owns(id)||immersion.ownsThread(id)});
const catchupHidden=id=>internalSessions.owns(id)||voiceController.owns(id)||nativeVoice.owns(id)||immersion.ownsThread(id)||!!db.prepare('SELECT archived FROM pocket_discovered_threads WHERE thread_id=?').get(id)?.archived;
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
  // Scheduled reports consume stored observations only: no runtime/model RPCs.
  readThread:async threadId=>{const row=db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE thread_id=?').get(threadId);if(!row)throw Error('No stored session observation.');const thread=JSON.parse(row.metadata);return {thread:{...thread,status:{type:'unverified'}}};},
  refreshRoster:async()=>{const threads=db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE archived=0').all().map(row=>JSON.parse(row.metadata)).filter(thread=>!catchupHidden(thread.id));return {threads,threadCount:threads.length,complete:false,reason:'Stored public observations only; this scheduled report does not poll or wake workers.'};},
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
const coordinatorHub=createCoordinatorHub({db,controller:voiceController,catchup:sessionCatchup,hidden:catchupHidden,key:secrets.adminToken,authorize:actor=>humanIngress.active(actor),app,route:fn=>route(fn),read:async path=>{const result=await fetch(`http://127.0.0.1:${server.address().port}${path}`,{headers:{Authorization:`Bearer ${secrets.adminToken}`},signal:AbortSignal.timeout(2500)});if(!result.ok)throw Error('Native public evidence unavailable');return result.json();}});
const coordinatorCollector=new CoordinatorCollector({db,discovery:sessionDiscovery,memory:coordinatorHub.memory,catchup:sessionCatchup,hidden:catchupHidden,
 ready:()=>!nativeHollywood&&codex.ready,subscribe:thread=>nativeSubscriptions.observe(thread,{alreadyAttached:attached.has(thread.id)}),
 read:path=>coordinatorHub.memory.read(path)});
coordinatorHub.memory.collection=()=>coordinatorCollector.status();
if(!nativeHollywood)setInterval(()=>void coordinatorCollector.tick().catch(error=>console.error('Coordinator evidence collection unavailable',error.code||error.name)),15000).unref();
if(!nativeHollywood)setInterval(()=>void coordinatorReports.tick().catch(error=>console.error('Coordinator report',error.message)),60000).unref();

const timeline=new LiveTimeline();
const historyHeads=new HistoryHeads();
setInterval(()=>timeline.prune(),60000).unref();
const conversationNotes=new ConversationNotes(db,(threadId,notes)=>emit("contextNotes",{threadId,notes}));
// Notes hydrate when their conversation opens, not while hundreds of list cards load.
const inheritedHistoryCache=new Map();let inheritedHistoryCacheBytes=0;
const history=new ThreadHistory(codex,{identity:sendIdentity,inheritedHistory:id=>{const cached=inheritedHistoryCache.get(id);if(cached){inheritedHistoryCache.delete(id);inheritedHistoryCache.set(id,cached);return cached.turns;}if(!db.prepare("SELECT 1 FROM sqlite_master WHERE name='terminal_fork_imports'").get())return [];const row=db.prepare("SELECT json_extract(packet,'$.publicHistory') AS history FROM terminal_fork_imports WHERE fork_id=? AND state IN ('prepared','accepted','unknown')").get(id);if(!row?.history)return [];const turns=JSON.parse(row.history),bytes=Buffer.byteLength(row.history);if(bytes<=8*1024*1024){while(inheritedHistoryCache.size&&(inheritedHistoryCacheBytes+bytes>8*1024*1024||inheritedHistoryCache.size>=16)){const key=inheritedHistoryCache.keys().next().value;inheritedHistoryCacheBytes-=inheritedHistoryCache.get(key).bytes;inheritedHistoryCache.delete(key);}inheritedHistoryCache.set(id,{turns,bytes});inheritedHistoryCacheBytes+=bytes;}return turns;}});
const workRecord=new WorkRecord(db,{version:appVersion});
const recordWork=fn=>{try{fn();}catch(e){console.error("Work record storage unavailable:",e.code||e.name);}};
for(const row of db.prepare("SELECT thread_id FROM watches WHERE enabled=1 UNION SELECT thread_id FROM outgoing UNION SELECT thread_id FROM session_starts WHERE thread_id IS NOT NULL").all())workRecord.enroll({id:row.thread_id});
let scanningWork=false;
setInterval(async()=>{
  if(nativeHollywood||scanningWork||!codex.ready)return;
  scanningWork=true;
  try{await workRecord.scan(history,id=>JSON.parse(db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE thread_id=?').get(id)?.metadata||'{}'));}
  catch(e){console.error('Work record history unavailable:',e.code||e.name);}
  finally{scanningWork=false;}
},15000).unref();
const activityBoard=new ActivityBoard();
const liveDelivery=new LiveActivityDelivery((type,payload)=>emit(type,payload));
const threadPreviews=new ThreadPreviews(history,(threadId,preview)=>liveDelivery.preview(threadId,preview));
const sessionStarts=new SessionStarts(db,codex,(thread,row)=>{const known={...thread,activityAt:Date.now(),name:thread.name||row.text?.slice(0,90)||'New task',status:thread.status?.type==='active'?thread.status:{type:'pending'},discoveryPending:true};sessionDiscovery.remember(known);recordWork(()=>workRecord.enroll(known));attached.add(thread.id);timeline.seed(thread);completions.follow(thread.id,thread);emit('sessionStarted',{threadId:thread.id,thread:known});void sendOutgoing(row,thread);},humanIngress);
const now=()=>Date.now();
const emit=(type,payload)=>{const m=JSON.stringify({type,...payload}); for(const s of sockets.clients)if(s.readyState===WebSocket.OPEN)s.send(m);};
const rateLimits=new AccountRateLimits(nativeHollywood?nativeVoiceCodex:codex,usage=>emit('rateLimits',{usage}),{observations:new CreditObservations(db,secrets.adminToken),creditAuthorization:creditContinuation});
if(nativeHollywood){
  // Observe an already-connected account audio transport; quota reads never
  // launch an agent or open audio just to refresh a number.
  rateLimits.disconnected();
  nativeVoiceCodex.on('connected',()=>void rateLimits.refresh({force:true}));
  nativeVoiceCodex.on('disconnected',()=>rateLimits.disconnected());
  nativeVoiceCodex.on('event',m=>{
    if(m.method==='account/rateLimits/updated')rateLimits.liveUpdate(m.params);
    if(m.method==='account/updated'){rateLimits.clear();void rateLimits.refresh({force:true});}
  });
}
setInterval(()=>{if(!nativeHollywood&&sockets.clients.size)void rateLimits.refresh();},60000).unref();
const safeEqual=(a,b)=>{const x=Buffer.from(a),y=Buffer.from(b);return x.length===y.length&&timingSafeEqual(x,y);};
const getToken=req=>(req.headers.authorization||'').replace(/^Bearer /,'');
const identity=req=>{const t=getToken(req); if(t&&safeEqual(t,secrets.adminToken))return {id:'owner',name:'Local owner'};return db.prepare('SELECT id,name FROM devices WHERE token_hash=?').get(hash(t));};
const requireAuth=(req,res,next)=>{req.device=identity(req); if(!req.device)return res.status(401).json({error:'Pair this device to continue.'});next();};
const owner=(req,res,next)=>{if(req.device.id!=='owner')return res.status(403).json({error:'Local owner access required'});next();};
const route=fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next);
const requireId=id=>{if(typeof id!=='string'||!/^[a-zA-Z0-9_-]{5,100}$/.test(id))throw new Error('Invalid thread identifier');return id;};
const terminalForkConfigPath=resolve(dir,'terminal-forks.json');
const terminalForkConfig=existsSync(terminalForkConfigPath)?JSON.parse(readFileSync(terminalForkConfigPath,'utf8')):null;
const terminalForkImports=terminalForkConfig?new TerminalForkImports({db,codex,codeHome:terminalForkConfig.codeHome,environmentId:terminalForkConfig.environmentId,directory:resolve(dir,'terminal-forks','imports'),onImportPlan:(result,packet)=>{for(const id of result.newIds)internalSessions.register(id,id===packet.bundle.forkId?'terminal-fork-pending':'terminal-fork-ancestor');},onPrepared:(nativeThread,packet)=>{
  const thread={...nativeThread,name:packet.sourceName||nativeThread.name||'Transferred work',status:{type:'pending'},discoveryPending:true};
  db.prepare("DELETE FROM pocket_internal_sessions WHERE thread_id=? AND kind='terminal-fork-pending'").run(thread.id);
  history.fullSnapshotThreads.add(thread.id);sessionDiscovery.remember(thread);attached.add(thread.id);
  db.prepare('INSERT INTO watches(thread_id,name,enabled) VALUES(?,?,1) ON CONFLICT(thread_id) DO UPDATE SET name=excluded.name,enabled=1').run(thread.id,thread.name||'Transferred work');
  emit('sessionStarted',{threadId:thread.id,thread});
}}):null;
if(terminalForkImports)for(const row of db.prepare("SELECT fork_id FROM terminal_fork_imports WHERE state IN ('prepared','accepted','unknown')").all())history.fullSnapshotThreads.add(row.fork_id);
const terminalForks=terminalForkConfig?new TerminalForks({db,codex,codeHome:terminalForkConfig.codeHome,directory:resolve(dir,'terminal-forks','exports'),environmentId:terminalForkConfig.environmentId,targets:terminalForkConfig.targets||{},transport:sshTerminalFork,publicHistory:thread=>[...history.inheritedHistory(thread.id),...(thread.turns||[])],onFork:id=>internalSessions.register(id,'terminal-fork-export'),publish:transfer=>emit('terminalFork',{transfer}),
  readSource:async threadId=>{await codex.connect();let thread=(await codex.call('thread/read',{threadId,includeTurns:false})).thread;if(thread.status?.type==='active')return {thread,turn:null};let turns=(await codex.call('thread/turns/list',{threadId,limit:1,itemsView:'summary',sortDirection:'desc'})).data;
    if(!turns.length){const full=(await codex.call('thread/read',{threadId,includeTurns:true})).thread;turns=full.turns||[];}
    return {thread,turn:turns.at(-1)};},
  checkSource:async threadId=>{
    if([...pending.values()].some(p=>p.params?.threadId===threadId))throw Error('Resolve the source’s pending question or approval before transferring.');
    if(db.prepare("SELECT 1 FROM outgoing WHERE thread_id=? AND state IN ('unknown','sending','queued','held')").get(threadId))throw Error('Reconcile or explicitly resolve the source’s pending outgoing messages before transferring.');
    if(db.prepare('SELECT archived FROM pocket_discovered_threads WHERE thread_id=?').get(threadId)?.archived)throw Error('Archived work is not eligible for self transfer.');
    const policy=await recoveryUsagePolicy(codex,threadId);if(!policy.ok)throw Error(policy.reason||'Source usage policy prevents continuation.');
    const {goal}=await codex.call('thread/goal/get',{threadId});if(goal?.status==='active')throw Error('This source has an automatic native goal. Its budget and autonomous admission need an explicit ownership handover before terminal transfer can run.');
  }
}):null;
if(!nativeHollywood&&terminalForks)setInterval(()=>{if(codex.ready)void terminalForks.tick().catch(e=>console.error('Terminal fork check',e.code||e.name));},30000).unref();
const notificationSessionFields=id=>{const row=db.prepare("SELECT metadata FROM pocket_discovered_threads WHERE thread_id=?").get(id);return row?sessionIdentity(JSON.parse(row.metadata)):{};};
const notificationRow=r=>({...notificationSessionFields(r.thread_id),...r,...teamNotificationFields(db,r.id),title_revision:notificationTitles.revision(r.thread_id),spoken_text:r.spoken_text||spokenText(r.title,r.body),attachments:JSON.parse(r.attachments||'[]')});
function rememberSpeechContext(threadId,turn){
  const prompt=turnPrompt(turn);if(!threadId||!prompt)return;
  db.prepare('INSERT INTO speech_contexts(thread_id,turn_id,context) VALUES(?,?,?) ON CONFLICT(thread_id) DO UPDATE SET turn_id=excluded.turn_id,context=excluded.context').run(threadId,turn.id||null,promptContext(prompt));
}
const recoveryQueue=new RecoveryQueue(db);
const turnRecovery=new TurnRecovery({db,
  read:async threadId=>{await codex.connect();const {thread}=await codex.call('thread/read',{threadId,includeTurns:false});return history.read(thread,{summary:true});},
  health:async(threadId,kind,thread)=>{
    try{terminalForkImports?.inputAllowed(threadId);}catch{return {ok:false,blocked:true,reason:'This destination is waiting for terminal-fork ownership release.'};}
    if(terminalForks&&!terminalForks.recoveryAllowed(threadId))return {ok:false,blocked:true,reason:'This source has a terminal transfer checkpoint. Recovery must not restart its primary task.'};
    if(!codex.ready||syncing.has(threadId))return false;
    if(!recoveryStorageReady(dir))return false;
    const policy=await recoveryUsagePolicy(codex,threadId);if(!policy.ok)return policy;
    if(thread?.goal&&['paused','budgetLimited','usageLimited','tokenLimited'].includes(thread.goal.status))return {ok:false,blocked:true,reason:'The task’s usage-stop policy is paused. Recovery will not override it.'};
    if([...pending.values()].some(m=>m.params?.threadId===threadId))return {ok:false,blocked:true,reason:'This task has an unresolved request that needs your answer before continuing.'};
    if(db.prepare("SELECT 1 FROM outgoing WHERE thread_id=? AND state='unknown'").get(threadId))return {ok:false,blocked:true,reason:'Earlier message delivery is uncertain. Reconcile that message before continuing; it has not been sent again.'};
    if(db.prepare("SELECT 1 FROM outgoing WHERE thread_id=? AND state IN ('sending','queued')").get(threadId))return false;
    await rateLimits.refresh();if(rateLimits.failed)return false;
    const accountUsage=recoveryAccountUsage(rateLimits,creditContinuation);if(!accountUsage.ok)return accountUsage;
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
    if(recovery.kind==='continuation'&&!turnRecovery.continuationEnabled()){turnRecovery.set(threadId,'awaitingAssessment');throw Error('Automatic successful-turn chaining is suspended.');}
    return codex.call('turn/start',{threadId,clientUserMessageId:requestId,input:[{type:'text',text}]});
  },
  publish:(threadId,recovery)=>{if(recovery.state==='completed'&&recoveryQueue.release(threadId,recovery.source_turn)){emit('reply',{threadId,state:'queued'});void flush();}if(recovery.state==='cancelled')recoveryQueue.forget(threadId,recovery.source_turn);emit('turnRecovery',{threadId,recovery});if(recovery.message){sessionCatchup.put(threadId,{key:'recovery:'+recovery.source_turn,kind:'blocked',text:recovery.message,turnId:recovery.current_turn});notify(threadId,'Task recovery',recovery.message,'update');}}
});
sessionCatchup.recoverableFailure=(threadId,turnId)=>turnRecovery.recoverableFailure(threadId,turnId);
if(!nativeHollywood)setInterval(()=>void turnRecovery.tick().catch(error=>console.error('Turn recovery',error.message)),10000).unref();
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
    n={...notificationSessionFields(threadId),id:Number(r.lastInsertRowid),thread_id:threadId,title_revision:notificationTitles.revision(threadId),title,body,kind,attachments,created_at:at,spoken_summary:spokenSummary(title,body,speech,speechContext),spoken_text:spokenText(title,body,speech,speechContext)};
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
  let metadata=(await codex.call('thread/read',{threadId,includeTurns:false})).thread,initial=null;sessionDiscovery.remember(metadata);recordWork(()=>workRecord.enroll(metadata));
  if(sessionIdentity(metadata).isChild&&!sessionIdentity(metadata).canAcceptDirectInput){
    attached.add(threadId);const snapshotRevision=timeline.version,snapshotHistoryHead=historyHeads.snapshot(threadId);const thread=await history.read(metadata,{before,preferPaging:true,...(recent?{maxItems:8,maxBytes:512000,maxTurns:1}:{})});if(!before)timeline.seed(thread,{throughVersion:snapshotRevision});return {...thread,_pocketSnapshotRevision:snapshotRevision,_pocketSnapshotHistoryHead:snapshotHistoryHead};
  }
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
  const snapshotRevision=timeline.version,snapshotHistoryHead=historyHeads.snapshot(threadId);
  const thread=initial||await history.read(metadata,{before,...(recent?{maxItems:8,maxBytes:512000,maxTurns:1,preferPaging:true}:{})});if(!before){timeline.seed(thread,{throughVersion:snapshotRevision});rememberSpeechContext(threadId,thread.turns?.at(-1));}return {...thread,_pocketSnapshotRevision:snapshotRevision,_pocketSnapshotHistoryHead:snapshotHistoryHead};
}
const isActive=t=>t.status?.type==='active';
const savedTurnSettings=id=>{const row=db.prepare('SELECT settings FROM turn_settings WHERE thread_id=?').get(id);return row?JSON.parse(row.settings):null;};
async function sendOutgoing(row,initialThread=null){
  const threadId=row.thread_id,id=row.id;
  if(syncing.has(threadId))return;
  syncing.add(threadId);
  try{
    let sourceRole;try{terminalForkImports?.inputAllowed(threadId);sourceRole=terminalForks?.inputRole(threadId);}catch{return;}
    const t=initialThread||await attach(row.thread_id);requireDirectSessionInput(t);
    // Re-read after attachment: the user may have edited or removed the waiting message.
    row=db.prepare('SELECT * FROM outgoing WHERE id=?').get(row.id);
    if(!row||row.state!=='queued')return;
    if(row.mode==='queue'){
      if(isActive(t)||t.turns?.some(x=>x.status==='inProgress'))return;
      const earlier=db.prepare("SELECT 1 FROM outgoing WHERE thread_id=? AND mode='queue' AND state IN ('queued','held','sending') AND rowid<(SELECT rowid FROM outgoing WHERE id=?)").get(row.thread_id,row.id);
      if(earlier)return;
    }
    if(!db.prepare("UPDATE outgoing SET state='sending',updated_at=? WHERE id=? AND state='queued'").run(now(),row.id).changes)return;
    const input=[{type:'text',text:(sourceRole?sourceRole+'\n\nUser input:\n':'')+row.text}],overrides=turnOverrides(savedTurnSettings(t.id));
    let result;
    const active=t.turns?.findLast(x=>x.status==='inProgress');
    if(row.mode!=='queue'&&isActive(t)&&active) {
      try {result=await codex.call('turn/steer',{threadId:t.id,expectedTurnId:active.id,clientUserMessageId:row.id,input});}
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
  m=sendIdentity.event(m);
  const p=m.params||{}, threadId=p.threadId || p.thread?.id;
  if(threadId&&(internalSessions.owns(threadId,p.thread)||voiceController.owns(threadId)||nativeVoice.owns(threadId)||immersion.ownsThread(threadId)))return;
  const activity=sessionDiscovery.observe(m);
  if(activity){
    if(activity.status||m.method==='item/started'&&p.item?.type==='userMessage')emit('sessionActivity',activity);
    if(!db.prepare('SELECT 1 FROM pocket_discovered_threads WHERE thread_id=?').get(threadId))void sessionDiscovery.discoverLive(threadId).then(thread=>{if(thread)emit('sessionStarted',{threadId,thread:{...thread,...catalogFields(thread)}});});
  }
  if(m.method==='thread/started'&&p.thread)emit('sessionStarted',{threadId,thread:{...p.thread,...catalogFields(p.thread)}});
  const interaction=sessionInteraction(m);if(interaction)emit('sessionInteraction',{...interaction,activityAt:activity?.activityAt});
  threadPreviews.observe(m);activityBoard.observe(m);recordWork(()=>workRecord.observe(m));
  if(m.method==="item/completed"||p.item?.type==="userMessage")conversationNotes.observe(threadId,p.turnId,p.item);
  if(m.method==="turn/completed")for(const item of p.turn?.items||[])conversationNotes.observe(threadId,p.turn.id,item);
  if(threadId&&/^(item\/|turn\/completed)/.test(m.method))liveDelivery.activity(()=>activityBoard.snapshot());
  if(m.method==='account/rateLimits/updated')rateLimits.liveUpdate(p);
  if(m.method==='account/updated'){rateLimits.clear();void rateLimits.refresh({force:true});}
  if(threadId){const update=timeline.ingest(m);if(update){historyHeads.observe(threadId,update.version);liveDelivery.timeline({...update,nativeMethod:m.method,nativeItemId:p.itemId||p.item?.id||null,historyHead:historyHeads.snapshot(threadId)});}}
  if(threadId&&m.method==='thread/realtime/itemCompleted'){
    const version=++timeline.version;historyHeads.observe(threadId,version);
    liveDelivery.timeline({threadId,version,reload:true,historyHead:historyHeads.snapshot(threadId)});
  }
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
  coordinatorHub.memory.observe(m);
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
  if(nativeHollywood||reconnecting)return;reconnecting=true;
  try{
    await codex.connect();
    await sessionStarts.flush();
    for(const w of db.prepare('SELECT thread_id FROM watches').all())if(!attached.has(w.thread_id)){try{await attach(w.thread_id);}catch{if(!codex.ready)break;}}
    await flush();
  }catch(e){ /* Reconnect without changing the running Codex process. */ }finally{reconnecting=false;}
},5000).unref();
if(!nativeHollywood)codex.connect().catch(()=>{});

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
app.use('/api',(req,res,next)=>humanIngress.middleware(req,res,next));
// An enterprise room gateway cannot expose the old single-owner session lanes.
// Retained work stays in the database; native mode never starts its schedulers.
app.use('/api',(req,res,next)=>{
  if(!nativeHollywood)return next();
  const safe=/^\/coordinator\/(?:evidence|receipt|sources|listening|voice-preferences|catchup(?:\/presented)?|voice-updates(?:\/presented)?)$/.test(req.path)||/^\/voice\/turns\/[a-zA-Z0-9_-]{8,100}\/timing$/.test(req.path)||/^\/immersion(?:\/v2)?(?:\/translate)?$/.test(req.path)||req.path.startsWith('/hollywood/v3/')||['/voice/start','/voice/text','/voice/history'].includes(req.path)||/^\/voice\/native\/(start|stop|input|commit|speak|heartbeat|capabilities)$/.test(req.path)||/^\/voice\/native\/input\/(begin|finalize|cancel)$/.test(req.path)||/^\/voice\/turns\/[a-zA-Z0-9_-]{8,100}(?:\/presented)?$/.test(req.path)||/^\/voice\/renderings\/[a-zA-Z0-9_-]{8,100}(?:\/(?:audio|cancel))?$/.test(req.path)||/^\/voice\/recordings(?:\/[a-zA-Z0-9_-]{8,100}(?:\/(?:audio|cancel|retry-audio|cancel-request))?)?$/.test(req.path)||['/backends','/status','/pairing','/devices','/device/push','/device/disconnect','/app/update','/app/update/apk','/app/update/announce'].includes(req.path)||
    req.method==='DELETE'&&/^\/devices\/[0-9a-f-]{36}$/.test(req.path);
  if(!safe)return res.status(403).json({error:'Use the original Hollywood room and identity for this environment.',code:'HOLLYWOOD_NATIVE_ROUTE_REQUIRED'});
  if(req.path==='/status'){void rateLimits.refresh();return void hollywood.status(req.device.id).then(status=>res.json({connected:status.available,backend:'lax',binding:status,usage:rateLimits.snapshot(),
    host:hostName,local:localMode,version:appVersion,device:req.device.name,deviceId:req.device.id,firebase:push.config,
    push:{enabled:push.enabled,registered:!!db.prepare('SELECT 1 FROM push_tokens WHERE device_id=?').get(req.device.id)}})).catch(next);}
  next();
});

app.get('/api/terminal-forks',owner,(_req,res)=>res.json({enabled:!!terminalForks,targets:Object.keys(terminalForkConfig?.targets||{}),transfers:terminalForks?db.prepare('SELECT id FROM terminal_forks ORDER BY created_at DESC LIMIT 100').all().map(r=>terminalForks.view(r.id)):[]}));
app.post('/api/terminal-forks',owner,route(async(req,res)=>{if(!terminalForks)return res.status(503).json({error:'The environment owner has not registered terminal-fork destinations.'});res.status(202).json(terminalForks.request(req.body));}));
app.get('/api/terminal-forks/imports/:id',owner,(req,res)=>res.json(terminalForkImports?.view(requireId(req.params.id))||{id:req.params.id,state:'disabled'}));
app.post('/api/terminal-forks/imports/:id/prepare',owner,route(async(req,res)=>{if(!terminalForkImports)return res.status(503).json({error:'This destination has not registered native terminal imports.'});await codex.connect();res.json(await terminalForkImports.prepare(requireId(req.params.id)));}));
app.post('/api/terminal-forks/imports/:id/activate',owner,route(async(req,res)=>{if(!terminalForkImports)return res.status(503).json({error:'This destination has not registered native terminal imports.'});await codex.connect();res.json(await terminalForkImports.activate(requireId(req.params.id)));}));
app.get('/api/terminal-forks/:id',owner,(req,res)=>{const result=terminalForks?.view(requireId(req.params.id));if(!result)return res.status(404).json({error:'Transfer not found.'});res.json(result);});
app.post('/api/terminal-forks/:id/release',owner,(req,res)=>{try{if(!terminalForks)throw Error('Terminal transfers are not registered.');res.json(terminalForks.release(requireId(req.params.id)));if(codex.ready)void terminalForks.tick().catch(()=>{});}catch(e){res.status(e.status||409).json({error:e.message});}});
app.post('/api/terminal-forks/:id/cancel',owner,(req,res)=>{try{if(!terminalForks)throw Error('Terminal transfers are not registered.');res.json(terminalForks.cancel(requireId(req.params.id)));}catch(e){res.status(e.status||409).json({error:e.message});}});
const roomProgressConfig=resolve(dir,'room-progress.json');
const roomProgressScopes=existsSync(roomProgressConfig)?JSON.parse(readFileSync(roomProgressConfig,'utf8')):[];
const roomProgress=new RoomProgress({db,client:roomVoice.client,scopes:roomProgressScopes,
 publish:(thread,title,event,onPersist)=>notify(thread,title,event.body,'update',[],null,null,null,null,onPersist)});
roomProgress.mount(app);
const roomProgressPoll=()=>void roomProgress.poll().catch(e=>console.error('Room progress unavailable',e.name));
if(!nativeHollywood){setInterval(roomProgressPoll,10000).unref();roomProgressPoll();}
mountHollywood(app,{dir,gateway:hollywood});
if(nativeHollywood)app.get('/api/backends',route(async(req,res)=>res.json({defaultBackend:'lax',canonical:true,backends:[{...await hollywood.status(req.device.id),voice:!!audioCommand}]})));
if(!nativeHollywood)mountLosangelex(app,{dir,codex,db,workRecord,hollywood,publish:(thread,onPersist,event)=>notify(thread,'Losangelex needs you',event?.body||'Open the team conversation to review its request.',event?.kind==='approval'?'approval':'question',[],null,null,null,null,onPersist),resolve:resolveAttention});
app.get('/api/computer-use',route(async(_req,res)=>res.json(await computerUseStatus())));
mountAppUpdates(app,{updates:new AppUpdates({dir,db,push}),owner,route,onAnnounce:notification=>emit('notification',{notification})});
coordinatorHub.mount();
app.get('/api/coordinator/reporting',(req,res)=>res.json(coordinatorReports.settings(req.device.id)));
app.post('/api/coordinator/reporting',route(async(req,res)=>res.json(coordinatorReports.configure(req.device.id,req.body))));
app.get('/api/coordinator/reporting/:deviceId',owner,(req,res)=>res.json(coordinatorReports.settings(req.params.deviceId)));
app.post('/api/coordinator/reporting/:deviceId',owner,route(async(req,res)=>res.json(coordinatorReports.configure(req.params.deviceId,req.body))));
app.get('/api/coordinator/reports',(req,res)=>{try{res.json(coordinatorReports.inbox(req.device.id,{before:Number(req.query.before||Number.MAX_SAFE_INTEGER),limit:Number(req.query.limit||40)}));}catch(e){res.status(e.status||500).json({error:e.message});}});
app.get('/api/voice/history',route(async(req,res)=>{if(nativeHollywood)return res.json(await hollywoodVoice.history(req.device.id,Number(req.query.before||Number.MAX_SAFE_INTEGER)));const before=Number(req.query.before||Number.MAX_SAFE_INTEGER);if(!Number.isSafeInteger(before)||before<=0)return res.status(400).json({error:'Invalid history cursor.'});res.json(voiceController.history(req.device.id,before,{conversationOnly:req.query.conversationOnly==='1'}));}));
app.post('/api/voice/start',route(async(req,res)=>{
  if(nativeHollywood){
    if(req.body.threadId||!req.body.room)throw Object.assign(Error('Choose an exact Hollywood room before recording.'),{status:400});
    const destination=await hollywoodVoice.destination(req.device.id,req.body.room);roomVoiceFocus.set(req.device.id,destination);
    return res.json({focus:'room',room:destination,host:hostName,native:true,backend:'lax'});
  }
  if(req.body.room){
    if(req.body.threadId)throw Object.assign(Error('Choose one room or session focus.'),{status:400});
    const destination=await roomVoice.destination(req.body.room);
    roomVoiceFocus.set(req.device.id,destination);
    return res.json({focus:'room',room:destination,host:hostName,native:true});
  }
  roomVoiceFocus.delete(req.device.id);
  let threadId=null;
  if(req.body.threadId){threadId=requireId(req.body.threadId);if(catchupHidden(threadId))return res.status(400).json({error:'Choose a work session.'});await codex.connect();const result=await codex.call('thread/read',{threadId,includeTurns:false});if(result.thread?.id!==threadId)return res.status(409).json({error:'Session identity changed. Choose the intended session again.'});requireDirectSessionInput(result.thread);}
  await voiceController.ensure(req.device.id);
  const s=voiceController.setFocus(req.device.id,threadId,{mode:threadId?'direct':'coordinator'});
  if(typeof req.body.fullPermissions==='boolean')db.prepare('UPDATE voice_sessions SET full=? WHERE device=?').run(req.body.fullPermissions?1:0,req.device.id);
  res.json({threadId:s.thread_id,selected:s.selected,focus:s.focus,host:hostName,speech:voiceSpeech.status(),native:true});
}));
app.get('/api/voice/native/capabilities',(_req,res)=>res.json({turnJournalVersion:1,maxRecordingSeconds:MAX_RECORDING_SECONDS,maxRecordingBytes:MAX_RECORDING_WAV_BYTES,immutableDestination:true,recordingDigest:true,durableAudioAdmission:nativeAudioIntake,cancellationTombstones:true,liveTranscriptionVersion:1,renderedSpeechVersion:nativeSpeechEnabled?1:0,speechPolicy:SPEECH_POLICY}));
app.post('/api/voice/renderings/:id',route(async(req,res)=>{
 if(!nativeSpeechEnabled)throw Object.assign(Error('Native rendered speech is not installed on this environment. Text remains available.'),{status:503});
 res.status(202).json(await voiceRenderings.create(req.device.id,req.params.id,req.body));
}));
app.get('/api/voice/renderings/:id',route(async(req,res)=>{
 const row=voiceRenderings.get(req.device.id,req.params.id);if(!row)return res.status(404).json({error:'Speech request not found.'});res.json(row);
}));
app.get('/api/voice/renderings/:id/audio',route(async(req,res)=>{
 const result=await voiceRenderings.audio(req.device.id,req.params.id);res.set('Cache-Control','private, no-store').set('X-NextComp-Audio-SHA256',result.sha256).type('audio/wav').send(result.audio);
}));
app.post('/api/voice/renderings/:id/cancel',route(async(req,res)=>res.json(voiceRenderings.cancel(req.device.id,req.params.id,req.body.attemptId))));
app.post('/api/voice/recordings',route(async(req,res)=>{
 if(!nativeAudioIntake)throw Object.assign(Error('Durable native audio intake is not installed on this environment. Recording remains on the device.'),{status:503});
 res.status(202).json(await voiceRecordings.create(req.device.id,req.body,req.humanPrincipal));
}));
app.put('/api/voice/recordings/:id/audio',express.raw({type:'audio/wav',limit:MAX_RECORDING_WAV_BYTES}),route(async(req,res)=>{
 if(!Buffer.isBuffer(req.body))throw Object.assign(Error('Expected recorded WAV audio.'),{status:415});
 res.status(202).json(await voiceRecordings.upload(req.device.id,req.params.id,req.body));
}));
app.get('/api/voice/recordings/:id',route(async(req,res)=>{
 const receipt=await voiceRecordings.inspect(req.device.id,req.params.id);
 if(!receipt)return res.status(404).json({error:'Recording admission not found.'});
 res.json(receipt);
}));
app.post('/api/voice/recordings/:id/cancel-request',route(async(req,res)=>res.json(await voiceRecordings.cancelRequest(req.device.id,req.params.id))));
app.post('/api/voice/recordings/:id/retry-audio',route(async(req,res)=>{
 const receipt=await voiceRecordings.retryAudio(req.device.id,req.params.id);
 humanIngress.action('voice-recording',voiceRecordings.key(req.device.id,req.params.id),'retry-audio',req.humanPrincipal);
 res.status(202).json(receipt);
}));
app.post('/api/voice/recordings/:id/cancel',route(async(req,res)=>res.json(await voiceRecordings.cancel(req.device.id,req.params.id,req.body,req.humanPrincipal))));
app.post('/api/voice/native/start',route(async(req,res)=>{if(req.body.purpose!==undefined&&!['voice','speech','transcription'].includes(req.body.purpose))throw Object.assign(Error('Invalid audio purpose.'),{status:400});if(nativeHollywood&&!audioCommand)throw Object.assign(Error('Native room audio is not configured on this environment.'),{status:503});res.json(await nativeVoice.start(req.device.id,req.body.sdp,req.body.purpose,req.body.text??null));}));
app.post('/api/voice/native/input/begin',route(async(req,res)=>res.json(nativeLiveInput.begin(req.device.id,req.body.id,req.body.connectionId))));
app.post('/api/voice/native/input/finalize',route(async(req,res)=>res.json(await nativeLiveInput.finalize(req.device.id,req.body))));
app.post('/api/voice/native/input/cancel',route(async(req,res)=>{nativeLiveInput.cancel(req.device.id,req.body.id);res.json({ok:true});}));
app.post('/api/voice/native/stop',route(async(req,res)=>{await nativeVoice.stop(req.device.id,req.body.connectionId);res.json({ok:true});}));
app.post('/api/voice/native/input',route(async(req,res)=>{
 const destination=Object.hasOwn(req.body,'destination')?await voiceDestination(req.device.id,req.body.destination):undefined;
 res.json(nativeVoice.begin(req.device.id,req.body.connectionId,req.body.turnId,destination));
}));
app.post('/api/voice/native/commit',route(async(req,res)=>res.json(await nativeVoice.commit(req.device.id,req.body.connectionId,req.body.turnId,req.body.audioSha256??null))));
app.post('/api/voice/native/speak',route(async(req,res)=>res.json(await nativeVoice.speak(req.device.id,req.body.connectionId,req.body.text,req.body.voiceTurnId))));
app.post('/api/voice/native/heartbeat',route(async(req,res)=>{nativeVoice.session(req.device.id,req.body.connectionId);res.json({ok:true});}));
app.post('/api/voice/turns/:id',express.raw({type:'audio/wav',limit:MAX_RECORDING_WAV_BYTES}),(req,res,next)=>{try{if(nativeHollywood)return res.status(400).json({error:'Use native recorded room input on this environment.'});if(!voiceSpeech.status().configured)return res.status(503).json({error:'Voice is not configured.'});res.status(202).json(voiceController.submit(req.device.id,req.params.id,req.body));}catch(e){next(e);}});
app.post('/api/voice/text',route(async(req,res)=>{res.status(202).json(nativeHollywood?await hollywoodVoice.submitText(req.device.id,req.body.turnId,req.body.text,roomVoiceFocus.get(req.device.id)):roomVoiceFocus.has(req.device.id)?roomVoice.submitText(req.device.id,req.body.turnId,req.body.text,roomVoiceFocus.get(req.device.id)):voiceController.submitText(req.device.id,req.body.turnId,req.body.text,req.body.correctionOf));}));
app.get('/api/voice/turns/:id',route(async(req,res)=>{const row=nativeHollywood?await hollywoodVoice.refresh(req.device.id,req.params.id):roomVoice.get(req.device.id,req.params.id)?await roomVoice.refresh(req.device.id,req.params.id):voiceController.get(req.device.id,req.params.id);if(!row){const admission=nativeVoice.receipt(req.device.id,req.params.id);if(admission)return res.json(admission);return res.status(404).json({error:'Voice turn not found.'});}if(!nativeHollywood&&row.state==='completed')voiceController.mark(req.device.id,req.params.id,'response_first_fetched');const admission=nativeVoice.admission(req.device.id,req.params.id);res.json(admission?{...row,audioSha256:admission.audio_sha256||null}:row);}));
app.post('/api/voice/turns/:id/presented',route(async(req,res)=>res.json(nativeHollywood?{ok:true}:roomVoice.get(req.device.id,req.params.id)?.state==='completed'?{ok:true}:await voiceController.presented(req.device.id,req.params.id))));
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
app.get('/api/status',(req,res)=>{const threadId=req.query.threadId;if(threadId!==undefined&&(typeof threadId!=='string'||!/^[a-zA-Z0-9_-]{5,100}$/.test(threadId)))return res.status(400).json({error:'Invalid thread identifier'});void rateLimits.refresh();res.json({...codex.status(),...(threadId?{historyHead:historyHeads.snapshot(threadId)}:{}),capabilities:{readOnlyTimeline:true,canonicalTimeline:1},usage:rateLimits.snapshot(),host:hostName,local:localMode,defaultCwd,version:appVersion,device:req.device.name,deviceId:req.device.id,firebase:push.config,push:{enabled:push.enabled,registered:!!db.prepare('SELECT 1 FROM push_tokens WHERE device_id=?').get(req.device.id)}});});
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
  try{const row=sessionStarts.enqueue(req.body,req.humanPrincipal);res.status(row.state==='started'?200:202).json(row);}
  catch(e){res.status(e.status||500).json({error:e.message});}
});
app.get('/api/session-starts/:id',(req,res)=>{const row=sessionStarts.get(req.params.id);if(!row)return res.status(404).json({error:'Task request not found.'});res.json(row);});
app.get('/api/work-record',(req,res)=>res.json({...workRecord.list({before:req.query.before,limit:req.query.limit}),team:workRecord.teamList(req.device.id),voice:voiceController.history(req.device.id).turns.slice(-30).reverse().map(turn=>({id:turn.id,status:turn.state,request:turn.transcript,output:turn.response,createdAt:turn.created_at,evidence:'agent-reported',source:{backend:'voice',turnId:turn.id}}))}));
app.get('/api/activity',(_req,res)=>res.json({items:activityBoard.snapshot()}));
app.get('/api/threads',route(async(req,res)=>{
  await codex.connect();
  if(req.query.view==='coordinator'){
    const cursor=typeof req.query.cursor==='string'?req.query.cursor:null;
    if(cursor&&cursor.length>4096)return res.status(400).json({error:'Invalid discovery cursor.'});
    const result=await sessionDiscovery.page({limit:100,sortKey:'recency_at',sortDirection:'desc',archived:false,useStateDbOnly:true,sourceKinds:INTERACTIVE_SOURCES,...(cursor?{cursor}:{})});
    const found=new Map();for(const thread of result.data||[]){if(catchupHidden(thread.id))continue;sessionDiscovery.remember(thread,{archived:false});found.set(thread.id,thread);}
    if(!cursor)for(const row of db.prepare("SELECT d.metadata FROM session_starts s JOIN pocket_discovered_threads d ON d.thread_id=s.thread_id WHERE s.state='started' AND d.archived=0 ORDER BY s.updated_at DESC LIMIT 100").all()){
      const thread=JSON.parse(row.metadata);if(!found.has(thread.id)&&!catchupHidden(thread.id))found.set(thread.id,{...thread,...sessionDiscovery.live.get(thread.id)});
    }
    return res.json({threads:[...found.values()].map(thread=>({id:thread.id,name:catalogName(thread),preview:thread.preview||'',cwd:thread.cwd,status:thread.status,updatedAt:thread.updatedAt,createdAt:thread.createdAt,activityAt:thread.activityAt,source:thread.source,discoveryPending:!!thread.discoveryPending,...catalogFields(thread)})),nextCursor:result.nextCursor||null,refreshPending:false});
  }
  const r=await sessionDiscovery.list({archived:req.query.archived==='true',cursor:typeof req.query.cursor==='string'?req.query.cursor:null,searchTerm:typeof req.query.search==='string'?req.query.search.slice(0,200):null});
  const watches=db.prepare('SELECT * FROM watches').all();
  const unread=new Map(db.prepare("SELECT n.thread_id,COUNT(*) AS total FROM notifications n LEFT JOIN notification_reads r ON r.device_id=? AND r.thread_id=n.thread_id WHERE n.kind!='coordinator_report' AND (n.id>COALESCE(r.through_id,0) OR EXISTS(SELECT 1 FROM notification_attention a WHERE a.notification_id=n.id AND a.resolved_at IS NULL)) GROUP BY n.thread_id").all(req.device.id).map(row=>[row.thread_id,row.total]));
  for(const renamed of notificationTitles.reconcile(r.data||[])){emit('threadRenamed',renamed);void push.renameThread(renamed).catch(error=>console.error('Rename push',error.message));}
  res.json({threads:(r.data||[]).filter(t=>!internalSessions.owns(t.id,t)&&!voiceController.owns(t.id)&&!nativeVoice.owns(t.id)&&!immersion.ownsThread(t.id)).map(t=>{
    const followed=!!watches.find(w=>w.thread_id===t.id&&w.enabled);
    const updatedMs=sessionWorkTime(t);
    const preview=threadPreviews.get(t,{hydrate:followed||t.status?.type==='active'||Date.now()-updatedMs<15*60000});
    const start=db.prepare("SELECT state FROM outgoing WHERE thread_id=? AND id LIKE 'start-%' ORDER BY created_at DESC LIMIT 1").get(t.id);
    const awaitingStart=start&&['queued','sending'].includes(start.state)&&['idle','notLoaded','pending',undefined].includes(t.status?.type);
    return {...catalogContext.get(t),unreadCount:unread.get(t.id)||0,id:t.id,name:catalogName(t),...preview,...(awaitingStart&&!preview.preview?{preview:'Starting…',previewRole:'activity',previewKind:'pending'}:{}),cwd:t.cwd,status:awaitingStart?{type:'pending'}:t.status,discoveryPending:!!t.discoveryPending||!!awaitingStart,activityAt:t.activityAt,updatedAt:t.updatedAt,archived:req.query.archived==='true',watched:followed,...catalogFields(t)};
  }),nextCursor:r.nextCursor||null,refreshPending:!!r.refreshPending});
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
app.post('/api/threads/:id/continuation',owner,route(async(req,res)=>{
 if(typeof req.body.evidence!=='string'||!req.body.evidence.trim()||req.body.evidence.length>8000)return res.status(400).json({error:'Provide a bounded evidence-backed continuation assessment.'});
 const threadId=requireId(req.params.id);await codex.connect();
 const {thread}=await codex.call('thread/read',{threadId,includeTurns:false});
 const snapshot=await history.read(thread,{summary:true});const latest=snapshot.turns?.at(-1);
 if(!latest||latest.id!==req.body.turnId)return res.status(409).json({error:'Read the current task before authorizing continuation.'});
 const managedChild=sessionIdentity(thread).isChild;
 const needsInput=[...pending.values()].some(m=>m.params?.threadId===threadId);
 const uncertainDelivery=!!db.prepare("SELECT 1 FROM outgoing WHERE thread_id=? AND state IN ('unknown','sending','queued')").get(threadId);
 const policy=await recoveryUsagePolicy(codex,threadId);
 const usageStopped=!policy.ok||['paused','budgetLimited','usageLimited','tokenLimited'].includes(thread.goal?.status);
 const scheduled=turnRecovery.authorizeContinuation(snapshot,{...req.body,managedChild,needsInput,uncertainDelivery,usageStopped});
 if(!scheduled)return res.status(409).json({error:'Continuation was not admitted; inspect stop, input and delivery state.',recovery:turnRecovery.get(threadId)||null});
 res.json({scheduled,recovery:turnRecovery.get(threadId)});
}));
app.post('/api/threads/:id/continuation/checkpoint',owner,route(async(req,res)=>{
 if(!['continue','completed','needsInput','waitingDependency'].includes(req.body.state)||typeof req.body.evidence!=='string'||!req.body.evidence.trim()||req.body.evidence.length>8000)return res.status(400).json({error:'Provide a task checkpoint and concrete evidence.'});
 const threadId=requireId(req.params.id);await codex.connect();
 const {thread}=await codex.call('thread/read',{threadId,includeTurns:false});
 const snapshot=await history.read(thread,{summary:true});const latest=snapshot.turns?.at(-1);
 const turnId=req.body.turnId||latest?.id;
 const accepted=workObservations.record(snapshot,{...req.body,turnId});
 if(!accepted)return res.status(409).json({error:'Checkpoint is stale or conflicts with an already saved observation. Read the current turn before reporting.'});
 res.json({accepted:true,observationOnly:true,admission:'none',observation:workObservations.latest(threadId),recovery:turnRecovery.get(threadId)||null});
}));
app.post('/api/threads/:id/timeline/position',route(async(req,res)=>{
  const id=requireId(req.params.id);await codex.connect();
  res.json(await history.locateItemSuccessor(id,req.body));
}));
app.get('/api/threads/:id/native-timeline',route(async(req,res)=>{
 const id=requireId(req.params.id),cursor=req.query.before??null;
 if(cursor!==null&&(typeof cursor!=='string'||!cursor||cursor.length>16384))
  return res.status(400).json({error:'Invalid native timeline cursor.'});
 await codex.connect();
 const {thread}=await codex.call('thread/read',{threadId:id,includeTurns:false});
 if(thread?.id!==id)throw Error('Native timeline belongs to another session.');
 if(cursor===null)void nativeSubscriptions.observe(thread,{alreadyAttached:attached.has(id)}).catch(error=>console.error('Native live subscription unavailable',error.message));
 const revision=timeline.version,historyHead=historyHeads.snapshot(id);
 const page=await nativeTimelineReader.read(id,{cursor,limit:64});
 const requests=[...pending.values()].filter(m=>m.params.threadId===id);
 const response={
  thread:{...thread,...catalogFields(thread),turns:[]},
  timeline:projectNativeTimeline(page,{identity:sendIdentity,threadId:id,revision}),
  revision,historyHead,pending:requests,
  notes:conversationNotes.list(id),recovery:turnRecovery.get(id)||null,turnSettings:savedTurnSettings(id),
  notifications:notificationReads.decorate(db.prepare('SELECT * FROM notifications WHERE thread_id=? ORDER BY id DESC LIMIT 30').all(id).map(notificationRow),req.device.id),
  outgoing:pendingOutgoing(db,id),watched:!!db.prepare('SELECT * FROM watches WHERE thread_id=? AND enabled=1').get(id)
 };
 if(cursor===null){response.catchup=sessionCatchup.capture(req.device.id,id,response);coordinatorHub.memory.capture(id,response);}
 res.set('Cache-Control','no-store').json(response);
}));
app.get(['/api/threads/:id','/api/threads/:id/head'],route(async(req,res)=>{
  const timelineView=req.query.view==='timeline'||req.path.endsWith('/head');
  const raw=timelineView?await readThreadProjection(requireId(req.params.id),{codex,history,timeline,historyHeads,discovery:sessionDiscovery},{before:req.query.before||null}):await attach(req.params.id,{before:req.query.before||null});
  const t=raw._pocketPage&&req.query.before?raw:timeline.merge(raw,{includeMissing:!raw._pocketPage,includeMissingItems:!raw._pocketPage});
  const requests=[...pending.values()].filter(m=>m.params.threadId===t.id);
  const page=timelinePage(t,requests,{before:raw._pocketPage?null:req.query.before||null,limit:8,notifications:db.prepare('SELECT * FROM notifications WHERE thread_id=?').all(t.id).map(notificationRow)});
  if(raw._pocketPage)Object.assign(page,raw._pocketPage);
  // Paging is bounded on the wire; retain the original thread on the Codex host.
  const turns=(t.turns||[]).slice(-25).map(turn=>({...turn,items:(turn.items||[]).filter(x=>['userMessage','agentMessage','imageGeneration','fileChange','commandExecution'].includes(x.type)).map(x=>x.type==='commandExecution'?{...x,aggregatedOutput:x.aggregatedOutput?.slice(-12000)}:x)}));
  conversationNotes.remember(t);recordWork(()=>workRecord.remember(t));
  const response={recovery:turnRecovery.get(t.id)||null,notes:conversationNotes.list(t.id),turnSettings:savedTurnSettings(t.id),thread:{...t,...catalogFields(t),turns:timelineView?[]:turns},timeline:page,revision:raw._pocketSnapshotRevision??timeline.version,historyHead:raw._pocketSnapshotHistoryHead,pending:requests,notifications:notificationReads.decorate(db.prepare('SELECT * FROM notifications WHERE thread_id=? ORDER BY id DESC LIMIT 30').all(t.id).map(notificationRow),req.device.id),outgoing:pendingOutgoing(db,t.id),watched:!!db.prepare('SELECT * FROM watches WHERE thread_id=? AND enabled=1').get(t.id)};
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
app.post('/api/threads/:id/catalog-context',owner,route(async(req,res)=>{
  const id=requireId(req.params.id),summary=String(req.body.summary||'').trim();
  if(!summary||summary.length>500)return res.status(400).json({error:'Provide a concise evidence-backed context, up to 500 characters.'});
  await codex.connect();const {thread}=await codex.call('thread/read',{threadId:id,includeTurns:false});
  if(thread?.id!==id)return res.status(409).json({error:'Session identity changed.'});
  catalogContext.put(id,summary,req.body.needsInput===true);res.json({ok:true});
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
  terminalForkImports?.inputAllowed(requireId(req.params.id));terminalForks?.inputRole(requireId(req.params.id));
  const threadId=requireId(req.params.id), text=String(req.body.text||'').trim(), id=String(req.body.id||randomUUID()), mode=req.body.mode||'auto';
  if(!text||text.length>32000||id.length>100)return res.status(400).json({error:'Reply must be 1–32000 characters.'});
  if(!['auto','steer','queue'].includes(mode))return res.status(400).json({error:'Choose steer or queue.'});
  const existing=db.prepare('SELECT * FROM outgoing WHERE id=?').get(id);
  if(existing){if(existing.thread_id!==threadId||existing.text!==text||existing.mode!==mode)return res.status(409).json({error:'Reply id already belongs to a different message.'});return res.json({id,state:existing.state});}
  const cachedDestination=db.prepare('SELECT metadata FROM pocket_discovered_threads WHERE thread_id=?').get(threadId);
  if(cachedDestination){const known=JSON.parse(cachedDestination.metadata);if(sessionIdentity(known).isChild){const destination=codex.ready?(await codex.call('thread/read',{threadId,includeTurns:false})).thread:known;requireDirectSessionInput(destination);}else requireDirectSessionInput(known);}
  turnRecovery.cancel(threadId);
  admissionTransaction(db,()=>{humanIngress.record('reply',id,req.humanPrincipal);db.prepare('INSERT INTO outgoing(id,thread_id,text,mode,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?)').run(id,threadId,text,mode,'queued',now(),now());});
  const wasFollowing=!!db.prepare('SELECT 1 FROM watches WHERE thread_id=? AND enabled=1').get(threadId);
  // A phone reply explicitly opts this task into a completion notification.
  db.prepare('INSERT INTO watches(thread_id,name,enabled) VALUES(?,?,1) ON CONFLICT(thread_id) DO UPDATE SET enabled=1').run(threadId,'Codex replied');
  if(!wasFollowing)completions.follow(threadId);
  const admitted=sessionDiscovery.recordIntent(threadId);emit('sessionActivity',admitted);
  // Record accepted user guidance without claiming older results were read.
  sessionCatchup.interact(req.device.id,threadId);
  res.status(202).json({id,state:'queued'}); if(codex.ready)void sendOutgoing({id,thread_id:threadId,text,mode,created_at:now()});
}));
app.post('/api/threads/:id/queue/resume',route(async(req,res)=>{
  const threadId=requireId(req.params.id);
  humanIngress.action('queue',threadId,'resume',req.humanPrincipal);
  db.prepare("UPDATE outgoing SET state='queued',updated_at=? WHERE thread_id=? AND mode='queue' AND state='held'").run(now(),threadId);
  emit('reply',{threadId,state:'queued'});res.json({ok:true});void flush();
}));
app.post('/api/threads/:id/replies/:replyId',route(async(req,res)=>{
  const threadId=requireId(req.params.id),id=req.params.replyId;
  const outcome=admissionTransaction(db,()=>{const value=recoverReply(db,threadId,id,req.body,now());if(!value.error)humanIngress.action('reply',id,req.body.action,req.humanPrincipal);return value;});
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
app.get('/api/immersion/v2',(req,res)=>res.json(transgrammaticImmersion.snapshot(req.device.id)));
app.post('/api/immersion/v2',route(async(req,res)=>{immersion.setEnabled(req.device.id,false);res.json(transgrammaticImmersion.setEnabled(req.device.id,req.body.enabled,req.body.density));}));
app.post('/api/immersion/v2/translate',route(async(req,res)=>res.json(transgrammaticImmersion.submit(req.device.id,req.body.sources))));

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
  sockets.handleUpgrade(req,socket,head,ws=>{ws.deviceId=device.id;ws.alive=true;ws.on('pong',()=>ws.alive=true);ws.on('error',()=>{});db.prepare('UPDATE devices SET last_seen=? WHERE id=?').run(now(),device.id);if(nativeHollywood){void hollywood.status(device.id).then(value=>{if(ws.readyState===WebSocket.OPEN)ws.send(JSON.stringify({type:'status',backend:'lax',connected:value.available,binding:value}));});}else{ws.send(JSON.stringify({type:'status',...codex.status()}));ws.send(JSON.stringify({type:'rateLimits',usage:rateLimits.snapshot()}));}if(!nativeHollywood)void rateLimits.refresh();sockets.emit('connection',ws,req);});
});
setInterval(()=>{for(const ws of sockets.clients){if(!ws.alive){ws.terminate();continue;}ws.alive=false;ws.ping();}},25000).unref();
app.use((err,req,res,next)=>{console.error(req.method,req.path,err.code||'',err.message);if(res.destroyed||res.headersSent)return;res.status(err.status||400).json({error:err.message,...(err.code?{code:err.code}:{})});});
const port=Number(process.env.PORT||18880);
server.listen(port,'127.0.0.1',()=>{
 console.log(`NextComp listening on 127.0.0.1:${port}`);
 if(!nativeHollywood){voiceController.restoreQueued();void coordinatorCollector.tick().catch(()=>{});}
 if(nativeAudioIntake)void voiceRecordings.start().catch(error=>console.error('Voice recording recovery unavailable',error.message));
});
