// Actual UI → authenticated HTTP → real Codex transport; synthetic native endpoint only.
import test from 'node:test';
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import {fixture} from './helpers/native-mcp-auth-fixture.mjs';
import {NativeSettingsPanel} from '../web/native-settings.mjs';
if(!process.env.NEXTCOMP_JSDOM_MODULE)throw Error('Existing NEXTCOMP_JSDOM_MODULE required; no dependency installation.');
const {JSDOM}=await import(pathToFileURL(process.env.NEXTCOMP_JSDOM_MODULE));
async function until(predicate){for(let n=0;n<200&&!predicate();n++)await new Promise(r=>setTimeout(r,5));assert.ok(predicate());}
test('configured MCP selection → explicit host sign-in → exact completion, with private URL cleared across source change',async t=>{
 const dom=new JSDOM('<!doctype html><body></body>'),previous=globalThis.document;globalThis.document=dom.window.document;
 dom.window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};dom.window.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new dom.window.Event('close'));};
 t.after(()=>{globalThis.document=previous;dom.window.close();});
 const f=await fixture(t);let scope='thread-one';const calls=[];
 const panel=new NativeSettingsPanel({getScope:()=>scope,openThread:()=>{},api:async(path,body,options)=>{
  assert.equal(options.scope,'thread-one','each request retains its source');calls.push({path,body});const r=await f.api(path,body);if(r.status!==200)throw Error(r.data.error);return r.data;
 }});
 const button=label=>[...document.querySelectorAll('button')].find(n=>n.textContent===label);
 const click=label=>{const b=button(label);assert.ok(b,label);assert.equal(b.disabled,false);b.click();};
 await panel.open();await panel.load('mcp');click('Review sign-in');await until(()=>button('Start sign-in on original host'));
 assert.equal(f.held.length,0);click('Start sign-in on original host');await until(()=>f.held.length===1);f.held[0]();await until(()=>document.querySelector('textarea'));
 assert.match(document.querySelector('textarea').value,/PRIVATE_URL/);assert.match(document.body.textContent,/callback may not work from this device/);assert.doesNotMatch(document.body.textContent,/Native sign-in completed/);
 f.event({name:'fixture',threadId:'thread-one',loginId:'login-one',success:true});await until(()=>f.db.prepare('SELECT state FROM native_mcp_auth').get().state==='completed');click('Reload sign-in receipt');await until(()=>document.body.textContent.includes('Native sign-in completed'));
 assert.equal(document.querySelector('textarea'),null);assert.equal(calls.filter(c=>c.body).length,1);
 // Dismissing an inspected pending flow clears the private URL immediately.
 click('Start sign-in on original host');await until(()=>f.held.length===2);f.held[1]({loginId:'login-two'});await until(()=>document.querySelector('textarea'));
 click('I inspected the host; review dismissal');click('Dismiss inspected sign-in receipt');assert.equal(document.querySelector('textarea'),null);
 await until(()=>document.body.textContent.includes('Receipt dismissed without cancellation'));
 click('Reload sign-in receipt');await until(()=>button('Start sign-in on original host'));
 // Account boundary removes the already-rendered URL and dialog.
 click('Start sign-in on original host');await until(()=>f.held.length===3);f.held[2]({loginId:'login-three'});await until(()=>document.querySelector('textarea'));
 panel.event({type:'nativeAuthBoundary'});assert.equal(document.querySelector('textarea'),null);assert.equal(document.querySelector('[aria-label="MCP sign-in"]'),null);
 f.event({name:'fixture',threadId:'thread-one',loginId:'login-three',success:true});await until(()=>f.db.prepare("SELECT state FROM native_mcp_auth ORDER BY at DESC,rowid DESC").get().state==='completed');
 await panel.open();await panel.load('mcp');click('Review sign-in');await until(()=>button('Start sign-in on original host'));
 // New explicit flow, then navigate while its original reply is pending.
 click('Start sign-in on original host');await until(()=>f.held.length===4);
 panel.closeForNavigation();scope='another-host-thread';f.held[3]({loginId:'login-four'});await until(()=>f.db.prepare("SELECT state FROM native_mcp_auth ORDER BY at DESC,rowid DESC").get().state==='pending');
 assert.equal(document.querySelector('[aria-label="MCP sign-in"]'),null);assert.equal(document.querySelector('textarea'),null);assert.ok(!document.body.textContent.includes('PRIVATE_URL'));assert.equal(f.held.length,4);
});
