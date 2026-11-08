import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {WorkRecord} from '../server/work-record.mjs';
import {displayItem} from '../server/timeline.mjs';
import {ThreadHistory} from '../server/history.mjs';
const make=()=>{const db=new DatabaseSync(':memory:');return {db,record:new WorkRecord(db,{version:'test',clock:()=>500000})};};
const request={id:'u',type:'userMessage',content:[{type:'text',text:'Improve NextComp and publish the output'}]};
const answer={id:'a',type:'agentMessage',phase:'final_answer',text:'Added the work record. [Output](/project/result.md)'};
test('automatic capture deduplicates replay, preserves source and survives restart',()=>{
 const {db,record}=make();record.enroll({id:'t',cwd:'/project',name:'NextComp'});
 const thread={id:'t',turns:[{id:'one',startedAt:100,status:'completed',items:[request,answer]}]};
 record.remember(thread);record.remember(thread);
 const entry=new WorkRecord(db).list().entries[0];
 assert.equal(entry.items.length,2);assert.equal(entry.source.turnId,'one');assert.equal(entry.cwd,'/project');assert.equal(entry.source_at,100000);assert.equal(entry.version,'test');assert.equal(entry.items[1].evidence,'agent-reported');
});
test('unrelated global events, reasoning and command output are not copied',()=>{
 const account=make();assert.doesNotThrow(()=>account.record.observe({method:'account/updated',params:{}}));
 const {db,record}=make();record.observe({method:'item/completed',params:{threadId:'unrelated',turnId:'x',item:request}});assert.equal(record.list().entries.length,0);
 record.enroll({id:'t'});
 record.item('t','x',{id:'secret',type:'reasoning',summary:['private reasoning']});
 record.item('t','x',{id:'cmd',type:'commandExecution',command:'credential command',aggregatedOutput:'secret log',exitCode:1});
 record.item('t','x',{id:'files',type:'fileChange',status:'failed',changes:[{path:'/project/new.md',diff:'secret diff'}]});
 const serialized=JSON.stringify(record.list());assert.doesNotMatch(serialized,/private reasoning|credential command|secret log|secret diff/);assert.match(serialized,/failed/);assert.equal(db.prepare('SELECT COUNT(*) n FROM work_record_items').get().n,2);
});
test('history order, cursor pagination and follow-ups use source time rather than backfill order',()=>{
 const {record}=make();record.enroll({id:'t'});
 record.turn('t',{id:'new',startedAt:200,status:'completed',items:[answer]});
 record.turn('t',{id:'old',startedAt:100,status:'completed',items:[request]});
 const page=record.list({limit:1});assert.equal(page.entries[0].id,'new');assert.equal(page.entries[0].continuation,true);
 const earlier=record.list({before:page.before});assert.equal(earlier.entries[0].id,'old');assert.equal(earlier.entries[0].continuation,false);assert.equal(earlier.before,null);
 assert.throws(()=>record.list({before:'bad'}),/Invalid/);
});
test('bounded read-only backfill resumes its cursor and never downgrades completed live turns',async()=>{
 const {db,record}=make();record.enroll({id:'t'});record.turn('t',{id:'x',status:'completed',items:[answer]});
 const calls=[];const history={read:async(meta,opts)=>{calls.push({meta,opts});return {id:'t',turns:[{id:'x',status:'inProgress',items:[]}],_pocketPage:{hasEarlier:true,before:'page2'}};}};
 await record.scan(history,()=>({historyMode:'paginated'}));await record.scan(history,()=>({}));
 assert.equal(calls[0].opts.preferPaging,true);assert.equal(calls[0].opts.maxItems,80);assert.equal(calls[1].opts.before,'page2');assert.equal(record.list().entries[0].status,'completed');assert.equal(record.list().coverage.backfilling,1);
 assert.equal(db.prepare('SELECT cursor FROM work_record_sessions').get().cursor,'page2');
});
test('display-compacted history keeps user words, output and file paths without diffs',()=>{
 const {record}=make();record.enroll({id:'t'});
 for(const item of [request,answer,{id:'f',type:'fileChange',changes:[{path:'result.md',diff:'internal diff'}]}])record.item('t','one',displayItem(item,'one'));
 const items=record.list().entries[0].items;assert.equal(items[0].text,request.content[0].text);assert.deepEqual(items[2].paths,['result.md']);
});
test('team room/direct records remain device and environment scoped with observed-message provenance',()=>{
 const {record}=make(),command={path:'teams/team/history',query:{task:'task',visibility:'direct:worker'}};
 record.team('phone','env1',command,{data:[{id:1,author:'you',body:'Fix it'}]});record.team('phone','env1',command,{data:[{id:1,author:'you',body:'Fix it'}]});
 record.team('other','env1',command,{data:[{id:2,body:'private other device'}]});record.team('phone','env2',command,{data:[{id:1,body:'different environment'}]});
 assert.equal(record.teamList('phone').length,2);assert.doesNotMatch(JSON.stringify(record.teamList('phone')),/private other device/);assert.equal(record.teamList('phone')[1].visibility,'direct:worker');assert.equal(record.teamList('phone')[1].evidence,'message-observed');
});

