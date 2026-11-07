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
