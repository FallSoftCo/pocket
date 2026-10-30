import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
test('local release journey refuses unsafe archives; reconciles lost activation without restart and supports explicit rollback',()=>{
 const p=spawnSync('python3',[fileURLToPath(new URL('./backend-release_test.py',import.meta.url))],{encoding:'utf8'});
 assert.equal(p.status,0,p.stdout+p.stderr);
});
