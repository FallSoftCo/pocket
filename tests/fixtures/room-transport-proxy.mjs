// Owned loopback-only fault boundary for real-room native qualification.
// Canonical requests still require their original gateway authentication.
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
const config=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
if(!Number.isInteger(config.port)||!Number.isInteger(config.upstreamPort)||!config.controlToken)throw Error('Private QA configuration required');
let fault={aux:false,dropMessageAck:false};
const sockets=new Set();
const record=value=>fs.appendFileSync(config.journal,JSON.stringify({at:Date.now(),...value})+'\n',{mode:0o600});
const server=http.createServer(async(req,res)=>{
 try{
  const chunks=[];let bytes=0;
  for await(const chunk of req){bytes+=chunk.length;if(bytes>32000)throw Error('QA body limit');chunks.push(chunk)}
  const body=Buffer.concat(chunks);
  if(req.url==='/__qa/control'){
   if(req.headers.authorization!=='Bearer '+config.controlToken){res.writeHead(403).end();return}
   if(req.method==='POST'){const next=JSON.parse(body.toString());if(next.disconnect)for(const socket of sockets)socket.destroy();delete next.disconnect;fault={...fault,...next};record({control:fault})}
   res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify(fault));return;
  }
  const auxiliary=/\/api\/hollywood\/v3\/rooms\/[0-9a-f-]+(?:\?|$|\/attention)/.test(req.url)&&!req.url.includes('/history');
  if(fault.aux&&req.method==='GET'&&auxiliary){
   record({method:req.method,path:req.url,status:503,auxiliaryFault:true});
   res.writeHead(503,{'Content-Type':'application/json'}).end(JSON.stringify({error:'QA work status temporarily unavailable',code:'QA_AUXILIARY_FAILURE'}));return;
  }
  if(!req.url.startsWith('/api/')&&req.url!=='/health'&&req.url!=='/events'){res.writeHead(404).end();return}
  const headers={...req.headers};delete headers.host;delete headers.connection;delete headers['content-length'];
  const response=await fetch('http://127.0.0.1:'+config.upstreamPort+req.url,{method:req.method,headers,...(body.length?{body}:{}),redirect:'manual',signal:AbortSignal.timeout(20000)});
  const value=Buffer.from(await response.arrayBuffer());
  let metadata={};try{const d=JSON.parse(value);metadata={events:d.events?.length,nextCursor:d.nextCursor,lastSeq:d.events?.at(-1)?.seq,eventId:d.event?.id,operationId:body.length?JSON.parse(body).operationId:undefined}}catch{}
  record({method:req.method,path:req.url,status:response.status,...metadata});
  if(fault.dropMessageAck&&req.method==='POST'&&/\/rooms\/[0-9a-f-]+\/messages$/.test(req.url)&&response.ok){fault.dropMessageAck=false;record({droppedAcknowledgement:true,operationId:metadata.operationId});res.destroy();return}
  const out=Object.fromEntries([...response.headers].filter(([key])=>!['content-length','content-encoding','transfer-encoding','connection'].includes(key)));
  res.writeHead(response.status,out).end(value);
 }catch(error){record({method:req.method,path:req.url,error:error.name});if(!res.headersSent)res.writeHead(502,{'Content-Type':'application/json'}).end(JSON.stringify({error:'QA upstream unavailable'}));else res.destroy()}
});
server.on('upgrade',(req,socket,head)=>{
 if(req.url!=='/events'){socket.destroy();return}
 sockets.add(socket);socket.on('close',()=>sockets.delete(socket));
 const upstream=net.connect(config.upstreamPort,'127.0.0.1',()=>{
  const lines=[`${req.method} ${req.url} HTTP/${req.httpVersion}`];
  for(let i=0;i<req.rawHeaders.length;i+=2)lines.push(req.rawHeaders[i]+': '+req.rawHeaders[i+1]);
  upstream.write(lines.join('\r\n')+'\r\n\r\n');if(head.length)upstream.write(head);
  socket.pipe(upstream);upstream.pipe(socket);record({websocket:'connected'});
 });
 upstream.on('error',()=>socket.destroy());socket.on('error',()=>upstream.destroy());socket.on('close',()=>upstream.destroy());
});
server.listen(config.port,'127.0.0.1',()=>record({listening:'127.0.0.1:'+config.port,upstream:'127.0.0.1:'+config.upstreamPort}));
