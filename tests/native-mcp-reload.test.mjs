import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture} from './helpers/native-mcp-reload-fixture.mjs';
import {NativeMcpReload} from '../server/native-mcp-reload.mjs';
async function until(p){for(let n=0;n<200&&!p();n++)await new Promise(r=>setTimeout(r,5));assert.ok(p());}

test('paired host reload: explicit reviewed source, durable original intent, partial failure, account changes, unsupported Mac',async t=>{
 const f=await fixture(t);const catalog=(await f.api('/api/native/configuration?section=mcp&threadId=thread-one')).data;
 assert.equal(catalog.canReviewReload,true);assert.equal(f.held.length,0);
 let view=(await f.detail()).data;assert.match(view.notice,/across this host/);assert.match(view.notice,/partial changes/);
 const stale=f.input(view);f.setConfig();assert.equal((await f.api('/api/native/configuration',stale)).status,409);assert.equal(f.held.length,0);
 view=(await f.detail()).data;const body=f.input(view);
 assert.equal((await f.api('/api/native/configuration',body,'owner')).status,403);
 const pending=f.api('/api/native/configuration',body);await until(()=>f.held.length===1);
 assert.equal((await f.api('/api/native/configuration',body)).data.state,'dispatching');assert.equal((await f.detail()).data.canReload,false);
 f.setAccount(null);f.held[0]({error:true});const failed=(await pending).data;
 assert.equal(failed.state,'failed');assert.match(failed.notice,/already have changed/);assert.ok(!JSON.stringify(failed).includes('PRIVATE CONFIG'));
 f.setAccount('first-account');assert.equal((await f.detail()).data.canReload,true);
 assert.equal((await f.api('/api/native/configuration',body)).data.state,'failed');assert.equal(f.held.length,1);
 const old=f.input((await f.detail()).data);old.attemptId='attempt-two';f.setAccount('new-account');assert.equal((await f.api('/api/native/configuration',old)).status,409);
 f.codex.initializeResult.userAgent='codex_pocket/0.163.0 (Mac OS 26.6; arm64)';assert.equal((await f.detail()).status,422);
 assert.equal((await f.api('/api/native/configuration?section=mcp')).data.canReviewReload,false);
 assert.ok(f.calls.every(c=>['initialize','thread/read','config/read','mcpServerStatus/list','config/mcpServer/reload'].includes(c.method)));
});

test('lost reload transport and recovered receipt never replay; explicit inspected dismissal permits a new action',async t=>{
 const f=await fixture(t),body=f.input((await f.detail()).data);const pending=f.api('/api/native/configuration',body);await until(()=>f.held.length===1);
 f.codex.closeTransport();assert.equal((await pending).data.state,'unknown');await f.codex.connect();
 const recovered=new NativeMcpReload({db:f.db,codex:f.codex,admissions:f.options.admissions,source:f.native.mcpAuth});
 assert.equal(recovered.recent('phone','thread-one')[0].state,'unknown');
 assert.equal((await f.detail()).data.canReload,false);assert.equal((await f.api('/api/native/configuration',body)).data.state,'unknown');assert.equal(f.held.length,1);
 const dismiss={section:'mcp',action:'reloadAcknowledge',threadId:'thread-one',attemptId:body.attemptId};assert.equal((await f.api('/api/native/configuration',dismiss)).status,409);
 dismiss.confirmInspected=true;assert.equal((await f.api('/api/native/configuration',dismiss)).data.state,'acknowledged');assert.equal((await f.detail()).data.canReload,true);
 const next={...f.input((await f.detail()).data),attemptId:'attempt-two'},start=f.api('/api/native/configuration',next);await until(()=>f.held.length===2);f.held[1]();assert.equal((await start).data.state,'accepted');
});

test('reload caller timeout retains original late acknowledgement and prevents another host operation',async t=>{
 const f=await fixture(t),body=f.input((await f.detail()).data),start=f.api('/api/native/configuration',body);await until(()=>f.held.length===1);
 assert.equal((await start).data.state,'dispatching');assert.equal(f.codex.operationCapacityAvailable(),false);
 assert.equal((await f.detail()).data.canReload,false);assert.equal((await f.api('/api/native/configuration',body)).data.state,'dispatching');
 f.held[0]();await until(()=>f.db.prepare('SELECT state FROM native_mcp_reload').get().state==='accepted');
 const view=(await f.detail()).data;assert.equal(view.receipts[0].state,'accepted');assert.equal(view.canReload,true);assert.equal(f.held.length,1);
});
