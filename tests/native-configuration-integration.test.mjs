import {pluginState,pluginReply,pluginAccountReply} from './helpers/native-plugin-fixture.mjs';
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

for(const runtime of ['0.167.0','mac-0.163.0'])test(`paired HTTP native settings preserve initialization identity and ${runtime==='0.167.0'?'dispatch qualified writes once':'reject every unqualified Mac mutation'}`,{timeout:70000},async t=>{
 const dir=await mkdtemp(join(tmpdir(),'nextcomp-native-config-')),socket=join(dir,'codex.sock'),calls=[],plugins=pluginState();
 let accountId='synthetic-account',holdAccount=null,cwd='/fake/project';
 const server=http.createServer(),wss=new WebSocketServer({server});await new Promise(r=>server.listen(socket,r));
 wss.on('connection',ws=>ws.on('message',async raw=>{
  const m=JSON.parse(raw);if(m.id===undefined)return;calls.push(m);let result={};
  if(m.method==='account/rateLimits/read'){result=pluginAccountReply(m.params,accountId);if(holdAccount){const hold=holdAccount;holdAccount=null;hold.started();await hold.wait;}}
  if(m.method==='initialize')result={userAgent:runtime==='0.167.0'?'codex_cli_rs/0.167.0 (fixture)':'codex_pocket/0.163.0 (Mac OS 26.6.0; arm64) unknown (nextcomp_compatibility_readonly; 1)',platformOs:'linux',platformFamily:'unix',codexHome:'/fake-private-home'};
  if(m.method==='thread/read')result={thread:{id:m.params.threadId,cwd,status:{type:'idle'},turns:[]}};
  if(m.method==='skills/list')result={data:[{skills:[{name:'fixture-skill',path:'/fake/skills/fixture/SKILL.md',enabled:true,scope:'user',description:'Synthetic fixture only'}],errors:[]}]};
  if(m.method==='app/installed')result={apps:[]};
  if(m.method==='model/list')result={data:[{model:'fixture-model',displayName:'Fixture model',defaultReasoningEffort:'medium',supportedReasoningEfforts:[{reasoningEffort:'medium'}],serviceTiers:[{id:'priority',name:'Priority',description:'Fixture tier'}]}]};
  if(m.method.startsWith('plugin/')){try{const reply=await pluginReply(plugins,m.method,m.params);if(reply){if(reply.drop){ws.terminate();return;}ws.send(JSON.stringify({id:m.id,...reply}));plugins.sent?.(m);return;}}catch(error){ws.send(JSON.stringify({id:m.id,error:{code:-32603,message:error.message}}));return;}}
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
  plugins.catalogSize=10074;plugins.installedBulk=65;
  assert.equal(caps.data.features.skills.readOnly,true);assert.equal(caps.data.features.skills.mutation.state,'unavailable');
  for(const section of ['settings','skills','mcp','apps','plugins','hooks','agents']){
   const response=await api('/api/native/configuration?threadId=fixture-thread&section='+section);
   assert.equal(response.status,200,section);assert.equal(response.data.state,'available',section);assert.equal(response.data.readOnly,true);assert.match(response.data.notice,/Configuration changes remain unavailable/);
   assert.ok(response.data.items.every(item=>item.configurable!==true));
  }
  const macPath='/api/native/configuration?section=plugins&threadId=fixture-thread';
  const macPage=await api(macPath);assert.equal(macPage.data.total,65);assert.equal(macPage.data.items.length,30);assert.equal(macPage.data.browseAvailable,false);assert.ok(macPage.data.items.every(p=>!p.inspectable));
  const macReads=calls.filter(c=>c.method==='plugin/installed').length;
  const macNext=await api(macPath+'&cursor='+encodeURIComponent(macPage.data.nextCursor));assert.equal(macNext.data.items.length,30);assert.equal(calls.filter(c=>c.method==='plugin/installed').length,macReads);
  assert.equal((await api(macPath+'&pluginView=browse')).status,409);assert.equal(calls.some(c=>c.method==='plugin/list'),false);
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
 plugins.catalogSize=10074;
 const listedBefore=calls.filter(c=>c.method==='plugin/list').length;
 const installed=await api('/api/native/configuration?section=plugins&threadId=fixture-thread');assert.equal(installed.data.pluginView,'installed');assert.equal(installed.data.total,0);assert.equal(calls.filter(c=>c.method==='plugin/list').length,listedBefore,'default installed view never fetches full discovery');
 const browsePath='/api/native/configuration?section=plugins&threadId=fixture-thread&pluginView=browse';
 const firstPage=await api(browsePath);assert.equal(firstPage.data.total,10074);assert.equal(firstPage.data.items.length,30);assert.ok(JSON.stringify(firstPage.data).length<40000);
 const listCount=calls.filter(c=>c.method==='plugin/list').length;
 const nextPage=await api(browsePath+'&cursor='+encodeURIComponent(firstPage.data.nextCursor));assert.equal(nextPage.data.items.length,30);assert.equal(nextPage.data.items[0].name,'Catalog plugin 00030');assert.equal(nextPage.data.offset,30);assert.notEqual(firstPage.data.items[0].key,nextPage.data.items[0].key);
 const selected=await api('/api/native/plugins/detail?threadId=fixture-thread&key='+nextPage.data.items[0].key);assert.equal(selected.data.detail.id,nextPage.data.items[0].id);assert.equal(calls.filter(c=>c.method==='plugin/read').at(-1).params.pluginName,'plugins_bulk_00030');
 const afterDetail=calls.filter(c=>c.method==='plugin/list').length;
 const search=await api(browsePath+'&query=FIXTURE');assert.equal(search.data.items.length,1);assert.equal(search.data.items[0].name,'fixture');assert.equal(search.data.query,'fixture');assert.equal(calls.filter(c=>c.method==='plugin/list').length,afterDetail,'search reuses discovery snapshot; detail alone uses fresh policy');
 const previous=await api(browsePath+'&cursor='+encodeURIComponent(nextPage.data.previousCursor));assert.equal(previous.data.items[0].key,firstPage.data.items[0].key);assert.equal(calls.filter(c=>c.method==='plugin/list').length,listCount+1);
 for(const suffix of ['&query=other','&pluginView=installed','&query=x&query=y'])assert.equal((await api(browsePath+'&cursor='+encodeURIComponent(firstPage.data.nextCursor)+suffix)).status>=400,true);
 assert.equal((await api(browsePath.replace('fixture-thread','another-session')+'&cursor='+encodeURIComponent(firstPage.data.nextCursor))).status,409);
 cwd='/fake/changed';assert.equal((await api(browsePath+'&cursor='+encodeURIComponent(firstPage.data.nextCursor))).status,409);cwd='/fake/project';
 assert.equal((await api(browsePath+'&cursor='+encodeURIComponent(firstPage.data.nextCursor)+'bad')).status,409);
 const refreshed=await api(browsePath+'&refresh=1');assert.equal((await api(browsePath+'&cursor='+encodeURIComponent(firstPage.data.nextCursor))).status,409,'explicit reload retires previous snapshot');
 // Count/byte bounds reject whole oversized snapshots; they never truncate.
 plugins.catalogSize=20001;
 const oversized=await api(browsePath.replace('fixture-thread','oversized-context'));assert.equal(oversized.status,503);assert.match(oversized.data.error,/bounded snapshot capacity/);assert.equal(oversized.data.items,undefined);
 plugins.catalogSize=31;
 const eviction=await api(browsePath.replace('fixture-thread','eviction-original'));
 for(let index=0;index<4;index++)assert.equal((await api(browsePath.replace('fixture-thread','eviction-'+index))).status,200);
 assert.equal((await api(browsePath.replace('fixture-thread','eviction-original')+'&cursor='+encodeURIComponent(eviction.data.nextCursor))).status,409,'bounded snapshot count evicts oldest instead of retaining every search source');
 plugins.catalogSize=1;
 const catalog=await api('/api/native/configuration?section=plugins&pluginView=browse&query=fixture&refresh=1&threadId=fixture-thread');assert.equal(catalog.status,200);const key=catalog.data.items[0].key;assert.equal(typeof key,'string');
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
 // Reconnection invalidates confirmations even with the same native version,
 // account and byte-identical plugin details.
 plugins.catalogSize=31;const connectionCursor=(await api(browsePath+'&refresh=1')).data.nextCursor;plugins.catalogSize=1;
 assert.equal(typeof connectionCursor,'string');
 const oldTransport=await detail();
 assert.equal((await api(browsePath+'&cursor='+encodeURIComponent(connectionCursor))).status,200,'exact cursor is still live immediately before disconnect');
 for(const ws of wss.clients)ws.terminate();
 await waitFor(async()=>{reviewed=await detail();return reviewed.status===200&&reviewed.data.canInstall;});
 assert.notEqual(reviewed.data.reviewToken,oldTransport.data.reviewToken);assert.equal((await api(browsePath+'&cursor='+encodeURIComponent(connectionCursor))).status,409,'connection changes invalidate local snapshot cursors');
 assert.equal((await api('/api/native/configuration',operation('install','old-transport-attempt',oldTransport.data))).status,409);
 // Original token cannot cross account changes even with byte-identical plugin data.
 plugins.catalogSize=31;const accountCursor=(await api(browsePath+'&refresh=1')).data.nextCursor;plugins.catalogSize=1;
 reviewed=await detail();const priorAccount=reviewed.data;let accountStarted,accountRelease,identityStarted,identityRelease;
 const identityReading=new Promise(r=>identityStarted=r);plugins.holdRead={started:identityStarted,wait:new Promise(r=>identityRelease=r)};
 const staleIdentityRead=detail();await identityReading;
 const accountReading=new Promise(r=>accountStarted=r);holdAccount={started:accountStarted,wait:new Promise(r=>accountRelease=r)};accountId='second-synthetic-account';
 for(const ws of wss.clients)ws.send(JSON.stringify({method:'account/updated',params:{authMode:'chatgpt',planType:'pro'}}));
 await accountReading;const refreshing=await detail();assert.equal(refreshing.data.canInstall,false);assert.match(refreshing.data.reason,/account usage refresh/);
 assert.equal((await api('/api/native/configuration',operation('install','old-account-attempt',priorAccount))).status,409);accountRelease();
 await waitFor(async()=>{reviewed=await detail();return reviewed.data.canInstall;});assert.notEqual(reviewed.data.reviewToken,priorAccount.reviewToken);identityRelease();assert.equal((await staleIdentityRead).status,409,'an identity boundary invalidates an in-flight detail snapshot');assert.equal((await api(browsePath+'&cursor='+encodeURIComponent(accountCursor))).status,409,'account boundary invalidates catalog pages');
 assert.ok(!JSON.stringify(reviewed.data).includes(accountId));
 plugins.catalogSize=31;const mutationCursor=(await api(browsePath+'&refresh=1')).data.nextCursor;plugins.catalogSize=1;
 const install=operation('install','original-attempt');
 // B captures old details before A installs; release only after A's native ack.
 let readStarted,readRelease;const reading=new Promise(r=>readStarted=r);plugins.holdRead={started:readStarted,wait:new Promise(r=>readRelease=r)};
 const stalePreflight=api('/api/native/configuration',operation('install','delayed-second-attempt'));await reading;
 let receipt=await api('/api/native/configuration',install);assert.equal(receipt.status,200);assert.equal(receipt.data.state,'acknowledged');assert.equal(receipt.data.readiness,'unverified');assert.equal(receipt.data.appsNeedingAuth[0].name,'Fixture app');assert.ok(!JSON.stringify(receipt.data).includes('installUrl'));
 assert.equal((await api(browsePath+'&cursor='+encodeURIComponent(mutationCursor))).status,409,'mutation invalidates presentation snapshots without caching fresh policy');
 readRelease();assert.equal((await stalePreflight).status,409,'a completed operation invalidates already-started preflight snapshots');
 await api('/api/native/configuration',install);assert.equal(calls.filter(c=>c.method==='plugin/install').length,1);
 plugins.installedOnly=true;
 reviewed=await detail();assert.equal(reviewed.data.detail.installed,true);assert.equal(reviewed.data.canUninstall,true);plugins.installedOnly=false;
 assert.equal((await api('/api/native/configuration',operation('uninstall','remove-attempt-01'))).data.state,'acknowledged');
 reviewed=await detail();plugins.failInstall=true;const partial=operation('install','partial-effect-01');receipt=await api('/api/native/configuration',partial);assert.equal(receipt.data.state,'unknown');assert.equal(receipt.data.nativeTerminal,true);assert.ok(!JSON.stringify(receipt).includes('PRIVATE'));await api('/api/native/configuration',partial);
 reviewed=await detail();assert.equal(reviewed.data.receipts[0].canAcknowledge,true);assert.equal(reviewed.data.detail.installed,true);assert.equal(reviewed.data.blocked,true);assert.equal((await api('/api/native/configuration',operation('uninstall','blocked-after-unknown'))).status,409);
 const anotherSession=await api('/api/native/plugins/detail?threadId=another-session&key='+key);assert.equal(anotherSession.data.blocked,true,'unknown mutations block the original plugin across sessions');
 plugins.missing=true;assert.equal((await detail()).status,409);const retained=await api('/api/native/plugins/receipts?key='+key);assert.equal(retained.data.receipts[0].state,'unknown');assert.equal(retained.data.receipts[0].attemptId,partial.attemptId);plugins.missing=false;
 reviewed=await detail();assert.equal((await api('/api/native/configuration',operation('acknowledge',partial.attemptId))).data.state,'unknown');
 reviewed=await detail();assert.equal(reviewed.data.canUninstall,true);await api('/api/native/configuration',operation('uninstall','remove-after-review'));
 reviewed=await detail();plugins.dropInstall=true;const lost=operation('install','lost-reply-01');receipt=await api('/api/native/configuration',lost);assert.equal(receipt.data.state,'unknown');assert.equal(receipt.data.nativeTerminal,false);await api('/api/native/configuration',lost);
 await waitFor(async()=>{reviewed=await detail();return reviewed.status===200&&!reviewed.data.reason.includes('account usage refresh');});assert.equal(reviewed.data.receipts[0].canAcknowledge,false);assert.equal(reviewed.data.canUninstall,false);assert.equal((await api('/api/native/configuration',operation('acknowledge',lost.attemptId))).status,409);
 assert.ok(!JSON.stringify(reviewed.data).includes('synthetic-account'));
 assert.equal(calls.filter(c=>c.method==='plugin/install').length,3);assert.equal(calls.filter(c=>c.method==='plugin/uninstall').length,2);
 plugins.remoteId=null;const missingIdentity=await api('/api/native/configuration?section=plugins&pluginView=browse&query=fixture&refresh=1');assert.equal(missingIdentity.data.items[0].inspectable,false);assert.equal((await detail()).status,409);plugins.remoteId='plugins_fixture';
 plugins.local=true;plugins.installed=false;const localKey=(await api('/api/native/configuration?section=plugins&pluginView=browse&query=fixture&refresh=1&threadId=fixture-thread')).data.items[0].key;
 const localDetail=()=>api('/api/native/plugins/detail?threadId=fixture-thread&key='+localKey);
 let local=await localDetail();assert.equal(local.data.canInstall,true);assert.equal((await api('/api/native/configuration',{...operation('install','local-original-attempt',local.data),key:localKey})).data.state,'acknowledged');
 assert.deepEqual(calls.filter(c=>c.method==='plugin/install').at(-1).params,{marketplacePath:'/synthetic/marketplace.json',pluginName:'fixture',installAttemptId:'local-original-attempt'});
 local=await localDetail();assert.equal((await api('/api/native/configuration',{...operation('uninstall','local-remove-attempt',local.data),key:localKey})).data.state,'acknowledged');
 assert.deepEqual(calls.filter(c=>c.method==='plugin/uninstall').at(-1).params,{pluginId:'fixture@market'});
 // Native terminal error receipts stay with their original observed identity;
 // identical inventory in another account cannot acknowledge them away.
 local=await localDetail();plugins.failInstall=true;
 const localPartial={...operation('install','local-partial-account',local.data),key:localKey};
 assert.equal((await api('/api/native/configuration',localPartial)).data.nativeTerminal,true);
 local=await localDetail();assert.equal(local.data.receipts[0].canAcknowledge,true);
 accountId=null;for(const ws of wss.clients)ws.send(JSON.stringify({method:'account/updated',params:{authMode:null,planType:null}}));
 await waitFor(async()=>{local=await localDetail();return local.status===200&&!local.data.canInstall&&!local.data.receipts[0].canAcknowledge;});
 assert.match(local.data.reason,/account usage refresh/);
 accountId='third-synthetic-account';for(const ws of wss.clients)ws.send(JSON.stringify({method:'account/updated',params:{authMode:'chatgpt',planType:'pro'}}));
 await waitFor(async()=>{local=await localDetail();return local.status===200&&!local.data.reason.includes('account usage refresh');});
 assert.equal(local.data.receipts[0].canAcknowledge,false);
 assert.equal((await api('/api/native/configuration',{...operation('acknowledge',localPartial.attemptId,local.data),key:localKey})).status,409);
 // Real adapter deadlines, original native IDs and durable readback. Two
 // bounded 25 s waits exercise eventual success and eventual RPC failure.
 for(const lateError of [false,true]){
  plugins.local=false;plugins.remoteId=lateError?'plugins_late_error':'plugins_late_success';plugins.installed=false;
  const remoteId=plugins.remoteId,timeoutKey=(await api('/api/native/configuration?section=plugins&pluginView=browse&query=fixture&refresh=1')).data.items[0].key;
  const timeoutDetail=()=>api('/api/native/plugins/detail?threadId=fixture-thread&key='+timeoutKey);
  const timeoutView=await timeoutDetail();let installStarted,installRelease,installSent;
  const installing=new Promise(r=>installStarted=r),sent=new Promise(r=>installSent=r),attemptId=lateError?'late-native-error-attempt':'late-native-success-attempt';
  plugins.holdInstall={started:installStarted,wait:new Promise(r=>installRelease=r)};plugins.failInstall=lateError;
  let replyId;plugins.sent=m=>{if(m.method==='plugin/install'&&m.params.installAttemptId===attemptId){replyId=m.id;installSent();}};
  const timeoutBody={...operation('install',attemptId,timeoutView.data),key:timeoutKey};
  const timedOutRequest=api('/api/native/configuration',timeoutBody);await installing;
  const during=await timeoutDetail();assert.equal(during.data.canUninstall,false);assert.equal(during.data.receipts[0].state,'dispatching');
  // Only one live plugin operation is retained globally, including another plugin.
  plugins.remoteId='plugins_other_while_pending';plugins.installed=false;
  const otherKey=(await api('/api/native/configuration?section=plugins&pluginView=browse&query=fixture&refresh=1')).data.items[0].key;
  const other=await api('/api/native/plugins/detail?threadId=fixture-thread&key='+otherKey);
  assert.equal(other.data.canInstall,false);assert.match(other.data.reason,/original native plugin reply/);
  assert.equal((await api('/api/native/configuration',{...operation('install','global-capacity-'+attemptId,other.data),key:otherKey})).status,409);
  plugins.remoteId=remoteId;plugins.installed=true;
  // Ordinary reads retain their existing deadline and are not observed later.
  let readRelease,readStarted,ordinaryRead;
  if(!lateError){const started=new Promise(r=>readStarted=r);plugins.holdRead={started:readStarted,wait:new Promise(r=>readRelease=r)};ordinaryRead=timeoutDetail();await started;}
  const expired=await timedOutRequest;assert.equal(expired.data.state,'dispatching');assert.equal(expired.data.nativeTerminal,false);
  if(ordinaryRead){assert.equal((await ordinaryRead).status,503);readRelease();}
  const pending=await timeoutDetail();assert.equal(pending.data.receipts[0].state,'dispatching');assert.equal(pending.data.canUninstall,false);
  await api('/api/native/configuration',timeoutBody);
  assert.equal((await api('/api/native/configuration',{...timeoutBody,action:'acknowledge',reviewToken:pending.data.reviewToken})).status,409);
  installRelease();await sent;
  const after=await timeoutDetail();assert.equal(after.data.receipts[0].attemptId,attemptId);assert.equal(after.data.receipts[0].nativeTerminal,true);assert.equal(after.data.receipts[0].state,lateError?'unknown':'acknowledged');
  if(lateError){assert.equal(after.data.receipts[0].canAcknowledge,true);assert.equal(after.data.canUninstall,false);assert.ok(!JSON.stringify(after).includes('PRIVATE'));}
  else {assert.equal(after.data.canUninstall,true);assert.equal(after.data.receipts[0].readiness,'unverified');}
  assert.equal(calls.find(c=>c.method==='plugin/install'&&c.params.installAttemptId===attemptId).id,replyId,'same original RPC id resolves the retained receipt');
  assert.equal(calls.filter(c=>c.method==='plugin/install'&&c.params.installAttemptId===attemptId).length,1);
 }
 assert.equal(calls.filter(c=>c.method==='account/read').length,0,'plugin browsing and admission never introduce account or auth refresh RPCs');


 assert.equal((await api('/api/models')).data.models[0].serviceTiers[0].id,'priority');
 assert.equal((await api('/api/threads/fixture-thread/settings',{model:'fixture-model',effort:'medium',mode:'default',serviceTier:'priority'})).status,200);
 assert.equal((await api('/api/native/capabilities?threadId=fixture-thread')).data.requested.turnSettings.serviceTier,'priority');
 assert.ok(!calls.some(c=>/thread\/(goal|resume)|turn\/start/.test(c.method)));
});
