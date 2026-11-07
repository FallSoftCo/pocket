import test from 'node:test';
import assert from 'node:assert/strict';
import {productionFixture,nativePage,question,until,wireCheck} from './helpers/production-native-fixture.mjs';
import {HistoryReader} from '../web/native-history.mjs';
const {SessionStore}=await import(process.env.NEXTCOMP_TEST_SESSION_STORE_MODULE||new URL('../web/session-store.mjs',import.meta.url).href);
import {createWebClient} from '../server/web-client.mjs';
import {WebEnvironments,scopedId} from '../server/web-environments.mjs';

const path='/api/threads/thread-shared/native-timeline';

test('production native wire to HTTP: cold history replacement rejects delayed pages, reconnect keeps canonical source order',{timeout:20000},async t=>{
 const f=await productionFixture(t);await f.watch();const reader=new HistoryReader(f.api),store=new SessionStore(),id=f.thread.id;
 // The read starts with an empty client cache. Invalidate only through a real
 // native event forwarded by the server, then release the old response.
 const gate=f.barrier(),ticket=store.ticket();const pending=reader.read(id);pending.catch(()=>{});t.after(gate.release);await gate.entered;
 f.emit({method:'thread/reverted',params:{threadId:id}});
 const event=await until(()=>f.events.find(e=>e.type==='conversationChanged'&&e.kind==='revert'),Boolean,'native replacement broadcast');
 store.replaceHistory(id,event.historyHead);f.page=nativePage('Replacement answer',2);gate.release();
 const stale=await pending;assert.throws(()=>store.head(id,stale,{ticket}),/history changed/);assert.equal(store.get(id),null);
 store.head(id,await reader.read(id),{ticket:store.ticket()});assert.deepEqual(store.get(id).rows.map(r=>r.text),['Synthetic request','Replacement answer']);
 f.dropNext=true;await assert.rejects(reader.read(id),e=>e.status===503&&e.code==='CODEX_DISCONNECTED');assert.equal(reader.protocols.get(id),'native');
 assert.equal(store.get(id).rows.at(-1).text,'Replacement answer','outage must retain the last validated page');
 f.page=nativePage('After reconnect',30,'native:older/opaque+cursor');
 store.head(id,await reader.read(id),{ticket:store.ticket()});assert.deepEqual(store.get(id).rows.map(r=>r.text),['Synthetic request','After reconnect'],'disconnected old island cannot precede fresh head');
 const cursor=store.get(id).before;f.page=nativePage('Earlier answer',20,null);
 store.head(id,await reader.read(id,{before:cursor}),{older:true,cursor,ticket:store.ticket()});
 assert.deepEqual(store.get(id).rows.map(r=>r.text),['Synthetic request','Earlier answer','Synthetic request','After reconnect']);
 assert.equal(f.calls.findLast(c=>c.method==='thread/timeline/list').params.cursor,cursor);
 assert.ok(!f.calls.some(c=>['thread/resume','turn/start','thread/turns/list'].includes(c.method)));f.assertClean();
});

test('paired request answer crosses real backend and native transport; recycled native IDs cannot reuse decisions',{timeout:20000},async t=>{
 const f=await productionFixture(t);await f.watch();f.emit(question());
 let page=await until(()=>f.api(path),p=>p.pending.length===1,'first pending native request');const old=page.pending[0];assert.ok(old._pocketUI.token);
 const body={answers:{choice:'First decision'},requestToken:old._pocketUI.token};
 assert.equal((await f.request('/api/requests/73/answer',body)).status,200);
 await until(()=>f.answers.length,n=>n===1,'first native answer');assert.deepEqual(f.answers[0],{id:73,result:{answers:{choice:{answers:['First decision']}}}});
 assert.equal((await f.request('/api/requests/73/answer',body)).status,200);assert.equal(f.answers.length,1);
 assert.equal((await f.api(path)).pending.length,1,'HTTP acceptance is not native resolution');
 f.disconnect();await until(()=>f.events.some(e=>e.type==='status'&&e.connected===false),Boolean,'native disconnect event');
 await f.api(path);f.emit(question());page=await until(()=>f.api(path),p=>p.pending.length===1,'reused native request');
 assert.notEqual(page.pending[0]._pocketUI.token,old._pocketUI.token);
 assert.equal((await f.request('/api/requests/73/answer',body)).status,409);assert.equal(f.answers.length,1,'stale answer must not reach the new native request');
 assert.equal((await f.request('/api/requests/73/answer',{answers:{choice:'Current decision'},requestToken:page.pending[0]._pocketUI.token})).status,200);
 await until(()=>f.answers.length,n=>n===2,'reconnected native answer');
 f.emit({method:'serverRequest/resolved',params:{threadId:f.thread.id,requestId:73}});
 await until(()=>f.api(path),p=>p.pending.length===0,'authoritative native resolution');
 assert.equal((await f.request('/api/requests/73/answer',body)).status,409);assert.equal(f.answers.length,2);f.assertClean();
});

