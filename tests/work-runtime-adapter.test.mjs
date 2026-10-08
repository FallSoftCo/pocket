import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {WorkAdmission} from '../server/work-admission.mjs';
import {WorkRuntimeAdapter} from '../server/work-runtime-adapter.mjs';
function fixture(call){
 const db=new DatabaseSync(':memory:');const work=new WorkAdmission({db});
 work.accept({id:'unit',taskId:'task',threadId:'thread',instruction:'Reconcile the accepted operation receipt; do not replay it',acceptance:'exact-result',eventId:'accepted',source:'user'});
 const a=work.claim({complete:true,observedAt:Date.now(),activeThreadIds:[]});
 const calls=[];const runtime={call:async(method,params)=>{calls.push({method,params});return call?.(method,params)||{turn:{id:'native'}};}};
 return {db,work,a,calls,adapter:new WorkRuntimeAdapter({work,runtime})};
}
test('dispatch only the persisted bounded instruction, preserving runtime settings',async()=>{
 const f=fixture();assert.equal((await f.adapter.dispatch(f.a.id)).submitted,true);
 assert.deepEqual(f.calls,[{method:'turn/start',params:{threadId:'thread',clientUserMessageId:f.a.id,input:[{type:'text',text:f.work.unit('unit').instruction}]}}]);
 assert.equal((await f.adapter.dispatch(f.a.id)).submitted,false);assert.equal(f.calls.length,1);f.db.close();
});
test('concurrent joins issue one native request',async()=>{
 let resolve;const f=fixture(()=>new Promise(r=>resolve=r));const first=f.adapter.dispatch(f.a.id);
 assert.equal((await f.adapter.dispatch(f.a.id)).submitted,false);resolve({turn:{id:'native'}});await first;assert.equal(f.calls.length,1);f.db.close();
});
test('lost ACK and restart never resubmit; exact history acceptance rejoins',async()=>{
 const f=fixture(()=>{throw Error('timeout after send');});await f.adapter.dispatch(f.a.id);
 const restarted=new WorkRuntimeAdapter({work:f.work,runtime:{call:()=>{throw Error('Must not resend');}}});
 assert.equal((await restarted.dispatch(f.a.id)).submitted,false);
 assert.equal((await restarted.reconcile(f.a.id,{complete:true,thread:{id:'thread',turns:[{id:'turn',items:[{type:'userMessage',clientId:f.a.id}]}]}})).matched,true);
 assert.equal(f.work.attempt(f.a.id).turn_id,'turn');assert.equal(f.calls.length,1);f.db.close();
});
test('incomplete, unrelated, missing and duplicate history cannot invent acceptance',async()=>{
 const f=fixture(()=>{throw Error('lost ACK');});await f.adapter.dispatch(f.a.id);
 for(const candidate of [{complete:false,thread:{id:'thread'}},{complete:true,thread:{id:'other'}},{complete:true,thread:{id:'thread',turns:[]}},{complete:true,thread:{id:'thread',turns:[{id:'wrong',items:[{type:'agentMessage',clientId:f.a.id}]}]}},{complete:true,thread:{id:'thread',turns:['a','b'].map(id=>({id,items:[{type:'userMessage',clientId:f.a.id}]}))}}])assert.equal((await f.adapter.reconcile(f.a.id,candidate)).matched,false);
 assert.equal(f.work.attempt(f.a.id).state,'unknown');f.db.close();
});
test('cancel before dispatch sends nothing',async()=>{
 const f=fixture();f.work.cancel('unit');assert.equal((await f.adapter.dispatch(f.a.id)).submitted,false);assert.equal(f.calls.length,0);f.db.close();
});
test('cancel while native ACK is pending interrupts exact late turn but retains slot',async()=>{
 let resolve;const f=fixture(method=>method==='turn/start'?new Promise(r=>resolve=r):{});
 const p=f.adapter.dispatch(f.a.id);f.work.cancel('unit');resolve({turn:{id:'late'}});await p;
 assert.deepEqual(f.calls[1],{method:'turn/interrupt',params:{threadId:'thread',turnId:'late'}});
 assert.equal(f.work.attempt(f.a.id).state,'stopping');assert.equal(f.work.unit('unit').state,'stopping');f.db.close();
});
test('native turn completion cannot automatically accept results or start successor',async()=>{
 const f=fixture();await f.adapter.dispatch(f.a.id);
 f.work.settleAttempt(f.a.id,{turnId:'native',status:'completed',receiptId:'terminal',effectsReconciled:false});
 assert.equal(f.work.attempt(f.a.id).state,'unknown');assert.equal((await f.adapter.dispatch(f.a.id)).submitted,false);assert.equal(f.calls.length,1);f.db.close();
});

test('independent adapter instances cannot resend an attempt across the durable send boundary',async()=>{
 let resolve;const f=fixture(()=>new Promise(r=>resolve=r));
 const other=new WorkRuntimeAdapter({work:new WorkAdmission({db:f.db}),runtime:{call:()=>{throw Error('Duplicate submission');}}});
 const p=f.adapter.dispatch(f.a.id);assert.equal((await other.dispatch(f.a.id)).submitted,false);
 resolve({turn:{id:'only-turn'}});await p;assert.equal(f.calls.length,1);f.db.close();
});
