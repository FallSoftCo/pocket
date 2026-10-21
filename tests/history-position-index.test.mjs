import test from 'node:test';
import assert from 'node:assert/strict';
import {HistoryPositionIndex} from '../server/history-position-index.mjs';
import {ThreadHistory} from '../server/history.mjs';
test('complete ordinary turn read resolves hidden input with rebuilt provider identity without extra RPC',async()=>{
 const calls=[];const codex={call:async(method)=>{calls.push(method);if(method!=='thread/turns/list')throw Error('unexpected provider read');return {data:[{id:'turn',items:[{id:'canonical',type:'userMessage',clientId:'send',content:[{type:'text',text:'private input'}]},{id:'answer',type:'agentMessage',text:'reply'}]}]};}};
 const history=new ThreadHistory(codex);history.turnOnlyThreads.add('thread');
 assert.equal((await history.locateItemSuccessor('thread',{turnId:'turn',itemId:'live',clientId:'send',knownItemIds:['answer']})).available,false);
 const page=await history.read({id:'thread'},{maxItems:1});assert.equal(page.turns[0].items.length,1);
 assert.deepEqual(await history.locateItemSuccessor('thread',{turnId:'turn',itemId:'live',clientId:'send',knownItemIds:['answer']}),{available:true,insertBefore:'turn/answer',cursor:null});
 assert.deepEqual(calls,['thread/turns/list']);assert.ok(!JSON.stringify([...history.positions.turns]).includes('private input'));
});
test('scope, ambiguity, chronological successor and expiration are conservative',()=>{
 let now=0;const index=new HistoryPositionIndex({now:()=>now,ttl:10});
 index.observe('a','t',[{id:'prior'},{id:'u',kind:'user',clientId:'send'},{id:'next'}]);
 const request={itemId:'old',clientId:'send',knownItemIds:['prior','next']};
 assert.equal(index.resolve('a','t',request).insertBefore,'t/next');assert.equal(index.resolve('b','t',request),null);
 index.observe('a','t',[{id:'u',kind:'user',clientId:'send'},{id:'u2',kind:'user',clientId:'send'}]);assert.equal(index.resolve('a','t',request),null);
 now=11;assert.equal(index.resolve('a','t',request),null);
});
test('bounded capacity and explicit clear discard evidence',()=>{
 const index=new HistoryPositionIndex({maxTurns:1,maxItems:2});index.observe('a','t',[{id:'1'}]);index.observe('b','t',[{id:'2'}]);assert.equal(index.turns.size,1);
 index.observe('c','t',[{id:'1'},{id:'2'},{id:'3'}]);assert.equal(index.turns.size,1);index.clear();assert.equal(index.turns.size,0);
});

test('only missing observed turn advertises a history-dependent retry',async()=>{
 const history=new ThreadHistory({});history.turnOnlyThreads.add('thread');
 const request={turnId:'turn',itemId:'live',clientId:'send',knownItemIds:['answer']};
 assert.equal((await history.locateItemSuccessor('thread',request)).awaitingHistory,true);
 history.positions.observe('thread','turn',[{id:'other',kind:'user',clientId:'other'}]);
 assert.equal((await history.locateItemSuccessor('thread',request)).awaitingHistory,undefined);
});

test('bounded ordinary read indexes receipt-enriched complete input before slicing',async()=>{
 const calls=[];
 const history=new ThreadHistory({call:async method=>{
   calls.push(method);
   assert.equal(method,'thread/turns/list');
   return {data:[{id:'turn',items:[
     {id:'canonical',type:'userMessage',content:[{type:'text',text:'input'}]},
     {id:'answer',type:'agentMessage',text:'reply'}
   ]}]};
 }},{identity:{snapshot:thread=>({...thread,turns:thread.turns.map(turn=>({...turn,items:turn.items.map(item=>item.type==='userMessage'?{...item,clientId:'send'}:item)}))})}});
 history.turnOnlyThreads.add('thread');
 for(let n=0;n<2;n++){
   if(n===1)history.fullSnapshotThreads.add('thread');
   const page=await history.read({id:'thread'},{maxItems:1});
   assert.equal(page.turns[0].items.length,1);
   assert.equal(page.turns[0].items[0].id,'answer');
   assert.deepEqual(await history.locateItemSuccessor('thread',{turnId:'turn',itemId:'different-live-id',clientId:'send',knownItemIds:['answer']}),
     {available:true,insertBefore:'turn/answer',cursor:null});
 }
 assert.deepEqual(calls,['thread/turns/list','thread/turns/list']);
});

test('opaque-only native pagination locates a delayed input without full reads or chronology guesses',async()=>{
 const calls=[];
 const history=new ThreadHistory({call:async(method,params)=>{
   calls.push({method,params});assert.equal(method,'thread/items/list');
   if(params.cursor&&typeof params.cursor==='object')throw Error('invalid type: map, expected a string');
   const item=(id,type,clientId)=>({turnId:'turn',item:{id,type,clientId,text:'identical text'}});
   if(!params.cursor)return {data:[item('latest','agentMessage'),item('reply','agentMessage')],nextCursor:'older'};
   assert.equal(params.cursor,'older');
   return {data:[item('new-provider-input','userMessage','send'),item('prior','agentMessage')]};
 }});
 const result=await history.locateItemSuccessor('thread',{turnId:'turn',itemId:'live-provider-input',clientId:'send',knownItemIds:['prior','reply','latest']});
 assert.deepEqual(result,{available:true,insertBefore:'turn/reply',cursor:null});
 assert.equal(calls.length,3);
 assert.ok(calls.slice(1).every(c=>c.params.sortDirection==='desc'&&c.params.limit===8));
});
test('opaque position continuation binds the exact input and visible anchors',async()=>{
 const history=new ThreadHistory({call:async(_method,params)=>({data:[{item:{id:'r'+(params.cursor||0),type:'agentMessage',text:'output'}}],nextCursor:String(Number(params.cursor||0)+1)})});
 history.itemAnchorsSupported=false;
 const args={turnId:'turn',itemId:'input',clientId:'send',knownItemIds:['r0']};
 const page=await history.locateItemSuccessor('thread',args);
 assert.ok(page.cursor.startsWith('position-scan:'));
 await assert.rejects(history.locateItemSuccessor('thread',{...args,clientId:'other',cursor:page.cursor}),/Invalid item position/);
 await assert.rejects(history.locateItemSuccessor('thread',{...args,knownItemIds:['other'],cursor:page.cursor}),/Invalid item position/);
 const next=await history.locateItemSuccessor('thread',{...args,cursor:page.cursor});
 assert.notEqual(next.cursor,page.cursor);
});

test('partial ordering windows cannot prove the absence of a known successor',()=>{
 const index=new HistoryPositionIndex();
 const request={itemId:'input',knownItemIds:['reply']};
 index.observe('thread','turn',[{id:'input',kind:'user'}]);
 assert.equal(index.resolve('thread','turn',request),null);
 index.observe('thread','turn',[{id:'input',kind:'user'}],{complete:true});
 assert.deepEqual(index.resolve('thread','turn',request),{available:true,insertBefore:null,cursor:null});
});
