import http from 'node:http';
import path from 'node:path';

export const COMPUTER_USE_INSTRUCTIONS=`For shared computer use, use the registered computer-use MCP tools discover, resources, run, status and control. This is trusted cooperative automation: scripts have full Node and mature Playwright with the existing main Chrome browser/context/page and can use desktop tools. Resource and owner labels coordinate work; they are not permissions or a page/action whitelist. Use an owner identifying NextComp and the current thread/task. Discover the existing tabs and resource examples, reuse the main profile and task-owned tabs, and preserve unrelated pages, login state and drafts.

For independent DOM work, declare the exact shared page resource when sharing a tab; disjoint resources run concurrently. Declare the actual shared resource consistently for browser/account global changes. For an adaptive visual/input loop, acquire desktop:main once with resources, retain that lease across multiple run submit calls, renew when needed, and release when finished. Waiting owns no input. A screenshot alone need not reserve the desktop. runJob joins its declared resources automatically; do not add another lease around it. Keep native pressed-key/button cleanup in the script finally block. Use status wait/events for completion rather than model polling. Cancel, renew or release original IDs with control. Preserve original owner/ID and immutable request body after lost replies; observe their status rather than replaying uncertain external writes. The broker releases resources after its executor stops; interruptions do not prove browser-side effects absent. Resources being available do not prove business success. Do not use the retired ComputerUseClient, worker credentials, manual withLease adapters or old broker endpoints. BLD remains explicitly exempt; preserve all business authorization, deliberate-stop and task-ownership constraints.`;

export function newThreadComputerUseOptions(existing='') {
 return {developerInstructions:[existing,COMPUTER_USE_INSTRUCTIONS].filter(Boolean).join('\n\n')};
}

export async function computerUseStatus({env=process.env,requestImpl=http.request,timeoutMs=2000}={}) {
 const socket=env.TRUSTED_BROKER_SOCKET;
 if(!socket)return {configured:false,available:false,enforcement:'cooperative',reason:'not_configured'};
 if(typeof socket!=='string'||!path.isAbsolute(socket)||path.normalize(socket)!==socket)
  return {configured:true,available:false,enforcement:'cooperative',reason:'invalid_socket_configuration'};
 try {
  const rows=await new Promise((resolve,reject)=>{
   let settled=false;
   const done=(err,value)=>{if(settled)return;settled=true;clearTimeout(timer);err?reject(err):resolve(value);};
   const request=requestImpl({socketPath:socket,path:'/',method:'POST',headers:{'Content-Type':'application/json'}},response=>{
    let bytes=0,chunks=[];
    response.on('data',chunk=>{bytes+=chunk.length;if(bytes>65536){response.destroy();done(Error('Bounded health response exceeded'));return;}chunks.push(Buffer.from(chunk));});
    response.on('error',error=>done(error));
    response.on('aborted',()=>done(Error('Health response interrupted')));
    response.on('end',()=>{try{const value=JSON.parse(Buffer.concat(chunks).toString('utf8'));if(response.statusCode!==200||!Array.isArray(value.result))throw Error('Unexpected broker health contract');done(null,value.result);}catch(error){done(error);}});
   });
   const timer=setTimeout(()=>{request.destroy();done(Error('Broker health timeout'));},timeoutMs);
   request.on('error',error=>done(error));
   request.end(JSON.stringify({method:'events',input:{owner:'nextcomp:health',after:0}}));
  });
  // Successful read-only control-plane call is not a browser/account readiness claim.
  return {configured:true,available:true,enforcement:'cooperative',transport:'private-unix-socket',availabilityScope:'broker-control-plane',browserAccess:'not_checked'};
 } catch {
  return {configured:true,available:false,enforcement:'cooperative',reason:'broker_unavailable'};
 }
}
