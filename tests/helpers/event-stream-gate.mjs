// Transparent loopback transport fault, not an HTTP/backend behavior fixture.
// Interrupt /events or inject one /api/status response; all other traffic passes unchanged.
import http from 'node:http';
import net from 'node:net';
export async function eventStreamGate(target) {
 const base=new URL(target),sockets=new Set(),requests=[];let blocked=false,nextStatusFailure=null;
 if(base.hostname!=='127.0.0.1')throw Error('Event-stream gate requires disposable loopback backend');
 const server=http.createServer((req,res)=>{
  const record={method:req.method,path:req.url,at:Date.now()};requests.push(record);res.on('finish',()=>{record.status=res.statusCode;});
  if(req.url==='/api/status'&&nextStatusFailure){const status=nextStatusFailure;nextStatusFailure=null;record.injected=status;res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify({error:'Controlled status failure'}));return;}
  const upstream=http.request({hostname:base.hostname,port:base.port,path:req.url,method:req.method,headers:req.headers},reply=>{res.writeHead(reply.statusCode,reply.headers);reply.pipe(res);});
  upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end();});req.pipe(upstream);req.on('aborted',()=>upstream.destroy());
 });
 server.on('upgrade',(req,downstream,head)=>{
  requests.push({method:'UPGRADE',path:req.url,blocked,at:Date.now()});
  if(blocked||req.url!=='/events'){downstream.destroy();return;}
  const upstream=net.connect(Number(base.port),base.hostname);sockets.add(downstream);sockets.add(upstream);
  const close=()=>{sockets.delete(downstream);sockets.delete(upstream);downstream.destroy();upstream.destroy();};
  downstream.on('error',close);upstream.on('error',close);downstream.on('close',close);upstream.on('close',close);
  upstream.on('connect',()=>{upstream.write(`${req.method} ${req.url} HTTP/${req.httpVersion}\r\n`+Object.entries(req.headers).map(([name,value])=>name+': '+value).join('\r\n')+'\r\n\r\n');if(head.length)upstream.write(head);downstream.pipe(upstream);upstream.pipe(downstream);});
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 return {base:`http://127.0.0.1:${server.address().port}`,requests,
  interrupt(){blocked=true;for(const socket of sockets)socket.destroy();},
  recover({statusFailure=null}={}){nextStatusFailure=statusFailure;blocked=false;},
  async close(){for(const socket of sockets)socket.destroy();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}};
}
