import http from 'node:http';
import crypto from 'node:crypto';
const qaBase=Number(process.env.PORT||19995);
const profiles=[{port:qaBase,host:'System76',token:'synthetic-system76',device:'synthetic-device-a'},{port:qaBase+1,host:'OZZZ',token:'synthetic-ozzz',device:'synthetic-device-b'},{port:qaBase+2,host:'Phone',token:'synthetic-phone',device:'synthetic-device-phone'}];
const sent=[],notes=[],updateChecks=[];let holdVoiceHistory=false;const voiceReads=[],voiceReturns=[];
function event(socket,value){const b=Buffer.from(JSON.stringify(value));socket.write(Buffer.concat([b.length<126?Buffer.from([129,b.length]):Buffer.from([129,126,b.length>>8,b.length&255]),b]));}
for(const profile of profiles){
 const sockets=new Set();
 const thread=(id='same-thread')=>({id,name:profile.host+' synthetic work'+(id==='second-thread'?' two':''),cwd:'/synthetic',status:{type:'idle'},canAcceptDirectInput:true,preview:profile.host+(id==='second-thread'?' second':'')+' answer only',previewRole:'codex',previewKind:'message',activityAt:id==='second-thread'?2000:1000,updatedAt:1000});
 const server=http.createServer(async(req,res)=>{
  if(req.headers.authorization!=='Bearer '+profile.token){res.writeHead(401);res.end('{}');return;}
  let raw='';for await(const b of req)raw+=b;
  const path=new URL(req.url,'http://localhost').pathname;let body={};
  console.log(JSON.stringify({host:profile.host,path,method:req.method}));
  if(path==='/api/backends')body={backends:[{id:'codex',configured:true,available:true}]};
  else if(path==='/api/status')body={host:profile.host,connected:true,local:profile.host==='Phone',deviceId:profile.device};
  else if(path==='/api/threads')body={threads:[thread(),thread('second-thread')]};
  else if(/^\/api\/threads\/[^/]+$/.test(path)){const t=thread(path.split('/').at(-1));body={thread:{...t,turns:[]},timeline:{rows:[{id:t.id+'/answer',turnId:'synthetic',kind:'message',text:t.preview}],hasEarlier:false,before:null},revision:0,notes:[],pending:[],notifications:[],outgoing:[]};}
  else if(/^\/api\/threads\/[^/]+\/reply$/.test(path)){const input=JSON.parse(raw);sent.push({host:profile.host,thread:path.split('/')[3],id:input.id});body={id:input.id,state:'accepted'};}
  else if(path==='/api/app/update'){updateChecks.push({host:profile.host,device:profile.device});body={available:false};}
  else if(path==='/api/notifications'||path==='/api/attention')body={notifications:[],items:[]};
  else if(path==='/api/activity')body={items:[]};
  else if(path==='/_qa/notify'){for(const socket of sockets)event(socket,{type:'notification',notification:{id:901,thread_id:'same-thread',title:'Phone result only',body:'Synthetic phone result',kind:'result',attachments:[],created_at:Date.now()}});body={ok:true};}
  else if(/^\/api\/threads\/[^/]+\/notes$/.test(path)){const note=JSON.parse(raw);notes.push({host:profile.host,thread:path.split('/')[3],id:note.id,text:note.text});body={notes:[note]};}
  else if(path==='/_qa/hold-voice-history'){holdVoiceHistory=true;body={ok:true};}
  else if(path==='/_qa/release-voice-history'){holdVoiceHistory=false;body={ok:true};}
  else if(path==='/api/voice/history'){
   voiceReads.push(profile.host);
   while(holdVoiceHistory)await new Promise(resolve=>setTimeout(resolve,30));
   voiceReturns.push(profile.host);
   body={turns:[{id:'history-'+profile.host,transcript:profile.host+' voice input',response:profile.host+' voice response'}],hasEarlier:false};
  }
  else if(path.startsWith('/api/voice/')){res.statusCode=503;body={error:'Synthetic fixture blocks voice execution and speech'};}
  else if(path==='/_qa/state')body={sent,notes,voiceReads,voiceReturns,updateChecks};
  res.setHeader('Content-Type','application/json');res.end(JSON.stringify(body));
 });
 server.on('upgrade',(req,socket)=>{if(req.headers.authorization!=='Bearer '+profile.token){socket.destroy();return;}socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+crypto.createHash('sha1').update(req.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')+'\r\n\r\n');sockets.add(socket);socket.on('close',()=>sockets.delete(socket));socket.on('error',()=>sockets.delete(socket));event(socket,{type:'status',connected:true});});
 server.listen(profile.port,'127.0.0.1',()=>console.log('Synthetic environment ready',profile.host,profile.port));
}
