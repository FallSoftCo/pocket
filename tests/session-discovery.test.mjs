import test from 'node:test';import assert from 'node:assert/strict';import {DatabaseSync} from 'node:sqlite';import {SessionDiscovery,INTERACTIVE_SOURCES} from '../server/session-discovery.mjs';
function setup(call){const db=new DatabaseSync(':memory:');db.exec("CREATE TABLE watches(thread_id TEXT,enabled INTEGER);INSERT INTO watches VALUES('new-task',1),('archived-task',1),('hidden-controller',1);");const calls=[],discovery=new SessionDiscovery({db,codex:{call:async(m,p)=>{calls.push({m,p});return call(m,p);}},hidden:id=>id==='hidden-controller'});return {db,calls,discovery};}
test('status and reconnect metadata do not manufacture recent user activity',()=>{
  const f=setup(()=>({data:[]}));
  f.discovery.remember({id:'new-task',name:'Ongoing work',updatedAt:10,status:{type:'active'}});
  for(const type of ['notLoaded','idle','active','idle']){
    f.discovery.updateStatus('new-task',{type});
    const row=f.discovery.snapshot(false).data[0];
    assert.equal(row.updatedAt,10);assert.equal(row.status.type,type);assert.equal(row.discoveryPending,false);
  }
  f.discovery.remember({id:'new-task',name:'Ongoing work',updatedAt:20,status:{type:'idle'}});
  assert.equal(f.discovery.snapshot(false).data[0].updatedAt,20,'real indexed activity remains available');f.db.close();
});
test('state-index pagination includes top-level exec/app-server sessions and merges newly watched work without resume',async()=>{const f=setup((m,p)=>m==='thread/list'?p.cursor?{data:[{id:'older',recencyAt:1,updatedAt:10000}]}:{data:[{id:'listed',recencyAt:2,updatedAt:2}],nextCursor:'next'}:{thread:{id:p.threadId,recencyAt:3,updatedAt:3,path:p.threadId==='archived-task'?'/x/archived_sessions/a.jsonl':'/x/sessions/a.jsonl'}});const r=await f.discovery.list();assert.deepEqual(r.data.map(t=>t.id),['new-task','listed','older']);assert.ok(f.calls.every(c=>['thread/list','thread/read'].includes(c.m)));assert.deepEqual(f.calls[0].p.sourceKinds,INTERACTIVE_SOURCES);assert.equal(f.calls[1].p.cursor,'next');assert.ok(f.calls.filter(c=>c.m==='thread/list').every(c=>c.p.useStateDbOnly));f.db.close();});
test('archive filters and private coordinator threads remain excluded; unavailable metadata is not fabricated',async()=>{const f=setup((m)=>{if(m==='thread/list')return {data:[{id:'hidden-controller'},{id:'archived-task',updatedAt:8}]};throw Error('not found');});assert.deepEqual((await f.discovery.list({archived:true})).data.map(t=>t.id),['archived-task']);assert.ok(f.calls.every(c=>c.m==='thread/list'));f.db.close();});
test('created metadata remains immediately visible before a first response even if read index is not ready',async()=>{const f=setup(m=>m==='thread/list'?{data:[]}:Promise.reject(Error('state index pending')));f.discovery.remember({id:'new-task',name:'Check workstation temperatures and fans',cwd:'/project',updatedAt:10});const r=await f.discovery.list();assert.equal(r.data[0].id,'new-task');assert.equal(r.data[0].name,'Check workstation temperatures and fans');assert.equal(r.data[0].discoveryPending,true);f.discovery.markArchived('new-task',true);assert.ok(!(await f.discovery.list()).data.some(t=>t.id==='new-task'));f.db.close();});
test('live turn status clears created pending metadata before the first assistant response',async()=>{const f=setup(m=>m==='thread/list'?{data:[]}:Promise.reject(Error('index pending')));f.discovery.remember({id:'new-task',name:'Inspection',status:{type:'pending'}});f.discovery.updateStatus('new-task',{type:'active'});assert.equal((await f.discovery.list()).data[0].status.type,'active');f.discovery.updateStatus('new-task',{type:'idle'});assert.equal((await f.discovery.list()).data[0].status.type,'idle');f.db.close();});
test('a stalled list shares one refresh and returns remembered creation within a bounded deadline',async()=>{let release;const wait=new Promise(resolve=>{release=resolve});const f=setup(m=>m==='thread/list'?wait:{thread:{id:'new-task'}});f.discovery.remember({id:'new-task',name:'Temperature check',status:{type:'pending'}});const start=Date.now();const [a,b]=await Promise.all([f.discovery.list(),f.discovery.list()]);assert.equal(a.data[0].id,'new-task');assert.equal(b.data[0].id,'new-task');assert.ok(Date.now()-start<2500);assert.equal(f.calls.filter(c=>c.m==='thread/list').length,1);release({data:[]});await f.discovery.refreshing.get(false);f.db.close();});
test('reactivated old work keeps live recency and status across stale list hydration',async()=>{
 const f=setup(m=>m==='thread/list'?{data:[{id:'old',updatedAt:1,status:{type:'idle'}}]}:{thread:{id:'new-task',updatedAt:2}});
 f.discovery.remember({id:'old',updatedAt:1,status:{type:'idle'}});
 const event=f.discovery.observe({method:'turn/started',params:{threadId:'old',turn:{id:'turn'}}},()=>50000);
 assert.deepEqual(event,{threadId:'old',activityAt:50000,status:{type:'active'},discoveryPending:false});
 let row=(await f.discovery.list()).data.find(t=>t.id==='old');assert.equal(row.activityAt,50000);assert.equal(row.status.type,'active');assert.equal(row.updatedAt,1);
 f.discovery.observe({method:'thread/status/changed',params:{threadId:'old',status:{type:'idle'}}},()=>60000);
 row=(await f.discovery.list()).data.find(t=>t.id==='old');assert.equal(row.activityAt,50000);assert.equal(row.status.type,'idle');
 f.discovery.observe({method:'item/agentMessage/delta',params:{threadId:'old',itemId:'reply',delta:'Now'}},()=>70000);
 row=(await f.discovery.list()).data.find(t=>t.id==='old');assert.equal(row.activityAt,70000);f.db.close();
});
test('unknown live sessions discover metadata before any first response without resume',async()=>{
 const f=setup((m,p)=>m==='thread/list'?{data:[]}:{thread:{id:p.threadId,name:'New work',updatedAt:1,status:{type:'idle'}}});
 f.discovery.observe({method:'turn/started',params:{threadId:'new-live'}},()=>90000);
 const thread=await f.discovery.discoverLive('new-live');assert.equal(thread.id,'new-live');assert.equal(thread.activityAt,90000);assert.equal(thread.status.type,'active');
 assert.ok((await f.discovery.list()).data.some(t=>t.id==='new-live'));assert.ok(f.calls.every(c=>['thread/list','thread/read'].includes(c.m)));f.db.close();
});
test('reconnect releases stale live status while retaining genuine activity time',async()=>{
 const f=setup(m=>m==='thread/list'?{data:[{id:'old',updatedAt:1,status:{type:'idle'}}]}:{thread:{id:'new-task',updatedAt:1}});
 f.discovery.remember({id:'old',updatedAt:1});f.discovery.observe({method:'turn/started',params:{threadId:'old'}},()=>123456);
 f.discovery.clearLiveStatus();const row=(await f.discovery.list()).data.find(t=>t.id==='old');
 assert.equal(row.activityAt,123456);assert.equal(row.status.type,'idle');f.db.close();
});
test('more than 200 live IDs prioritize genuine reactivation over insertion order and status noise',async()=>{
 const f=setup((m,p)=>m==='thread/list'?{data:[]}:{thread:{id:p.threadId,updatedAt:1}});
 for(let i=0;i<260;i++){
  const id='work-'+i;f.discovery.remember({id,name:'Work '+i,updatedAt:1});
  f.discovery.observe({method:'item/agentMessage/delta',params:{threadId:id,delta:'Progress'}},()=>1000+i);
 }
 f.discovery.observe({method:'turn/started',params:{threadId:'work-0'}},()=>50000);
 for(let i=0;i<300;i++)f.discovery.observe({method:'thread/status/changed',params:{threadId:'noise-'+i,status:{type:'notLoaded'}}},()=>60000+i);
 f.discovery.observe({method:'turn/started',params:{threadId:'hidden-controller'}},()=>99999);
 f.discovery.markArchived('work-259',true);
 const ids=f.discovery.knownIds();assert.equal(ids.length,200);assert.equal(ids[0],'work-0');assert.ok(!ids.includes('hidden-controller'));assert.ok(!ids.includes('work-259'));assert.ok(ids.every(id=>!id.startsWith('noise-')));
 const row=(await f.discovery.list()).data.find(t=>t.id==='work-0');assert.equal(row.name,'Work 0');assert.equal(row.activityAt,50000);assert.equal(row.status.type,'active');assert.equal(row.updatedAt,1);f.db.close();
});
test('persisted genuine activity keeps discovery priority after reconnect and live-cache eviction',()=>{
 const f=setup(()=>({data:[]}));f.discovery.remember({id:'reactivated',name:'Existing',activityAt:80000,updatedAt:1});
 for(let i=0;i<240;i++)f.discovery.remember({id:'metadata-'+i,name:'Metadata only',updatedAt:999999});
 f.discovery.live.clear();assert.ok(f.discovery.knownIds().includes('reactivated'));f.db.close();
});