test('composed history scan reports exact availability, advances fairly and recovers durable and missed history without work admission',async()=>{
 const db=new DatabaseSync(':memory:');let now=1000;
 const record=new WorkRecord(db,{version:'test',clock:()=>now});
 const ids=['a-temporary','b-durable','c-healthy'],calls=[],faults=new Map();
 const known=new Map(ids.map(id=>[id,{id,ephemeral:id==='a-temporary',historyMode:'paginated',status:{type:'notLoaded'},cwd:'/private/workspace',name:'Private task title'}]));
 for(const id of ids)record.enroll(known.get(id));
 record.remember({id:'b-durable',turns:[{id:'old',status:'completed',items:[{id:'saved',type:'agentMessage',text:'Previously observed output'}]}]});
 const cursor='page:'+Buffer.from('native-durable-cursor').toString('base64url');
 db.prepare('UPDATE work_record_sessions SET cursor=? WHERE id=?').run(cursor,'b-durable');
 db.exec("CREATE TABLE outgoing(id TEXT PRIMARY KEY,thread_id TEXT,state TEXT);INSERT INTO outgoing VALUES('receipt-original','b-durable','unknown')");
 const refusal=(id,code=-32600)=>Object.assign(Error(`thread not loaded: ${id}`),{rpc:{code,message:`thread not loaded: ${id}`,data:{privateToken:'do-not-log-this'}}});
 faults.set(ids[0],refusal(ids[0]));faults.set(ids[1],refusal(ids[1]));
 let healthyText='Healthy observed output';
 const history=new ThreadHistory({async call(method,params){
  calls.push({method,params});assert.ok(['thread/turns/list','thread/items/list'].includes(method),'scan cannot invoke a work or resume API');
  if(faults.has(params.threadId))throw faults.get(params.threadId);
  if(method==='thread/turns/list')return {data:[{id:'turn',status:'completed'}],nextCursor:null,backwardsCursor:'anchor'};
  return {data:[{turnId:'turn',item:{id:'answer',type:'agentMessage',text:params.threadId==='c-healthy'?healthyText:'Recovered output'}}],nextCursor:null};
 }});
 const scan=()=>{now+=15000;return record.scan(history,id=>known.get(id));};
 const expectFailure=async(id,code,nativeRpcCode)=>{
  await assert.rejects(scan(),error=>{
   assert.equal(error.code,code);
   assert.deepEqual(error.workRecordDiagnostic,{source:{backend:'codex',threadId:id},operation:'history/read',code,nativeRpcCode});
   assert.doesNotMatch(JSON.stringify(error.workRecordDiagnostic),/Private task|private\/workspace|do-not-log-this|thread not loaded/);
   return true;
  });
 };
 await expectFailure('a-temporary','THREAD_EPHEMERAL_UNAVAILABLE',-32600);
 await expectFailure('b-durable','THREAD_HISTORY_UNAVAILABLE',-32600);
 assert.equal(db.prepare('SELECT cursor FROM work_record_sessions WHERE id=?').get('b-durable').cursor,cursor);
 assert.equal(record.list().entries.find(e=>e.session==='b-durable').items[0].text,'Previously observed output');
 await scan();assert.ok(record.list().entries.some(e=>e.session==='c-healthy'),'failed earlier sessions cannot starve healthy work');
 assert.equal(db.prepare('SELECT hydrated FROM work_record_sessions WHERE id=?').get('c-healthy').hydrated,1);
 // Even matching error text is not unavailable-session evidence under another
 // native code. The cached notLoaded status must not manufacture that evidence.
 faults.set('a-temporary',refusal('a-temporary',-32001));
 await expectFailure('a-temporary','WORK_RECORD_HISTORY_READ_FAILED',-32001);
 faults.delete('b-durable');const beforeRecovery=calls.length;await scan();
 assert.equal(calls[beforeRecovery].params.cursor,'native-durable-cursor','retry resumes the original bounded read position');
 assert.equal(db.prepare('SELECT hydrated FROM work_record_sessions WHERE id=?').get('b-durable').hydrated,1);
 assert.ok(record.list().entries.some(e=>e.session==='b-durable'&&e.items.some(i=>i.text==='Recovered output')));
 healthyText='Missed event recovered through reconciliation';await scan();
 assert.ok(record.list().entries.some(e=>e.session==='c-healthy'&&e.items.some(i=>i.text===healthyText)),'already hydrated sessions remain eligible for missed-history reconciliation');
 faults.delete('a-temporary');await scan();
 assert.equal(db.prepare('SELECT hydrated FROM work_record_sessions WHERE id=?').get('a-temporary').hydrated,1,'cached notLoaded alone cannot suppress a successful native read');
 assert.deepEqual({...db.prepare('SELECT * FROM outgoing').get()},{id:'receipt-original',thread_id:'b-durable',state:'unknown'});
 assert.equal(db.prepare('SELECT count(*) AS n FROM work_record_sessions').get().n,3);db.close();
});

