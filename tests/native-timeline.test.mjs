import test from 'node:test';
import assert from 'node:assert/strict';
import {NativeTimelineReader,validateNativeTimelinePage} from '../server/native-timeline.mjs';
test('native pages retain boundary/item ties, opaque cursors and single-flight source reads',async()=>{
 const original={data:[
  {type:'turnStarted',position:7,turnId:'turn'},
  {type:'item',position:7,turnId:'turn',item:{id:'user',type:'userMessage'}},
  {type:'item',position:11,turnId:'turn',item:{id:'answer',type:'agentMessage'}}
 ],nextCursor:'opaque-not-a-client-position',activeRealtimeSessionAtPageStart:null};
 let release;const gate=new Promise(resolve=>release=resolve);const calls=[];
 const reader=new NativeTimelineReader({connect:async()=>{},call:async(method,params)=>{calls.push({method,params});await gate;return original;}});
 const one=reader.read('thread'),two=reader.read('thread');release();
 const [a,b]=await Promise.all([one,two]);
 assert.deepEqual(calls,[{method:'thread/timeline/list',params:{threadId:'thread',limit:64}}]);
 assert.deepEqual(a,original);a.data[0].turnId='mutated';assert.equal(b.data[0].turnId,'turn');
 assert.throws(()=>validateNativeTimelinePage({...original,data:[original.data[2],original.data[1]]}),/canonical order/);
 assert.throws(()=>validateNativeTimelinePage(original,{cursor:original.nextCursor}),/repeated/);
});
