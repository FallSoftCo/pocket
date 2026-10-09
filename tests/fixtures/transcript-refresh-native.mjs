import http from 'node:http';import crypto from 'node:crypto';import fs from 'node:fs';
let revision=1;const sockets=new Set(),reads=[];const log=process.env.NEXTCOMP_QA_LOG||'/tmp/nextcomp-transcript-fixture-events.jsonl';
const thread=()=>({id:'same-thread',name:'Synthetic freshness work',cwd:'/synthetic',status:{type:'idle'},preview:'Fresh response '+revision,previewKind:'message',previewRole:'codex',activityAt:revision*1000,updatedAt:revision*1000,recencyAt:revision*1000});
function send(socket,event){const b=Buffer.from(JSON.stringify(event));const h=b.length<126?Buffer.from([129,b.length]):Buffer.from([129,126,b.length>>8,b.length&255]);socket.write(Buffer.concat([h,b]));}
const server=http.createServer(async(req,res)=>{
 let raw='';for await(const b of req)raw+=b;
 if(req.headers.authorization!=='Bearer synthetic-system76'){res.writeHead(401);res.end('{}');return;}
 const path=new URL(req.url,'http://localhost').pathname;let body={};
 if(path==='/api/status')body={host:'Synthetic',connected:true,deviceId:'synthetic-device-a'};
 else if(path==='/api/threads')body={threads:[thread()]};
 else if(path==='/api/threads/same-thread'){
  const older=req.url.includes('before=');const rev=revision;reads.push({at:Date.now(),older,revision:rev});fs.appendFileSync(log,JSON.stringify(reads.at(-1))+'\n');
  await new Promise(r=>setTimeout(r,older?2200:1700));
  body={thread:thread(),timeline:{rows:[{id:older?'older-message':'same-message',kind:'message',text:older?'Older synthetic history':'Fresh response '+rev,version:rev===1?80:0}],hasEarlier:!older,before:older?null:'older1'},revision:rev===1?80:0,notes:[],pending:[],outgoing:[],notifications:[]};
 }else if(path==='/_qa/advance'){revision++;for(const s of sockets)send(s,{type:'sessionPreview',threadId:'same-thread',preview:thread().preview,previewKind:'message',activityAt:revision*1000});body={revision};}
 else if(path==='/_qa/repeat'){for(const s of sockets)send(s,{type:'sessionPreview',threadId:'same-thread',preview:thread().preview,previewKind:'message',activityAt:revision*1000});body={revision};}
 else if(path==='/_qa/silent'){revision++;body={revision};}
 else if(path==='/_qa/state')body={revision,reads};
 else if(path==='/api/notifications'||path==='/api/attention')body={notifications:[]};
 else if(path==='/api/activity')body={items:[]};
 res.setHeader('Content-Type','application/json');res.end(JSON.stringify(body));
});
server.on('upgrade',(req,socket)=>{if(req.headers.authorization!=='Bearer synthetic-system76'){socket.destroy();return;}socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+crypto.createHash('sha1').update(req.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')+'\r\n\r\n');sockets.add(socket);socket.on('close',()=>sockets.delete(socket));socket.on('error',()=>sockets.delete(socket));send(socket,{type:'status',connected:true});});
server.listen(19998,'127.0.0.1',()=>console.log('Owned transcript fixture ready'));
