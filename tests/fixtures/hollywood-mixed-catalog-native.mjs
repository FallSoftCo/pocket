// Synthetic routing only: no upstream, runtime, provider or model.
// Explicit free owned loopback PORT; optional private NEXTCOMP_QA_LOG.
import http from 'node:http';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
const port=Number(process.env.PORT);
if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Explicit owned PORT required');
const authority='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',human='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const roomId='cccccccc-cccc-4ccc-8ccc-ccccccccccc1',agent='dddddddd-dddd-4ddd-8ddd-ddddddddddd1';
const binding={id:'lax',api:3,name:'Synthetic rooms',configured:true,available:true,modes:['rooms','direct'],
 bindingId:'e'.repeat(64),installationId:authority,authorityId:authority,principalId:human,defaultRoomId:roomId,voice:false};
const room={id:roomId,authority,name:'Original room',owner:agent,purpose:'Synthetic original authority',revision:1};
const thread={id:'existing-session',name:'Original session',cwd:'/synthetic',status:{type:'idle'},canAcceptDirectInput:true,
 preview:'Stock session answer from original stock host',previewRole:'codex',previewKind:'message',activityAt:1700000000000,updatedAt:1700000000000};
const sockets=new Set(),reads=[],websockets=[];
let blockedTaskWrites=0,wrongSource=0,outage=false;
function record(row){if(process.env.NEXTCOMP_QA_LOG)fs.appendFileSync(process.env.NEXTCOMP_QA_LOG,JSON.stringify(row)+'\n',{mode:0o600});}
function origin(req){return {'Bearer synthetic-mixed-room':'room','Bearer synthetic-mixed-stock':'stock'}[req.headers.authorization];}
function send(socket,value){const b=Buffer.from(JSON.stringify(value));socket.write(Buffer.concat([b.length<126?Buffer.from([129,b.length]):Buffer.from([129,126,b.length>>8,b.length&255]),b]));}
const server=http.createServer(async(req,res)=>{
 const source=origin(req);if(!source){res.writeHead(401).end('{}');return;}
 let raw='';for await(const b of req){raw+=b;if(raw.length>32000){res.writeHead(413).end();return;}}
 const u=new URL(req.url,'http://127.0.0.1'),path=u.pathname;
 const row={source,method:req.method,path};record(row);if(req.method==='GET')reads.push(row);
 let body={},status=200;
 if(path==='/_qa/state')body={reads,websockets,blockedTaskWrites,wrongSource,outage};
 else if(path==='/_qa/room-outage'){outage=true;body={ok:true};}
 else if(path==='/_qa/room-online'){outage=false;body={ok:true};}
 else if(req.method==='POST'){
  if(/\/(notifications|catchup)\/read$/.test(path))body={ok:true,threadId:'existing-session',cursor:0,ids:[]};
  else {blockedTaskWrites++;status=503;body={error:'No task/provider writes in owned routing fixture'};}
 }else if(path==='/api/backends')body={backends:source==='room'?[{id:'codex',configured:false,available:false},binding]:[{id:'codex',configured:true,available:true}]};
 else if(path==='/api/status')body={host:source==='room'?'Room host':'Stock host',connected:true,deviceId:'synthetic-'+source,capabilities:{readOnlyTimeline:true}};
 else if(path==='/api/app/update')body={available:false};
 else if(path==='/api/hollywood/v3/rooms'){
  if(source!=='room'||req.headers['x-hollywood-binding']!==binding.bindingId){wrongSource++;status=403;body={error:'Wrong original room source'};}
  else if(outage){status=503;body={error:'Synthetic room catalog unavailable'};}
  else body={binding,rooms:[{...room,activity:{version:1,observedAt:Date.now(),pending:0,claimed:0,running:0,cancelRequested:0,unknown:0,waitingForHuman:0}}]};
 }else if(path.startsWith('/api/hollywood/v3/')){
  if(source!=='room'||req.headers['x-hollywood-binding']!==binding.bindingId){wrongSource++;status=403;body={error:'Wrong original room source'};}
  else if(path==='/api/hollywood/v3/rooms/'+roomId+'/history'){
   const view=u.searchParams.get('view')||'all',after=Number(u.searchParams.get('after')||0);
   const event={id:'00000000-0000-4000-8000-000000000117',seq:1,authority,room:roomId,author:agent,authorName:'Synthetic room agent',audience:'room',kind:'message',body:'Canonical room answer from original authority'};
   body={binding,roomId,view,events:view==='activity'||after>=1?[]:[event],nextCursor:view==='activity'?0:1,hasMore:false,olderCursor:null};
  }else if(path.endsWith('/attention'))body={binding,attention:[],nextCursor:0};
  else if(path==='/api/hollywood/v3/rooms/'+roomId)body={binding,room,permissions:{can_post:true,can_request:true},actors:[{id:agent,name:'Synthetic room agent',kind:'agent'},{id:human,name:'Synthetic viewer',kind:'human'}],deliveries:[]};
  else {status=404;body={binding,error:'No synthetic receipt; original remains unknown'};}
 }else if(path==='/api/threads')body={threads:source==='stock'?[thread]:[]};
 else if(/^\/api\/threads\/existing-session(?:\/head)?$/.test(path)){
  if(source!=='stock'){wrongSource++;status=403;body={error:'Wrong original stock source'};}
  else body={thread:{...thread,turns:[]},timeline:{rows:[{id:'synthetic/answer',turnId:'synthetic',kind:'message',text:thread.preview}],hasEarlier:false,before:null},revision:1,notes:[],pending:[],notifications:[],outgoing:[]};
 }else if(path==='/api/replies/00000000-0000-4000-8000-000000000119'){
  if(source!=='stock'){wrongSource++;status=403;body={error:'Wrong original receipt source'};}
  else body={id:path.split('/').at(-1),thread_id:'existing-session',state:'unknown'};
 }else if(path==='/api/notifications')body={notifications:[]};
 else if(path==='/api/attention'||path==='/api/activity')body={items:[]};
 else if(path==='/api/speech')body={items:[],count:0};
 else if(path==='/api/voice/history')body={turns:[],hasEarlier:false};
 res.writeHead(status,{'Content-Type':'application/json'}).end(JSON.stringify(body));
});
server.on('upgrade',(req,socket)=>{
 const source=origin(req);if(!source||req.url!=='/events'){socket.destroy();return;}
 socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+createHash('sha1').update(req.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')+'\r\n\r\n');
 sockets.add(socket);websockets.push({source});record({source,kind:'websocket'});
 socket.on('close',()=>sockets.delete(socket));socket.on('error',()=>socket.destroy());
 socket.on('data',()=>{});send(socket,{type:'status',connected:true});
});
server.listen(port,'127.0.0.1',()=>console.log('Owned mixed catalog fixture ready '+port));
for(const signal of ['SIGTERM','SIGINT'])process.once(signal,()=>{for(const s of sockets)s.destroy();server.close(()=>process.exit(0));});
