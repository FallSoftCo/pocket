import test from 'node:test';
import assert from 'node:assert/strict';
import {latestMessage,ThreadPreviews,sessionInteraction} from '../server/thread-previews.mjs';
test('only a live started user message supplies origin-identified session ordering intent',()=>{
  const params={threadId:'existing',item:{id:'user-1',type:'userMessage',content:[]}};
  assert.deepEqual(sessionInteraction({method:'item/started',params}),{threadId:'existing',interactionId:'user-1'});
  for(const method of ['item/completed','thread/status/changed','turn/completed','item/agentMessage/delta'])assert.equal(sessionInteraction({method,params}),null);
  assert.equal(sessionInteraction({method:'item/started',params:{...params,item:{id:'tool',type:'commandExecution'}}}),null);
  assert.equal(sessionInteraction({method:'item/started',params:{...params,item:{type:'userMessage'}}}),null);
  assert.equal(sessionInteraction({method:'item/started',params:{item:params.item}}),null);
});
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
test('cards follow commands, progress, edits and then the latest answer',async()=>{
 const {latestActivity}=await import('../server/thread-previews.mjs');const changed=[];
 const p=new ThreadPreviews({read:async()=>({turns:[]})},(id,v)=>changed.push(v));
 p.observe({method:'item/started',params:{threadId:'work',item:{id:'cmd',type:'commandExecution',command:'npm test'}}});
 assert.equal(changed.at(-1).preview,'Running · npm test');assert.equal(changed.at(-1).previewKind,'command');
 p.observe({method:'item/commandExecution/outputDelta',params:{threadId:'work',itemId:'cmd',delta:'old line\n23 tests passed\n'}});
 assert.match(changed.at(-1).preview,/23 tests passed/);
 p.observe({method:'item/started',params:{threadId:'work',item:{id:'edit',type:'fileChange',changes:[{path:'/project/app.ts'}]}}});
 assert.equal(changed.at(-1).preview,'Editing · app.ts');
 p.observe({method:'item/agentMessage/delta',params:{threadId:'work',itemId:'answer',delta:'All checks passed.'}});
 assert.equal(changed.at(-1).preview,'All checks passed.');assert.equal(changed.at(-1).previewRole,'assistant');
 assert.equal(latestActivity({turns:[{items:[{type:'userMessage',content:'Fix it'},{type:'commandExecution',command:'npm test',status:'inProgress'}]}]}).previewKind,'command');
});
test('thinking summaries stream by part and raw reasoning never becomes a preview',async()=>{
 const {latestActivity}=await import('../server/thread-previews.mjs');const changed=[];
 const p=new ThreadPreviews({read:async()=>({turns:[]})},(_,v)=>changed.push(v));
 p.observe({method:'item/started',params:{threadId:'work',item:{id:'r',type:'reasoning',summary:[],content:['hidden']}}});
 assert.equal(changed.at(-1).preview,'Thinking…');
 for(const delta of ['Comparing ','the layouts'])p.observe({method:'item/reasoning/summaryTextDelta',params:{threadId:'work',itemId:'r',summaryIndex:0,delta}});
 assert.equal(changed.at(-1).preview,'Thinking · Comparing the layouts');
 p.observe({method:'item/reasoning/textDelta',params:{threadId:'work',itemId:'r',delta:'hidden'}});
 assert.equal(changed.at(-1).preview,'Thinking · Comparing the layouts');
 p.observe({method:'item/reasoning/summaryTextDelta',params:{threadId:'work',itemId:'r',summaryIndex:1,delta:'Checking thumb reach'}});
 assert.equal(changed.at(-1).preview,'Thinking · Checking thumb reach');
 assert.equal(latestActivity({turns:[{items:[{type:'userMessage',content:'request'},{type:'reasoning',summary:['Checking thumb reach'],content:['hidden']}]}]}).preview,'Thinking · Checking thumb reach');
});
test('cold historical cards keep metadata context without triggering history reads',()=>{let reads=0;const p=new ThreadPreviews({read:async()=>{reads++;return {turns:[]}}},()=>{});const value=p.get({id:'old',preview:'An earlier input',updatedAt:1},{hydrate:false});assert.equal(value.preview,'An earlier input');assert.equal(reads,0);assert.equal(p.queue.size,0);assert.equal(p.running.size,0);});
