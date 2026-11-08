import {pluginState,pluginReply} from './helpers/native-plugin-fixture.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {WebSocketServer} from 'ws';
import {pairedTestClient} from './paired-human.mjs';
const waitFor=async fn=>{for(let i=0;i<150;i++){if(await fn())return;await new Promise(r=>setTimeout(r,30));}throw Error('Fixture did not start');};

for(const runtime of ['0.167.0','mac-0.163.0'])test(`paired HTTP native settings preserve initialization identity and ${runtime==='0.167.0'?'dispatch qualified writes once':'reject every unqualified Mac mutation'}`,async t=>{
 const dir=await mkdtemp(join(tmpdir(),'nextcomp-native-config-')),socket=join(dir,'codex.sock'),calls=[],plugins=pluginState();
 const server=http.createServer(),wss=new WebSocketServer({server});await new Promise(r=>server.listen(socket,r));
 wss.on('connection',ws=>ws.on('message',async raw=>{
  const m=JSON.parse(raw);if(m.id===undefined)return;calls.push(m);let result={};
  if(m.method==='initialize')result={userAgent:runtime==='0.167.0'?'codex_cli_rs/0.167.0 (fixture)':'codex_pocket/0.163.0 (Mac OS 26.6.0; arm64) unknown (nextcomp_compatibility_readonly; 1)',platformOs:'linux',platformFamily:'unix',codexHome:'/fake-private-home'};
  if(m.method==='thread/read')result={thread:{id:m.params.threadId,cwd:'/fake/project',status:{type:'idle'},turns:[]}};
  if(m.method==='skills/list')result={data:[{skills:[{name:'fixture-skill',path:'/fake/skills/fixture/SKILL.md',enabled:true,scope:'user',description:'Synthetic fixture only'}],errors:[]}]};
  if(m.method==='model/list')result={data:[{model:'fixture-model',displayName:'Fixture model',defaultReasoningEffort:'medium',supportedReasoningEfforts:[{reasoningEffort:'medium'}],serviceTiers:[{id:'priority',name:'Priority',description:'Fixture tier'}]}]};
  if(m.method.startsWith('plugin/')){try{const reply=await pluginReply(plugins,m.method,m.params);if(reply){if(reply.drop){ws.terminate();return;}return ws.send(JSON.stringify({id:m.id,...reply}));}}catch(error){ws.send(JSON.stringify({id:m.id,error:{code:-32603,message:error.message}}));return;}}
  ws.send(JSON.stringify({id:m.id,result}));
 }));
 const port=32000+Math.floor(Math.random()*2000),base=`http://127.0.0.1:${port}`;
 const child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});
 t.after(async()=>{if(child.exitCode===null)await new Promise(r=>{child.once('exit',r);child.kill();});for(const ws of wss.clients)ws.terminate();await new Promise(r=>wss.close(r));await new Promise(r=>server.close(r));await rm(dir,{recursive:true,force:true});});
 await waitFor(async()=>{try{return (await fetch(base+'/health')).ok;}catch{return false;}});
 const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json'),'utf8')),api=await pairedTestClient(base,adminToken);
 const caps=await api('/api/native/capabilities?threadId=fixture-thread');assert.equal(caps.status,200);assert.equal(caps.data.runtime.version,runtime==='0.167.0'?'0.167.0':'0.163.0');assert.equal(caps.data.observed.state,'unavailable');assert.equal(caps.data.features.goals.state,'unsupported');assert.ok(!JSON.stringify(caps.data).includes('/fake-private-home'));
 const view=await api('/api/native/configuration?threadId=fixture-thread&section=skills');assert.equal(view.status,200);assert.equal(view.data.items[0].name,'fixture-skill');
 const body={threadId:'fixture-thread',section:'skills',id:'/fake/skills/fixture/SKILL.md',enabled:false};
 const owner=await fetch(base+'/api/native/configuration',{method:'POST',headers:{Authorization:'Bearer '+adminToken,'Content-Type':'application/json'},body:JSON.stringify(body)});assert.equal(owner.status,403);
 if(runtime==='mac-0.163.0'){
  assert.equal(caps.data.features.skills.readOnly,true);assert.equal(caps.data.features.skills.mutation.state,'unavailable');
  for(const section of ['settings','skills','mcp','apps','plugins','hooks','agents']){
   const response=await api('/api/native/configuration?threadId=fixture-thread&section='+section);
   assert.equal(response.status,200,section);assert.equal(response.data.state,'available',section);assert.equal(response.data.readOnly,true);assert.match(response.data.notice,/Configuration changes remain unavailable/);
   assert.ok(response.data.items.every(item=>item.configurable!==true));
  }
  const before=calls.length;
  for(const section of ['skills','apps','mcp']){
   const response=await api('/api/native/configuration',{...body,section,expectedVersion:'previously-observed-version'});
   assert.equal(response.status,409);assert.match(response.data.error,/remain read-only/);
  }
  assert.equal((await api('/api/native/plugins/detail?key='+'a'.repeat(64))).status,409);
  assert.equal((await api('/api/native/configuration',{section:'plugins',key:'a'.repeat(64),reviewToken:'b'.repeat(64),action:'install',attemptId:'mac-attempt-01',confirmEffects:true})).status,409);
  assert.equal(calls.length,before);assert.ok(!calls.some(c=>/write|thread\/(goal|resume)|turn\/start/.test(c.method)));
  return;
 }
 assert.equal((await api('/api/native/configuration',body)).status,200);
 assert.deepEqual(calls.filter(c=>c.method==='skills/config/write').map(c=>c.params),[{path:'/fake/skills/fixture/SKILL.md',enabled:false}]);
 const catalog=await api('/api/native/configuration?section=plugins&threadId=fixture-thread');assert.equal(catalog.status,200);const key=catalog.data.items[0].key;assert.equal(typeof key,'string');
 const detail=()=>api('/api/native/plugins/detail?threadId=fixture-thread&key='+key);
 let reviewed=await detail();assert.equal(reviewed.status,200);assert.equal(reviewed.data.canInstall,true);assert.equal(reviewed.data.detail.mustShowInstallationInterstitial,true);assert.match(reviewed.data.effects,/browser on the host/);
 const operation=(action,id,view=reviewed.data)=>({section:'plugins',threadId:'fixture-thread',key,action,attemptId:id,reviewToken:view.reviewToken,confirmEffects:true,confirmInterstitial:true});
 const forbidden=await fetch(base+'/api/native/configuration',{method:'POST',headers:{Authorization:'Bearer '+adminToken,'Content-Type':'application/json'},body:JSON.stringify(operation('install','owner-install-01'))});assert.equal(forbidden.status,403);
 assert.equal((await api('/api/native/configuration',{...operation('install','missing-interstitial'),confirmInterstitial:false})).status,409);
 plugins.version='v2';assert.equal((await api('/api/native/configuration',operation('install','stale-review-01'))).status,409);reviewed=await detail();
 plugins.availability='DISABLED_BY_ADMIN';let disabled=await detail();assert.equal(disabled.data.canInstall,false);assert.equal((await api('/api/native/configuration',operation('install','disabled-install',disabled.data))).status,409);plugins.availability='AVAILABLE';
 plugins.installPolicy='INSTALLED_BY_DEFAULT';assert.equal((await detail()).data.canInstall,true);plugins.installPolicy='AVAILABLE';
 plugins.wrongIdentity=true;assert.equal((await detail()).status,409);plugins.wrongIdentity=false;
 assert.equal(calls.filter(c=>c.method==='plugin/install').length,0);
 reviewed=await detail();const install=operation('install','original-attempt');
 let receipt=await api('/api/native/configuration',install);assert.equal(receipt.status,200);assert.equal(receipt.data.state,'acknowledged');assert.equal(receipt.data.readiness,'unverified');assert.equal(receipt.data.appsNeedingAuth[0].name,'Fixture app');assert.ok(!JSON.stringify(receipt.data).includes('installUrl'));
 await api('/api/native/configuration',install);assert.equal(calls.filter(c=>c.method==='plugin/install').length,1);
 plugins.installedOnly=true;
 reviewed=await detail();assert.equal(reviewed.data.detail.installed,true);assert.equal(reviewed.data.canUninstall,true);plugins.installedOnly=false;
 assert.equal((await api('/api/native/configuration',operation('uninstall','remove-attempt-01'))).data.state,'acknowledged');
 reviewed=await detail();plugins.failInstall=true;const partial=operation('install','partial-effect-01');receipt=await api('/api/native/configuration',partial);assert.equal(receipt.data.state,'unknown');assert.ok(!JSON.stringify(receipt).includes('PRIVATE'));await api('/api/native/configuration',partial);
 reviewed=await detail();assert.equal(reviewed.data.detail.installed,true);assert.equal(reviewed.data.blocked,true);assert.equal((await api('/api/native/configuration',operation('uninstall','blocked-after-unknown'))).status,409);
 const anotherSession=await api('/api/native/plugins/detail?threadId=another-session&key='+key);assert.equal(anotherSession.data.blocked,true,'unknown mutations block the original plugin across sessions');
 plugins.missing=true;assert.equal((await detail()).status,409);const retained=await api('/api/native/plugins/receipts?key='+key);assert.equal(retained.data.receipts[0].state,'unknown');assert.equal(retained.data.receipts[0].attemptId,partial.attemptId);plugins.missing=false;
 reviewed=await detail();assert.equal((await api('/api/native/configuration',operation('acknowledge',partial.attemptId))).data.state,'unknown');
 reviewed=await detail();assert.equal(reviewed.data.canUninstall,true);await api('/api/native/configuration',operation('uninstall','remove-after-review'));
 reviewed=await detail();plugins.dropInstall=true;const lost=operation('install','lost-reply-01');receipt=await api('/api/native/configuration',lost);assert.equal(receipt.data.state,'unknown');await api('/api/native/configuration',lost);
 assert.equal(calls.filter(c=>c.method==='plugin/install').length,3);assert.equal(calls.filter(c=>c.method==='plugin/uninstall').length,2);
 plugins.remoteId=null;const missingIdentity=await api('/api/native/configuration?section=plugins');assert.equal(missingIdentity.data.items[0].inspectable,false);assert.equal((await detail()).status,409);plugins.remoteId='plugins_fixture';
 plugins.local=true;plugins.installed=false;const localKey=(await api('/api/native/configuration?section=plugins&threadId=fixture-thread')).data.items[0].key;
 const localDetail=()=>api('/api/native/plugins/detail?threadId=fixture-thread&key='+localKey);
 let local=await localDetail();assert.equal(local.data.canInstall,true);assert.equal((await api('/api/native/configuration',{...operation('install','local-original-attempt',local.data),key:localKey})).data.state,'acknowledged');
 assert.deepEqual(calls.filter(c=>c.method==='plugin/install').at(-1).params,{marketplacePath:'/synthetic/marketplace.json',pluginName:'fixture',installAttemptId:'local-original-attempt'});
 local=await localDetail();assert.equal((await api('/api/native/configuration',{...operation('uninstall','local-remove-attempt',local.data),key:localKey})).data.state,'acknowledged');
 assert.deepEqual(calls.filter(c=>c.method==='plugin/uninstall').at(-1).params,{pluginId:'fixture@market'});

 assert.equal((await api('/api/models')).data.models[0].serviceTiers[0].id,'priority');
 assert.equal((await api('/api/threads/fixture-thread/settings',{model:'fixture-model',effort:'medium',mode:'default',serviceTier:'priority'})).status,200);
 assert.equal((await api('/api/native/capabilities?threadId=fixture-thread')).data.requested.turnSettings.serviceTier,'priority');
 assert.ok(!calls.some(c=>/thread\/(goal|resume)|turn\/start/.test(c.method)));
});