test('cached child activation cannot resurrect a parent across reconnect; live work remains confirmed',async()=>{
 const db=new DatabaseSync(':memory:');const calls=[];const discovery=new SessionDiscovery({db,codex:{call:async(m,p)=>{calls.push(m);return m==='thread/list'?{data:[{id:'parent',createdAt:1,recencyAt:1,status:{type:'idle'}}]}:{thread:{id:p.threadId,source:'cli',createdAt:1,recencyAt:1,status:{type:'notLoaded'}}};}}});
 discovery.remember({id:'child',parentThreadId:'parent',createdAt:1,recencyAt:1,status:{type:'active'}});
 let result=await discovery.list();assert.equal(result.data.find(t=>t.id==='child').status.type,'notLoaded');
 discovery.observe({method:'turn/started',params:{threadId:'child'}},()=>50000);result=await discovery.list();assert.equal(result.data.find(t=>t.id==='child').status.type,'active');
 discovery.clearLiveStatus();result=await discovery.list();assert.equal(result.data.find(t=>t.id==='child').status.type,'notLoaded');assert.ok(calls.every(m=>m!=='thread/resume'));db.close();
});

test('older runtime recency rejection degrades to creation order, never metadata-update order',async()=>{
 const f=setup((m,p)=>{if(m==='thread/list'&&p.sortKey==='recency_at'){const e=Error('unknown variant recency_at');e.rpc={code:-32602};throw e;}return m==='thread/list'?{data:[{id:'older',createdAt:1,updatedAt:99999},{id:'recent',createdAt:10,updatedAt:10}]}:{thread:{id:p.threadId,createdAt:2}};});
 const r=await f.discovery.list();assert.equal(r.data[0].id,'recent');assert.ok(f.calls.filter(c=>c.m==='thread/list').every(c=>['recency_at','created_at'].includes(c.p.sortKey)));f.db.close();
});

