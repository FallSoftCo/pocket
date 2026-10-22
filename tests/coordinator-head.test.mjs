import test from 'node:test';
import assert from 'node:assert/strict';
import {CoordinatorHeadReader} from '../server/coordinator-head.mjs';
const absent=()=>Object.assign(Error('thread/timeline/list is not supported yet'),{rpc:{code:-32601}});
test('observation uses bounded native summary only after typed per-thread capability refusal; UI chronology never falls back',async()=>{
 let nativeReads=0,calls=[],now=1000;
 const reader=new CoordinatorHeadReader({clock:()=>now,native:{async read(id){nativeReads++;if(id==='old')throw absent();return {data:[]}}},codex:{async call(method,args){calls.push({method,args});return {data:[{id:'turn2',status:'completed',items:[{id:'answer',type:'agentMessage',text:'A completed result',phase:'final_answer'}]},{id:'turn1',status:'completed',items:[{id:'request',type:'userMessage',content:[{type:'text',text:'Requested work'}]}]}],nextCursor:'earlier'}}}});
 const [a,b]=await Promise.all([reader.read('old',{observationOnly:true}),reader.read('old',{observationOnly:true})]);
 assert.deepEqual(a,b);assert.equal(calls.length,1);assert.equal(nativeReads,1);
 assert.deepEqual(a.observation.rows.filter(x=>x.type).map(x=>x.text),['Requested work','A completed result']);assert.equal(a.observation.evidenceOnly,true);assert.equal(a.page,undefined);
 assert.deepEqual(calls[0],{method:'thread/turns/list',args:{threadId:'old',limit:3,sortDirection:'desc',itemsView:'summary'}});
 await reader.read('old',{observationOnly:true});assert.equal(nativeReads,1);
 await reader.read('modern',{observationOnly:true});assert.equal(nativeReads,2);assert.equal(calls.length,2);
 await assert.rejects(reader.read('old'),/not supported/);assert.equal(calls.length,2);
 now+=61000;await reader.read('old',{observationOnly:true});assert.equal(nativeReads,4);
});
test('network/permission failures and malformed pages remain failures; no broad history read or resume',async()=>{
 let calls=0;
 const r=new CoordinatorHeadReader({native:{async read(){throw Object.assign(Error('denied'),{rpc:{code:-32001}})}},codex:{async call(){calls++}}});
 await assert.rejects(r.read('worker',{observationOnly:true}),/denied/);assert.equal(calls,0);
 const bad=new CoordinatorHeadReader({native:{async read(){throw absent()}},codex:{async call(){return {data:[{id:'duplicate',status:'completed',items:[]},{id:'duplicate',status:'completed',items:[]}],nextCursor:null}}}});
 await assert.rejects(bad.read('worker',{observationOnly:true}),/Invalid native observation turn/);
});
