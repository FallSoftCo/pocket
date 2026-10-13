import {SendIdentity} from '../../server/send-identity.mjs';
import {DatabaseSync} from 'node:sqlite';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {LiveTimeline,timelinePage} from '../../server/timeline.mjs';
import http from 'node:http';import crypto from 'node:crypto';import fs from 'node:fs';
const voiceReceiptReads=[];const replyReceipts=new Map(),receiptReads=[],recoveries=[];let replyAckState="accepted";let badReply=false,holdReceipt=false,wrongReceiptThread=false;let failedReadsRemaining=0;let malformedRead=false;let failReply=false,holdReply=false;let quietReading=false;let visibility=false;const acknowledgements=[],ackAttempts=[];let failedAckReads=0;let anchored=false,holdPosition=false,failPosition=false;const positions=[];let boundary=false;let pacing=false;let clippedPreview=false;let revision=1,ordering=false,holdRefresh=false,failRead=false,failStatus=503;let secondRevision=1,secondVisible=false;const sockets=new Set(),reads=[],replies=[];const log=process.env.NEXTCOMP_QA_LOG||'/tmp/nextcomp-transcript-fixture-events.jsonl';
let coordinatorPaging=false;const coordinatorHistoryReads=[];let catalogPagingCount=0;let awaitingPosition=false,positionEvidence=false;let holdHistory=false;let messageIdentity=false;let sameTurnGap=false;let omitSnapshotHead=false;let headEpoch="synthetic-runtime",headCounter=null,holdCatalog=false,catalogBlocked=false,holdActivity=false,failCatalog=false,runtimeOffline=false;let notificationOverflow=false;let pendingNavigation=false;let notificationOnly=false;let quietReconnect=false,rejectLive=false,connections=0;
const thread=()=>({id:'same-thread',name:'Synthetic freshness work',cwd:'/synthetic',canAcceptDirectInput:true,status:{type:'idle'},preview:'Fresh response '+(clippedPreview?1:revision),previewKind:'message',previewRole:'codex',activityAt:(notificationOnly?1:revision)*1000,updatedAt:(notificationOnly?1:revision)*1000,recencyAt:(notificationOnly?1:revision)*1000});
const secondThread=()=>({...thread(),id:'second-thread',name:'Other synthetic work',preview:'Other response '+secondRevision,activityAt:secondRevision*1000});
function send(socket,event){const b=Buffer.from(JSON.stringify(event));const h=b.length<126?Buffer.from([129,b.length]):Buffer.from([129,126,b.length>>8,b.length&255]);socket.write(Buffer.concat([h,b]));}
const server=http.createServer(async(req,res)=>{
 let raw='';for await(const b of req)raw+=b;
 if(req.headers.authorization!=='Bearer synthetic-system76'){res.writeHead(401);res.end('{}');return;}
 const path=new URL(req.url,'http://localhost').pathname;let body={};
 if(path==='/api/voice/history'){const older=new URL(req.url,'http://localhost').searchParams.has('before');coordinatorHistoryReads.push({older});body={turns:coordinatorPaging?Array.from({length:older?5:25},(_,n)=>({id:'coordinator-passage-'+(older?n:n+5),transcript:'Coordinator request '+(older?n:n+5),response:'Coordinator passage '+(older?n:n+5)+': retained synthetic response for a normal reading viewport.',state:'completed'})):[],hasEarlier:coordinatorPaging&&!older,before:coordinatorPaging&&!older?5:null};}
 else if(path==='/_qa/coordinator-pages'){coordinatorPaging=true;body={ok:true};}
 else if(path==='/api/status'){const selected=new URL(req.url,'http://localhost').searchParams.get('threadId');body={host:'Synthetic',connected:!runtimeOffline,deviceId:'synthetic-device-a',...(selected?{historyHead:{threadId:selected,epoch:headEpoch,version:selected==='same-thread'?(headCounter??revision):secondRevision}}:{})};}
 else if(path==='/api/voice/turns/saved-unknown'){voiceReceiptReads.push(path);body={id:'saved-unknown',state:'unknown',error:'Synthetic uncertain delivery'};}
 else if(path==='/api/voice/native/start'){res.statusCode=503;body={error:'Synthetic native transport unavailable'};}
 else if(path==='/api/threads'){catalogBlocked=holdCatalog;while(holdCatalog)await new Promise(resolve=>setTimeout(resolve,30));catalogBlocked=false;if(failCatalog){failCatalog=false;res.statusCode=503;body={error:'Synthetic catalog temporarily unavailable'};}else body={threads:[thread(),...(secondVisible?[secondThread()]:[]),...Array.from({length:catalogPagingCount},(_,n)=>({...thread(),id:"catalog-page-"+n,name:"Catalog passage "+n,activityAt:Date.now()-n*60000,updatedAt:Date.now()-n*60000,recencyAt:Date.now()-n*60000}))]};}
 else if(path==='/_qa/unknown-reply'){replyAckState='unknown';body={ok:true};}
 else if(path==='/_qa/provider-identity-echo'){
  const sent=replies.at(-1);if(!sent){res.statusCode=409;body={error:'No synthetic send recorded'};}else{
   const live=new LiveTimeline();const item={id:'metadata-poor-provider',type:'userMessage',content:[{type:'text',text:sent.text}]};
   const directory=fs.mkdtempSync(join(tmpdir(),'nextcomp-native-send-evidence-'));
   let db,row;
   try{
    db=new DatabaseSync(join(directory,'evidence.sqlite'));let identity=new SendIdentity(db);
    identity.event({method:'item/started',params:{threadId:sent.threadId,turnId:'evidence-turn',item:{...item,clientId:sent.id}}});
    db.close();db=new DatabaseSync(join(directory,'evidence.sqlite'));identity=new SendIdentity(db);
    live.ingest(identity.event({method:'item/completed',params:{threadId:sent.threadId,turnId:'evidence-turn',item}}));
    const merged=live.merge(identity.snapshot({id:sent.threadId,turns:[{id:'evidence-turn',status:'inProgress',items:[item]}]}));
    row=timelinePage(merged).rows.find(row=>row.kind==='user');
   }finally{db?.close();fs.rmSync(directory,{recursive:true,force:true});}
   for(const socket of sockets)send(socket,{type:'timeline',threadId:sent.threadId,turnId:'evidence-turn',version:1000,row:{...row,version:1000}});
   body={ok:true,clientId:row.clientId,connections:sockets.size};
  }
 }
 else if(path==='/_qa/provider-independent-echo'){
  const sent=replies.at(-1);if(!sent){res.statusCode=409;body={error:'No synthetic send recorded'};}else{
   const row={id:'evidence-turn/independent-provider',itemId:'independent-provider',turnId:'evidence-turn',kind:'user',clientId:'independent-synthetic-send',text:sent.text,version:1001};
   for(const socket of sockets)send(socket,{type:'timeline',threadId:sent.threadId,turnId:'evidence-turn',version:1001,row});
   body={ok:true};
  }
 }

 else if(/^\/api\/threads\/(same|second)-thread\/replies\/[^/]+$/.test(path)){const id=path.split('/').at(-1);const input=JSON.parse(raw);recoveries.push({id,...input});const saved=replyReceipts.get(id);if(!saved){res.statusCode=404;body={error:'Reply not found.'};}else{saved.state='accepted';body={id,state:'accepted'};}}
 else if(path==='/_qa/bad-reply'){badReply=true;holdReceipt=true;body={ok:true};}
 else if(path==='/_qa/emit-accepted'){const sent=replies[0];if(sent)for(const socket of sockets)send(socket,{type:'reply',threadId:sent.threadId,id:sent.id,state:'accepted'});body={ok:true};}
 else if(path==='/_qa/hold-ack'){holdReply=true;body={ok:true};}
 else if(path==='/_qa/release-receipt'){holdReceipt=false;wrongReceiptThread=false;body={ok:true};}
 else if(path==='/_qa/wrong-receipt-thread'){wrongReceiptThread=true;holdReceipt=false;body={ok:true};}
 else if(path.startsWith('/api/replies/')){const id=path.split('/').at(-1);receiptReads.push(id);const saved=replyReceipts.get(id);if(!saved||holdReceipt){res.statusCode=404;body={error:'Reply not found.'};}else body={...saved,...(wrongReceiptThread?{thread_id:'wrong-thread'}:{})};}
 else if(path==='/_qa/fail-reply'){failReply=true;holdReply=true;body={ok:true};}
 else if(path==='/_qa/release-reply'){holdReply=false;body={ok:true};}
 else if(path==='/_qa/pending-navigation'){pendingNavigation=true;body={ok:true};}
 else if(path==='/_qa/visibility'){visibility=true;body={ok:true};}
 else if(path==='/_qa/quiet-reading'){visibility=true;quietReading=true;body={ok:true};}
 else if(/^\/api\/threads\/same-thread\/(notifications|catchup)\/read$/.test(path)){const input=JSON.parse(raw);ackAttempts.push({path,...input});if(failedAckReads>0){failedAckReads--;res.writeHead(503,{"Content-Type":"application/json"});res.end(JSON.stringify({error:"Synthetic acknowledgement outage"}));return;}acknowledgements.push({path,...input});body={ok:true,threadId:"same-thread",cursor:0,throughId:input.throughId,ids:input.throughId?[input.throughId]:[]};}
 else if(path==='/_qa/anchored'){anchored=true;holdPosition=true;body={ok:true};}
 else if(path==='/_qa/await-position-history'){anchored=true;holdPosition=false;awaitingPosition=true;positionEvidence=false;body={ok:true};}
 else if(path==='/_qa/release-position'){holdPosition=false;body={ok:true};}
 else if(path==='/_qa/fail-position'){failPosition=true;body={ok:true};}
 else if(path==='/api/threads/same-thread/timeline/position'){const input=JSON.parse(raw);positions.push(input);if(failPosition){failPosition=false;res.statusCode=503;body={error:'Synthetic item position unavailable'};}else{while(holdPosition)await new Promise(r=>setTimeout(r,50));body=awaitingPosition&&!positionEvidence?{available:false,insertBefore:null,cursor:null,awaitingHistory:true}:anchored?{available:true,insertBefore:input.turnId+'/answer',cursor:null}:{available:false,insertBefore:null,cursor:null};}}
 else if(path==='/_qa/boundary'){boundary=true;body={ok:true};}
 else if(path==='/_qa/pacing'){pacing=true;body={ok:true};}
 else if(path==='/_qa/malformed-read'){malformedRead=true;body={ok:true};}
 else if(path==='/_qa/fail-read-burst'){failedReadsRemaining=4;failStatus=503;body={ok:true};}
 else if(path==='/_qa/fail-read'){failRead=true;failStatus=503;body={ok:true};}
 else if(path==='/_qa/fail-read-permanent'){failRead=true;failStatus=401;body={ok:true};}
 else if(path==='/_qa/hold-history'){holdHistory=true;body={ok:true};}
 else if(path==='/_qa/release-history'){holdHistory=false;body={ok:true};}
 else if(path==='/_qa/message-identity'){messageIdentity=true;body={ok:true};}
 else if(path==='/_qa/same-turn-gap'){sameTurnGap=true;body={ok:true};}
 else if(path==='/_qa/show-second'){secondVisible=true;body={ok:true};}
 else if(path==='/_qa/advance-second'){secondRevision++;for(const s of sockets)send(s,{type:'sessionPreview',threadId:'second-thread',preview:secondThread().preview,previewKind:'message',activityAt:secondRevision*1000});body={revision:secondRevision};}
 else if(path==='/api/threads/second-thread'){
  const rev=secondRevision;reads.push({at:Date.now(),threadId:'second-thread',older:false,revision:rev});
  await new Promise(r=>setTimeout(r,300));
  body={historyHead:{threadId:'second-thread',epoch:headEpoch,version:headCounter??rev},thread:secondThread(),timeline:{rows:[{id:'other/message',turnId:'other',kind:'message',text:'Other response '+rev}],hasEarlier:false,before:null},revision:0,notes:[],pending:[],outgoing:[],notifications:[]};
 }

 else if(path==='/api/threads/same-thread'){
  if(malformedRead){malformedRead=false;res.setHeader('Content-Type','application/json');res.end(JSON.stringify({error:'Synthetic invalid successful response'}));return;}
  if(failRead||failedReadsRemaining>0){if(failedReadsRemaining>0)failedReadsRemaining--;reads.push({at:Date.now(),older:req.url.includes("before="),revision,status:failStatus});failRead=false;res.writeHead(failStatus,{'Content-Type':'application/json'});res.end(JSON.stringify({error:failStatus===401?'Synthetic history permission rejected':'Synthetic history refresh unavailable'}));return;}
  if(awaitingPosition&&positions.length)positionEvidence=true;
  const older=req.url.includes('before=');const rev=revision;reads.push({at:Date.now(),older,revision:rev});fs.appendFileSync(log,JSON.stringify(reads.at(-1))+'\n');
  await new Promise(r=>setTimeout(r,older?2200:1700));
  while(holdHistory)await new Promise(r=>setTimeout(r,30));
  while(!older&&rev===2&&holdRefresh)await new Promise(r=>setTimeout(r,50));
  body={historyHead:{threadId:'same-thread',epoch:headEpoch,version:headCounter??rev},thread:thread(),timeline:{rows:[{id:older?'older-message':'same-message',kind:'message',turnId:'synthetic-turn',text:older?'Older synthetic history':'Fresh response '+rev,version:rev===1?80:0}],hasEarlier:!older,before:older?null:'older1'},revision:rev===1?80:0,notes:[],pending:[],outgoing:[],notifications:[]};
  if(sameTurnGap){
   const cursor=new URL(req.url,'http://localhost').searchParams.get('before');
   const header={id:'synthetic-turn/header',kind:'turn',turnId:'synthetic-turn'};
   const old=[{id:'gap-user',kind:'user',turnId:'synthetic-turn',text:'Shared turn request'},{id:'gap-old-answer',kind:'message',turnId:'synthetic-turn',text:'Fresh response 1'}];
   const answer={id:'gap-new-answer',kind:'message',turnId:'synthetic-turn',text:'Fresh response 2'};
   const tool={id:'gap-new-tool',kind:'activity',turnId:'synthetic-turn',text:'New tool action'};
   body.timeline=rev===1?{rows:[header,...old],hasEarlier:false,before:null}:cursor==='gap-answer'?{rows:[header,answer],hasEarlier:true,before:'gap-old',insertBefore:tool.id}:cursor==='gap-old'?{rows:[header,...old],hasEarlier:false,before:null,insertBefore:answer.id}:{rows:[header,tool],hasEarlier:true,before:'gap-answer'};
  }
  if(messageIdentity)body.timeline={rows:[{id:'identity/header',kind:'turn',turnId:'identity'},
   {id:'identity/item-183',itemId:'item-183',kind:'user',turnId:'identity',clientId:'send-one',text:'Same deliberate text'},
   {id:'identity/item-184',itemId:'item-184',kind:'user',turnId:'identity',clientId:'send-two',text:'Same deliberate text'},
   {id:'identity/answer',itemId:'answer',kind:'message',turnId:'identity',text:'Fresh response 1'}],hasEarlier:false,before:null};
  if(omitSnapshotHead)delete body.historyHead;
  if(visibility&&!older){body.timeline={rows:Array.from({length:20},(_,n)=>({id:'visible-'+n,kind:'message',text:n===19?'Fresh response '+rev:('Visible passage '+n+' '+('Readable synthetic history. '.repeat(10)))})),hasEarlier:false,before:null};body.notifications=[{id:rev,thread_id:'same-thread',kind:'complete'}];body.catchup={token:'capture-'+rev};}
  if(quietReading){body.notifications=[];delete body.catchup;}
  if(pendingNavigation&&!older){
   const request={id:'synthetic-pending',method:'item/tool/requestUserInput',params:{threadId:'same-thread',questions:[{id:'review',question:'Which synthetic review should continue?',options:[{label:'First',description:'Synthetic only'},{label:'Second',description:'Synthetic only'}]}]}};
   body.timeline={rows:Array.from({length:30},(_,n)=>n===12?{id:'request/synthetic-pending',kind:'request',request}:n>=25?{id:'pending/final-'+n,kind:'message',phase:'final_answer',text:n===29?'Fresh response '+rev:('Earlier retained synthetic answer '+n+'. ').repeat(20)}:{id:'pending/activity-'+n,kind:'activity',type:'commandExecution',status:'completed',title:'Synthetic inspection',text:('Read synthetic context '+n+'. ').repeat(12)}),hasEarlier:false,before:null};body.pending=[request];
  }
  if(boundary&&older)body.timeline={rows:[{id:'order/header',turnId:'order',kind:'turn',status:'inProgress'},{id:'order/user',turnId:'order',kind:'user',text:'Ordering request'}],hasEarlier:false,before:null,insertBefore:'order/answer'};
  if(pacing&&older){const n=Number(new URL(req.url,'http://localhost').searchParams.get('before')?.match(/\d+/)?.[0]||1);body.timeline={rows:[{id:'pacing-'+n,kind:'message',text:'Earlier pacing passage '+n}],hasEarlier:n<10,before:'older'+(n+1)};}
  if(anchored&&!older)body.timeline.rows=[{id:'order/header',turnId:'order',kind:'turn',status:'inProgress'},{id:'order/answer',itemId:'answer',turnId:'order',kind:'message',text:'Stale anchored answer'}];
  if(ordering&&!older)body.timeline.rows=[{id:'order/header',turnId:'order',kind:'turn',status:'inProgress'},{id:'order/user',turnId:'order',kind:'user',text:'Ordering request'},{id:'order/answer',turnId:'order',kind:'message',text:'Stale ordering answer'},{id:'order/end',turnId:'order',kind:'turnEnd',status:'completed'}];
 }else if(path==='/_qa/order'){ordering=true;body={ok:true};}
 else if(path==='/_qa/release'){holdRefresh=false;body={ok:true};}
 else if(path==='/_qa/advance'){revision++;if(revision===2)holdRefresh=true;for(const s of sockets)send(s,{type:'sessionPreview',threadId:'same-thread',preview:thread().preview,previewKind:'message',activityAt:revision*1000});body={revision};}
 else if(path==='/_qa/repeat'){for(const s of sockets)send(s,{type:'sessionPreview',threadId:'same-thread',preview:thread().preview,previewKind:'message',activityAt:revision*1000});body={revision};}
 else if(path==='/_qa/activity-only'){revision++;for(const s of sockets)send(s,{type:'sessionActivity',threadId:'same-thread',status:{type:'idle'},activityAt:revision*1000});body={revision};}
 else if(path==='/_qa/repeat-activity'){for(const s of sockets)send(s,{type:'sessionActivity',threadId:'same-thread',status:{type:'idle'},activityAt:revision*1000});body={revision};}
 else if(path==='/_qa/clipped-preview'){clippedPreview=true;body={ok:true};}
 else if(path==='/_qa/omit-snapshot-head'){omitSnapshotHead=true;body={ok:true};}
 else if(path==='/_qa/restore-snapshot-head'){omitSnapshotHead=false;body={ok:true};}
 else if(path==='/_qa/catalog-pages'){catalogPagingCount=40;body={ok:true};}
 else if(path==='/_qa/hold-catalog'){holdCatalog=true;body={ok:true};}
 else if(path==='/_qa/release-catalog'){holdCatalog=false;body={ok:true};}
 else if(path==='/_qa/head-only-update'){headCounter=(headCounter??revision)+1;revision++;body={revision};}
 else if(path==='/_qa/head-live-update'){revision++;headCounter=81;for(const socket of sockets)send(socket,{type:'timeline',threadId:'same-thread',turnId:'synthetic-turn',version:81,historyHead:{threadId:'same-thread',epoch:headEpoch,version:81},row:{id:'same-message',turnId:'synthetic-turn',kind:'message',text:'Fresh response '+revision,version:81}});body={revision};}
 else if(path==='/_qa/reset-history-head'){headEpoch='synthetic-runtime-restarted';headCounter=1;revision++;body={revision};}
 else if(path==='/_qa/notification-overflow'){notificationOverflow=true;notificationOnly=true;clippedPreview=true;revision++;body={revision};}
 else if(path==='/_qa/notification-only'){notificationOnly=true;clippedPreview=true;revision++;body={revision};}
 else if(path==='/_qa/live-outage'){rejectLive=true;for(const socket of sockets)socket.destroy();body={ok:true};}
 else if(path==='/_qa/live-recover'){rejectLive=false;revision++;body={revision};}
 else if(path==='/_qa/quiet-reconnect'){quietReconnect=true;revision++;for(const socket of sockets)socket.destroy();body={revision};}
 else if(path==='/_qa/silent'){revision++;body={revision};}
 else if(/^\/api\/threads\/(same|second)-thread\/reply$/.test(path)){const sent=JSON.parse(raw);replies.push({...sent,threadId:path.split('/')[3]});if(!failReply)replyReceipts.set(sent.id,{id:sent.id,thread_id:path.split('/')[3],state:replyAckState});while(holdReply)await new Promise(r=>setTimeout(r,50));await new Promise(r=>setTimeout(r,150));if(failReply){failReply=false;res.statusCode=503;body={error:'Synthetic delayed reply failure'};}else if(badReply){badReply=false;body={id:"wrong-id",state:"accepted"};}else body={id:sent.id,state:replyAckState};}
 else if(path==='/_qa/fail-acks'){failedAckReads=2;body={ok:true};}
 else if(path==='/_qa/state')body={revision,catalogBlocked,coordinatorHistoryReads,connections,reads,replies,positions,voiceReceiptReads,acknowledgements,ackAttempts,receiptReads,recoveries};
 else if(path==='/api/notifications')body={notifications:notificationOnly&&!notificationOverflow?[{id:revision,thread_id:'same-thread',kind:'complete'}]:[]};
 else if(path==='/api/attention')body={notifications:[]};
 else if(path==='/_qa/runtime-offline'){runtimeOffline=true;body={ok:true};}
 else if(path==='/_qa/runtime-online'){runtimeOffline=false;body={ok:true};}
 else if(path==='/_qa/fail-catalog'){failCatalog=true;body={ok:true};}
 else if(path==='/_qa/hold-activity'){holdActivity=true;body={ok:true};}
 else if(path==='/_qa/release-activity'){holdActivity=false;body={ok:true};}
 else if(path==='/api/activity'){while(holdActivity)await new Promise(r=>setTimeout(r,30));body={items:[]};}
 res.setHeader('Content-Type','application/json');res.end(JSON.stringify(body));
});
server.on('upgrade',(req,socket)=>{if(rejectLive){socket.destroy();return;}if(req.headers.authorization!=='Bearer synthetic-system76'){socket.destroy();return;}socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+crypto.createHash('sha1').update(req.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')+'\r\n\r\n');connections++;sockets.add(socket);socket.on('close',()=>sockets.delete(socket));socket.on('error',()=>sockets.delete(socket));if(!quietReconnect)send(socket,{type:'status',connected:!runtimeOffline});});
server.listen(19998,'127.0.0.1',()=>console.log('Owned transcript fixture ready'));