test('malformed cached parent cycles terminate without resuming or dropping history',async()=>{
 const f=setup((m,p)=>m==='thread/list'?{data:[{id:'a',parentThreadId:'b',recencyAt:10}]}:{thread:{id:p.threadId,parentThreadId:p.threadId==='a'?'b':'a',recencyAt:10}});
 f.discovery.remember({id:'b',parentThreadId:'a',recencyAt:1});
 const result=await f.discovery.list();assert.ok(result.data.some(t=>t.id==='a'));assert.ok(result.data.some(t=>t.id==='b'));assert.ok(f.calls.every(c=>['thread/list','thread/read'].includes(c.m)));f.db.close();
});
test('renamed cached owners remain discoverable when native search is empty without faking active status',async()=>{
 const f=setup(()=>({data:[],nextCursor:null}));
 f.discovery.remember({id:'conference',name:'AcceleratorCON application and presentation',status:{type:'active'},recencyAt:100});
 f.discovery.remember({id:'hidden-controller',name:'AcceleratorCon worker'});
 const result=await f.discovery.list({searchTerm:'acceleratorcon'});
 assert.deepEqual(result.data.map(t=>t.id),['conference']);assert.equal(result.data[0].status.type,'notLoaded');
 f.discovery.observe({method:'turn/started',params:{threadId:'conference'}});
 assert.equal((await f.discovery.list({searchTerm:'AcceleratorCon'})).data[0].status.type,'active');
 assert.ok(f.calls.every(c=>c.m==='thread/list'));f.db.close();
});
