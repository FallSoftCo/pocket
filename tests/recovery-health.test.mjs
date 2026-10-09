import test from 'node:test';
import assert from 'node:assert/strict';
import {recoveryStorageReady,recoveryUsagePolicy} from '../server/recovery-health.mjs';

test('due recovery waits for writable filesystem headroom and failed storage audit',()=>{
 assert.equal(recoveryStorageReady('.', {statfs:()=>({bavail:0,bsize:4096})}),false);
 assert.equal(recoveryStorageReady('.', {statfs:()=>({bavail:16384,bsize:4096})}),true);
 assert.equal(recoveryStorageReady('.', {statfs:()=>{throw Error('ENOSPC')}}),false);
});
test('native goal stops are read independently of idle thread metadata',async()=>{
 const calls=[];const codex={call:async(method,params)=>{calls.push({method,params});return {goal:{status:'paused'}};}};
 assert.equal((await recoveryUsagePolicy(codex,'original-task')).blocked,true);
 assert.deepEqual(calls,[{method:'thread/goal/get',params:{threadId:'original-task'}}]);
 for(const status of ['budgetLimited','usageLimited','tokenLimited'])assert.equal((await recoveryUsagePolicy({call:async()=>({goal:{status}})},'original-task')).blocked,true);
 assert.equal((await recoveryUsagePolicy({call:async()=>({goal:null})},'original-task')).ok,true);
});
test('failed native goal audit cannot silently authorize a model restart',async()=>{
 await assert.rejects(recoveryUsagePolicy({call:async()=>{throw Error('runtime unavailable')}},'original-task'),/runtime unavailable/);
});

test('exhausted included usage continues only with account-bound existing-credit authority',async()=>{
 const {recoveryAccountUsage}=await import('../server/recovery-health.mjs');
 const {AccountRateLimits}=await import('../server/rate-limits.mjs');
 const c=new AccountRateLimits({ready:true},()=>{},{clock:()=>1000});
 const bucket={limitId:'codex',planType:'promax',primary:{usedPercent:100},secondary:null,spendControlReached:false,credits:{hasCredits:true,balance:'62028.17'}};
 const update=b=>c.update({accountId:'signed-in-account',rateLimits:b},{replace:true});const permission={enabled:true,accountId:'signed-in-account'};
 update(bucket);assert.equal(recoveryAccountUsage(c,permission,{now:1000}).existingCredits,true);
 assert.equal(recoveryAccountUsage(c,null,{now:1000}).ok,false);
 assert.equal(recoveryAccountUsage(c,{...permission,accountId:'different'},{now:1000}).ok,false);
 for(const change of [{spendControlReached:true},{spendControlReached:null},{individualLimit:{remainingPercent:0}},{planType:'business'},{credits:{hasCredits:true,balance:'0'}},{credits:{hasCredits:false,balance:'100'}},{credits:{hasCredits:true,balance:'garbage'}},{rateLimitReachedType:'workspace_owner_usage_limit_reached'}]){
 update({...bucket,...change});assert.equal(recoveryAccountUsage(c,permission,{now:1000}).ok,false,JSON.stringify(change));}
 update(bucket);assert.equal(recoveryAccountUsage(c,permission,{now:200000}).ok,false);c.failed=true;assert.equal(recoveryAccountUsage(c,permission,{now:1000}).ok,false);
 assert.equal(JSON.stringify(c.snapshot()).includes('signed-in-account'),false);assert.equal(c.snapshot().credits.balance,'62028.17');assert.equal(JSON.stringify(c.snapshot()).includes('signed-in-account'),false);c.clear();assert.equal(c.accountId,null);
});
