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
