import test from 'node:test';
import assert from 'node:assert/strict';
import {threadReadError,readThreadMetadata} from '../server/thread-read-availability.mjs';
const rpc=(code,message)=>Object.assign(Error(message),{rpc:{code,message}});

test('read availability requires exact native code and source identity; it never converts auth, transport, or unknown errors',async()=>{
 const threadId='one',metadata={id:threadId,ephemeral:true},operation='thread/read';
 for(const error of [Error('thread not loaded: one'),rpc(-32001,'thread not loaded: one'),rpc(-32600,'thread not loaded: other'),rpc(-32600,'thread not loaded: one extra'),rpc(-32600,'thread other is archived'),Object.assign(Error('offline'),{status:503,code:'CODEX_DISCONNECTED'})]){
  assert.equal(threadReadError(error,{threadId,metadata,operation}),error);
 }
 for(const known of [null,{id:'other',ephemeral:true},{id:threadId,ephemeral:false}]){
  const error=threadReadError(rpc(-32600,'thread not loaded: one'),{threadId,metadata:known,operation});
  assert.equal(error.code,'THREAD_HISTORY_UNAVAILABLE');assert.deepEqual(error.readAvailability,{threadId,kind:'historyUnavailable',operation});
 }
 const codex={call:async()=>({thread:{id:'other'}})};
 await assert.rejects(readThreadMetadata(codex,threadId),{status:502,message:'History belongs to another session.'});
});

test('only the original turns-list refusal establishes initial message availability',()=>{
 const message='thread one is not materialized yet; thread/turns/list is unavailable before first user message';
 const error=()=>Object.assign(rpc(-32600,message),{rpcMethod:'thread/turns/list'});
 const context={threadId:'one',operation:'history/read'};
 const typed=threadReadError(error(),context);
 assert.equal(typed.status,409);assert.equal(typed.code,'THREAD_NOT_MATERIALIZED');
 assert.deepEqual(typed.readAvailability,{threadId:'one',kind:'notMaterialized',operation:'history/read'});
 for(const [failure,options] of [
  [error(),{...context,threadId:'other'}],[error(),{...context,operation:'thread/read'}],
  [Object.assign(error(),{rpcMethod:'thread/read'}),context],
  [rpc(-32600,message),context],
  [Object.assign(error(),{rpc:{code:-32001,message}}),context],
  [Object.assign(error(),{rpc:{code:-32600,message:message+' extra'}}),context],
 ])assert.equal(threadReadError(failure,options),failure);
 assert.equal(threadReadError(rpc(-32600,'thread not loaded: one'),context).code,'THREAD_HISTORY_UNAVAILABLE');
});
