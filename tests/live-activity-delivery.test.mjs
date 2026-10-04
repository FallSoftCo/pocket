import test from 'node:test';
import assert from 'node:assert/strict';
import {LiveActivityDelivery} from '../server/live-activity-delivery.mjs';
test('burst delivers latest value for each session without resetting its deadline',()=>{
 let now=0;const jobs=[],sent=[];
 const d=new LiveActivityDelivery((type,p)=>sent.push({type,...p}),{clock:()=>now,schedule:(fn,delay)=>{jobs.push({fn,at:now+delay});return jobs.length;}});
 d.preview('a',{preview:'first'});const deadline=jobs[0].at;
 for(let i=1;i<50;i++){now++;d.preview(i%2?'a':'b',{preview:String(i)});}
 assert.equal(jobs.length,1);assert.equal(jobs[0].at,deadline);
 jobs[0].fn();assert.deepEqual(sent.map(x=>[x.threadId,x.preview]),[['a','49'],['b','48']]);
 now=100;d.preview('a',{preview:'next'});assert.equal(jobs.length,2);
});
test('parallel traffic slows delivery within a bounded latency and activity samples latest state',()=>{
 let now=0;const jobs=[],sent=[];
 const d=new LiveActivityDelivery((type,p)=>sent.push(p),{clock:()=>now,schedule:(fn,delay)=>{jobs.push({fn,delay});return jobs.length;}});
 for(let i=0;i<30;i++)d.preview(String(i),{preview:'Working'});
 jobs[0].fn();d.preview('0',{preview:'new'});
 assert.ok(jobs[1].delay>jobs[0].delay);assert.ok(jobs[1].delay<=800);
 let items=['old'];d.activity(()=>items);items=['new'];jobs[2].fn();assert.deepEqual(sent.at(-1).items,['new']);assert.ok(jobs[2].delay<=1600);
});
