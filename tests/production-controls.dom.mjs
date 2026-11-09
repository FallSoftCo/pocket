import {environmentRoute,unqualifyPayload,qualifyPayload,scopedId} from '../server/web-environments.mjs';
import {environmentPath} from '../web/environments.mjs';
// Explicit DOM qualification: fails closed when JSDOM is unavailable. npm test
// covers the sibling wire/HTTP suite; this file is intentionally a separate tier.
import test from 'node:test';
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import {productionFixture,question,until} from './helpers/production-native-fixture.mjs';
import {ConversationToolsPane} from '../web/conversation-tools.mjs';
if(!process.env.NEXTCOMP_JSDOM_MODULE)throw Error('NEXTCOMP_JSDOM_MODULE is required for production DOM qualification (no skip fallback).');
const {JSDOM}=await import(pathToFileURL(process.env.NEXTCOMP_JSDOM_MODULE));
const {RequestList}=await import(process.env.NEXTCOMP_TEST_REQUEST_LIST_MODULE||new URL('../web/request-list.mjs',import.meta.url).href);
const path='/api/threads/thread-shared/native-timeline';
function documentFixture(t){
 const dom=new JSDOM('<!doctype html><body><section id="requests"></section></body>',{url:'https://fixture.invalid'});
 const prior=Object.fromEntries(['document','Event','localStorage','confirm'].map(k=>[k,globalThis[k]]));Object.assign(globalThis,{document:dom.window.document,Event:dom.window.Event,localStorage:dom.window.localStorage,confirm:()=>true});
 dom.window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};dom.window.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new Event('close'));};
 t.after(()=>{Object.assign(globalThis,prior);dom.window.close();});
 const create=(tag,cls='',text='')=>{const n=document.createElement(tag);n.className=cls;n.textContent=text;return n;};
 const button=(label,fn)=>{const b=create('button','',label);b.onclick=fn;return b;};
 const click=label=>{const b=[...document.querySelectorAll('button')].find(b=>b.textContent===label);assert.ok(b,`Missing action ${label}`);assert.equal(b.disabled,false,`Disabled action ${label}`);b.click();};
 return {create,button,click};
}

test('real request controls retain unknown delivery, forbid replay and clear secrets on native ID reuse',{timeout:20000},async t=>{
 const ui=documentFixture(t),f=await productionFixture(t);await f.watch();let loseAck=true,posts=0,refreshes=0;
 const view=new RequestList({holder:document.querySelector('#requests'),...ui,api:async(path,body)=>{posts++;const result=await f.api(path,body);if(loseAck){loseAck=false;throw Error('Controlled loss after native dispatch');}return result;},refresh:async()=>{view.update(f.thread.id,(await f.api(path)).pending);refreshes++;}});
 f.emit(question());const first=await until(()=>f.api(path),p=>p.pending.length===1,'pending request card');view.update(f.thread.id,first.pending);
 const input=document.querySelector('[data-question="choice"]');assert.equal(input.type,'password');input.value='Original secret';input.dispatchEvent(new Event('input',{bubbles:true}));ui.click('Submit answers');
 await until(()=>document.body.textContent,text=>text.includes('Delivery unconfirmed'),'unconfirmed decision');
 await until(()=>f.answers.length,n=>n===1,'native decision');assert.equal(posts,1);assert.deepEqual(f.answers[0].result,{answers:{choice:{answers:['Original secret']}}});
 ui.click('Check current state');await until(()=>refreshes,n=>n===1,'original pending request refresh completed');assert.equal(document.querySelector('[data-question="choice"]').disabled,true);assert.equal(posts,1);
 f.disconnect();await until(()=>f.events.some(e=>e.type==='status'&&!e.connected),Boolean,'request transport disconnect');await f.api(path);f.emit(question());
 const current=await until(()=>f.api(path),p=>p.pending.length===1,'new connection request');assert.notEqual(current.pending[0]._pocketUI.token,first.pending[0]._pocketUI.token);
 view.update(f.thread.id,current.pending);
 assert.equal(document.querySelector('[data-question="choice"]').value,'','REUSED_REQUEST_SECRET_MUST_CLEAR');
 assert.equal(document.querySelector('[data-question="choice"]').disabled,false);
 document.querySelector('[data-question="choice"]').value='New decision';ui.click('Submit answers');
 await until(()=>f.answers.length,n=>n===2,'new request answer');assert.equal(posts,2);assert.deepEqual(f.answers[1].result,{answers:{choice:{answers:['New decision']}}});
 f.emit({method:'serverRequest/resolved',params:{threadId:f.thread.id,requestId:73}});const done=await until(()=>f.api(path),p=>p.pending.length===0,'request resolved');view.update(f.thread.id,done.pending);assert.equal(document.querySelectorAll('.request-card').length,0);f.assertClean();
});

