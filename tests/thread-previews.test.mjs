import test from 'node:test';
import assert from 'node:assert/strict';
import {latestMessage,ThreadPreviews} from '../server/thread-previews.mjs';
test('preview chooses latest actual message, ignores commands and nontext inputs',()=>{
  assert.deepEqual(latestMessage({turns:[{items:[{type:'userMessage',content:[{type:'text',text:'Make a tree'},{type:'image',url:'private'}]},{type:'agentMessage',text:'  Made\n a tree. '},{type:'commandExecution',aggregatedOutput:'noise'}]}]}),{preview:'Made a tree.',previewRole:'assistant'});
  assert.deepEqual(latestMessage({turns:[{items:[{type:'agentMessage',text:'Done.'}]},{items:[{type:'userMessage',content:[{type:'text',text:'Make it blue'}]}]}]}),{preview:'Make it blue',previewRole:'user'});
  assert.equal(latestMessage({turns:[{items:[{type:'reasoning',text:'private'}]}]}),null);
});
test('preview hydration is bounded and does not resume sessions',async()=>{
 let live=0,max=0;const waiting=[];const changed=[];
 const p=new ThreadPreviews({read:async t=>{live++;max=Math.max(max,live);await new Promise(r=>waiting.push(r));live--;return {turns:[{items:[{type:'userMessage',content:'latest '+t.id}]}]};}},(id,value)=>changed.push({id,...value}));
 for(let i=0;i<5;i++)assert.equal(p.get({id:String(i),updatedAt:1,preview:'old'}).preview,'old');
 assert.equal(max,3);waiting.splice(0).forEach(r=>r());await new Promise(r=>setImmediate(r));waiting.splice(0).forEach(r=>r());await new Promise(r=>setImmediate(r));
 assert.equal(changed.length,5);assert.equal(p.get({id:'0',updatedAt:1}).preview,'latest 0');assert.equal(max,3);
});
test('live response deltas update cards and late hydration cannot overwrite them',async()=>{
 let resolve;const changed=[];
 const p=new ThreadPreviews({read:()=>new Promise(r=>resolve=r)},(id,v)=>changed.push({id,...v}));
 p.get({id:'task',updatedAt:1});
 p.observe({method:'item/agentMessage/delta',params:{threadId:'task',itemId:'reply',delta:'Checking '}});
 p.observe({method:'item/agentMessage/delta',params:{threadId:'task',itemId:'reply',delta:'the build.'}});
 assert.equal(changed.at(-1).preview,'Checking the build.');assert.equal(changed.at(-1).previewRole,'assistant');
 resolve({turns:[{items:[{type:'userMessage',content:'old request'}]}]});await new Promise(r=>setImmediate(r));
 assert.equal(p.get({id:'task',updatedAt:1}).preview,'Checking the build.');
 p.observe({method:'item/completed',params:{threadId:'task',item:{id:'reply',type:'agentMessage',text:'Build passed.'}}});
 assert.equal(changed.at(-1).preview,'Build passed.');
});
