// Call qualifyColdPageSend({context,outputDir}) with an existing Playwright
// BrowserContext. It owns only newly created pages and a unique localhost origin.
// Actual web app -> actual gateway -> actual backend/outbox -> schema-validated
// disposable native wire. No browser launch, provider, model or real task calls.
// Normal run must return state=passed. A second run with negativeControl:true
// must throw COLD_PAGE_RECOVERY_MUST_NOT_REPEAT_POST; its failure receipt stays
// failed. Use a fresh output directory and a bounded 60-second browser job.
import assert from 'node:assert/strict';
import http from 'node:http';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
import {createWebClient} from '../server/web-client.mjs';
import {productionFixture,until} from './helpers/production-native-fixture.mjs';
const digest=value=>createHash('sha256').update(value).digest('hex');
const prefix='nextcomp.web.v1.';
const message='Disposable cold-page send; no external execution.';
const replayNeedle='const inspect=async()=>{try{await checkOriginalPending(p);}';
const replayMutant='const inspect=async()=>{try{await api(`/api/threads/${p.scope}/reply`,{id:p.id,text:p.text,mode:p.mode},{scope:p.scope});await checkOriginalPending(p);}';

export async function qualifyColdPageSend({context,outputDir,negativeControl=false}) {
 const output=resolve(outputDir);await mkdir(output,{recursive:false});
 const cleanup=[],httpCalls=[],pageErrors=[];let page,f,gateway,origin,host,original,mutantLoads=0;
 const receipt={state:'running',kind:'actual-browser-page-recreation-unknown-human-send',negativeControl,
  restartKind:'Page closed and recreated in same existing browser context; backend and browser process retained',providerCalls:false,layoutQualified:false};
 const persist=()=>writeFile(join(output,'receipt.json'),JSON.stringify(receipt,null,2)+'\n');
 // Screenshots are diagnostics, not a layout gate. Record capture failures
 // explicitly; required behavioral/wire assertions below still fail the run.
 async function capture(name){try{await page.screenshot({path:join(output,name),timeout:3000,animations:'disabled'});}catch(error){(receipt.captureErrors??=[]).push({name,error:error.message});}}
 await persist();
 try {
  f=await productionFixture({after:fn=>cleanup.push(fn)},{name:'Cold page fixture'});
  const backendPid=f.pid;receipt.backendPid=backendPid;f.loseSend=true;
  const allocator=http.createServer();await new Promise(r=>allocator.listen(0,'127.0.0.1',r));const port=allocator.address().port;await new Promise(r=>allocator.close(r));
  host='nextcomp-cold-'+randomUUID()+'.localhost';origin=`http://${host}:${port}`;
  gateway=createWebClient({backend:f.base,origin,secure:false});
  gateway.server.prependListener('request',(req,res)=>{
   const record={method:req.method,path:req.url};httpCalls.push(record);let body='';
   req.on('data',chunk=>{body+=chunk;});req.on('end',()=>{if(body){try{record.body=JSON.parse(body);}catch{record.body='non-JSON';}}});
   res.on('finish',()=>{record.status=res.statusCode;});
  });
  await new Promise(r=>gateway.server.listen(port,'127.0.0.1',r));
  cleanup.push(async()=>{for(const s of gateway.sockets.clients)s.terminate();gateway.server.closeAllConnections();await new Promise(r=>gateway.server.close(r));});
  const source=await readFile(new URL('../web/app.mjs',import.meta.url),'utf8');receipt.appSha256=digest(source);
  receipt.inputSha256={};
  for(const path of ['tests/cold-page-send.browser.mjs','tests/helpers/production-native-fixture.mjs','tests/fixtures/native-contract-0167/schemas.json','server/index.mjs','server/web-client.mjs','web/app.mjs','web/receipt-policy.mjs','web/storage.mjs'])receipt.inputSha256[path]=digest(await readFile(new URL('../'+path,import.meta.url)));
  async function newPage(recreated) {
   const p=await context.newPage();page=p;p.setDefaultTimeout(8000);p.on('pageerror',error=>pageErrors.push(error.message));
   if(recreated&&negativeControl)await p.route(origin+'/app.mjs',async route=>{
    const response=await route.fetch(),body=await response.text();assert.equal(body,source,'Negative control must mutate exactly the checked app source');
    assert.equal(body.split(replayNeedle).length,2,'Negative-control target must occur once');
    const mutated=body.replace(replayNeedle,replayMutant);receipt.mutantSha256=digest(mutated);mutantLoads++;
    const headers={...response.headers(),'content-type':'text/javascript','cache-control':'no-store','content-length':String(Buffer.byteLength(mutated))};
    delete headers['content-encoding'];delete headers.etag;
    await route.fulfill({response,body:mutated,headers});
   });
   // Synchronize the first original-receipt read with the actual backend's
   // unknown state. The real response is continued unchanged, never fabricated.
   if(!recreated)await p.route(/\/api\/replies\/[^/?]+$/,async route=>{
    const id=new URL(route.request().url()).pathname.split('/').at(-1);
    await until(()=>f.api('/api/replies/'+id),r=>r.state==='unknown','native-loss receipt unknown');
    await route.continue();
   });
   await p.goto(origin,{waitUntil:'domcontentloaded'});return p;
  }
  await newPage(false);
  const owner=JSON.parse(await readFile(join(f.dir,'secrets.json'))).adminToken;
  const pairing=await f.request('/api/pairing',{},owner);
  await page.locator('#pairing').waitFor({state:'visible'});
  await page.locator('input[name=code]').fill(pairing.data.code);
  await page.locator('#pair-form button[type=submit]').click();
  await page.locator('#app').waitFor({state:'visible'});
  await page.locator(`[data-id="${f.thread.id}"] .preview`).click();
  await until(()=>page.locator('#draft').isEnabled(),Boolean,'native conversation ready');
  await page.locator('#draft').fill(message);await page.locator('#send').click();
  original=await until(()=>page.evaluate(key=>JSON.parse(localStorage.getItem(key)),prefix+'pending'),p=>p?.state==='unknown','persisted original unknown human send');
  assert.equal(original.scope,f.thread.id);assert.equal(original.text,message);assert.ok(original.id&&original.deviceId);
  assert.equal(await page.locator('#draft').inputValue(),message,'Unknown delivery retains original draft');
  const backendReceipt=await f.api('/api/replies/'+original.id);
  assert.equal(backendReceipt.state,'unknown');assert.equal(backendReceipt.thread_id,original.scope);
  const dispatches=()=>f.calls.filter(c=>['turn/start','turn/steer'].includes(c.method));
  assert.equal(dispatches().length,1);assert.equal(dispatches()[0].params.clientUserMessageId,original.id);
  assert.equal(dispatches()[0].params.input[0].text,message);
  const posts=()=>httpCalls.filter(r=>r.method==='POST'&&/\/reply$/.test(r.path));
  assert.equal(posts().length,1);assert.equal(posts()[0].body.id,original.id);
  const cookieBefore=(await context.cookies(origin)).find(c=>c.name==='nextcomp-test');
  assert.ok(cookieBefore?.httpOnly);assert.equal(await page.evaluate(()=>document.cookie),'');
  receipt.original={id:original.id,scope:original.scope,deviceId:original.deviceId,state:backendReceipt.state};
  await capture('before-close.png');
  const readsBefore=httpCalls.filter(r=>r.method==='GET'&&r.path==='/api/replies/'+original.id).length;
  await page.close();assert.equal(page.isClosed(),true);page=null;
  await newPage(true);await page.locator('#app').waitFor({state:'visible'});
  await until(()=>httpCalls.filter(r=>r.method==='GET'&&r.path==='/api/replies/'+original.id&&r.status===200).length,n=>n>readsBefore,'new page reads original receipt');
  await until(()=>page.locator('#error').innerText(),text=>/Original delivery is unconfirmed/.test(text),'new page completes unknown receipt inspection');
  const restored=await page.evaluate(key=>JSON.parse(localStorage.getItem(key)),prefix+'pending');
  assert.equal(restored.id,original.id);assert.equal(restored.scope,original.scope);assert.equal(restored.deviceId,original.deviceId);assert.equal(restored.state,'unknown');
  assert.equal(await page.locator('#draft').inputValue(),message);
  assert.equal(await page.locator('#send').isDisabled(),true,'Pending original send must survive page recreation');
  assert.match(await page.locator('#outbox').innerText(),/unknown/);
  const cookieAfter=(await context.cookies(origin)).find(c=>c.name==='nextcomp-test');assert.equal(cookieAfter?.value,cookieBefore.value);
  assert.equal(f.pid,backendPid,'This journey must not restart the backend');
  assert.equal(posts().length,1,'COLD_PAGE_RECOVERY_MUST_NOT_REPEAT_POST');
  assert.equal(dispatches().length,1,'Cold page must not redispatch native input');
  assert.equal((await f.api('/api/replies/'+original.id)).state,'unknown');
  assert.ok(!f.calls.some(c=>['review/start','thread/compact/start','thread/start'].includes(c.method)));
  f.assertClean();assert.deepEqual(pageErrors,[]);assert.equal(mutantLoads,negativeControl?1:0);
  await capture('after-reopen.png');
  receipt.state='passed';receipt.originalReceiptReadsAfterRecreation=httpCalls.filter(r=>r.method==='GET'&&r.path==='/api/replies/'+original.id).length-readsBefore;
  receipt.replyPosts=posts().length;receipt.nativeDispatches=dispatches().length;return receipt;
 } catch(error) {
  receipt.state='failed';receipt.error=error.message;receipt.stack=error.stack;
  if(page&&!page.isClosed())await capture('failure.png');
  throw error;
 } finally {
  receipt.mutantLoads=mutantLoads;receipt.pageErrors=pageErrors;
  receipt.replyPosts=httpCalls.filter(r=>r.method==='POST'&&/\/reply$/.test(r.path)).length;
  receipt.nativeDispatches=f?.calls.filter(c=>['turn/start','turn/steer'].includes(c.method)).length||0;
  // Private logs contain disposable pairing only; never return cookies/tokens.
  await writeFile(join(output,'http.json'),JSON.stringify(httpCalls,null,2)+'\n');
  if(f){await writeFile(join(output,'native.json'),JSON.stringify(f.calls,null,2)+'\n');await writeFile(join(output,'backend.log'),f.logs);}
  try {
   if(page&&!page.isClosed()){
    await page.evaluate(async()=>{localStorage.clear();for(const r of await navigator.serviceWorker?.getRegistrations()||[])await r.unregister();for(const name of ['nextcomp-recording','nextcomp-voice-journal'])indexedDB.deleteDatabase(name);}).catch(()=>{});
    await page.close();
   }
   if(host)await context.clearCookies({domain:host});
  } finally {
   const cleanupErrors=[];
   for(const fn of cleanup.reverse())try{await fn();}catch(error){cleanupErrors.push(error.message);}
   receipt.cleanupComplete=cleanupErrors.length===0;
   if(cleanupErrors.length){receipt.state='failed';receipt.cleanupErrors=cleanupErrors;}
   await persist();
   if(cleanupErrors.length)throw new Error('Cold-page fixture cleanup failed: '+cleanupErrors.join('; '));
  }
 }
}
