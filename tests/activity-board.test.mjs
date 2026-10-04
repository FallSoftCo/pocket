import test from 'node:test';import assert from 'node:assert/strict';import {ActivityBoard} from '../server/activity-board.mjs';
test('mission shows concurrent current actions and expires completed activity without histories',()=>{
 let now=1;const b=new ActivityBoard(()=>now);
 for(const id of ['a','b'])b.observe({method:'item/started',params:{threadId:'task',item:{id,type:'commandExecution',command:'npm test'}}});
 assert.equal(b.snapshot().length,2);assert.equal(b.snapshot()[0].state,'working');
 b.observe({method:'item/commandExecution/outputDelta',params:{threadId:'task',itemId:'a',delta:'23 tests passed'}});assert.match(b.snapshot().find(x=>x.itemId==='a').preview,/23 tests passed/);
 b.observe({method:'turn/completed',params:{threadId:'task'}});assert.ok(b.snapshot().every(x=>x.state==='finished'));
 now+=90001;assert.deepEqual(b.snapshot(),[]);
});
test('mission never exposes raw reasoning or unbounded output',()=>{
 const b=new ActivityBoard();b.observe({method:'item/started',params:{threadId:'task',item:{id:'thinking',type:'reasoning',text:'private reasoning'}}});assert.deepEqual(b.snapshot(),[]);
 b.observe({method:'item/agentMessage/delta',params:{threadId:'task',itemId:'reply',delta:'x'.repeat(10000)}});assert.equal(b.snapshot()[0].preview.length,280);
});
test('mission keeps useful work when thinking summaries and raw reasoning arrive',()=>{
 const b=new ActivityBoard();
 b.observe({method:'item/started',params:{threadId:'a',item:{id:'command',type:'commandExecution',command:'npm test'}}});
 b.observe({method:'item/reasoning/summaryTextDelta',params:{threadId:'a',itemId:'r',summaryIndex:0,delta:'Checking ergonomics'}});
 assert.equal(b.snapshot().length,1);assert.match(b.snapshot()[0].preview,/npm test/);
 b.observe({method:'item/reasoning/textDelta',params:{threadId:'a',itemId:'r',delta:'hidden'}});
 assert.equal(b.snapshot().length,1);assert.match(b.snapshot()[0].preview,/npm test/);
});
