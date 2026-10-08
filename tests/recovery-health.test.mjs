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
