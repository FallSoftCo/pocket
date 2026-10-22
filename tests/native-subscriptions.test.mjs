import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {NativeSubscriptions} from '../server/native-subscriptions.mjs';
test('live subscriptions are single-flight and only reuse an observed loaded active runtime',async()=>{
 const c=new EventEmitter();c.ready=true;const calls=[];
 c.call=async(method,params)=>{calls.push({method,params});return method==='thread/loaded/list'?{data:['active']}:{thread:{id:params.threadId}};};
 const s=new NativeSubscriptions(c);
 assert.equal(await s.observe({id:'idle',status:{type:'idle'}}),false);
 assert.equal(await s.observe({id:'cold',status:{type:'active'}}),false);
 const a=s.observe({id:'active',status:{type:'active'}}),b=s.observe({id:'active',status:{type:'active'}});
 assert.equal(a,b);assert.equal(await a,true);
 assert.equal(await s.observe({id:'active',status:{type:'active'}}),true);
 assert.deepEqual(calls,[{method:'thread/loaded/list',params:{}},{method:'thread/resume',params:{threadId:'active',excludeTurns:true}}]);
});
test('disconnect settles pending subscriptions and never retains an old connection generation',async()=>{
 const c=new EventEmitter();c.ready=true;let release;
 c.call=()=>new Promise(resolve=>{release=resolve;});
 const s=new NativeSubscriptions(c);const result=s.observe({id:'active',status:{type:'active'}});
 c.ready=false;c.emit('disconnected');assert.equal(await result,false);
 release({data:['active']});await new Promise(r=>setImmediate(r));assert.equal(s.subscribed.size,0);
});
