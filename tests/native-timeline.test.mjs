import test from 'node:test';
import assert from 'node:assert/strict';
import {NativeTimelineReader,validateNativeTimelinePage,projectNativeTimeline} from '../server/native-timeline.mjs';
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

test('native display keeps full public messages, omits raw reasoning and preserves invisible coverage keys',()=>{
 const text='long public answer '.repeat(2000);
 const source={data:[
  {type:'turnStarted',position:1,turnId:'t',startedAt:3},
  {type:'item',position:1,turnId:'t',item:{id:'u',type:'userMessage',content:[{text:'question'}]}},
  {type:'item',position:2,turnId:'t',item:{id:'hidden',type:'reasoning',summary:[],content:['private raw reasoning']}},
  {type:'item',position:3,turnId:'t',item:{id:'a',type:'agentMessage',text}},
  {type:'realtime',position:4,item:{id:'voice',realtimeSessionId:'s',type:'transcriptSegment',role:'assistant',text:'spoken result'}},
  {type:'turnCompleted',position:5,turnId:'t',status:'completed'}
 ],nextCursor:'native opaque cursor',activeRealtimeSessionAtPageStart:null};
 const result=projectNativeTimeline(source,{threadId:'thread',revision:8});
 assert.equal(result.entries.length,6);assert.equal(result.entries[2].row,null);
 assert.equal(result.entries[3].row.text,text);assert.deepEqual(result.entries[1].key,[1,1,'u']);
 assert.equal(result.entries[1].row._inputOrder,'canonical');
 assert.equal(result.before,source.nextCursor);assert.equal(result.rows.at(-2).text,'spoken result');
 assert.ok(!JSON.stringify(result).includes('private raw reasoning'));
});
