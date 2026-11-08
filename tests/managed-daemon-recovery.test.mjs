import test from 'node:test';
import assert from 'node:assert/strict';
import {ManagedDaemonRecovery} from '../server/managed-daemon-recovery.mjs';
test('only unavailable enrolled managed transport starts once; no turn, restart or repeated poll',async()=>{
 let calls=0,finish,now=0;const recovery=new ManagedDaemonRecovery('/private/codex',{clock:()=>now,run:()=>{calls++;return new Promise(resolve=>finish=resolve);}});
 assert.equal(await recovery.recover({cause:{code:'ETIMEDOUT'}}),false);
 const unavailable={cause:{code:'ECONNREFUSED'}};
 const a=recovery.recover(unavailable),b=recovery.recover(unavailable);assert.equal(a,b);
 await Promise.resolve();assert.equal(calls,1);finish();assert.equal(await a,true);
 assert.equal(await recovery.recover(unavailable),false);assert.equal(calls,1);
 now=120001;const c=recovery.recover(unavailable);await Promise.resolve();finish();assert.equal(await c,true);assert.equal(calls,2);
 assert.equal(await new ManagedDaemonRecovery().recover(unavailable),false);
});
