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
 const calls=[],held=[];let account='account-one',hold=()=>false,apps=[{id:'fixture',enabled:true}],runtime='codex/0.167.0';
 wss.on('connection',ws=>ws.on('message',raw=>{
  const m=JSON.parse(raw);if(!m.id)return;calls.push(m);
  const result=()=>{switch(m.method){
   case 'initialize':return {userAgent:runtime};
   case 'thread/read':return {thread:{id:m.params.threadId,cwd:'/synthetic'}};
   case 'skills/list':return {data:[{skills:[{name:'Fixture',path:'/synthetic/SKILL.md',enabled:true}]}]};
   case 'app/installed':return {apps};
   case 'app/list':return {data:m.params.cursor?[{id:'last',name:'Last discovered app',isEnabled:true,isAccessible:false}]:[{id:'fixture',name:'Installed catalog name',description:'Synthetic description',isEnabled:true,isAccessible:true,installUrl:'https://private.invalid/never-return',appMetadata:{secret:'never-return'}},{id:'discovered',name:'Discovery only',isEnabled:true,isAccessible:true}],nextCursor:m.params.cursor?null:'native-next-private'};
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
 const get=async(query)=>{const r=await fetch('http://127.0.0.1:'+server.address().port+'/api/native/configuration?section=apps&'+query);return {status:r.status,data:await r.json()};};
 return {codex,calls,held,native,api,get,setApps:value=>apps=value,setRuntime:value=>runtime=value,hold:fn=>hold=fn,setAccount:a=>account=a,event:method=>{for(const ws of wss.clients)ws.send(JSON.stringify({method,params:{}}));}};
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


test('Apps HTTP/socket journey keeps discovery explicit, cursor account-bound, refresh deliberate and Mac installed-only',async t=>{
 const f=await fixture(t);f.setApps([{id:'fixture',runtimeName:'Installed runtime name',enabled:false,callable:false},...Array.from({length:34},(_,i)=>({id:'app_'+i,runtimeName:'App '+i,enabled:true,callable:i===0}))]);
 const path='threadId=thread-one';let view=await f.get(path);assert.equal(view.status,200);assert.equal(view.data.appsView,'installed');assert.equal(view.data.items.length,30);assert.equal(view.data.total,35);assert.equal(view.data.items[0].callable,false);assert.equal(f.calls.some(c=>c.method==='app/list'),false);
 let next=await f.get(path+'&cursor='+view.data.nextCursor);assert.equal(next.data.items.length,5);assert.equal(next.data.nextCursor,null);assert.equal(f.calls.filter(c=>c.method==='app/installed').length,1);
 // Configuring a genuine item on page two still validates its exact installed snapshot.
 assert.equal((await f.api({...body('apps'),id:'app_33'})).status,200);assert.equal(writes(f).length,1);
 const browse=await f.get(path+'&appsView=browse');assert.equal(browse.status,200);assert.equal(browse.data.items[0].installed,true);assert.equal(browse.data.items[0].enabled,false);assert.equal(browse.data.items[0].callable,false);assert.equal(browse.data.items[1].installed,false);assert.equal(browse.data.items[1].enabled,null);assert.equal(browse.data.items[1].configuredEnabled,true);assert.equal(browse.data.items[1].callable,null);assert.equal(browse.data.items[1].configurable,false);assert.equal(browse.data.items[1].authStatus,'unverified');assert.ok(!JSON.stringify(browse.data).includes('never-return'));assert.ok(!JSON.stringify(browse.data).includes('native-next-private'));
 assert.deepEqual(f.calls.filter(c=>c.method==='app/list').at(-1).params,{threadId:'thread-one',limit:30,forceRefetch:false});
 const before=f.calls.length;assert.equal((await f.get('threadId=other&appsView=browse&cursor='+browse.data.nextCursor)).status,409);assert.equal(f.calls.length,before);
 next=await f.get(path+'&appsView=browse&cursor='+browse.data.nextCursor);assert.equal(next.data.items[0].id,'last');assert.equal(f.calls.at(-1).params.cursor,'native-next-private');
 f.setAccount('account-two');assert.equal((await f.get(path+'&appsView=browse&cursor='+browse.data.nextCursor)).status,409);f.setAccount('account-one');
 assert.equal((await f.get(path+'&appsView=browse&refresh=1&cursor='+browse.data.nextCursor)).status,400);
 await f.get(path+'&appsView=browse&refresh=1');assert.equal(f.calls.filter(c=>c.method==='app/installed').at(-1).params.forceRefresh,true);assert.equal(f.calls.filter(c=>c.method==='app/list').at(-1).params.forceRefetch,true);
 f.hold(m=>m.method==='app/list');const held=f.get(path+'&appsView=browse');await until(()=>f.held.length===1);f.setAccount('account-two');f.held[0]();assert.equal((await held).status,409);f.hold(()=>false);f.setAccount('account-one');
 const epoch=f.native.configurationEpoch;f.event('account/updated');await until(()=>f.native.configurationEpoch!==epoch);assert.equal((await f.get(path+'&appsView=browse&cursor='+browse.data.nextCursor)).status,409);
 f.hold(m=>m.method==='app/list');const lost=f.get(path+'&appsView=browse');await until(()=>f.held.length===2);f.codex.closeTransport();const lostView=await lost;assert.deepEqual(lostView.data.items,[]);assert.notEqual(lostView.data.state,'available');f.hold(()=>false);await f.codex.connect();
 f.setRuntime('codex_pocket/0.163.0 (Mac OS 26.6.0; arm64)');f.codex.closeTransport();await until(()=>!f.codex.ready);await f.codex.connect();const mac=await f.get(path);assert.equal(mac.status,200);assert.equal(mac.data.browseAvailable,false);assert.equal(mac.data.refreshAvailable,false);assert.ok(mac.data.items.every(a=>!a.configurable));
 const macCalls=f.calls.length;assert.equal((await f.get(path+'&appsView=browse')).status,409);assert.equal((await f.get(path+'&refresh=1')).status,409);assert.equal(f.calls.length,macCalls);assert.equal(writes(f).length,1);assert.ok(!f.calls.some(c=>['turn/start','thread/resume','mcpServer/oauth/login'].includes(c.method)));
});
