import test from 'node:test';
import assert from 'node:assert/strict';
import {AccountRateLimits} from '../server/rate-limits.mjs';

const week=usedPercent=>({usedPercent,windowDurationMins:10080,resetsAt:2000000000});
const session={usedPercent:10,windowDurationMins:300,resetsAt:1900000000};
const cache=()=>new AccountRateLimits({ready:true,call:async()=>({})});

test('weekly allowance can be primary or secondary, and selects the Codex bucket',()=>{
  const c=cache();c.update({rateLimits:{primary:week(82),secondary:null}},{replace:true});
  assert.equal(c.snapshot().weekly.remainingPercent,18);
  c.update({rateLimits:{primary:session,secondary:week(29)},rateLimitsByLimitId:{codex:{primary:session,secondary:week(29)},reviews:{primary:week(90)}}},{replace:true});
  assert.equal(c.snapshot().weekly.remainingPercent,71);
  c.update({rateLimits:{primary:session,secondary:null}},{replace:true});
  assert.equal(c.snapshot().weekly,null,'a session quota must never be presented as weekly');
  assert.equal(c.snapshot().state,'unavailable');
});

test('partial live changes retain weekly windows, explicit null clears them',()=>{
  const c=cache();c.update({rateLimits:{limitId:'codex',primary:session,secondary:week(45)}});
  c.liveUpdate({rateLimits:{limitId:'codex',primary:{...session,usedPercent:20}}});
  assert.equal(c.snapshot().weekly.remainingPercent,55);
  c.liveUpdate({rateLimits:{limitId:'reviews',primary:week(99)}});
  assert.equal(c.snapshot().weekly.remainingPercent,55,'another bucket must not overwrite Codex');
  c.liveUpdate({rateLimits:{limitId:'codex',secondary:null}});
  assert.equal(c.snapshot().weekly,null);
});

test('invalid or missing quota data is unavailable, exhausted allowance is exactly zero',()=>{
  for(const used of [null,'',NaN,Infinity]){
    const c=cache();c.update({rateLimits:{primary:week(used)}},{replace:true});assert.equal(c.snapshot().weekly,null);
  }
  const c=cache();c.update({rateLimits:{primary:week(104)}},{replace:true});
  assert.equal(c.snapshot().weekly.remainingPercent,0);
  c.update({},{replace:true});assert.equal(c.snapshot().weekly,null);
});

test('refreshes are deduplicated and cached; failures and disconnects retain last known data',async()=>{
  let now=1000,calls=0,fail=false;
  const codex={ready:true,async call(method){assert.equal(method,'account/rateLimits/read');calls++;if(fail)throw Error('offline');return {rateLimits:{primary:week(82)},accountId:'PRIVATE',rateLimitResetCredits:{availableCount:1}};}};
  const c=new AccountRateLimits(codex,()=>{},{clock:()=>now});
  await Promise.all([c.refresh(),c.refresh()]);assert.equal(calls,1);
  assert.ok(!JSON.stringify(c.snapshot()).includes('PRIVATE'));
  await c.refresh();assert.equal(calls,1);
  now+=60000;fail=true;await c.refresh();
  assert.equal(c.snapshot().weekly.remainingPercent,18);assert.equal(c.snapshot().stale,true);
  codex.ready=false;c.disconnected();assert.equal(c.snapshot().weekly.remainingPercent,18);
  c.clear();assert.equal(c.snapshot().weekly,null,'account changes clear the old allowance');
});

test('in-flight reads merge newer partial notifications and cannot restore a cleared account',async()=>{
  let resolve;
  const c=new AccountRateLimits({ready:true,call:()=>new Promise(r=>{resolve=r;})});
  const first=c.refresh();c.liveUpdate({rateLimits:{limitId:'codex',primary:{...session,usedPercent:30}}});
  resolve({rateLimits:{limitId:'codex',primary:session,secondary:week(82)}});await first;
  assert.equal(c.snapshot().weekly.remainingPercent,18);
  assert.equal(c.buckets.codex.primary.usedPercent,30);
  const second=c.refresh({force:true});c.clear();resolve({rateLimits:{primary:week(99)}});await second;
  assert.equal(c.snapshot().weekly,null);
});