test('real conversation pane recovers an unknown operation after backend restart through receipt reads only',{timeout:20000},async t=>{
 const ui=documentFixture(t),f=await productionFixture(t);f.loseCompact=true;const calls=[];let selected=f.thread.id;
 const pane=new ConversationToolsPane({api:async(path,body)=>{calls.push({path,body});return f.api(path,body);},source:()=>selected,device:()=>f.deviceId,appendDraft:()=>assert.fail('Recovery cannot insert a draft'),applied:()=>{}});
 await pane.show();ui.click('Compact context');
 await until(()=>pane.dialog.textContent,text=>text.includes('unknown'),'unknown compact receipt rendered');
 const mutation=calls.find(c=>c.body?.kind==='compact');assert.ok(mutation);assert.equal(f.calls.filter(c=>c.method==='thread/compact/start').length,1);
 pane.dialog.close();await f.restart();await pane.show();
 assert.ok(pane.dialog.textContent.includes('unknown'));ui.click('Refresh context and receipts');
 await until(()=>calls.filter(c=>c.path.endsWith('/conversation-tools')).length,n=>n>=3,'refreshed original operation');
 assert.equal(calls.filter(c=>c.body?.kind==='compact').length,1);assert.equal(f.calls.filter(c=>c.method==='thread/compact/start').length,1);
 selected='other-thread';ui.click('Compact context');await until(()=>pane.error.textContent,text=>text.includes('original conversation'),'source change blocks old pane action');
 assert.equal(calls.filter(c=>c.body).length,1);f.assertClean();
});

