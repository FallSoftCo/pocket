import test from 'node:test';
import assert from 'node:assert/strict';
import {AccountUsageCache} from '../web/account-usage.mjs';
const sample=(now,remainingPercent=61)=>({weekly:{remainingPercent,resetsAt:Math.floor(now/1000)+86400},updatedAt:now,credits:{balance:1200,updatedAt:now},continuation:{label:'Existing credits available'}});
test('room account observation never borrows quota from another source',()=>{
 const now=Date.now(),cache=new AccountUsageCache({clock:()=>now});cache.profile('paired-browser');cache.status('current',{deviceId:'local-device',usage:sample(now)},'CPU');
 assert.equal(cache.view('current').label,'61% left');assert.equal(cache.view('gpu').label,'Usage unavailable');
 assert.equal(cache.limits('gpu',sample(now,12)),false,'Unverified source events cannot seed an account observation');
 cache.status('gpu',{deviceId:'remote-device',usage:sample(now,27)},'GPU');
 assert.equal(cache.view('gpu').label,'27% left');assert.equal(cache.view('current').label,'61% left');
 assert.equal(cache.view('gpu').source,'GPU');assert.match(cache.view('gpu').provenance,/not a room-wide quota/);
});
test('original browser owner and physical device credential changes discard old observations',()=>{
 const now=Date.now(),cache=new AccountUsageCache({clock:()=>now});cache.profile('first-owner');cache.status('current',{deviceId:'first-device',usage:sample(now)},'CPU');
 cache.status('current',{deviceId:'different-device'},'CPU');assert.equal(cache.view('current').label,'Usage unavailable');
 cache.status('gpu',{deviceId:'remote-device',usage:sample(now)},'GPU');cache.profile('second-owner');assert.equal(cache.observations.size,0);
 assert.equal(cache.view('gpu').label,'Usage unavailable');
});
test('stale or unavailable retains a labelled last-known percentage/reset, never invents zero',()=>{
 let now=Date.now();const cache=new AccountUsageCache({clock:()=>now});cache.status('current',{deviceId:'device',usage:sample(now)},'CPU');
 assert.equal(cache.view('current').state,'fresh');assert.equal(cache.needsStatus('current'),false);now+=120001;
 assert.equal(cache.view('current').state,'stale');assert.equal(cache.view('current').label,'61% left');assert.match(cache.view('current').reset,/Resets/);
 cache.unavailable('current');assert.equal(cache.view('current').freshness,'Last known observation');assert.equal(cache.needsStatus('current'),true);
});
test('zero remaining preserves explicit credit and continuation facts, with separate credit freshness',()=>{
 const now=Date.now(),cache=new AccountUsageCache({clock:()=>now}),usage=sample(now,0);cache.status('current',{deviceId:'device',usage},'CPU');
 assert.match(cache.view('current').label,/^0% left.*cr$/);assert.equal(cache.view('current').credits,'1200 credits');assert.equal(cache.view('current').continuation,'Existing credits available');
 usage.credits.updatedAt=now-120001;cache.limits('current',usage);assert.equal(cache.view('current').state,'stale');assert.equal(cache.view('current').creditStale,true);
});
test('malformed or absent remaining data is unavailable, not a coerced zero limit',()=>{
 const now=Date.now(),cache=new AccountUsageCache({clock:()=>now});
 for(const value of [null,false,'',-1,101,NaN]){cache.status('current',{deviceId:'device',usage:sample(now,value)},'CPU');assert.equal(cache.view('current').label,'Usage unavailable');assert.equal(cache.view('current').state,'unavailable');}
 assert.throws(()=>cache.status('gpu',{deviceId:'owner',usage:sample(now)},'GPU'),/unconfirmed/);
});

test('menu shows banked benefits independently of weekly reset, with explicit zero and unavailable',()=>{
 let now=Date.now();const cache=new AccountUsageCache({clock:()=>now}),usage=sample(now);
 usage.resetCredits={availableCount:2,updatedAt:now,stale:false};
 cache.status('current',{deviceId:'device',usage},'CPU');assert.equal(cache.view('current').resetCredits,'2 banked resets available');
 now+=120001;assert.match(cache.view('current').resetCredits,/Last known/);
 cache.limits('current',{...usage,resetCredits:{availableCount:0,updatedAt:now,stale:false}});
 assert.equal(cache.view('current').resetCredits,'0 banked resets available');
 cache.unavailable('current');assert.match(cache.view('current').resetCredits,/Last known/);
 cache.profile('another-owner');assert.equal(cache.view('current').resetCredits,'Banked resets unavailable');
});
