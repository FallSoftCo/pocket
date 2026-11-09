import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {once} from 'node:events';
import express from 'express';
import {WebSocketServer} from 'ws';
import {Codex} from '../server/codex.mjs';
import {registerNativeConfigurationRoutes} from '../server/native-configuration.mjs';
import {HumanIngress} from '../server/human-ingress.mjs';
async function until(p){for(let n=0;n<200&&!p();n++)await new Promise(r=>setTimeout(r,5));assert.ok(p());}
async function fixture(t){
 const dir=await mkdtemp(join(tmpdir(),'config-boundary-')),wire=http.createServer(),wss=new WebSocketServer({server:wire}),socket=join(dir,'native.sock');await new Promise(r=>wire.listen(socket,r));
 const calls=[],held=[];let account='account-one',hold=()=>false;
 wss.on('connection',ws=>ws.on('message',raw=>{
  const m=JSON.parse(raw);if(!m.id)return;calls.push(m);
  const result=()=>{switch(m.method){
   case 'initialize':return {userAgent:'codex/0.167.0'};
   case 'thread/read':return {thread:{id:m.params.threadId,cwd:'/synthetic'}};
   case 'skills/list':return {data:[{skills:[{name:'Fixture',path:'/synthetic/SKILL.md',enabled:true}]}]};
   case 'app/installed':return {apps:[{id:'fixture',enabled:true}]};
   case 'config/read':return {config:{mcp_servers:{fixture:{enabled:true}}},layers:[{name:{type:'user'},version:'same-version'}]};
   case 'mcpServerStatus/list':return {data:[{name:'fixture',tools:{}}]};
   case 'config/value/write':case 'skills/config/write':return {};
   default:assert.fail('Unexpected native method '+m.method);
  }};
  const reply=()=>ws.send(JSON.stringify({id:m.id,result:result()}));if(hold(m))held.push(reply);else reply();
 }));
 const codex=new Codex(socket),db=new DatabaseSync(':memory:');db.exec("CREATE TABLE devices(id TEXT PRIMARY KEY,name TEXT);INSERT INTO devices VALUES('phone','Phone');");
 const ingress=new HumanIngress(db),app=express();app.use(express.json());app.use((req,res,next)=>{req.device={id:'phone'};next();});app.use(ingress.middleware.bind(ingress));
 const native=registerNativeConfigurationRoutes(app,{db,codex,admissions:ingress,accountEvidence:()=>account?{accountId:account}:null,requireId:x=>x,route:fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next)});
 app.use((e,req,res,next)=>res.status(e.status||500).json({error:e.message}));const server=app.listen(0,'127.0.0.1');await once(server,'listening');
 const api=async(body)=>{const r=await fetch('http://127.0.0.1:'+server.address().port+'/api/native/configuration',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});return {status:r.status,data:await r.json()};};
 t.after(async()=>{const done=codex.ready?once(codex,'disconnected'):Promise.resolve();codex.closeTransport();await done;for(const ws of wss.clients)ws.terminate();wss.close();await Promise.all([new Promise(r=>wire.close(r)),new Promise(r=>server.close(r))]);db.close();await rm(dir,{recursive:true,force:true});});
 return {codex,calls,held,native,api,hold:fn=>hold=fn,setAccount:a=>account=a,event:method=>{for(const ws of wss.clients)ws.send(JSON.stringify({method,params:{}}));}};
}
const body=section=>({section,threadId:'thread-one',id:section==='skills'?'/synthetic/SKILL.md':'fixture',enabled:false,expectedVersion:'same-version'});
const writes=f=>f.calls.filter(c=>['skills/config/write','config/value/write'].includes(c.method));

test('held native preflight cannot carry skill/app/MCP changes across account epochs or missing account evidence',async t=>{
 const f=await fixture(t);
 for(const [section,method,change] of [['skills','skills/list','identity'],['apps','config/read','epoch'],['mcp','mcpServerStatus/list','missing']]){
  f.hold(m=>m.method===method&&(method!=='config/read'||m.params.includeLayers));const before=f.held.length;
  const pending=f.api(body(section));await until(()=>f.held.length===before+1);
  if(change==='identity')f.setAccount('account-two');
  if(change==='missing')f.setAccount(null);
  if(change==='epoch'){const epoch=f.native.configurationEpoch;f.event('account/updated');await until(()=>f.native.configurationEpoch!==epoch);}
  f.held[before]();const denied=await pending;assert.equal(denied.status,409);assert.match(denied.data.error,/account or connection/);assert.equal(writes(f).length,0);
  f.setAccount('account-one');f.hold(()=>false);
 }
 const accepted=await f.api(body('skills'));assert.equal(accepted.status,200);assert.equal(writes(f).length,1);
});

test('replacement transport refuses held preflight; original dispatched-write loss is surfaced without replay',async t=>{
 const f=await fixture(t);f.hold(m=>m.method==='thread/read');let pending=f.api(body('apps'));await until(()=>f.held.length===1);
 f.codex.closeTransport();assert.notEqual((await pending).status,200);await f.codex.connect();assert.equal(writes(f).length,0);
 f.hold(()=>false);assert.equal((await f.api(body('apps'))).status,200);assert.equal(writes(f).length,1);
 f.hold(m=>m.method==='skills/config/write');pending=f.api(body('skills'));await until(()=>writes(f).length===2&&f.held.length===2);
 f.codex.closeTransport();assert.notEqual((await pending).status,200);await f.codex.connect();
 await f.native.inspect({threadId:'thread-one',section:'skills'});assert.equal(writes(f).length,2,'readback and reconnect must never replay the uncertain original write');
});