test('settings plugin journey binds review to source and retains uncertain native effects across restart',{timeout:25000},async t=>{
 const ui=documentFixture(t),f=await productionFixture(t),{pluginState}=await import('./helpers/native-plugin-fixture.mjs'),{NativeSettingsPanel}=await import('../web/native-settings.mjs');
 f.plugins={...pluginState(),catalogSize:10074};f.configuration={id:'fixture-app',name:'Fixture app',enabled:true,version:1};const origin=scopedId('remote',f.thread.id);let selected=origin,loseHttpAck=false,holdDetail=null,holdPage=null,holdApps=null;const calls=[];
 const panel=new NativeSettingsPanel({getScope:()=>selected,openThread:()=>assert.fail('Plugin controls never navigate or submit work'),api:async(path,body,options)=>{
  calls.push({path,body,scope:options.scope});assert.equal(options.scope,origin,'every read/write remains on the captured source');
  const routed=environmentRoute(environmentPath(path,options.scope));assert.equal(routed.environmentId,'remote');
  const isPage=path.startsWith('/api/native/configuration?section=plugins')&&path.includes('&cursor=');const isApps=path.startsWith('/api/native/configuration?section=apps');const hold=isApps?holdApps:isPage?holdPage:path.startsWith('/api/native/plugins/detail')?holdDetail:null;if(hold){if(isApps)holdApps=null;else if(isPage)holdPage=null;else holdDetail=null;}
  const result=qualifyPayload(await f.api(routed.path,body?unqualifyPayload(body,'remote'):undefined),'remote');if(hold){hold.started();await hold.wait;if(hold.fail)throw Error('Older reload failed');}if(body&&loseHttpAck){loseHttpAck=false;throw Error('Synthetic HTTP acknowledgement loss');}return result;
 }});
 const waitText=text=>until(()=>panel.dialog.textContent,s=>s.includes(text),text);
 // Existing settings journey also qualifies Apps through the real HTTP/native boundary.
 f.appsBrowse=true;await panel.open();await panel.load('apps');
 assert.equal(f.calls.filter(c=>c.method==='app/list').length,0,'installed inspection must not discover apps');
 assert.match(panel.body.textContent,/Installed: Yes · Enabled: Yes · Callable: No/);
 const appAction=label=>[...panel.dialog.querySelectorAll('button')].find(b=>b.textContent===label).onclick();
 await appAction('Browse apps');assert.equal(panel.body.querySelectorAll('[data-app-row]').length,30);
 assert.match(panel.body.textContent,/Installed: No · Enabled: Unknown · Callable: Unknown/);
 const appDetails=panel.body.querySelector('[data-app-row] details');appDetails.open=true;assert.match(appDetails.textContent,/Authentication: unverified/);
 assert.equal(panel.body.querySelectorAll('[data-app-row] button').length,1,'only installed app may offer configuration');
 await appAction('Next apps page');assert.equal(panel.body.querySelectorAll('[data-app-row]').length,1);assert.ok(!panel.body.textContent.includes('Available app 0'));
 await appAction('Refresh apps');assert.equal(panel.body.querySelectorAll('[data-app-row]').length,30);
 assert.ok(f.calls.some(c=>c.method==='app/list'&&c.params.forceRefetch===true));assert.ok(f.calls.some(c=>c.method==='app/installed'&&c.params.forceRefresh===true));
 let appsStarted,appsRelease;const appsReading=new Promise(r=>appsStarted=r);holdApps={started:appsStarted,wait:new Promise(r=>appsRelease=r)};
 const oldApps=appAction('Next apps page');await appsReading;panel.closeForNavigation();selected=f.thread.id;appsRelease();await oldApps;
 assert.equal(panel.dialog.open,false);assert.equal(panel.body.querySelectorAll('[data-app-row]').length,0,'held Apps page must not paint after source switch');
 selected=origin;await panel.open();await panel.load('apps');panel.event({type:'nativeAuthBoundary'});assert.equal(panel.dialog.open,false,'account boundary closes app inspection');
 assert.equal(calls.filter(c=>c.body).length,0,'Apps browsing cannot mutate configuration or submit work');
 f.userAgent='codex_pocket/0.163.0 (Mac OS 26.6; arm64)';await f.restart();await panel.open();await panel.load('apps');
 assert.match(panel.body.textContent,/Installed: Yes · Enabled: Yes · Callable: No/);
 assert.ok(![...panel.dialog.querySelectorAll('button')].some(b=>['Browse apps','Refresh apps','Disable for this environment'].includes(b.textContent)),'Mac installed inspection must not advertise discovery, refresh or writes');
 panel.closeForNavigation();f.userAgent='codex/0.167.0';await f.restart();
 const plugins=async()=>{await panel.open();await panel.load('plugins');await panel.load('plugins',undefined,{view:'browse',query:'fixture'});};
 await panel.open();await panel.load('plugins');assert.equal(panel.body.querySelectorAll('[data-plugin-row]').length,0);assert.equal(f.calls.some(c=>c.method==='plugin/list'),false,'opening installed view does not discover 10k entries');
 await [...panel.body.querySelectorAll('button')].find(b=>b.textContent==='Browse catalog').onclick();assert.equal(panel.body.querySelectorAll('[data-plugin-row]').length,30);assert.ok(panel.body.textContent.includes('1–30 of 10074'));
 const oldReview=panel.body.querySelector('[data-plugin-row] button').onclick;
 let next=[...panel.dialog.querySelectorAll('button')].find(b=>b.textContent==='Next plugin page').onclick;
 selected=f.thread.id;const beforeSwitch=calls.length;await next();assert.equal(calls.length,beforeSwitch,'stale source page callback performs no request');selected=origin;
 let pageStarted,pageRelease;const pageReading=new Promise(r=>pageStarted=r);holdPage={started:pageStarted,wait:new Promise(r=>pageRelease=r)};
 const latePage=next();await pageReading;selected=f.thread.id;pageRelease();await latePage;assert.equal(panel.body.querySelectorAll('[data-plugin-row]').length,0,'late page from original source is not rendered after switching');selected=origin;
 await panel.load('plugins');next=[...panel.dialog.querySelectorAll('button')].find(b=>b.textContent==='Next plugin page').onclick;
 panel.body.scrollTop=300;await next();assert.equal(panel.body.scrollTop,0,'new page starts at its first row');assert.equal(panel.body.querySelectorAll('[data-plugin-row]').length,30);assert.ok(panel.body.textContent.includes('31–60 of 10074'));assert.ok(!panel.body.textContent.includes('Catalog plugin 00000'));
 await oldReview();assert.ok(panel.body.textContent.includes('31–60 of 10074'),'old page selection cannot open replacement rows');
 await panel.body.querySelector('[data-plugin-row] button').onclick();assert.ok(panel.body.textContent.includes('Catalog plugin 00030'));assert.equal(f.calls.filter(c=>c.method==='plugin/read').at(-1).params.pluginName,'plugins_bulk_00030');ui.click('Back to plugins');await waitText('1–30 of 10074');
 const search=panel.body.querySelector('input[aria-label="Search plugins"]');search.value='fixture';search.form.dispatchEvent(new Event('submit',{cancelable:true}));await waitText('1–1 of 1');assert.equal(panel.body.querySelectorAll('[data-plugin-row]').length,1);
 f.plugins.catalogSize=1;
 assert.equal(f.calls.some(c=>c.method==='plugin/install'),false);ui.click('Review plugin');await waitText('Review installation');
 const other=await productionFixture(t,{name:'Other source with the same native IDs'});other.plugins=pluginState();
 const originalView=await f.api('/api/native/plugins/detail?threadId='+f.thread.id+'&key='+((await f.api('/api/native/configuration?section=plugins&pluginView=browse&query=fixture')).items[0].key));
 const otherView=await other.api('/api/native/plugins/detail?threadId='+other.thread.id+'&key='+originalView.detail.key);
 assert.notEqual(originalView.reviewToken,otherView.reviewToken,'review snapshots are sealed to the original backend');
 assert.equal((await other.request('/api/native/configuration',{section:'plugins',threadId:other.thread.id,key:originalView.detail.key,reviewToken:originalView.reviewToken,action:'install',attemptId:'wrong-backend-attempt',confirmEffects:true,confirmInterstitial:true})).status,409);
 assert.equal(other.calls.some(c=>c.method==='plugin/install'),false);other.assertClean();
 ui.click('Review installation');assert.ok(panel.body.textContent.includes('browser on the host'));
 let checkbox=panel.body.querySelector('input[type=checkbox]');assert.ok(checkbox);assert.equal([...panel.body.querySelectorAll('button')].find(b=>b.textContent==='Confirm installation').disabled,true);
 checkbox.checked=true;checkbox.dispatchEvent(new Event('change'));selected=f.thread.id;ui.click('Confirm installation');assert.equal(calls.filter(c=>c.body).length,0,'source switch before confirmation prevents POST');
 selected=origin;panel.dialog.close();await plugins();
 let entered,release;f.plugins.holdRead={started:()=>entered?.(),wait:new Promise(r=>release=r)};const reading=new Promise(r=>entered=r);const oldOpening=[...panel.body.querySelectorAll('button')].find(b=>b.textContent==='Review plugin').onclick();await reading;
 panel.dialog.close();await plugins();release();await oldOpening;assert.equal(panel.body.querySelectorAll('input[type=checkbox]').length,0);assert.ok(panel.body.textContent.includes('Review plugin'),'late detail must not replace a new opening');
 ui.click('Review plugin');await waitText('Review installation');
 // Retain the actual reload callback to reproduce queued same-pane reloads.
 // Await exact completions; both stale success and stale failure must be ignored.
 for(const fail of [false,true]){
  let started,finish;const reading=new Promise(r=>started=r);holdDetail={started,wait:new Promise(r=>finish=r),fail};
  const reload=[...panel.body.querySelectorAll('button')].find(b=>b.textContent==='Reload plugin details').onclick;
  const older=reload();await reading;f.plugins.version=fail?'newest-failure-case':'newest-success-case';await reload();
  const currentBody=panel.body.textContent,currentFeedback=panel.feedback.textContent,receiptReads=calls.filter(c=>c.path.startsWith('/api/native/plugins/receipts')).length;
  finish();await older;assert.equal(panel.body.textContent,currentBody);assert.equal(panel.feedback.textContent,currentFeedback);assert.ok(currentBody.includes(f.plugins.version));assert.equal(calls.filter(c=>c.path.startsWith('/api/native/plugins/receipts')).length,receiptReads);
 }
 ui.click('Review installation');checkbox=panel.body.querySelector('input');checkbox.checked=true;checkbox.dispatchEvent(new Event('change'));ui.click('Confirm installation');await waitText('Installation acknowledged');
 assert.ok(panel.feedback.textContent.includes('Readiness is unverified'));assert.equal(f.calls.filter(c=>c.method==='plugin/install').length,1);assert.equal(panel.body.querySelectorAll('a').length,0,'no invented authentication URL');
 ui.click('Reload plugin details');await waitText('Review removal');assert.ok(panel.body.textContent.includes('Fixture app'));ui.click('Review removal');ui.click('Confirm removal');await waitText('Removal acknowledged');
 // A received native error is terminal but still effects-uncertain; explicit
 // acknowledgement may permit a new action only in the original identity epoch.
 ui.click('Reload plugin details');await waitText('Review installation');ui.click('Review installation');checkbox=panel.body.querySelector('input');checkbox.checked=true;checkbox.dispatchEvent(new Event('change'));f.plugins.failInstall=true;ui.click('Confirm installation');await waitText('Outcome unconfirmed');
 ui.click('Reload plugin details');await waitText('Review uncertain outcome');ui.click('Review uncertain outcome');ui.click('Acknowledge uncertain outcome');await waitText('Outcome unconfirmed');ui.click('Reload plugin details');await waitText('Review removal');ui.click('Review removal');ui.click('Confirm removal');await waitText('Removal acknowledged');
 ui.click('Reload plugin details');await waitText('Review installation');ui.click('Review installation');checkbox=panel.body.querySelector('input');checkbox.checked=true;checkbox.dispatchEvent(new Event('change'));
 loseHttpAck=true;f.plugins.dropInstall=true;ui.click('Confirm installation');await waitText('Delivery unconfirmed');const original=calls.filter(c=>c.body).at(-1).body;
 panel.dialog.close();await f.restart();await plugins();assert.ok(panel.body.textContent.includes('unknown'),'receipt survives backend and dialog restart');
 ui.click('Review plugin');await waitText('Native completion is unknown');assert.ok(![...panel.body.querySelectorAll('button')].some(b=>['Review removal','Review uncertain outcome'].includes(b.textContent)));
 assert.equal(f.calls.filter(c=>c.method==='plugin/install').length,3);assert.equal(calls.filter(c=>c.body?.attemptId===original.attemptId).length,1);
 ui.click('Reload plugin details');await waitText('Native completion is unknown');
 assert.equal(f.calls.filter(c=>c.method==='plugin/install').length,3,'inventory readback and restart never authorize replay of an outstanding operation');
 assert.ok(!f.calls.some(c=>['turn/start','thread/resume'].includes(c.method)));f.assertClean();
});