test('scan diagnostics preserve source identity and omit arbitrary metadata and transport failure text',async()=>{
 const {db,record}=make();record.enroll({id:'original'});let reads=0;
 const history={async read(){reads++;throw Object.assign(Error('private-token-and-path'),{code:'CODEX_TIMEOUT'});}};
 await assert.rejects(record.scan(history,()=>({id:'another',ephemeral:true})),error=>{
  assert.deepEqual(error.workRecordDiagnostic,{source:{backend:'codex',threadId:'original'},operation:'catalog/read',code:'WORK_RECORD_SOURCE_MISMATCH'});return true;
 });assert.equal(reads,0);
 await assert.rejects(record.scan(history,()=>({id:'original',ephemeral:true})),error=>{
  assert.deepEqual(error.workRecordDiagnostic,{source:{backend:'codex',threadId:'original'},operation:'history/read',code:'CODEX_TIMEOUT'});return true;
 });
 await assert.rejects(record.scan({read:async()=>({id:'another',turns:[]})},()=>({id:'original'})),{code:'WORK_RECORD_SOURCE_MISMATCH'});
 assert.equal(record.has('another'),false);assert.equal(db.prepare('SELECT hydrated FROM work_record_sessions').get().hydrated,0);
 await assert.rejects(record.scan(history,()=>{throw Error('invalid private metadata contents');}),error=>{
  assert.deepEqual(error.workRecordDiagnostic,{source:{backend:'codex',threadId:'original'},operation:'catalog/read',code:'WORK_RECORD_METADATA_UNAVAILABLE'});return true;
 });db.close();
});
