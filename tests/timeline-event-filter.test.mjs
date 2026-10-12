import test from 'node:test';
import assert from 'node:assert/strict';
import {LiveTimeline} from '../server/timeline.mjs';
test('unsupported private events cannot cause display reloads or invent live turns',()=>{
 const timeline=new LiveTimeline();
 for(const method of ['item/reasoning/textDelta','item/reasoning/contentDelta','item/unknown/delta']){
  assert.equal(timeline.ingest({method,params:{threadId:'synthetic-thread',turnId:'synthetic-turn',itemId:'private-item',delta:'synthetic private payload'}}),null);
  assert.equal(timeline.version,0);assert.equal(timeline.threads.size,0);assert.equal(timeline.bytes,0);
 }
});
test('supported missing public output still requests bounded history recovery',()=>{
 const timeline=new LiveTimeline();
 for(const method of ['item/agentMessage/delta','item/plan/delta','item/commandExecution/outputDelta','item/reasoning/summaryTextDelta']){
  const update=timeline.ingest({method,params:{threadId:'synthetic-thread',turnId:'synthetic-turn',itemId:'missing-public-item',delta:'Public update'}});
  assert.equal(update.reload,true);assert.ok(update.version>0);
 }
});
