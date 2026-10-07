import test from 'node:test';import assert from 'node:assert/strict';
import {sessionIdentity,sessionWorkTime,compareSessionWork,requireDirectSessionInput} from '../server/session-catalog.mjs';
test('metadata reads/config timestamps cannot promote dormant inventory over last week work',()=>{
 const recent=Array.from({length:30},(_,i)=>({id:`recent-${i}`,createdAt:1000,recencyAt:2000+i,updatedAt:9000}));
 const dormant=Array.from({length:262},(_,i)=>({id:`old-${i}`,createdAt:10,recencyAt:100+i,updatedAt:999999+i}));
 const inventory=[...dormant,...recent].sort(compareSessionWork);
 assert.deepEqual(inventory.slice(0,30).map(t=>t.id),recent.reverse().map(t=>t.id));
 assert.equal(sessionWorkTime({createdAt:10,updatedAt:999999}),10000);
 assert.equal(sessionWorkTime({createdAt:10,recencyAt:100,activityAt:200000}),200000);
});
test('runtime parent identities support snake/camel serialized sources without inferring from titles',()=>{
 for(const source of [{subAgent:{thread_spawn:{parent_thread_id:'parent',agent_nickname:'worker',agent_role:'explorer'}}},JSON.stringify({subagent:{thread_spawn:{parent_thread_id:'parent'}}})]){
  const t=sessionIdentity({id:'child',source});assert.equal(t.parentThreadId,'parent');assert.equal(t.isChild,true);assert.equal(t.canAcceptDirectInput,false);
 }
 assert.equal(sessionIdentity({name:'subagent',source:'cli',forkedFromId:'fork'}).isChild,false);
 assert.equal(sessionIdentity({source:'subAgentReview'}).isChild,true);
});
test('unsupported child input fails before admission; explicit supported children remain usable',()=>{
 assert.throws(()=>requireDirectSessionInput({parentThreadId:'parent',canAcceptDirectInput:false}),e=>e.status===409&&/parent/.test(e.message));
 assert.throws(()=>requireDirectSessionInput({parentThreadId:'parent'}),e=>e.status===409);
 assert.doesNotThrow(()=>requireDirectSessionInput({parentThreadId:'parent',canAcceptDirectInput:true}));
 assert.doesNotThrow(()=>requireDirectSessionInput({source:'cli'}));
});
