import http from 'node:http';import {WebSocketServer} from 'ws';
import {spawn} from 'node:child_process';import {mkdir,readFile,writeFile,open,unlink} from 'node:fs/promises';import {createHash,randomUUID} from 'node:crypto';
// Explicit installation-owned media command: never discover credentials or provision a provider.
const root=process.env.NEXTCOMP_STOCK_QA_DATA,source=process.env.NEXTCOMP_STOCK_QA_GATEWAY_ROOT,
 audioFile=process.env.NEXTCOMP_STOCK_QA_AUDIO,command=process.env.NEXTCOMP_STOCK_QA_AUDIO_COMMAND,
 port=Number(process.env.NEXTCOMP_STOCK_QA_PORT||18897);
if(!root||!source||!audioFile||!command||process.env.NEXTCOMP_STOCK_QA_ALLOW_NATIVE_AUDIO!=='1')throw Error('Provide fresh private QA paths and explicitly opt in to the installation-owned audio command');
if(!Number.isSafeInteger(port)||port<1024||port>65535)throw Error('Invalid loopback QA port');
await mkdir(root,{mode:0o700});await mkdir(root+'/work',{recursive:true});
const socket=root+'/runtime.sock';const starts=[],wss=new WebSocketServer({noServer:true}),host=http.createServer();let gateway,paired,admin;
host.on('upgrade',(request,socket,head)=>wss.handleUpgrade(request,socket,head,ws=>wss.emit('connection',ws,request)));
wss.on('connection',ws=>ws.on('message',raw=>{
 const m=JSON.parse(raw);if(m.id===undefined)return;let result={};
 if(m.method==='initialize')result={};
 else if(m.method==='thread/start')result={thread:{id:'qa-stock-controller-0001',status:{type:'idle'}}};
 else if(m.method==='thread/resume'||m.method==='thread/read')result={thread:{id:m.params.threadId,status:{type:'idle'},turns:[]}};
 else if(m.method==='thread/list'||m.method==='thread/loaded/list'||m.method==='model/list')result={data:[],nextCursor:null};
 else if(m.method==='account/read')result={account:null};
 else if(m.method==='account/rateLimits/read')result={rateLimits:null};
 else if(m.method==='turn/start'){const turnId=randomUUID();starts.push({requestId:m.params.clientUserMessageId,threadId:m.params.threadId,turnId,ws});result={turn:{id:turnId}};}
 ws.send(JSON.stringify({id:m.id,result}));
}));
await new Promise((resolve,reject)=>{host.once('error',reject);host.listen(socket,resolve)});
const environment={PATH:process.env.PATH,HOME:process.env.HOME,PORT:String(port),POCKET_DATA:root+'/data',CODEX_SOCKET:socket,POCKET_CWD:root+'/work',POCKET_NATIVE_AUDIO_REMOTE:command};
const probe=http.createServer();await new Promise((resolve,reject)=>{probe.once('error',reject);probe.listen(port,'127.0.0.1',resolve)});await new Promise(resolve=>probe.close(resolve));
const output=await open(root+'/gateway.log','wx',0o600);gateway=spawn(process.execPath,[source+'/server/index.mjs'],{cwd:source,env:environment,stdio:['ignore',output.fd,output.fd],detached:true});await output.close();
await writeFile(root+'/owner.json',JSON.stringify({owner:process.env.NEXTCOMP_QA_OWNER||'owned-stock-http-qualification',gatewayPid:gateway.pid,port,source,scope:'temporary isolated stock gateway; private synthetic runtime responses, cached native audio only; no business/model worker',productionChanged:false}),{mode:0o600});
const report={scope:'actual HTTP/auth/durable audio -> explicit installation-owned media command -> stock coordinator queue, synthetic task runtime; NOT task model inference proof',productionChanged:false,phoneChanged:false,taskModelInferences:0};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const wait=async predicate=>{for(let n=0;n<100;n++){if(await predicate())return;await sleep(100)}throw Error('Owned HTTP qualification did not settle');};
const api=async(path,body,token=paired?.token,method)=>{
 const response=await fetch('http://127.0.0.1:'+port+path,{method:method|| (body===undefined?'GET':'POST'),headers:{...(token?{Authorization:'Bearer '+token}:{}),'Content-Type':Buffer.isBuffer(body)?'audio/wav':'application/json'},...(body===undefined?{}:{body:Buffer.isBuffer(body)?body:JSON.stringify(body)}),signal:AbortSignal.timeout(20000)});
 return {status:response.status,body:await response.json()};
};
const assert=(test,message)=>{if(!test)throw Error(message)};
try {
 await wait(async()=>{try{return (await fetch('http://127.0.0.1:'+port+'/health')).ok}catch{return false}});
 admin=JSON.parse(await readFile(root+'/data/secrets.json','utf8')).adminToken;
 const code=await api('/api/pairing',{},admin);assert(code.status===200,'Pairing issuance failed');
 const pair=await api('/api/pair',{code:code.body.code,name:'Disposable stock intake QA'},null);assert(pair.status===200,'Pairing failed');paired=pair.body;
 await writeFile(root+'/paired.json',JSON.stringify(paired),{mode:0o600});
 const denied=await api('/api/voice/text',{turnId:'owner-must-not-send-0001',text:'Not human input'},admin);assert(denied.status===403,'Admin incorrectly admitted human input');report.adminHumanInputStatus=denied.status;
 const audio=await readFile(audioFile),sha=createHash('sha256').update(audio).digest('hex');
 const tombstoneId='stock-cancel-before-admission-0004';
 const original={id:tombstoneId,destination:{fullPermissions:false},audioSha256:sha,bytes:audio.length};
 const tombstone=await api('/api/voice/recordings/'+tombstoneId+'/cancel',original);
 assert(tombstone.status===200&&tombstone.body.state==='cancelled','Cancellation intent was not admitted');
 const lateCreate=await api('/api/voice/recordings',original);
 const lateUpload=await api('/api/voice/recordings/'+tombstoneId+'/audio',audio,paired.token,'PUT');
 assert(lateCreate.body.state==='cancelled'&&lateUpload.body.state==='cancelled'&&starts.length===0,'Late upload escaped cancellation');
 report.cancelBeforeAdmission=true;
 const create=async id=>{
  const admission=await api('/api/voice/recordings',{id,destination:{fullPermissions:false},audioSha256:sha,bytes:audio.length});assert(admission.status===202,'Recording admission failed');
  const upload=await api('/api/voice/recordings/'+id+'/audio',audio,paired.token,'PUT');assert(upload.status===202,'Recording upload failed');
  let receipt;await wait(async()=>{receipt=await api('/api/voice/recordings/'+id);return receipt.body.phase==='accepted'});
  assert(receipt.body.audioSha256===sha,'Original recording digest changed');return receipt;
 };
 const first='stock-http-original-0001',second='stock-http-queued-0002',third='stock-http-text-0003';
 await create(first);await wait(()=>starts.length===1);
 const queued=await create(second);assert(queued.body.state==='queued'&&starts.length===1,'Second recording was not queued independently');
 const cancelled=await api('/api/voice/recordings/'+second+'/cancel-request',{});assert(cancelled.body.state==='cancelled'&&cancelled.body.audioSha256===sha,'Queued cancellation was not source-bound');
 const cancelledAgain=await api('/api/voice/recordings/'+second+'/cancel-request',{});assert(cancelledAgain.status===200&&cancelledAgain.body.state==='cancelled','Lost queued cancellation ACK was not idempotent');
 const text=await api('/api/voice/text',{turnId:third,text:'Synthetic third queued command'});assert(text.status===202&&text.body.state==='queued','Text request not queued');
 const current=starts[0];current.ws.send(JSON.stringify({method:'item/completed',params:{threadId:current.threadId,item:{type:'agentMessage',phase:'final_answer',text:'Synthetic first result'}}}));current.ws.send(JSON.stringify({method:'turn/completed',params:{threadId:current.threadId,turn:{id:current.turnId,status:'completed'}}}));
 await wait(()=>starts.length===2);assert(starts[1].requestId==='voice-'+third,'Cancelled request ran or order changed');
 const last=starts[1];last.ws.send(JSON.stringify({method:'item/completed',params:{threadId:last.threadId,item:{type:'agentMessage',phase:'final_answer',text:'Synthetic third result'}}}));last.ws.send(JSON.stringify({method:'turn/completed',params:{threadId:last.threadId,turn:{id:last.turnId,status:'completed'}}}));
 let completed;await wait(async()=>{completed=await api('/api/voice/recordings/'+first);return completed.body.state==='completed'});
 const rejected=await api('/api/voice/recordings/'+first+'/retry-audio',{});assert(rejected.status===409,'Accepted request was replayable');
 const {DatabaseSync}=await import('node:sqlite');const journal=new DatabaseSync(root+'/data/pocket.sqlite',{readOnly:true});const mediaReceipts=journal.prepare('SELECT proof FROM voice_recordings WHERE proof IS NOT NULL').all().map(row=>JSON.parse(row.proof));journal.close();report.cachedMediaReceipts=mediaReceipts.filter(row=>row.mediaCacheHit===true).length;report.totalMediaReceipts=mediaReceipts.length;
 report.passed=true;report.pairedHumanAdmission=true;report.independentQueuedRecording=true;report.queuedCancellation=true;report.recordingAudioSha256=sha;report.syntheticTaskStarts=starts.map(s=>s.requestId);report.acceptedAudioRetryStatus=rejected.status;report.originalCompletedResponse=completed.body.response;
}catch(error){report.passed=false;report.error=error.message;process.exitCode=1;}
finally {
 await writeFile(root+'/receipt.json',JSON.stringify(report,null,2)+'\n',{mode:0o600});
 if(paired&&admin)try{await api('/api/devices/'+paired.id,undefined,admin,'DELETE')}catch{}
 if(gateway){gateway.kill('SIGTERM');await Promise.race([new Promise(resolve=>gateway.once('close',resolve)),sleep(5000)]);if(gateway.exitCode===null&&gateway.signalCode===null)try{process.kill(-gateway.pid,'SIGKILL')}catch(error){if(error.code!=='ESRCH')throw error;}}
 for(const ws of wss.clients)ws.terminate();await new Promise(resolve=>wss.close(resolve));await new Promise(resolve=>host.close(resolve));await unlink(socket).catch(()=>{});
 await writeFile(root+'/receipt.json',JSON.stringify(report,null,2)+'\n',{mode:0o600});console.log(JSON.stringify(report));
}
