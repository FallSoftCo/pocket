import test from 'node:test';
import assert from 'node:assert/strict';
import {fixture} from './helpers/native-mcp-auth-fixture.mjs';
import {NativeMcpAuth} from '../server/native-mcp-auth.mjs';
const tick=()=>new Promise(r=>setImmediate(r));
async function until(predicate){for(let n=0;n<100&&!predicate();n++)await new Promise(r=>setTimeout(r,5));assert.ok(predicate());}

test('paired HTTP review starts exactly one native OAuth attempt, correlates completion, redacts storage and forbids replay',async t=>{
 const f=await fixture(t);const catalog=await f.api('/api/native/configuration?section=mcp&threadId=thread-one');assert.equal(catalog.data.items[0].canReviewAuthentication,true);assert.ok(!JSON.stringify(catalog).includes('PRIVATE CONFIG'));
 const view=(await f.detail()).data;assert.equal(view.canStart,true);assert.equal(f.held.length,0);
 const body=f.input(view);assert.equal((await f.api('/api/native/configuration',body,'owner')).status,403);
 f.setConfig();assert.equal((await f.api('/api/native/configuration',body)).status,409);assert.equal(f.held.length,0);
 body.reviewToken=(await f.detail()).data.reviewToken;
 const start=f.api('/api/native/configuration',body);await until(()=>f.held.length===1);
 const pending=f.db.prepare('SELECT * FROM native_mcp_auth').get();assert.equal(pending.state,'dispatching');assert.equal(f.db.prepare("SELECT kind FROM human_admissions WHERE request_id='attempt-one'").get().kind,'mcp-oauth');
 const duplicate=await f.api('/api/native/configuration',body);assert.equal(duplicate.data.state,'dispatching');assert.equal(f.held.length,1);
 f.held[0]();const receipt=(await start).data;assert.equal(receipt.state,'pending');assert.match(receipt.authorizationUrl,/PRIVATE_URL/);
 assert.ok(!JSON.stringify(f.db.prepare('SELECT * FROM native_mcp_auth').all()).includes('PRIVATE_URL'));
 assert.equal((await f.detail()).data.canStart,false);
 f.event({name:'fixture',threadId:'thread-other',loginId:'login-one',success:true});await tick();assert.equal((await f.detail()).data.receipts[0].state,'pending');
 f.event({name:'fixture',threadId:'thread-one',success:true});await tick();assert.equal((await f.detail()).data.receipts[0].state,'pending');
 f.event({name:'fixture',threadId:'thread-one',loginId:'login-one',success:true});await until(()=>f.db.prepare('SELECT state FROM native_mcp_auth').get().state==='completed');
 const done=(await f.api('/api/native/configuration',body)).data;assert.equal(done.state,'completed');assert.equal(done.authorizationUrl,undefined);assert.equal(f.held.length,1);
 assert.ok(f.calls.every(c=>['initialize','thread/read','config/read','mcpServerStatus/list','mcpServer/oauth/login'].includes(c.method)));
});

test('lost transport and restart retain unknown auth without trusting unrelated or late completion',async t=>{
 const f=await fixture(t),body=f.input((await f.detail()).data);const start=f.api('/api/native/configuration',body);await until(()=>f.held.length===1);
 f.codex.closeTransport();assert.equal((await start).data.state,'unknown');
 await f.codex.connect();assert.equal((await f.api('/api/native/configuration',body)).data.state,'unknown');assert.equal(f.held.length,1);
 const restored=new NativeMcpAuth({...f.options,context:id=>f.native.context(id)});assert.equal(restored.recent('fixture','thread-one','phone')[0].state,'unknown');
 f.event({name:'fixture',threadId:'thread-one',loginId:'login-one',success:true});await tick();assert.equal((await f.detail()).data.canStart,false);
});

test('early correlated completion is retained; changed account and unsupported native versions cannot start',async t=>{
 const f=await fixture(t),view=(await f.detail()).data;f.setAccount('second-account');assert.equal((await f.api('/api/native/configuration',f.input(view))).status,409);assert.equal(f.held.length,0);
 f.setEarly();const body=f.input((await f.detail()).data),start=f.api('/api/native/configuration',body);await until(()=>f.held.length===1);f.held[0]();assert.equal((await start).data.state,'completed');
 f.codex.initializeResult.userAgent='codex_pocket/0.163.0 (Mac OS 26.6; arm64)';assert.equal((await f.detail()).status,409);assert.equal(f.held.length,1);
});

