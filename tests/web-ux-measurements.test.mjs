import test from 'node:test';
import assert from 'node:assert/strict';
import {UxMeasurements} from '../web/ux-measurements.mjs';

test('foreground cache-first layout and later fresh layout are distinct from response timing',()=>{
 let at=0;const ux=new UxMeasurements({now:()=>at});ux.foreground(true);
 const open=ux.begin('open'),request=ux.begin('history_request');
 at=12;ux.layout(open,false);at=90;ux.mark(request,'history_response');ux.end(request,'complete');
 let report=ux.snapshot();assert.equal(report.metrics.open_cached_first_content_layout.p95Ms,12);assert.equal(report.metrics.history_response.p95Ms,90);assert.equal(report.metrics.open_fresh_content_layout,undefined);
 at=135;ux.layout(open,true);ux.layout(open,true);
 report=ux.snapshot();assert.equal(report.metrics.open_first_content_layout.count,1);assert.equal(report.metrics.open_fresh_content_layout.p95Ms,135);assert.equal(report.counts.open_complete,1);
});
test('superseded navigation, cancellation and background retirement cannot become fast successes',()=>{
 let at=0;const ux=new UxMeasurements({now:()=>at});ux.foreground(true);
 const old=ux.begin('open');ux.end(old,'superseded');const selected=ux.begin('open');
 const cancelled=ux.begin('history_request');ux.end(cancelled,'cancelled');ux.mark(cancelled,'history_response');
 at=5;ux.layout(old,true);assert.deepEqual(ux.snapshot().metrics,{});
 ux.foreground(false);at=8;ux.layout(selected,true);assert.equal(ux.begin('send'),0);
 const report=ux.snapshot();assert.equal(report.counts.open_superseded,1);assert.equal(report.counts.open_backgrounded,1);assert.equal(report.counts.history_request_cancelled,1);assert.equal(report.counts.open_complete,undefined);assert.deepEqual(report.metrics,{});
});
test('bounded recent aggregates keep the long tail and do not export tokens or accept arbitrary dimensions',()=>{
 let at=0;const ux=new UxMeasurements({now:()=>at});ux.foreground(true);
 for(let n=0;n<150;n++){const token=ux.begin('open');at+=n===149?180_000:10;ux.layout(token,true);}
 const report=ux.snapshot();assert.equal(report.metrics.open_fresh_content_layout.count,128);assert.equal(report.metrics.open_fresh_content_layout.maxMs,180_000);assert.equal(report.counts.open_complete,150);
 assert.deepEqual(Object.keys(report).sort(),['counts','evidence','metrics','pending','retention','schema']);
 assert.throws(()=>ux.begin('session-private-id'));assert.throws(()=>ux.mark(0,'private message'));assert.throws(()=>ux.end(0,'raw failure text'));
 for(let n=0;n<40;n++)ux.begin('open');assert.equal(ux.snapshot().pending.open,32);assert.equal(ux.snapshot().counts.capacity_dropped,8);
});
test('send feedback commit precedes admission and neither is model task completion',()=>{
 let at=0;const ux=new UxMeasurements({now:()=>at});ux.foreground(true);const send=ux.begin('send');
 at=4;ux.mark(send,'send_feedback_committed');let report=ux.snapshot();assert.equal(report.metrics.send_admitted_callback,undefined);
 at=450;ux.mark(send,'send_admitted_callback');ux.mark(send,'send_admitted_callback');report=ux.snapshot();assert.equal(report.metrics.send_admitted_callback.count,1);assert.equal(report.counts.send_complete,1);
 const failure=ux.begin('history_request');ux.end(failure,'failed');assert.equal(ux.snapshot().counts.history_request_failed,1);assert.equal(ux.snapshot().metrics.history_response,undefined);
});

test('unknown initial send ends once and late observations cannot imply acceptance or non-execution',()=>{
 const ux=new UxMeasurements({now:()=>10});ux.foreground(true);const send=ux.begin('send');
 ux.mark(send,'send_feedback_committed');ux.end(send,'unknown');ux.end(send,'unknown');ux.mark(send,'send_admitted_callback');
 const failure=ux.begin('send');ux.end(failure,'failed');ux.end(failure,'failed');
 const report=ux.snapshot();assert.equal(report.counts.send_unknown,1);assert.equal(report.counts.send_failed,1);assert.equal(report.counts.send_complete,undefined);assert.equal(report.metrics.send_admitted_callback,undefined);assert.equal(report.pending.send,0);
});
