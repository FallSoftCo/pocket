import test from 'node:test';
import assert from 'node:assert/strict';
import {QuestionActions} from '../server/question-actions.mjs';
function fixture(method='item/tool/requestUserInput'){
 const m={id:12,method,params:{threadId:'t',questions:[{id:'a'},{id:'b'}]}};
 const pending=new Map([['12',m]]),writes=[],resolved=[];
 const row={kind:'question',thread_id:'t',request_id:'12',resolved_at:null};
 const codex={answer:(...args)=>writes.push(args)};
 return {m,pending,writes,row,codex,resolved,actions:new QuestionActions({pending,codex,db:{prepare:()=>({get:()=>row})},resolve:(...a)=>resolved.push(a)})};
}
test('skip sends explicit empty arrays for every field; repeated delivery is idempotent until authoritative resolution',()=>{
 const f=fixture();f.actions.skipNotification(3);f.actions.skipNotification(3);
 assert.deepEqual(f.writes,[[12,{answers:{a:{answers:[]},b:{answers:[]}}}]]);assert.equal(f.pending.size,1);assert.equal(f.resolved.length,0);
 f.pending.clear();assert.equal(f.actions.skipNotification(3).expired,true);assert.equal(f.resolved.length,1);
});
test('skip refuses approvals and mismatched linked threads',()=>{
 const f=fixture('item/commandExecution/requestApproval');assert.throws(()=>f.actions.answer('12',{skip:true}),{status:400});
 f.row.kind='approval';assert.throws(()=>f.actions.skipNotification(3),{status:400});assert.equal(f.writes.length,0);
 const g=fixture();g.row.thread_id='other';assert.throws(()=>g.actions.skipNotification(3),{status:409});
});
test('partial answers never submit; network failure remains retryable',()=>{
 const f=fixture();assert.throws(()=>f.actions.answer('12',{answers:{a:'yes'}}),{status:400});assert.equal(f.writes.length,0);
 f.codex.answer=()=>{throw new Error('offline');};assert.throws(()=>f.actions.skipNotification(3),/offline/);
 f.codex.answer=(...a)=>f.writes.push(a);f.actions.skipNotification(3);assert.equal(f.writes.length,1);
});