test('caller timeout retains the original OAuth observer and late reply without a second native start',async t=>{
 const f=await fixture(t),body=f.input((await f.detail()).data);const start=f.api('/api/native/configuration',body);await until(()=>f.held.length===1);
 const timedOut=await start;assert.equal(timedOut.data.state,'dispatching');assert.equal(f.codex.operationCapacityAvailable(),false);
 assert.equal((await f.api('/api/native/configuration',body)).data.state,'dispatching');assert.equal(f.held.length,1);
 f.held[0]();await until(()=>f.db.prepare('SELECT state FROM native_mcp_auth').get().state==='pending');
 assert.equal(f.codex.operationCapacityAvailable(),true);assert.match((await f.detail()).data.receipts[0].authorizationUrl,/PRIVATE_URL/);
});

test('unexposable URL keeps exact attempt identity; inspected dismissal preserves late outcome without replay',async t=>{
 const f=await fixture(t),body=f.input((await f.detail()).data);const start=f.api('/api/native/configuration',body);await until(()=>f.held.length===1);f.held[0]({url:'http://127.0.0.1/auth?private=LOCAL_ONLY'});
 const receipt=(await start).data;assert.equal(receipt.state,'pending');assert.equal(receipt.authorizationUrl,undefined);assert.equal(receipt.canAcknowledge,true);
 const dismiss={section:'mcp',action:'oauthAcknowledge',attemptId:body.attemptId,name:body.name,threadId:body.threadId};
 assert.equal((await f.api('/api/native/configuration',dismiss)).status,400);dismiss.confirmInspected=true;
 assert.equal((await f.api('/api/native/configuration',dismiss)).data.state,'acknowledged');assert.equal((await f.detail()).data.canStart,true);assert.equal(f.held.length,1);
 f.event({name:'fixture',threadId:'thread-one',loginId:'login-one',success:true});await until(()=>f.db.prepare('SELECT native_outcome FROM native_mcp_auth').get().native_outcome==='completed');
 const old=(await f.api('/api/native/configuration',body)).data;assert.equal(old.state,'acknowledged');assert.equal(old.nativeOutcome,'completed');assert.equal(f.held.length,1);
});

test('missing correlation identity remains inspectable unknown and old account does not block new account',async t=>{
 const f=await fixture(t),body=f.input((await f.detail()).data);const start=f.api('/api/native/configuration',body);await until(()=>f.held.length===1);f.held[0]({loginId:null});
 assert.equal((await start).data.state,'unknown');assert.equal((await f.detail()).data.canStart,false);
 f.setAccount('new-account');const other=(await f.detail()).data;assert.equal(other.canStart,true);assert.equal(other.receipts[0].authorizationUrl,undefined);assert.equal(other.receipts[0].canAcknowledge,false);
 f.setAccount('first-account');assert.equal((await f.api('/api/native/configuration',{...body,action:'oauthAcknowledge',confirmInspected:true})).data.state,'acknowledged');assert.equal((await f.detail()).data.canStart,true);assert.equal(f.held.length,1);
});

test('same-account cross-conversation blocker remains inspectable without leaking its original-device URL',async t=>{
 const f=await fixture(t),body=f.input((await f.detail()).data);const start=f.api('/api/native/configuration',body);await until(()=>f.held.length===1);f.held[0]();await start;
 f.db.exec("INSERT INTO devices VALUES('other-phone','Other phone')");
 const other=await f.api('/api/native/mcp-auth?threadId=thread-two&name=fixture',undefined,'other-phone');assert.equal(other.data.canStart,false);assert.equal(other.data.receipts[0].threadId,'thread-one');assert.equal(other.data.receipts[0].authorizationUrl,undefined);
 const inspected=await f.api('/api/native/configuration',{section:'mcp',action:'oauthAcknowledge',attemptId:body.attemptId,name:'fixture',threadId:'thread-one',confirmInspected:true},'other-phone');assert.equal(inspected.data.state,'acknowledged');
 assert.equal(f.db.prepare('SELECT device FROM native_mcp_auth').get().device,'phone');assert.match(f.db.prepare("SELECT principal FROM human_admission_actions WHERE action='inspected-uncertain-flow'").get().principal,/other-phone/);assert.equal(f.held.length,1);
});
