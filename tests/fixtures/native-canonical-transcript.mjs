import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import {projectNativeTimeline} from '../../server/native-timeline.mjs';
const port=Number(process.env.PORT||19998),epoch=process.env.NEXTCOMP_QA_EPOCH||crypto.randomUUID();
const capture=process.env.NEXTCOMP_QA_NATIVE_CAPTURE?JSON.parse(fs.readFileSync(process.env.NEXTCOMP_QA_NATIVE_CAPTURE,'utf8')):null;
const captured=new Map((capture?.pages||[]).map(p=>[p.cursor??'',p.response]));
let revision=1,hold=false,holdOlder=false,offline=false,replay=false,legacyReads=0,positionReads=0,workSubmissions=0,question=false,answers=0,streamFinished=false,streamsSent=0,streamTimer=null;
let archiveHold=false,archiveError=false,archiveOrigins=[];
let retainedResult=false,legacyHistoryReads=0,archived=false,archiveCalls=0,restoreCalls=0;
const sockets=new Set(),reads=[];let galleryMetadataReads=0,galleryImageReads=0,galleryCredentialLeaks=0;
let attentionStudy=false,attentionResolved=false,reportReads=0;
let queueStudy=false,queueRevision=1,queueHold=false;const queueCalls=[];let queueItems=[];
const queueSnapshot=()=>({version:1,revision:queueRevision,items:structuredClone(queueItems)});
const meta=()=>({id:'same-thread',name:'Native canonical work',cwd:'/synthetic',status:{type:queueStudy?'active':'idle'},updatedAt:1,recencyAt:1,canAcceptDirectInput:true});
// Opt-in repository lifecycle evidence; all identities/content remain synthetic.
let historyLifecycle=false,historyCatalogHold=false,historyCatalogReads=0;const lifecycleReads=[],lifecycleThreads=new Map();
const lifecycleMeta=(id,state)=>({...meta(),id,name:'History '+id,archived:state.archived===true,ephemeral:state.ephemeral===true,path:state.ephemeral?null:'/synthetic/retained-rollout',status:{type:state.status||'idle'},updatedAt:state.revision||1,activityAt:state.revision||1});
const item=(position,id,type,text)=>({type:'item',position,turnId:'native-turn',item:{id,type,startedAtMs:1794017100000+position*60000,...(type==='userMessage'?{content:[{type:'text',text}]}:{text})}});
function page(){
 return {data:[
  {type:'turnStarted',position:1,turnId:'native-turn',startedAt:1},
  item(2,'request-one','userMessage','Native request one'),
  item(3,'answer-one','agentMessage','Native answer one'),
  ...(revision>1?[item(4,'request-two','userMessage','Native request two'),item(5,'answer-two','agentMessage','Native answer two')]:[]),
  ...(!queueStudy?[{type:'turnCompleted',position:revision>1?6:4,turnId:'native-turn',status:'completed'}]:[]),
  ...(streamFinished?[
   {type:'turnStarted',position:10,turnId:'stream-turn',startedAt:2},
   {...item(11,'stream-user','userMessage','Native stream request'),turnId:'stream-turn'},
   {...item(12,'stream-answer','agentMessage','Native stream answer'),turnId:'stream-turn'},
   {type:'turnCompleted',position:13,turnId:'stream-turn',status:'completed'}
  ]:[])
 ],nextCursor:null,activeRealtimeSessionAtPageStart:null};
}
function send(socket,value){const bytes=Buffer.from(JSON.stringify(value));const header=bytes.length<126?Buffer.from([129,bytes.length]):Buffer.from([129,126,bytes.length>>8,bytes.length&255]);socket.write(Buffer.concat([header,bytes]));}
function broadcast(value){for(const s of sockets)send(s,value);}
function trace(value){if(process.env.NEXTCOMP_QA_LOG)fs.appendFileSync(process.env.NEXTCOMP_QA_LOG,JSON.stringify({at:Date.now(),...value})+'\n',{mode:0o600});}
const server=http.createServer(async(req,res)=>{

 const url=new URL(req.url,'http://localhost'),path=url.pathname;
 trace({kind:'request',method:req.method,path,query:url.search,revision,streamsSent,offline});
 if(process.env.NEXTCOMP_QA_HISTORY_TRACE==='1')console.log(JSON.stringify({at:Date.now(),method:req.method,path,query:url.search}));
 if(path.startsWith('/8bar/')){
  if(req.headers.authorization){galleryCredentialLeaks++;res.writeHead(403).end();return;}
  if(path==='/8bar/showcase-catalog.json'){galleryMetadataReads++;res.setHeader('Content-Type','application/json');res.end(JSON.stringify([{id:'native-preview',title:'Native material study',poster:'preview.svg'}]));return;}
  if(path==='/8bar/preview.svg'){galleryImageReads++;res.setHeader('Content-Type','image/svg+xml');res.end('<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360"><rect width="640" height="360" fill="#254d50"/><rect x="24" y="24" width="280" height="252" rx="4" fill="#b69972"/><text x="24" y="326" font-size="25" fill="white">Native material study</text></svg>');return;}
  res.writeHead(404).end();return;
 }

 if(!String(req.headers.authorization||'').startsWith('Bearer synthetic-system76')){res.writeHead(401).end('{}');return;}
 let raw='';for await(const chunk of req)raw+=chunk;
 let body={};res.setHeader('Content-Type','application/json');
 if(path==='/_qa/attention-setup'){
  attentionStudy=true;attentionResolved=false;queueStudy=true;queueRevision++;queueHold=false;queueCalls.length=0;
  queueItems=[{id:'needs-review-original',threadId:'same-thread',text:'Delivery needs checking',state:'unknown',mode:'steer'}];
  res.end(JSON.stringify({ok:true}));return;
 }
 if(path==='/_qa/attention-resolve'){
  attentionResolved=true;queueItems=[];queueRevision++;
  broadcast({type:'reply',threadId:'same-thread',id:'needs-review-original',state:'accepted',outbox:queueSnapshot()});
  res.end(JSON.stringify({ok:true,reportReads,calls:queueCalls}));return;
 }
 if(path==='/_qa/attention-end'){attentionStudy=false;queueStudy=false;queueItems=[];res.end(JSON.stringify({ok:true,reportReads,calls:queueCalls}));return;}
 if(path==='/api/coordinator/reports'){reportReads++;res.end(JSON.stringify({reports:[],unreadCount:0,hasEarlier:false}));return;}
 if(attentionStudy&&path==='/api/threads'){
  const evidence={version:1,verified:true,items:attentionResolved?[]:[{kind:'deliveryUnknown',id:'outgoing:needs-review-original',label:'Check message delivery'}]};
  res.end(JSON.stringify({threads:[{...meta(),attention:evidence,preview:'Review this original message'},{...meta(),id:'ordinary-work',name:'Ordinary completed work',status:{type:'idle'},preview:'Completed result',attention:{version:1,verified:true,items:[]}}]}));return;
 }
 if(path==='/_qa/queue-setup'){
  // Scenarios share one source/outbox lifetime: revisions must never rewind.
  queueStudy=true;queueHold=true;queueRevision++;queueCalls.length=0;
  queueItems=[{id:'queue-keep',threadId:'same-thread',text:'Keep this queue held',state:'held',mode:'queue'},{id:'queue-remove',threadId:'same-thread',text:'Remove only this queued message',state:'queued',mode:'queue'},{id:'queue-promote',threadId:'same-thread',text:'Original queued steering text',state:'held',mode:'queue'}];
  res.end(JSON.stringify({ok:true}));return;
 }
 if(path==='/_qa/queue-release'){queueHold=false;res.end(JSON.stringify({ok:true}));return;}
 if(path==='/_qa/queue-state'){res.end(JSON.stringify({calls:queueCalls,outbox:queueSnapshot()}));return;}
 if(queueStudy&&path.startsWith('/api/threads/same-thread/replies/')){
  const id=decodeURIComponent(path.split('/').at(-1)),action=JSON.parse(raw||'{}').action,row=queueItems.find(r=>r.id===id);
  if(!row){res.writeHead(404).end('{}');return;}
  queueCalls.push({id,action,text:row.text,expectedActiveTurn:'native-turn'});
  if(action==='send')while(queueHold&&!res.destroyed)await new Promise(resolve=>setTimeout(resolve,20));
  if(res.destroyed)return;
  if(action==='send'||action==='remove'){queueItems=queueItems.filter(r=>r.id!==id);queueRevision++;}
  else if(action==='edit'){row.text=JSON.parse(raw).text;queueRevision++;}
  else{res.writeHead(400).end('{}');return;}
  res.end(JSON.stringify({id,state:action==='send'?'accepted':action==='remove'?'cancelled':row.state,mode:action==='send'?'steer':'queue',outbox:queueSnapshot()}));return;
 }
 if(path==='/_qa/history-lifecycle'){
  historyLifecycle=true;const change=JSON.parse(raw||'{}');
  if(typeof change.holdCatalog==='boolean')historyCatalogHold=change.holdCatalog;
  if(change.releaseHolds){historyCatalogHold=false;for(const state of lifecycleThreads.values()){state.holdNative=false;state.holdHead=false;}}
  if(change.id)lifecycleThreads.set(change.id,{...lifecycleThreads.get(change.id),...change});
  res.end(JSON.stringify({reads:lifecycleReads,catalogReads:historyCatalogReads,threads:[...lifecycleThreads.entries()].map(([id,state])=>lifecycleMeta(id,state)),workSubmissions}));return;
 }
 if(historyLifecycle&&path==='/api/threads'){
  const threads=[...lifecycleThreads.entries()].filter(([,state])=>state.catalog!==false&&!!state.archived===(url.searchParams.get('archived')==='true')).map(([id,state])=>lifecycleMeta(id,state));
  historyCatalogReads++;
  while(historyCatalogHold&&!res.destroyed)await new Promise(r=>setTimeout(r,20));
  if(res.destroyed)return;
  res.end(JSON.stringify({threads}));return;
 }
 if(historyLifecycle&&/^\/api\/threads\/[^/]+(?:\/native-timeline|\/head)?$/.test(path)){
  const id=decodeURIComponent(path.split('/')[3]),state=structuredClone(lifecycleThreads.get(id)||{code:'THREAD_HISTORY_UNAVAILABLE'});
  const origin=req.headers.authorization.endsWith('-beta')?'B':'A',native=path.endsWith('/native-timeline');
  const entry={id,origin,path,cursor:url.searchParams.get('before'),at:Date.now(),settled:false};lifecycleReads.push(entry);
  while((native?lifecycleThreads.get(id)?.holdNative:lifecycleThreads.get(id)?.holdHead)&&!res.destroyed)await new Promise(r=>setTimeout(r,20));
  if(res.destroyed)return;
  entry.settled=true;
  const code=origin==='B'?null:state.code;
  if(code&&(code!=='THREAD_ARCHIVED'||native)){
   const kind={THREAD_HISTORY_UNAVAILABLE:'historyUnavailable',THREAD_EPHEMERAL_UNAVAILABLE:'ephemeralUnavailable',THREAD_ARCHIVED:'archived',THREAD_NOT_MATERIALIZED:'notMaterialized'}[code];
   entry.code=code;res.writeHead(409).end(JSON.stringify({code,error:'Synthetic history unavailable',readAvailability:{threadId:state.wrongIdentity?'another-thread':id,kind,operation:code==='THREAD_NOT_MATERIALIZED'?'history/read':'thread/read'}}));return;
  }
  if(!native){
   if(entry.cursor&&entry.cursor!=='archive-earlier'){res.writeHead(400).end('{"error":"Foreign archive cursor"}');return;}
   const rows=[{id:'archive/'+(entry.cursor?'earlier':'current'),kind:'message',turnId:'archive',text:entry.cursor?'Archived earlier response':'Archived retained response'}];
   res.end(JSON.stringify({thread:lifecycleMeta(id,{...state,archived:true}),timeline:{rows,canonicalIds:rows.map(row=>row.id),hasEarlier:!entry.cursor,before:entry.cursor?null:'archive-earlier'},historyHead:{threadId:id,epoch,version:1},notes:[],pending:[],notifications:[],outgoing:[]}));return;
  }
  res.end(JSON.stringify({thread:lifecycleMeta(id,state),timeline:projectNativeTimeline(page(),{threadId:id,revision:state.revision||1}),historyHead:{threadId:id,epoch,version:state.revision||1},notes:[],pending:[],notifications:[],outgoing:[]}));return;
 }
 if(path==='/api/status')body={host:'Native canonical source',connected:!offline,deviceId:'synthetic-device-a',capabilities:{readOnlyTimeline:true,canonicalTimeline:1},...(url.searchParams.has('threadId')?{historyHead:{threadId:'same-thread',epoch,version:revision}}:{})};
 else if(path==='/api/threads/legacy-thread/native-timeline'){res.writeHead(409).end(JSON.stringify({code:'NATIVE_TIMELINE_UNSUPPORTED',error:'This session uses turn history rather than native timeline history.'}));return;}
 else if(path==='/api/threads/denied-thread/native-timeline'){res.writeHead(403).end(JSON.stringify({code:'DENIED',error:'Synthetic permission refusal'}));return;}
 else if(path==='/api/threads/legacy-thread'||path==='/api/threads/legacy-thread/head'){
  legacyHistoryReads++;
  const older=url.searchParams.has('before');
  if(older&&url.searchParams.get('before')!=='legacy-opaque-cursor'){res.writeHead(400).end('{}');return;}
  const rows=[{id:'legacy-turn/'+(older?'earlier':'request'),turnId:'legacy-turn',kind:'user',text:older?'Earlier legacy request':'Legacy request'},{id:'legacy-turn/'+(older?'old-answer':'answer'),turnId:'legacy-turn',kind:'message',text:older?'Earlier legacy answer':'Legacy answer'}];
  body={thread:{...meta(),id:'legacy-thread',historyMode:'legacy'},timeline:{rows,canonicalIds:rows.map(r=>r.id),hasEarlier:!older,before:older?null:'legacy-opaque-cursor'},revision,historyHead:{threadId:'legacy-thread',epoch,version:revision},pending:[],outgoing:[],notes:[],notifications:[]};
 }
 else if(path==='/api/threads')body={threads:archived===(url.searchParams.get('archived')==='true')?[{...meta(),archived,preview:revision>1?'Native answer two':'Native answer one',previewRole:'codex',previewKind:'message'}]:[]};
 else if(path==='/api/threads/same-thread/archive'&&req.method==='POST'){archiveCalls++;archiveOrigins.push(req.headers.authorization);while(archiveHold&&!res.destroyed)await new Promise(r=>setTimeout(r,20));if(res.destroyed)return;if(archiveError){res.writeHead(409).end(JSON.stringify({error:'Close this session in its Mac terminal before archiving.'}));return;}archived=true;body={ok:true};}
 else if(path==='/api/threads/same-thread/unarchive'&&req.method==='POST'){archived=false;restoreCalls++;body={ok:true};}
 else if(path==='/api/threads/same-thread/native-timeline'){
  const cursor=url.searchParams.get('before')||'';
  reads.push({cursor,at:Date.now(),replay});
  while((hold||holdOlder&&cursor)&&!res.destroyed)await new Promise(resolve=>setTimeout(resolve,20));
  if(res.destroyed)return;
  if(offline){res.writeHead(503).end('{"error":"Synthetic native outage"}');return;}
  const source=replay?captured.get(cursor):page();
  if(!source){res.writeHead(409).end('{"error":"Capture boundary, not end of history"}');return;}
  body={thread:meta(),timeline:projectNativeTimeline(source,{threadId:'same-thread',revision}),revision,
   historyHead:{threadId:'same-thread',epoch,version:revision},pending:question?[{id:'native-question',method:'item/tool/requestUserInput',params:{threadId:'same-thread',turnId:'native-turn',itemId:'question',questions:[{id:'choice',header:'Choice',question:'Native pending question',options:[{label:'Proceed',description:'Proceed in the synthetic fixture'}]}]}}]:[],outgoing:queueStudy?queueSnapshot().items:[],...(queueStudy?{outbox:queueSnapshot()}:{}),notes:retainedResult?[{id:'answer-one',text:'Native answer one'},{id:'request-one',text:'Native request one'}]:[],notifications:[]};
 }else if(path==='/api/threads/same-thread'||path==='/api/threads/same-thread/head'){
  legacyReads++;res.writeHead(500).end('{"error":"Native source used legacy history"}');return;
 }else if(path==='/_qa/result'){retainedResult=true;revision++;broadcast({type:'timeline',threadId:'same-thread',version:revision,reload:true,historyHead:{threadId:'same-thread',epoch,version:revision}});body={ok:true};
 }else if(path==='/api/threads/same-thread/notes/remove'){retainedResult=false;body={notes:[]};
 }else if(path==='/_qa/question'){question=true;revision++;broadcast({type:'timeline',threadId:'same-thread',version:revision,reload:true,historyHead:{threadId:'same-thread',epoch,version:revision}});body={ok:true};
 }else if(path==='/api/requests/native-question/answer'){answers++;question=false;revision++;broadcast({type:'timeline',threadId:'same-thread',version:revision,reload:true,historyHead:{threadId:'same-thread',epoch,version:revision}});body={ok:true};
 }else if(path==='/api/threads/same-thread/timeline/position'){positionReads++;res.writeHead(500).end('{"error":"Legacy position repair entered native mode"}');return;
 }else if(path==='/_qa/stream-unpositioned'){
  if(streamTimer)throw Error('Synthetic stream already started');
  streamTimer=setInterval(()=>{
   streamsSent++;
   broadcast({type:'timeline',threadId:'same-thread',turnId:'stream-turn',version:revision,nativeMethod:'item/agentMessage/delta',nativeItemId:'stream-answer',historyHead:{threadId:'same-thread',epoch,version:revision},row:{id:'stream-turn/stream-answer',itemId:'stream-answer',turnId:'stream-turn',kind:'message',text:'Native stream answer',version:revision}});
   if(streamsSent>=20){clearInterval(streamTimer);streamTimer=null;}
  },100);body={ok:true,readBaseline:reads.length};
 }else if(path==='/_qa/stream-finish'){
  streamFinished=true;revision++;
  broadcast({type:'timeline',threadId:'same-thread',turnId:'stream-turn',version:revision,nativeMethod:'item/completed',nativeItemId:'stream-answer',historyHead:{threadId:'same-thread',epoch,version:revision},row:{id:'stream-turn/stream-answer',itemId:'stream-answer',turnId:'stream-turn',kind:'message',text:'Native stream answer',version:revision}});body={ok:true};
 }else if(path==='/_qa/advance-held'){
  revision=2;hold=true;
  broadcast({type:'timeline',threadId:'same-thread',turnId:'native-turn',version:2,historyHead:{threadId:'same-thread',epoch,version:2},
   row:{id:'native-turn/answer-two',itemId:'answer-two',turnId:'native-turn',kind:'message',text:'Native answer two',version:2}});
  broadcast({type:'timeline',threadId:'same-thread',turnId:'native-turn',version:2,historyHead:{threadId:'same-thread',epoch,version:2},
   row:{id:'native-turn/request-two',itemId:'request-two',turnId:'native-turn',kind:'user',text:'Native request two',version:2}});
  body={ok:true};
 }else if(path==='/_qa/hold-older'){holdOlder=true;body={ok:true};}
 else if(path==='/_qa/release-older'){holdOlder=false;body={ok:true};}
 else if(path==='/_qa/release'){hold=false;holdOlder=false;body={ok:true};}
 else if(path==='/_qa/disconnect'){offline=true;for(const s of sockets)s.destroy();body={ok:true};}
 else if(path==='/_qa/recover'){offline=false;revision++;broadcast({type:'status',connected:true});body={ok:true};}
 else if(path==='/_qa/enable-capture'){
  if(!capture){res.writeHead(409).end('{"error":"Private native capture required"}');return;}
  replay=true;body={source:'recorded-native-timeline',pages:captured.size,firstBefore:captured.get('')?.nextCursor};
 }else if(path==='/_qa/archive-hold'){archiveHold=true;body={ok:true};}
 else if(path==='/_qa/archive-refuse'){archiveError=true;archiveHold=false;body={ok:true};}
 else if(path==='/_qa/state')body={reads,archived,archiveCalls,archiveOrigins,restoreCalls,legacyReads,legacyHistoryReads,positionReads,workSubmissions,answers,streamsSent,galleryMetadataReads,galleryImageReads,galleryCredentialLeaks};
 else if(path==='/api/backends')body={backends:[{id:'codex',configured:true,available:true}]};
 else if(path==='/api/notifications')body={notifications:[]};
 else if(path==='/api/activity')body={items:[]};
 else if(path==='/api/attention')body={notifications:[]};
 else if(path.startsWith('/api/immersion'))body={enabled:false,density:0,translations:[],plans:[]};
 else if(path==='/api/models')body={models:[]};
 else if(path==='/api/voice/history')body={turns:[],hasEarlier:false};
 else if(req.method!=='GET'&&/\/api\/(threads\/.*\/(reply|recovery)|sessions|voice\/native\/start)/.test(path)){
  workSubmissions++;res.writeHead(403).end('{"error":"Read-only native qualification"}');return;
 }
 res.end(JSON.stringify(body));
});
server.on('upgrade',(req,socket)=>{
 if(!String(req.headers.authorization||'').startsWith('Bearer synthetic-system76')||offline){socket.destroy();return;}
 socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+crypto.createHash('sha1').update(req.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')+'\r\n\r\n');
 sockets.add(socket);socket.on('close',()=>sockets.delete(socket));socket.on('error',()=>sockets.delete(socket));send(socket,{type:'status',connected:true});
});
server.listen(port,'127.0.0.1',()=>console.log('Owned native canonical fixture ready '+port));
process.on('SIGTERM',()=>{trace({kind:'finalState',reads,streamsSent,legacyReads,positionReads,workSubmissions,answers});if(streamTimer)clearInterval(streamTimer);for(const s of sockets)s.destroy();server.close(()=>process.exit(0));});
