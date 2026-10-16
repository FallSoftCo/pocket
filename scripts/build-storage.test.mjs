import test from 'node:test';
import assert from 'node:assert/strict';
import {checkBuildStorage} from './build-storage.mjs';
const GiB = 1024 ** 3;
const disk = (root, stage) => path => ({bavail:path === '/' ? root : stage,bsize:1});
test('RAM staging cannot bypass physical root reserve',()=>assert.throws(()=>checkBuildStorage('/ram',disk(80*GiB,100*GiB)),/root filesystem/));
test('healthy root and RAM stage are checked independently',()=>{const s=checkBuildStorage('/ram',disk(81*GiB,2*GiB));assert.equal(s.rootRequired,80*GiB+512*1024**2);});
test('insufficient staging space is blocked',()=>assert.throws(()=>checkBuildStorage('/ram',disk(81*GiB,GiB-1)),/staging/));

test('verified RAM-only build preserves 80 GiB plus metadata headroom on root',()=>{
 const fs=path=>({bavail:path==='/'?80*GiB+32*1024**2:2*GiB,bsize:1,type:path==='/'?0xef53:0x01021994});
 const r=checkBuildStorage('/ram',fs,['/source','/outputs','/gradle']);assert.equal(r.rootRequired,80*GiB+16*1024**2);
 assert.throws(()=>checkBuildStorage('/ram',path=>({...fs(path),bavail:path==='/'?80*GiB:2*GiB}),['/source','/outputs','/gradle']),/root filesystem/);
});
test('a root-backed cache retains full root write headroom',()=>{
 const fs=path=>({bavail:path==='/'?80*GiB+32*1024**2:2*GiB,bsize:1,type:path==='/'||path==='/gradle'?0xef53:0x01021994});
 assert.throws(()=>checkBuildStorage('/ram',fs,['/source','/outputs','/gradle']),/root filesystem/);
});
