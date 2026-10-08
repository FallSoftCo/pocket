import test from 'node:test';
import assert from 'node:assert/strict';
import {runAccountReset} from '../server/account-reset.mjs';
const setup=()=>{
 let consumed=false,failAck=false,failVerify=false,percent=93;const calls=[],saved=[];
 const state={enabled:true,status:'scheduled',accountId:'account',creditId:'benefit',idempotencyKey:'same-durable-key',thresholdPercent:98,expiresAt:100000};
 const usage=()=>({accountId:'account',rateLimits:{primary:{usedPercent:consumed?0:percent,windowDurationMins:10080,resetsAt:90000},spendControlReached:false},rateLimitResetCredits:{availableCount:consumed?0:1,credits:consumed?[]:[{id:'benefit',status:'available',resetType:'codexRateLimits',expiresAt:100000}]}});
 const call=async(method,params)=>{calls.push({method,params});if(method==='account/read')return {account:{type:'chatgpt'}};if(method==='account/rateLimits/read'){if(consumed&&failVerify){failVerify=false;throw Error('read lost');}return usage();}assert.equal(method,'account/rateLimitResetCredit/consume');assert.equal(params.creditId,'benefit');assert.equal(params.idempotencyKey,'same-durable-key');const duplicate=consumed;consumed=true;if(failAck){failAck=false;throw Error('ack lost');}return {outcome:duplicate?'alreadyRedeemed':'reset'};};
 return {state,calls,saved,call,usage,save:s=>saved.push(structuredClone(s)),setPercent:p=>percent=p,loseAck:()=>failAck=true,loseVerification:()=>failVerify=true};
};
test('near-exhaustion schedule preserves benefit and then redeems exactly once',async()=>{
 const f=setup();await runAccountReset({...f,now:1000});assert.equal(f.state.status,'scheduled');assert.equal(f.calls.length,2);
 f.setPercent(98);await runAccountReset({...f,now:100000});assert.equal(f.state.status,'redeemed');assert.equal(f.state.afterWeeklyUsedPercent,0);assert.equal(f.state.remainingResets,0);
 const n=f.calls.length;await runAccountReset({...f,now:200000});assert.equal(f.calls.length,n);assert.equal(f.saved.some(s=>s.status==='redeeming'),true);
});
test('lost consume acknowledgement survives restart and reconciles one idempotent attempt',async()=>{
 const f=setup();f.setPercent(100);f.loseAck();await runAccountReset({...f,now:1000});assert.equal(f.state.status,'redeeming');
 const restored=JSON.parse(JSON.stringify(f.state));await runAccountReset({...f,state:restored,now:restored.nextAt});assert.equal(restored.status,'redeemed');assert.equal(restored.outcome,'alreadyRedeemed');assert.deepEqual(f.calls.filter(c=>c.method.endsWith('/consume')).map(c=>c.params.idempotencyKey),['same-durable-key','same-durable-key']);
});
test('acknowledged redemption with failed verification never consumes again',async()=>{
 const f=setup();f.setPercent(98);f.loseVerification();await runAccountReset({...f,now:1000});assert.equal(f.state.status,'acknowledged');
 await runAccountReset({...f,now:f.state.nextAt});assert.equal(f.state.status,'redeemed');assert.equal(f.calls.filter(c=>c.method.endsWith('/consume')).length,1);
});
test('different account, exhausted spending cap, unavailable and expired benefit cannot redeem',async()=>{
 for(const change of ['account','cap','missing','expired','type']){
  const f=setup();f.setPercent(100);const base=f.call;f.call=async(m,p)=>{if(m==='account/rateLimits/read'){const u=f.usage();if(change==='account')u.accountId='other';if(change==='cap')u.rateLimits.spendControlReached=true;if(change==='missing')u.rateLimitResetCredits.credits=[];if(change==='type')u.rateLimitResetCredits.credits[0].resetType='unknown';return u;}return base(m,p);};if(change==='expired')f.state.expiresAt=0;
  await runAccountReset({...f,now:1000});assert.equal(f.calls.filter(c=>c.method.endsWith('/consume')).length,0,change);
 }
});
test('imminent normal reset waits instead of wasting earned benefit',async()=>{
 const f=setup();f.setPercent(98);const base=f.call;f.call=async(m,p)=>{if(m==='account/rateLimits/read'){const u=f.usage();u.rateLimits.primary.resetsAt=100;return u;}return base(m,p);};await runAccountReset({...f,now:1000});assert.equal(f.state.status,'scheduled');assert.match(f.state.reason,/imminent/);
});
