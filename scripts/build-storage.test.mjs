import test from 'node:test';
import assert from 'node:assert/strict';
import {checkBuildStorage} from './build-storage.mjs';
const GiB = 1024 ** 3;
const disk = (root, stage) => path => ({bavail:path === '/' ? root : stage,bsize:1});
test('RAM staging cannot bypass physical root reserve',()=>assert.throws(()=>checkBuildStorage('/ram',disk(80*GiB,100*GiB)),/root filesystem/));
test('healthy root and RAM stage are checked independently',()=>{const s=checkBuildStorage('/ram',disk(81*GiB,2*GiB));assert.equal(s.rootRequired,80*GiB+512*1024**2);});
test('insufficient staging space is blocked',()=>assert.throws(()=>checkBuildStorage('/ram',disk(81*GiB,GiB-1)),/staging/));
