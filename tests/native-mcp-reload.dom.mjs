// Actual settings UI → authenticated HTTP → SQLite intent → native WebSocket fixture.
import test from 'node:test';
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import {fixture} from './helpers/native-mcp-reload-fixture.mjs';
import {NativeSettingsPanel} from '../web/native-settings.mjs';
if(!process.env.NEXTCOMP_JSDOM_MODULE)throw Error('Use an existing JSDOM installation.');
const {JSDOM}=await import(pathToFileURL(process.env.NEXTCOMP_JSDOM_MODULE));
async function until(p){for(let n=0;n<200&&!p();n++)await new Promise(r=>setTimeout(r,5));assert.ok(p());}
test('explicit MCP reload review, host switch during original dispatch, exact receipt recovery and account dismissal preserve draft',async t=>{
 const dom=new JSDOM('<body><textarea id="draft">Keep my original draft</textarea></body>'),previous=globalThis.document;globalThis.document=dom.window.document;
 dom.window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};dom.window.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new dom.window.Event('close'));};
 t.after(()=>{globalThis.document=previous;dom.window.close();});
 const f=await fixture(t);let source='thread-one';const requests=[];
 const panel=new NativeSettingsPanel({getScope:()=>source,openThread:()=>{},api:async(path,body,options)=>{
  requests.push({path,body,scope:options.scope});assert.equal(options.scope,'thread-one');const r=await f.api(path,body);if(r.status!==200)throw Error(r.data.error);return r.data;
 }});
 const button=label=>[...document.querySelectorAll('button')].find(n=>n.textContent===label);
 const click=label=>{const b=button(label);assert.ok(b,label);assert.equal(b.disabled,false);b.click();};
 await panel.open();await panel.load('mcp');assert.equal(f.held.length,0);click('Review host MCP reload');await until(()=>button('Reload MCP across this host'));
 assert.match(document.body.textContent,/loaded conversations/);assert.match(document.body.textContent,/partial changes/);assert.equal(f.held.length,0);
 click('Reload MCP across this host');await until(()=>f.held.length===1);
 assert.equal(f.db.prepare('SELECT state FROM native_mcp_reload').get().state,'dispatching');
 panel.closeForNavigation();source='beta-thread';f.held[0]();await until(()=>f.db.prepare('SELECT state FROM native_mcp_reload').get().state==='accepted');
 assert.equal(document.querySelector('[aria-label="Host MCP reload"]'),null);assert.equal(document.getElementById('draft').value,'Keep my original draft');
 source='thread-one';await panel.open();await panel.load('mcp');click('Review host MCP reload');await until(()=>document.body.textContent.includes('Native reload acknowledged'));
 assert.equal(f.held.length,1);click('Check reload receipts');await until(()=>button('Reload MCP across this host'));assert.equal(f.held.length,1);
 panel.event({type:'nativeAuthBoundary'});assert.equal(document.querySelector('[aria-label="Host MCP reload"]'),null);
 f.codex.initializeResult.userAgent='codex_pocket/0.163.0 (Mac OS 26.6; arm64)';await panel.open();await panel.load('mcp');assert.equal(button('Review host MCP reload'),undefined);
 assert.equal(document.getElementById('draft').value,'Keep my original draft');assert.equal(requests.filter(r=>r.body?.action==='reload').length,1);
});
