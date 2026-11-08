import test from 'node:test';import assert from 'node:assert/strict';
import {NativeHistory,HistoryReader} from '../web/native-history.mjs';import {SessionStore} from '../web/session-store.mjs';
const row=(n,text,kind='message')=>({id:'item:'+n,key:[n,1,'i'+n],row:{id:'turn/i'+n,turnId:'turn',kind,text,version:1}});
const page=(id,entries,before=null)=>({thread:{id},historyHead:{epoch:'runtime-one'},timeline:{native:1,entries,rows:entries.flatMap(e=>e.row?[e.row]:[]),before,hasEarlier:before!==null}});
test('source cursor bridges retained native islands without painting a disconnected old page as current',()=>{
 const h=new NativeHistory();h.accept(page('s',[row(1,'old')],'old-before'),'s');
 assert.deepEqual(h.accept(page('s',[row(9,'latest user','user'),row(10,'latest answer')],'bridge'),'s').rows.map(r=>r.text),['latest user','latest answer']);
 const bridged=h.accept(page('s',[row(1,'old'),row(2,'middle')],'old-before'),'s',{cursor:'bridge'});
 assert.deepEqual(bridged.rows.map(r=>r.text),['old','middle','latest user','latest answer']);assert.equal(bridged.before,'old-before');
 assert.equal(h.live({id:'turn/i10',kind:'message',text:'new live answer',version:9}),true);
 assert.equal(h.accept(page('s',[row(9,'latest user','user'),row(10,'stale')],'bridge'),'s').rows.at(-1).text,'new live answer');
 assert.equal(h.live({id:'unpositioned',text:'must not paint',version:10}),false);
});
test('native and legacy session protocols coexist and authorization failures never downgrade',async()=>{
 const calls=[];const reader=new HistoryReader(async(path,_,options)=>{calls.push({path,options});if(path.includes('denied'))throw Object.assign(Error('Forbidden'),{status:403});if(path.includes('legacy/native'))throw Object.assign(Error('Legacy'),{status:409,code:'NATIVE_TIMELINE_UNSUPPORTED'});if(path.includes('legacy'))return {thread:{id:'legacy'},timeline:{rows:[],before:'exact-legacy',hasEarlier:true}};return page('modern',[row(2,'request','user'),row(3,'answer')]);});
 await reader.read('legacy');await reader.read('modern');assert.equal(reader.protocols.get('legacy'),'turns');assert.equal(reader.protocols.get('modern'),'native');
 await reader.read('legacy',{before:'exact-legacy',background:true});assert.ok(calls.at(-1).path.endsWith('before=exact-legacy'));assert.equal(calls.at(-1).options.timeout,5000);
 await assert.rejects(reader.read('denied'),/Forbidden/);assert.equal(calls.filter(c=>c.path.includes('denied')).length,1);
});
test('one session entity supplies canonical user/answer order, catalog invalidation and local latest navigation',()=>{
 const s=new SessionStore();s.catalog([{id:'s',previewRole:'codex',preview:'a'}],s.ticket());s.catalog([{id:'s',previewRole:'codex',preview:'b'}],s.ticket());assert.deepEqual(s.catalogChanges,['s']);
 s.head('s',page('s',[row(1,'old passage')],'old-before'),{ticket:s.ticket()});
 s.head('s',page('s',[row(9,'question','user'),row(10,'answer')],'older'),{ticket:s.ticket()});
 assert.deepEqual(s.get('s').rows.map(r=>r.kind),['user','message']);
 s.head('s',page('s',[row(19,'new head')],'new-before'),{ticket:s.ticket(),anchor:'turn/i1'});assert.equal(s.get('s').rows.at(-1).text,'old passage');
 assert.equal(s.followLatest('s').rows.at(-1).text,'new head');
 assert.throws(()=>s.head('s',{thread:{id:'s'},timeline:{rows:[]}},{older:true}),/protocol changed/);
});

test('inactive history eviction preserves the selected page and lightweight catalog identity',()=>{const s=new SessionStore();s.protect('selected');s.head('selected',page('selected',[row(1,'keep selected')]),{ticket:s.ticket()});for(let n=0;n<40;n++)s.head('other'+n,page('other'+n,[row(1,'other')]),{ticket:s.ticket()});assert.equal(s.get('selected').rows[0].text,'keep selected');assert.equal(s.entity('other0').thread.id,'other0');assert.equal(s.has('other0'),false);assert.ok([...s.entities.values()].filter(e=>e.page).length<=32);});

import {fixture} from './web-browser-fixture.mjs';
test('authenticated HTTP gateway feeds native and legacy sessions into the same source-bound store without task writes',async t=>{
 const f=await fixture({port:0,origin:'http://browser.test',observationPages:{'parent-123':page('parent-123',[row(9,'question','user'),row(10,'answer')])}});
 t.after(()=>f.close());const target=f.gatewayAddress;
 const paired=await fetch(target+'/web/pair',{method:'POST',headers:{Origin:'http://browser.test','Content-Type':'application/json'},body:'{"code":"synthetic"}'});
 const cookie=paired.headers.get('set-cookie').split(';')[0];
 const reader=new HistoryReader(async path=>{const response=await fetch(target+path,{headers:{Cookie:cookie}});const data=await response.json();if(!response.ok)throw Object.assign(Error(data.error),{status:response.status,code:data.code});return data;});
 const store=new SessionStore();for(const id of ['parent-123','other-123'])store.head(id,await reader.read(id),{ticket:store.ticket()});
 assert.deepEqual(store.get('parent-123').rows.map(r=>r.text),['question','answer']);assert.equal(store.get('other-123').protocol,'turns');
 assert.equal(reader.protocols.get('parent-123'),'native');assert.equal(f.writes.filter(w=>!['/api/pair'].includes(w.path)).length,0);
});

test('a repaired source generation replaces larger old positions and releases unreachable coverage',()=>{
 const h=new NativeHistory();h.accept(page('mac',[row(900,'stale old answer')],'old'),'mac');
 const repaired=page('mac',[row(20,'current question','user'),row(21,'current answer')],'current');repaired.historyHead.epoch='after-repair';
 const result=h.accept(repaired,'mac');assert.deepEqual(result.rows.map(r=>r.text),['current question','current answer']);assert.equal(h.components.length,1);assert.equal(result.before,'current');
});
