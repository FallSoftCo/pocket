// One rendered configuration journey using the existing registered browser
// context and production fixture composition. Owns only disposable local state.
// negativeControl removes the stale-click guard from the served module only;
// that run must fail CONFIGURATION_STALE_CLICK_MUST_NOT_WRITE with its receipt.
import assert from 'node:assert/strict';
import http from 'node:http';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
import {createWebClient} from '../server/web-client.mjs';
import {WebEnvironments,scopedId} from '../server/web-environments.mjs';
import {productionFixture,nativePage,until} from './helpers/production-native-fixture.mjs';
const hash=value=>createHash('sha256').update(value).digest('hex');
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};

export async function qualifyConfiguration({context,outputDir,negativeControl=false}){
 const output=resolve(outputDir);await mkdir(output,{recursive:false});
 const cleanup=[],requests=[],pageErrors=[];let a,b,page,gateway,host,phase='setup',releaseSettings;
 const receipt={state:'running',kind:'rendered-source-bound-configuration-and-conflict',negativeControl,providerCalls:false,layoutQualified:false};
 const save=()=>writeFile(join(output,'receipt.json'),JSON.stringify(receipt,null,2)+'\n');await save();
 try{
  const t={after:fn=>cleanup.push(fn)};a=await productionFixture(t,{name:'Local configuration fixture'});b=await productionFixture(t,{name:'Remote configuration fixture'});
  for(const [f,label]of [[a,'Local'],[b,'Remote']]){f.page=nativePage(label+' retained answer');f.configuration={id:'app_shared',name:label+' connector',version:1,enabled:true};}
  const environments=new WebEnvironments({secure:false});
  const allocator=http.createServer();await new Promise(r=>allocator.listen(0,'127.0.0.1',r));const port=allocator.address().port;await new Promise(r=>allocator.close(r));
  host='nextcomp-config-'+randomUUID()+'.localhost';const origin=`http://${host}:${port}`;
  gateway=createWebClient({backend:a.base,origin,secure:false,environments});
  gateway.server.prependListener('request',(req,res)=>{const record={phase,method:req.method,path:req.url};requests.push(record);let body='';req.on('data',c=>body+=c);req.on('end',()=>{if(body)record.body=JSON.parse(body);});res.on('finish',()=>record.status=res.statusCode);});
  await new Promise(r=>gateway.server.listen(port,'127.0.0.1',r));cleanup.push(async()=>{for(const ws of gateway.sockets.clients)ws.terminate();gateway.server.closeAllConnections();await new Promise(r=>gateway.server.close(r));});
  receipt.inputSha256={};for(const file of ['web/app.mjs','web/native-settings.mjs','server/native-configuration.mjs','server/web-client.mjs','server/web-environments.mjs','tests/configuration.browser.mjs','tests/helpers/production-native-fixture.mjs','tests/fixtures/native-contract-0167/schemas.json'])receipt.inputSha256[file]=hash(await readFile(new URL('../'+file,import.meta.url)));
  page=await context.newPage();page.setDefaultTimeout(8000);await page.emulateMedia({reducedMotion:'reduce'});page.on('pageerror',e=>pageErrors.push(e.message));
  if(negativeControl)await page.route(origin+'/native-settings.mjs',async route=>{
   const response=await route.fetch(),source=await response.text(),needle='   if(!this.current(origin))return;\n   const result=';
   assert.equal(hash(source),receipt.inputSha256['web/native-settings.mjs']);assert.equal(source.split(needle).length,2);
   const mutated=source.replace(needle,'   const result=');receipt.mutantSha256=hash(mutated);
   const headers={...response.headers(),'content-type':'text/javascript','cache-control':'no-store','content-length':String(Buffer.byteLength(mutated))};delete headers['content-encoding'];delete headers.etag;
   await route.fulfill({response,body:mutated,headers});
  });
  await page.route(origin+'/web/pair',async route=>{const response=await route.fetch(),paired=await response.json();assert.ok(paired.id);environments.profiles[paired.id]=[{id:'remote',name:'Remote fixture',base:b.base+'/',token:b.token,deviceId:b.deviceId}];await route.fulfill({response});});
  const local=a.thread.id,remote=scopedId('remote',b.thread.id),configurationPath='/api/environments/remote/native/configuration';
  let conflict=false,holdSettings=false;const settingsEntered=deferred(),settingsReleased=deferred();releaseSettings=settingsReleased.resolve;
  await page.route(url=>url.pathname===configurationPath,async route=>{
   const request=route.request();
   if(request.method()==='POST'&&conflict){conflict=false;b.configuration.version++;receipt.conflictVersion='fixture-config-'+b.configuration.version;}
   if(request.method()==='GET'&&new URL(request.url()).searchParams.get('section')==='settings'&&holdSettings){
    holdSettings=false;const response=await route.fetch();settingsEntered.resolve();await settingsReleased.promise;await route.fulfill({response});return;
   }
   await route.continue();
  });
  await page.goto(origin,{waitUntil:'domcontentloaded'});
  const owner=JSON.parse(await readFile(join(a.dir,'secrets.json'))).adminToken,pairing=await a.request('/api/pairing',{},owner);
  await page.locator('input[name=code]').fill(pairing.data.code);await page.locator('#pair-form button[type=submit]').click();await page.locator('#app').waitFor({state:'visible'});
  const select=async(id,label)=>{await page.locator(`[data-id="${id}"] .preview`).click();await until(()=>page.locator('#transcript').innerText(),s=>s.includes(label+' retained answer'),'selected '+label);};
  const dialog=page.getByRole('dialog',{name:'Codex settings and agents'}),feedback=dialog.getByRole('status');
  const openSettings=async()=>{await page.locator('#session-menu').click();await page.getByRole('button',{name:'Codex settings & agents',exact:true}).click();await until(()=>feedback.innerText(),s=>s!=='Loading…','settings loaded');};
  const apps=async label=>{await dialog.getByRole('button',{name:'Apps',exact:true}).click();await dialog.getByRole('heading',{name:label+' connector',exact:true}).waitFor();};
  const close=()=>dialog.getByRole('button',{name:'Close',exact:true}).click();
  const posts=()=>requests.filter(r=>r.method==='POST'&&r.path.endsWith('/native/configuration'));
  const nativeWrites=f=>f.calls.filter(c=>c.method==='config/value/write');
  await select(local,'Local');await page.locator('#draft').fill('Local draft remains original');
  await select(remote,'Remote');await page.locator('#draft').fill('Remote draft remains original');
  await openSettings();await apps('Remote');
  phase='acknowledged-change';await dialog.getByRole('button',{name:'Disable for this environment',exact:true}).click();
  await until(()=>feedback.innerText(),s=>s.includes('Native configuration write acknowledged'),'acknowledged configuration');
  await dialog.getByRole('button',{name:'Enable for this environment',exact:true}).waitFor();
  assert.equal(posts().length,1);assert.equal(posts()[0].status,200);assert.equal(posts()[0].path,configurationPath);
  assert.deepEqual(posts()[0].body,{threadId:remote,section:'apps',id:'app_shared',enabled:false,expectedVersion:'fixture-config-1'});
  assert.equal(nativeWrites(a).length,0);assert.equal(nativeWrites(b).length,1);
  assert.deepEqual(nativeWrites(b)[0].params,{keyPath:'apps.app_shared.enabled',value:false,mergeStrategy:'replace',expectedVersion:'fixture-config-1'});
  assert.equal(b.configuration.enabled,false);assert.equal(a.configuration.enabled,true);
  phase='conflict-refusal';conflict=true;await dialog.getByRole('button',{name:'Enable for this environment',exact:true}).click();
  await until(()=>feedback.innerText(),s=>s.includes('Configuration changed. Reload before applying this change.'),'rendered version conflict');
  receipt.conflictFeedback=await feedback.innerText();assert.match(receipt.conflictFeedback,/inspect the outcome before retrying/);
  assert.equal(posts().length,2);assert.equal(posts()[1].status,409);assert.equal(posts()[1].body.expectedVersion,'fixture-config-2');
  assert.equal(nativeWrites(b).length,1,'Conflict must be refused before any second native configuration dispatch');assert.equal(b.configuration.enabled,false);
  assert.equal(await dialog.getByRole('button',{name:'Enable for this environment',exact:true}).isDisabled(),true,'Failed change requires explicit inspection, not automatic retry');
  phase='stale-click';await dialog.getByRole('button',{name:'Reload Apps',exact:true}).click();await until(()=>dialog.getByRole('button',{name:'Enable for this environment',exact:true}).isEnabled(),Boolean,'explicit reload');
  holdSettings=true;await dialog.getByRole('button',{name:'Enable for this environment',exact:true}).click();await settingsEntered.promise;
  await close();await select(local,'Local');assert.equal(await page.locator('#draft').inputValue(),'Local draft remains original');await openSettings();await apps('Local');
  releaseSettings();
  if(negativeControl)await until(()=>posts().length,n=>n>2,'negative stale dispatch');
  else await page.evaluate(()=>new Promise(r=>setTimeout(r,300)));
  receipt.configurationPosts=posts();receipt.nativeWrites={local:nativeWrites(a).length,remote:nativeWrites(b).length};
  assert.equal(posts().length,2,'CONFIGURATION_STALE_CLICK_MUST_NOT_WRITE');
  assert.equal(nativeWrites(a).length,0);assert.equal(nativeWrites(b).length,1);assert.equal(a.configuration.enabled,true);assert.equal(b.configuration.enabled,false);
  assert.equal(await dialog.getByRole('heading',{name:'Local connector',exact:true}).count(),1);assert.equal(await dialog.getByRole('heading',{name:'Remote connector',exact:true}).count(),0);
  await close();await select(remote,'Remote');assert.equal(await page.locator('#draft').inputValue(),'Remote draft remains original');await openSettings();await apps('Remote');await dialog.getByRole('button',{name:'Enable for this environment',exact:true}).waitFor();
  for(const f of [a,b]){f.assertClean();assert.equal(f.calls.some(c=>/^(?:turn\/|review\/|thread\/(?:start|resume|fork|compact)|.*terminate)/.test(c.method)),false);}
  assert.ok(requests.filter(r=>r.method==='POST').every(r=>['/web/pair','/web/tailnet-login',configurationPath].includes(r.path)||r.path.endsWith('/catchup/read')),'No unrelated mutations');
  assert.deepEqual(pageErrors,[]);receipt.state='passed';receipt.draftsPreserved=true;receipt.workSubmissions=0;return receipt;
 }catch(error){receipt.state='failed';receipt.error=error.message;receipt.stack=error.stack;throw error;}
 finally{
  releaseSettings?.();receipt.pageErrors=pageErrors;
  await writeFile(join(output,'http.json'),JSON.stringify(requests,null,2)+'\n');
  for(const [label,f]of [['local',a],['remote',b]])if(f){await writeFile(join(output,label+'-native.json'),JSON.stringify(f.calls,null,2)+'\n');await writeFile(join(output,label+'-backend.log'),f.logs);}
  const failures=[];if(page&&!page.isClosed()){try{await page.evaluate(()=>localStorage.clear());await page.close();}catch(e){failures.push(e.message);}}
  if(host)await context.clearCookies({domain:host});for(const fn of cleanup.reverse())try{await fn();}catch(e){failures.push(e.message);}
  receipt.cleanupComplete=!failures.length;if(failures.length){receipt.state='failed';receipt.cleanupErrors=failures;}await save();if(failures.length)throw Error('Configuration qualifier cleanup failed: '+failures.join('; '));
 }
}
