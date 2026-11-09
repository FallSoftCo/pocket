import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {once} from 'node:events';
import express from 'express';
import {WebSocketServer} from 'ws';
import {Codex} from '../../server/codex.mjs';
import {registerNativeConfigurationRoutes} from '../../server/native-configuration.mjs';
import {HumanIngress} from '../../server/human-ingress.mjs';
import {NativeMcpAuth} from '../../server/native-mcp-auth.mjs';

export async function fixture(t){
 const dir=await mkdtemp(join(tmpdir(),'mcp-auth-')),socket=join(dir,'native.sock');const wire=http.createServer(),wss=new WebSocketServer({server:wire});await new Promise(r=>wire.listen(socket,r));
 const calls=[],held=[];let account='first-account',configVersion=1,early=false;
 wss.on('connection',ws=>ws.on('message',raw=>{
  const m=JSON.parse(raw);if(!m.id)return;calls.push(m);
  const reply=result=>ws.send(JSON.stringify({id:m.id,result}));
  if(m.method==='initialize')return reply({userAgent:'codex/0.167.0'});
  if(m.method==='thread/read')return reply({thread:{id:m.params.threadId,cwd:'/synthetic/project'}});
  if(m.method==='config/read')return reply({config:{version:configVersion,mcp_servers:{fixture:{url:'https://fixture.invalid/mcp',http_headers:{Authorization:'PRIVATE CONFIG'}}}}});
  if(m.method==='mcpServerStatus/list')return reply({data:[{name:'fixture',authStatus:'notLoggedIn',tools:{}}]});
  assert.equal(m.method,'mcpServer/oauth/login');assert.deepEqual(m.params,{name:'fixture',threadId:'thread-one',timeoutSecs:300});
  const complete=({loginId='login-one',url='https://auth.invalid/authorize?state=PRIVATE_URL'}={})=>{if(early)ws.send(JSON.stringify({method:'mcpServer/oauthLogin/completed',params:{name:'fixture',threadId:'thread-one',loginId,success:true}}));reply({loginId,authorizationUrl:url});};held.push(complete);
 }));
 const codex=new Codex(socket),db=new DatabaseSync(':memory:');db.exec('CREATE TABLE devices(id TEXT PRIMARY KEY,name TEXT); INSERT INTO devices VALUES(\'phone\',\'Phone\');');
 const admissions=new HumanIngress(db),app=express();app.use(express.json());app.use((req,res,next)=>{req.device={id:req.get('X-Test-Device')||'phone'};next();});app.use(admissions.middleware.bind(admissions));
 const route=fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next);
 const options={db,admissions,accountEvidence:()=>({accountId:account}),codex,route,requireId:x=>x};const native=registerNativeConfigurationRoutes(app,options);
 app.use((e,req,res,next)=>res.status(e.status||500).json({error:e.message}));const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));const base='http://127.0.0.1:'+server.address().port;
 const api=async(path,body,device='phone')=>{const r=await fetch(base+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json','X-Test-Device':device},...(body?{body:JSON.stringify(body)}:{})});return {status:r.status,data:await r.json()};};
 const detail=()=>api('/api/native/mcp-auth?threadId=thread-one&name=fixture');
 const input=view=>({section:'mcp',action:'oauthStart',name:'fixture',threadId:'thread-one',attemptId:'attempt-one',reviewToken:view.reviewToken,confirmHostAuthentication:true});
 t.after(async()=>{const disconnected=codex.ready?once(codex,'disconnected'):Promise.resolve();codex.closeTransport();await disconnected;for(const ws of wss.clients)ws.terminate();wss.close();await Promise.all([new Promise(r=>wire.close(r)),new Promise(r=>server.close(r))]);db.close();await rm(dir,{recursive:true,force:true});});
 return {codex,db,native,calls,held,detail,input,api,setAccount:a=>account=a,setConfig:()=>configVersion++,setEarly:()=>early=true,event:p=>{for(const ws of wss.clients)ws.send(JSON.stringify({method:'mcpServer/oauthLogin/completed',params:p}));},options};
}
