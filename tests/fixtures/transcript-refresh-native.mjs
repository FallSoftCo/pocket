import http from 'node:http';import crypto from 'node:crypto';import fs from 'node:fs';
const replyReceipts=new Map(),receiptReads=[],recoveries=[];let replyAckState="accepted";let badReply=false,holdReceipt=false,wrongReceiptThread=false;let failedReadsRemaining=0;let malformedRead=false;let failReply=false,holdReply=false;let visibility=false;const acknowledgements=[],ackAttempts=[];let failedAckReads=0;let anchored=false,holdPosition=false,failPosition=false;const positions=[];let boundary=false;let pacing=false;let clippedPreview=false;let revision=1,ordering=false,holdRefresh=false,failRead=false,failStatus=503;let secondRevision=1,secondVisible=false;const sockets=new Set(),reads=[],replies=[];const log=process.env.NEXTCOMP_QA_LOG||'/tmp/nextcomp-transcript-fixture-events.jsonl';
let pendingNavigation=false;let notificationOnly=false;let quietReconnect=false,rejectLive=false,connections=0;
const thread=()=>({id:'same-thread',name:'Synthetic freshness work',cwd:'/synthetic',canAcceptDirectInput:true,status:{type:'idle'},preview:'Fresh response '+(clippedPreview?1:revision),previewKind:'message',previewRole:'codex',activityAt:(notificationOnly?1:revision)*1000,updatedAt:(notificationOnly?1:revision)*1000,recencyAt:(notificationOnly?1:revision)*1000});
const secondThread=()=>({...thread(),id:'second-thread',name:'Other synthetic work',preview:'Other response '+secondRevision,activityAt:secondRevision*1000});
function send(socket,event){const b=Buffer.from(JSON.stringify(event));const h=b.length<126?Buffer.from([129,b.length]):Buffer.from([129,126,b.length>>8,b.length&255]);socket.write(Buffer.concat([h,b]));}
const server=http.createServer(async(req,res)=>{
 let raw='';for await(const b of req)raw+=b;
 if(req.headers.authorization!=='Bearer synthetic-system76'){res.writeHead(401);res.end('{}');return;}
 const path=new URL(req.url,'http://localhost').pathname;let body={};
 if(path==='/api/status')body={host:'Synthetic',connected:true,deviceId:'synthetic-device-a'};
 else if(path==='/api/voice/native/start'){res.statusCode=503;body={error:'Synthetic native transport unavailable'};}
 else if(path==='/api/threads')body={threads:[thread(),...(secondVisible?[secondThread()]:[])]};
 else if(path==='/_qa/unknown-reply'){replyAckState='unknown';body={ok:true};}
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
 else if(/^\/api\/threads\/same-thread\/(notifications|catchup)\/read$/.test(path)){const input=JSON.parse(raw);ackAttempts.push({path,...input});if(failedAckReads>0){failedAckReads--;res.writeHead(503,{"Content-Type":"application/json"});res.end(JSON.stringify({error:"Synthetic acknowledgement outage"}));return;}acknowledgements.push({path,...input});body={ok:true,threadId:"same-thread",cursor:0,throughId:input.throughId,ids:input.throughId?[input.throughId]:[]};}
 else if(path==='/_qa/anchored'){anchored=true;holdPosition=true;body={ok:true};}
 else if(path==='/_qa/release-position'){holdPosition=false;body={ok:true};}
 else if(path==='/_qa/fail-position'){failPosition=true;body={ok:true};}
 else if(path==='/api/threads/same-thread/timeline/position'){const input=JSON.parse(raw);positions.push(input);if(failPosition){failPosition=false;res.statusCode=503;body={error:'Synthetic item position unavailable'};}else{while(holdPosition)await new Promise(r=>setTimeout(r,50));body=anchored?{available:true,insertBefore:input.turnId+'/answer',cursor:null}:{available:false,insertBefore:null,cursor:null};}}
 else if(path==='/_qa/boundary'){boundary=true;body={ok:true};}
 else if(path==='/_qa/pacing'){pacing=true;body={ok:true};}
 else if(path==='/_qa/malformed-read'){malformedRead=true;body={ok:true};}
 else if(path==='/_qa/fail-read-burst'){failedReadsRemaining=4;failStatus=503;body={ok:true};}
 else if(path==='/_qa/fail-read'){failRead=true;failStatus=503;body={ok:true};}
 else if(path==='/_qa/fail-read-permanent'){failRead=true;failStatus=401;body={ok:true};}
 else if(path==='/_qa/show-second'){secondVisible=true;body={ok:true};}
 else if(path==='/_qa/advance-second'){secondRevision++;for(const s of sockets)send(s,{type:'sessionPreview',threadId:'second-thread',preview:secondThread().preview,previewKind:'message',activityAt:secondRevision*1000});body={revision:secondRevision};}
 else if(path==='/api/threads/second-thread'){
  const rev=secondRevision;reads.push({at:Date.now(),threadId:'second-thread',older:false,revision:rev});
  await new Promise(r=>setTimeout(r,300));
  body={thread:secondThread(),timeline:{rows:[{id:'other/message',turnId:'other',kind:'message',text:'Other response '+rev}],hasEarlier:false,before:null},revision:0,notes:[],pending:[],outgoing:[],notifications:[]};
 }

 else if(path==='/api/threads/same-thread'){
  if(malformedRead){malformedRead=false;res.setHeader('Content-Type','application/json');res.end(JSON.stringify({error:'Synthetic invalid successful response'}));return;}
  if(failRead||failedReadsRemaining>0){if(failedReadsRemaining>0)failedReadsRemaining--;reads.push({at:Date.now(),older:req.url.includes("before="),revision,status:failStatus});failRead=false;res.writeHead(failStatus,{'Content-Type':'application/json'});res.end(JSON.stringify({error:failStatus===401?'Synthetic history permission rejected':'Synthetic history refresh unavailable'}));return;}
  const older=req.url.includes('before=');const rev=revision;reads.push({at:Date.now(),older,revision:rev});fs.appendFileSync(log,JSON.stringify(reads.at(-1))+'\n');
  await new Promise(r=>setTimeout(r,older?2200:1700));
  while(!older&&rev===2&&holdRefresh)await new Promise(r=>setTimeout(r,50));
  body={thread:thread(),timeline:{rows:[{id:older?'older-message':'same-message',kind:'message',text:older?'Older synthetic history':'Fresh response '+rev,version:rev===1?80:0}],hasEarlier:!older,before:older?null:'older1'},revision:rev===1?80:0,notes:[],pending:[],outgoing:[],notifications:[]};
  if(visibility&&!older){body.timeline={rows:Array.from({length:20},(_,n)=>({id:'visible-'+n,kind:'message',text:n===19?'Fresh response '+rev:('Visible passage '+n+' '+('Readable synthetic history. '.repeat(10)))})),hasEarlier:false,before:null};body.notifications=[{id:rev,thread_id:'same-thread',kind:'complete'}];body.catchup={token:'capture-'+rev};}
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
 else if(path==='/_qa/notification-only'){notificationOnly=true;clippedPreview=true;revision++;body={revision};}
 else if(path==='/_qa/live-outage'){rejectLive=true;for(const socket of sockets)socket.destroy();body={ok:true};}
 else if(path==='/_qa/live-recover'){rejectLive=false;revision++;body={revision};}
 else if(path==='/_qa/quiet-reconnect'){quietReconnect=true;revision++;for(const socket of sockets)socket.destroy();body={revision};}
 else if(path==='/_qa/silent'){revision++;body={revision};}
 else if(/^\/api\/threads\/(same|second)-thread\/reply$/.test(path)){const sent=JSON.parse(raw);replies.push({...sent,threadId:path.split('/')[3]});if(!failReply)replyReceipts.set(sent.id,{id:sent.id,thread_id:path.split('/')[3],state:replyAckState});while(holdReply)await new Promise(r=>setTimeout(r,50));await new Promise(r=>setTimeout(r,150));if(failReply){failReply=false;res.statusCode=503;body={error:'Synthetic delayed reply failure'};}else if(badReply){badReply=false;body={id:"wrong-id",state:"accepted"};}else body={id:sent.id,state:replyAckState};}
 else if(path==='/_qa/fail-acks'){failedAckReads=2;body={ok:true};}
 else if(path==='/_qa/state')body={revision,connections,reads,replies,positions,acknowledgements,ackAttempts,receiptReads,recoveries};
 else if(path==='/api/notifications')body={notifications:notificationOnly?[{id:revision,thread_id:'same-thread',kind:'complete'}]:[]};
 else if(path==='/api/attention')body={notifications:[]};
 else if(path==='/api/activity')body={items:[]};
 res.setHeader('Content-Type','application/json');res.end(JSON.stringify(body));
});
server.on('upgrade',(req,socket)=>{if(rejectLive){socket.destroy();return;}if(req.headers.authorization!=='Bearer synthetic-system76'){socket.destroy();return;}socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+crypto.createHash('sha1').update(req.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')+'\r\n\r\n');connections++;sockets.add(socket);socket.on('close',()=>sockets.delete(socket));socket.on('error',()=>sockets.delete(socket));if(!quietReconnect)send(socket,{type:'status',connected:true});});
server.listen(19998,'127.0.0.1',()=>console.log('Owned transcript fixture ready'));
