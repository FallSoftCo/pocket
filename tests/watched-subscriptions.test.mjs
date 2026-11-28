import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {NativeSubscriptions} from '../server/native-subscriptions.mjs';
import {restoreWatchedSubscriptions} from '../server/watched-subscriptions.mjs';

test('reconnect subscribes enabled loaded active watches without restoring historical sessions',async()=>{
 const codex=new EventEmitter();codex.ready=true;const calls=[];
 const loaded=['active','disabled','idle','attached','raced'];
 codex.call=async(method,params)=>{
  calls.push({method,params});
  if(method==='thread/loaded/list')return {data:loaded};
  if(method==='thread/read')return {thread:{id:params.threadId,status:{type:params.threadId==='idle'?'idle':params.threadId==='raced'?'notLoaded':'active'}}};
  assert.equal(method,'thread/resume');return {thread:{id:params.threadId}};
 };
 const subscriptions=new NativeSubscriptions(codex),attached=new Set(['attached']),observed=[];
 const watches=['active','disabled','idle','attached','raced','cold'].map(thread_id=>({thread_id,enabled:thread_id==='disabled'?0:1}));
 await restoreWatchedSubscriptions({watches,codex,subscriptions,attached,onSubscribed:t=>observed.push(t.id)});
 assert.deepEqual(observed,['active']);assert.ok(attached.has('active'));
 assert.deepEqual(calls.filter(c=>c.method==='thread/resume'),[{method:'thread/resume',params:{threadId:'active',excludeTurns:true}}]);
 assert.deepEqual(calls.filter(c=>c.method==='thread/read').map(c=>c.params.threadId).sort(),['active','idle','raced']);
 assert.ok(calls.every(c=>!['thread/turns/list','thread/items/list','turn/start'].includes(c.method)));
 await restoreWatchedSubscriptions({watches,codex,subscriptions,attached});
 assert.equal(calls.filter(c=>c.method==='thread/resume').length,1);
});

test('one stale watch cannot stop a healthy loaded watch from receiving events',async()=>{
 const codex=new EventEmitter();codex.ready=true;
 codex.call=async(method,p)=>{
  if(method==='thread/loaded/list')return {data:['bad','good']};
  if(p.threadId==='bad')throw Error('History temporarily unavailable');
  return {thread:{id:p.threadId,status:{type:'active'}}};
 };
 const attached=new Set();
 await restoreWatchedSubscriptions({watches:[{thread_id:'bad',enabled:1},{thread_id:'good',enabled:1}],codex,subscriptions:new NativeSubscriptions(codex),attached});
 assert.deepEqual([...attached],['good']);
});