test('unknown conversation control survives actual process restart and blocks human send without native replay',{timeout:20000},async t=>{
 const f=await productionFixture(t);f.loseCompact=true;
 const op='/api/threads/thread-shared/conversation-operations',body={id:'compact-original',kind:'compact'};
 const first=await f.request(op,body);assert.equal(first.status,200);assert.equal(first.data.state,'unknown');
 assert.equal(f.calls.filter(c=>c.method==='thread/compact/start').length,1);
 await f.restart();const receipt=await f.api(op+'/compact-original');assert.equal(receipt.state,'unknown');assert.equal(receipt.id,body.id);
 assert.equal((await f.api(op,body)).state,'unknown');
 const blocked=await f.request('/api/threads/thread-shared/reply',{id:'reply-never-dispatched',text:'Must remain blocked'});assert.equal(blocked.status,409);assert.match(blocked.data.error,/uncertain/);
 assert.equal((await f.request(op,{...body,kind:'review'})).status,409,'same operation ID cannot change meaning');
 assert.equal((await f.request(op+'/compact-original/acknowledge',{inspected:false})).status,400);
 assert.equal((await f.api(op+'/compact-original/acknowledge',{inspected:true})).state,'acknowledged');
 assert.equal((await f.api(op,body)).state,'acknowledged');assert.equal(f.calls.filter(c=>c.method==='thread/compact/start').length,1);
 assert.ok(!f.calls.some(c=>['turn/start','turn/steer','review/start'].includes(c.method)));f.assertClean();
});

test('two production authorities with identical native IDs remain separate across federated source changes and outage',{timeout:25000},async t=>{
 const a=await productionFixture(t,{name:'Source A'}),b=await productionFixture(t,{name:'Source B'});
 const environments=new WebEnvironments({secure:false,profiles:{[a.deviceId]:[{id:'remote',name:'Source B',base:b.base+'/',token:b.token,deviceId:b.deviceId}]}});
 const gateway=createWebClient({backend:a.base,origin:'http://browser.test',secure:false,environments});
 await new Promise(r=>gateway.server.listen(0,'127.0.0.1',r));t.after(async()=>{for(const s of gateway.sockets.clients)s.terminate();gateway.server.closeAllConnections();await new Promise(r=>gateway.server.close(r));});
 const base='http://127.0.0.1:'+gateway.server.address().port;
 // Pair through the production gateway using a genuine one-time backend code.
 const owner=JSON.parse(await (await import('node:fs/promises')).readFile(a.dir+'/secrets.json')).adminToken;
 const pair=await a.request('/api/pairing',{},owner);
 const response=await fetch(base+'/web/pair',{method:'POST',headers:{Origin:'http://browser.test','Content-Type':'application/json'},body:JSON.stringify({code:pair.data.code})});assert.equal(response.status,200);
 const cookie=response.headers.get('set-cookie').split(';')[0];
 // Browser has its own paired identity, so provision its exact profile entry.
 const status=await (await fetch(base+'/api/status',{headers:{Cookie:cookie}})).json();
 environments.profiles[status.deviceId]=environments.profiles[a.deviceId];
 const reader=new HistoryReader(async path=>{const r=await fetch(base+path,{headers:{Cookie:cookie},signal:AbortSignal.timeout(7000)});const value=await r.json();if(!r.ok)throw Object.assign(Error(value.error),{status:r.status,code:value.code});return value;});
 const store=new SessionStore(),local=a.thread.id,remote=scopedId('remote',local);
 for(const id of [local,remote,local])store.head(id,await reader.read(id),{ticket:store.ticket()});
 assert.equal(store.get(local).rows.at(-1).text,'Source A');assert.equal(store.get(remote).rows.at(-1).text,'Source B');
 b.dropNext=true;await assert.rejects(reader.read(remote));
 assert.equal(store.get(local).rows.at(-1).text,'Source A');assert.equal(store.get(remote).rows.at(-1).text,'Source B');
 b.page=nativePage('Source B recovered',40);store.head(remote,await reader.read(remote),{ticket:store.ticket()});
 assert.equal(store.get(remote).rows.at(-1).text,'Source B recovered');assert.equal(store.get(local).rows.at(-1).text,'Source A');
 assert.equal(reader.protocols.get(remote),'native');assert.equal(reader.protocols.get(local),'native');a.assertClean();b.assertClean();
});

test('generated contract rejects malformed fixture input instead of granting false native proof',()=>{
 assert.throws(()=>wireCheck('ToolRequestUserInputParams',{threadId:'thread-shared',questions:[]}),/ToolRequestUserInputParams/);
 const invalid=nativePage();invalid.data[0].position='ordered-looking-string';assert.throws(()=>wireCheck('ThreadTimelineListResponse',invalid),/ThreadTimelineListResponse/);
});
