// Owned synthetic native UI boundary. No upstream, provider, workers or task execution.
import http from 'node:http';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
const port=Number(process.env.PORT||process.argv[2]||18893);
if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Explicit owned loopback port required');
const token='synthetic-native-voice-only';
const authority='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',human='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const roomA='cccccccc-cccc-4ccc-8ccc-ccccccccccc1',roomB='cccccccc-cccc-4ccc-8ccc-ccccccccccc2';
const agentA='dddddddd-dddd-4ddd-8ddd-ddddddddddd1',agentB='dddddddd-dddd-4ddd-8ddd-ddddddddddd2';
const binding={id:'lax',name:'Synthetic LAX',configured:true,available:true,api:3,modes:['rooms','direct'],bindingId:'e'.repeat(64),installationId:authority,authorityId:authority,principalId:human,defaultRoomId:roomA,voice:true};
const rooms=[roomA,roomB].map((id,n)=>({id,authority,name:'Synthetic voice room '+(n?'B':'A'),owner:n?agentB:agentA,purpose:'Owned UI fixture; no provider or worker',revision:1}));
const sockets=new Set(),starts=[];let nativeAttempts=0,blockedTaskWrites=0;
const journal=process.env.NATIVE_VOICE_UI_LOG;
function record(row){if(journal)fs.appendFileSync(journal,JSON.stringify({at:Date.now(),...row})+'\n',{mode:0o600});}
const server=http.createServer(async(req,res)=>{
 let raw='';for await(const b of req){raw+=b;if(raw.length>32000){res.writeHead(413).end();return}}
 if(req.headers.authorization!=='Bearer '+token){res.writeHead(401).end('{}');return}
 const path=new URL(req.url,'http://127.0.0.1').pathname;let body={},status=200;
 if(path==='/_qa/status')body={starts,nativeAttempts,blockedTaskWrites,noProvider:true};
 else if(path==='/api/backends')body={backends:[{id:'codex',configured:false,available:false},binding]};
 else if(path==='/api/status')body={connected:true,host:'Owned synthetic native voice',deviceId:'synthetic-native-voice-device'};
 else if(path==='/api/app/update')body={available:false};
 else if(path==='/api/voice/history')body={turns:[],hasEarlier:false};
 else if(path==='/api/voice/start'&&req.method==='POST'){const input=JSON.parse(raw);starts.push(input);record({kind:'voice-start',input});body={ok:true};}
 else if(path==='/api/voice/native/start'){nativeAttempts++;record({kind:'provider-blocked'});status=503;body={error:'Owned QA provider intentionally unavailable'};}
 else if(path.startsWith('/api/voice/turns/')){status=404;body={error:'No synthetic task was submitted'};}
 else if(path==='/api/speech/control')body={ok:true};
 else if(path==='/api/hollywood/v3/rooms'&&req.method==='GET'){
  body={binding,rooms:rooms.map(r=>({...r,activity:{version:1,observedAt:Date.now(),pending:0,claimed:0,running:0,cancelRequested:0,unknown:0,waitingForHuman:0}}))};
 }else if(path.startsWith('/api/hollywood/v3/rooms/')&&req.method==='GET'){
  const id=path.split('/')[5],room=rooms.find(r=>r.id===id);
  if(!room){status=404;body={error:'Unknown synthetic room'};}
  else if(path.endsWith('/history'))body={binding,roomId:id,view:new URL(req.url,"http://127.0.0.1").searchParams.get("view")||"all",events:[],nextCursor:0,hasMore:false,before:null};
  else if(path.endsWith('/attention'))body={binding,attention:[],nextCursor:0};
  else body={binding,room,permissions:{can_post:true,can_request:true},actors:[{id:room.owner,name:'Synthetic agent '+(id===roomA?'A':'B'),kind:'agent'},{id:human,name:'Synthetic owner',kind:'human'}],deliveries:[]};
 }else if(req.method==='POST'){blockedTaskWrites++;status=503;body={error:'Owned fixture has no task/provider execution'};}
 else if(path==='/api/threads')body={threads:[]};
 else if(path==='/api/notifications')body={notifications:[]};
 else if(path==='/api/speech')body={items:[],count:0};
 else body={};
 res.writeHead(status,{'Content-Type':'application/json'}).end(JSON.stringify(body));
});
server.on('upgrade',(req,socket)=>{
 if(req.url!=='/events'||req.headers.authorization!=='Bearer '+token){socket.destroy();return}
 const accept=createHash('sha1').update(req.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
 socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+accept+'\r\n\r\n');
 sockets.add(socket);socket.on('close',()=>sockets.delete(socket));socket.on('error',()=>socket.destroy());socket.on('data',()=>{});
});
server.listen(port,'127.0.0.1',()=>console.log('Owned synthetic native voice fixture ready '+port));
for(const signal of ['SIGTERM','SIGINT'])process.once(signal,()=>{for(const s of sockets)s.destroy();server.close(()=>process.exit(0));});
