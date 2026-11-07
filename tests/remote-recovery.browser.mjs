// One composed journey, run with an existing Playwright context in a bounded
// browser job. Two production backends/gateway, disposable schema-checked native
// sockets, transparent remote /events outage. No production or provider calls.
// Page clock stays below the existing 10s periodic fallback: recovery must follow
// the actual remote reconnect event, not a lucky polling tick. No layout claim.
import assert from 'node:assert/strict';
import http from 'node:http';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
import {createWebClient} from '../server/web-client.mjs';
import {WebEnvironments,scopedId} from '../server/web-environments.mjs';
import {productionFixture,nativePage,question,until} from './helpers/production-native-fixture.mjs';
import {eventStreamGate} from './helpers/event-stream-gate.mjs';
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const fields=t=>[t.preview,t.previewRole,t.previewKind,t.interactionId,t.activityAt,t.updatedAt];

export async function qualifyRemoteRecovery({context,outputDir,statusFailure=null}) {
 assert.ok([null,401,503].includes(statusFailure),'Only the bounded 401/503 fault variants are supported');
 const output=resolve(outputDir);await mkdir(output,{recursive:false});
 const cleanup=[],requests=[],events=[],pageErrors=[];let page,a,b,host,origin,gateway,gate,phase='setup',pageClockMs=100;
 const receipt={state:'running',kind:'remote-only-event-recovery-before-periodic-fallback',providerCalls:false,layoutQualified:false,statusFailure};
 const save=()=>writeFile(join(output,'receipt.json'),JSON.stringify(receipt,null,2)+'\n');await save();
 try {
  const t={after:fn=>cleanup.push(fn)};a=await productionFixture(t,{name:'Workstation fixture'});b=await productionFixture(t,{name:'Mac fixture'});
  a.page=nativePage('Workstation retained answer');b.page=nativePage('Mac answer before outage');
  gate=await eventStreamGate(b.base);cleanup.push(()=>gate.close());
  const environments=new WebEnvironments({secure:false});
  const allocator=http.createServer();await new Promise(r=>allocator.listen(0,'127.0.0.1',r));const port=allocator.address().port;await new Promise(r=>allocator.close(r));
  host='nextcomp-recovery-'+randomUUID()+'.localhost';origin=`http://${host}:${port}`;
  gateway=createWebClient({backend:a.base,origin,secure:false,environments});
  gateway.server.prependListener('request',(req,res)=>{
   const record={phase,method:req.method,path:req.url};requests.push(record);let body='';req.on('data',chunk=>{body+=chunk;});req.on('end',()=>{if(body)record.body=JSON.parse(body);});res.on('finish',()=>{record.status=res.statusCode;});
  });
  await new Promise(r=>gateway.server.listen(port,'127.0.0.1',r));
  cleanup.push(async()=>{for(const ws of gateway.sockets.clients)ws.terminate();gateway.server.closeAllConnections();await new Promise(r=>gateway.server.close(r));});
  receipt.inputSha256={};for(const file of ['web/app.mjs','server/web-client.mjs','server/index.mjs','tests/remote-recovery.browser.mjs','tests/helpers/event-stream-gate.mjs'])receipt.inputSha256[file]=hash(await readFile(new URL('../'+file,import.meta.url)));
  page=await context.newPage();await page.emulateMedia({reducedMotion:'reduce'});page.setDefaultTimeout(8000);page.on('pageerror',error=>pageErrors.push(error.message));
  let browserSockets=0;
  page.on('websocket',socket=>{browserSockets++;socket.on('framereceived',({payload})=>{try{events.push({phase,...JSON.parse(String(payload))});}catch{}});});
  const complete=async()=>{
  const writes=requests.filter(r=>r.method!=='GET'&&r.method!=='HEAD');
  assert.ok(writes.every(r=>['/web/pair','/web/tailnet-login'].includes(r.path)||/^\/api\/threads\/[^/]+\/catchup\/read$/.test(r.path)),'No work/decision/config mutations permitted');
  assert.ok(writes.filter(r=>r.path.endsWith('/catchup/read')).every(r=>r.status===200),'Read acknowledgement must match its original source capture');
  for(const f of [a,b]){f.assertClean();assert.equal(f.calls.some(c=>/^(?:turn\/|review\/|thread\/(?:start|fork|compact)|.*terminate)/.test(c.method)),false);}
  assert.equal(browserSockets,1,'Browser socket must survive every remote-only interruption');assert.equal(await page.locator('#connection').innerText(),'Connected');
  assert.deepEqual(pageErrors,[]);receipt.state='passed';receipt.browserSockets=browserSockets;receipt.pageClockMs=pageClockMs;receipt.workSubmissions=0;return receipt;
  };
  const epoch=new Date();await page.clock.install({time:epoch});await page.clock.pauseAt(new Date(epoch.getTime()+100));
  const tick=async milliseconds=>{pageClockMs+=milliseconds;assert.ok(pageClockMs<10000,'Periodic fallback must not run');await page.clock.fastForward(milliseconds);};
  // Set the exact remote pairing before the real UI consumes its pairing reply.
  await page.route(origin+'/web/pair',async route=>{const response=await route.fetch(),paired=await response.json();assert.ok(paired.id);environments.profiles[paired.id]=[{id:'macbook',name:'Mac fixture',base:gate.base+'/',token:b.token,deviceId:b.deviceId}];await route.fulfill({response});});
  await page.goto(origin,{waitUntil:'domcontentloaded'});
  const owner=JSON.parse(await readFile(join(a.dir,'secrets.json'))).adminToken;const pairing=await a.request('/api/pairing',{},owner);
  await page.locator('input[name=code]').fill(pairing.data.code);await page.locator('#pair-form button[type=submit]').evaluate(n=>n.click());
  await page.locator('#app').waitFor({state:'visible'});
  const local=a.thread.id,remote=scopedId('macbook',b.thread.id);
  const select=async(id,text)=>{await page.locator(`[data-id="${id}"] .preview`).evaluate(n=>n.click());await until(()=>page.locator('#transcript').innerText(),value=>value.includes(text),'selected '+id);};
  await select(local,'Workstation retained answer');await page.locator('#draft').fill('Workstation draft retained');
  await select(remote,'Mac answer before outage');await page.locator('#draft').fill('Mac draft retained');
  await tick(500);
  await until(()=>events.filter(e=>e.type==='environmentStatus'&&e.environmentId==='macbook'&&e.available===true).length,n=>n===1,'initial Mac event stream');
  const catalog=()=>page.evaluate(async()=>{const r=await fetch('/api/threads');return r.json();});
  const before=(await catalog()).threads.find(t=>t.id===remote);assert.ok(before);const beforeFields=fields(before);
  phase='outage';const outageStarted=Date.now();gate.interrupt();await until(()=>events.some(e=>e.phase==='outage'&&e.type==='environmentStatus'&&e.available===false),Boolean,'remote outage reported');await tick(200);
  b.page=nativePage('Mac missed answer after outage');b.emit(question(901));
  const attention=await until(()=>b.api('/api/attention'),r=>r.notifications.length===1,'actual remote attention retained');
  assert.equal(attention.notifications[0].thread_id,b.thread.id);
  const after=(await catalog()).threads.find(t=>t.id===remote);assert.deepEqual(fields(after),beforeFields,'Catalog changes would mask the reconnect gap');
  const remoteHistory='/api/threads/'+remote+'/native-timeline';
  const reads=()=>requests.filter(r=>r.method==='GET'&&r.path.split('?')[0]===remoteHistory&&r.status===200).length;
  const attentionReads=()=>requests.filter(r=>r.method==='GET'&&r.path==='/api/attention'&&r.status===200).length;
  const headsBefore=reads(),attentionBefore=attentionReads();
  receipt.unchangedCatalogFields=true;receipt.backendPids=[a.pid,b.pid];receipt.cachedAnswerBeforeRecovery=await page.locator('#transcript').innerText();
  if(statusFailure){receipt.verificationLeaseRemainingMs=Math.min(...environments.verified.values())-Date.now();assert.ok(receipt.verificationLeaseRemainingMs>5000,'Status fault must exercise reconnect check, not expired credential verification');}
  phase='recovery';receipt.outageMs=Date.now()-outageStarted;const started=Date.now();gate.recover({statusFailure});
  if(statusFailure===401){
   await until(()=>gate.requests.some(r=>r.injected===401),Boolean,'reconnect rejected with 401');
   // Longer than the existing next 2s backoff. A returned credential rejection
   // must not acquire the retry behavior intended only for transient 5xx.
   await new Promise(resolve=>setTimeout(resolve,3000));
   const faultIndex=gate.requests.findIndex(r=>r.injected===401);
   assert.equal(gate.requests.slice(faultIndex+1).some(r=>r.path==='/api/status'||r.method==='UPGRADE'),false,'Returned 401 must not schedule reconnect');
   assert.equal(events.filter(e=>e.type==='environmentStatus'&&e.available===true).length,1);
   assert.equal(reads(),headsBefore);assert.equal(attentionReads(),attentionBefore);
   receipt.credentialRejectionDidNotRetry=true;receipt.controlObservationMs=3000;
   return await complete();
  }
  await until(()=>events.filter(e=>e.type==='environmentStatus'&&e.environmentId==='macbook'&&e.available===true).length,n=>n===2,'remote transport recovery',12000);
  await tick(700);
  // Allow the real HTTP work to settle without advancing the paused page timer.
  try{await until(()=>Promise.all([page.locator('#transcript').innerText(),page.locator('#attention-count').innerText()]),v=>v[0].includes('Mac missed answer after outage')&&v[1]==='1','event-driven history and attention recovery',2000);}catch{}
  if(statusFailure)assert.equal(gate.requests.filter(r=>r.injected===statusFailure).length,1,'Inject exactly one transient status response');
  receipt.recovery={historyReads:reads()-headsBefore,attentionReads:attentionReads()-attentionBefore,visible:await page.locator('#transcript').innerText(),attentionCount:await page.locator('#attention-count').innerText(),realElapsedMs:Date.now()-started,pageClockMs};
  assert.ok(reads()>headsBefore,'REMOTE_RECOVERY_MUST_REFRESH_SELECTED_HISTORY_BEFORE_POLL');
  assert.ok(attentionReads()>attentionBefore,'REMOTE_RECOVERY_MUST_REFRESH_MISSED_ATTENTION_BEFORE_POLL');
  assert.match(receipt.recovery.visible,/Mac missed answer after outage/);assert.equal(receipt.recovery.attentionCount,'1');
  assert.doesNotMatch(receipt.recovery.visible,/Workstation retained answer/);assert.equal(await page.locator('#draft').inputValue(),'Mac draft retained');
  assert.equal(browserSockets,1,'Workstation/browser socket must remain connected throughout remote outage');
  assert.equal(await page.locator('#connection').innerText(),'Connected');
  assert.equal(events.some(e=>e.type==='status'&&e.connected===false),false,'Remote outage must not become workstation outage');
  phase='select-workstation';await select(local,'Workstation retained answer');assert.equal(await page.locator('#draft').inputValue(),'Workstation draft retained');await tick(300);
  phase='workstation-stable';gate.interrupt();
  await until(()=>events.some(e=>e.phase==='workstation-stable'&&e.type==='environmentStatus'&&e.available===false),Boolean,'off-source outage');await tick(200);
  const localHeads=()=>requests.filter(r=>r.method==='GET'&&r.path.split('?')[0]==='/api/threads/'+local+'/native-timeline').length;
  const localBefore=localHeads(),remoteBefore=reads();gate.recover();
  await until(()=>events.filter(e=>e.type==='environmentStatus'&&e.environmentId==='macbook'&&e.available===true).length,n=>n===3,'off-source recovery',12000);await tick(700);
  await until(()=>requests.some(r=>r.phase==='workstation-stable'&&r.path==='/api/attention'&&r.status===200),Boolean,'off-source attention recovery');
  assert.equal(localHeads(),localBefore,'Remote recovery must not invalidate the workstation conversation');
  assert.equal(reads(),remoteBefore,'Inactive remote conversation must not be read merely for reconnect');
  assert.equal(requests.some(r=>r.phase==='workstation-stable'&&r.method==='POST'&&r.path.includes(remote)),false,'Mac read acknowledgements must not be sent while workstation stays selected');
  receipt.offSourceRecoveryPreservedLocalHistory=true;
  assert.deepEqual(fields((await catalog()).threads.find(t=>t.id===remote)),beforeFields,'Recovery must not fabricate catalog history/progress timestamps');
  phase='select-mac';await select(remote,'Mac missed answer after outage');assert.equal(await page.locator('#draft').inputValue(),'Mac draft retained');
  assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('nextcomp.web.v1.scope'))),remote);
  return await complete();
 } catch(error){receipt.state='failed';receipt.error=error.message;receipt.stack=error.stack;throw error;}
 finally {
  receipt.pageErrors=pageErrors;receipt.pageClockMs=pageClockMs;
  if(gate)await writeFile(join(output,'remote-gate.json'),JSON.stringify(gate.requests,null,2)+'\n');
  await writeFile(join(output,'http.json'),JSON.stringify(requests,null,2)+'\n');await writeFile(join(output,'events.json'),JSON.stringify(events,null,2)+'\n');
  for(const [label,f]of [['workstation',a],['mac',b]])if(f){await writeFile(join(output,label+'-native.json'),JSON.stringify(f.calls,null,2)+'\n');await writeFile(join(output,label+'-backend.log'),f.logs);}
  const failures=[];
  if(page&&!page.isClosed()){try{await page.evaluate(async()=>{localStorage.clear();for(const r of await navigator.serviceWorker?.getRegistrations()||[])await r.unregister();for(const name of ['nextcomp-recording','nextcomp-voice-journal'])indexedDB.deleteDatabase(name);});}catch(error){failures.push(error.message);}await page.close();}
  if(host)await context.clearCookies({domain:host});for(const fn of cleanup.reverse())try{await fn();}catch(error){failures.push(error.message);}
  receipt.cleanupComplete=!failures.length;if(failures.length){receipt.state='failed';receipt.cleanupErrors=failures;}await save();if(failures.length)throw Error('Remote recovery cleanup failed: '+failures.join('; '));
 }
}
