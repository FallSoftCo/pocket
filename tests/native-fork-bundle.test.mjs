import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {exportNativeFork,importNativeFork,digest,workspaceEvidence,bundlePath} from '../server/native-fork-bundle.mjs';

import {nativeFixture} from './fixtures/native-fork.mjs';
test('native fork exports its exact ancestor prefix and opaque native bytes',async t=>{
 const f=await nativeFixture(t),bundle=await exportNativeFork({codex:f.codex,fork:f.fork,codeHome:f.source,directory:f.staging});
 assert.deepEqual(bundle.files.map(x=>x.id),['source-thread','fork-thread']);const bytes=await readFile(bundle.files[0].localFile,'utf8');assert.match(bytes,/OPAQUE_NATIVE_CONTEXT/);assert.doesNotMatch(bytes,/Later unrelated/);
 for(const file of bundle.files)assert.equal(digest(await readFile(file.localFile)),file.sha256);
 const imported=await importNativeFork({bundle,codeHome:f.destination,directory:f.staging});assert.equal(imported.files,2);
 for(const file of bundle.files)assert.equal(digest(await readFile(bundlePath(f.destination,file.location))),file.sha256);
 await importNativeFork({bundle,codeHome:f.destination,directory:f.staging});
});
test('different existing lineage fails before any destination history is overwritten',async t=>{
 const f=await nativeFixture(t),bundle=await exportNativeFork({codex:f.codex,fork:f.fork,codeHome:f.source,directory:f.staging});const path=bundlePath(f.destination,bundle.files[0].location);await mkdir(join(f.destination,'sessions/2026/10/10'),{recursive:true});await writeFile(path,'OTHER HISTORY');
 await assert.rejects(importNativeFork({bundle,codeHome:f.destination,directory:f.staging}),/different history/);assert.equal(await readFile(path,'utf8'),'OTHER HISTORY');await assert.rejects(readFile(bundlePath(f.destination,bundle.files[1].location)),/ENOENT/);
});
test('existing matching ancestors may have additional source work without being truncated',async t=>{
 const f=await nativeFixture(t),bundle=await exportNativeFork({codex:f.codex,fork:f.fork,codeHome:f.source,directory:f.staging});const path=bundlePath(f.destination,bundle.files[0].location);await mkdir(join(f.destination,'sessions/2026/10/10'),{recursive:true});const full=await readFile(f.original.path);await writeFile(path,full);await importNativeFork({bundle,codeHome:f.destination,directory:f.staging});assert.deepEqual(await readFile(path),full);
});
test('checksum corruption, missing ancestor and unsafe paths stop native import',async t=>{
 const f=await nativeFixture(t),bundle=await exportNativeFork({codex:f.codex,fork:f.fork,codeHome:f.source,directory:f.staging});await writeFile(bundle.files[0].localFile,'CORRUPT');await assert.rejects(importNativeFork({bundle,codeHome:f.destination,directory:f.staging}),/checksum/);
 assert.throws(()=>bundlePath(f.destination,'../../auth.json'),/Invalid/);assert.throws(()=>bundlePath(f.destination,'sessions/2026/10/10/../../auth.json'),/Invalid/);
 await assert.rejects(exportNativeFork({codex:{async call(){throw Error('missing source rollout');}},fork:f.fork,codeHome:f.source,directory:join(f.base,'missing')}),/missing source/);
});
test('workspace evidence checks exact current files and rejects paths outside the workspace',async t=>{
 const f=await nativeFixture(t);await writeFile(join(f.source,'code.txt'),'source revision');const e=await workspaceEvidence(f.source,['code.txt']);assert.equal(e[0].sha256,digest('source revision'));await assert.rejects(workspaceEvidence(f.source,['../destination']),/escaped/);
});
test('completed boundary metadata must match verified native bytes',async t=>{const f=await nativeFixture(t),bundle=await exportNativeFork({codex:f.codex,fork:f.fork,codeHome:f.source,directory:f.staging});bundle.files[0].completedTurnIds=['invented-turn'];await assert.rejects(importNativeFork({bundle,codeHome:f.destination,directory:f.staging}),/boundary metadata/);});
test('existing destination fork with later work is never resumed by a new import',async t=>{const f=await nativeFixture(t),bundle=await exportNativeFork({codex:f.codex,fork:f.fork,codeHome:f.source,directory:f.staging});await importNativeFork({bundle,codeHome:f.destination,directory:f.staging});const file=bundle.files.at(-1),path=bundlePath(f.destination,file.location);await writeFile(path,(await readFile(path,'utf8'))+JSON.stringify({ordinal:5,type:'event_msg',payload:{type:'task_complete',turn_id:'other-turn'}})+'\n');await assert.rejects(importNativeFork({bundle,codeHome:f.destination,directory:f.staging}),/different history/);});
test('ancestor depth is bounded before additional lineage reads',async t=>{const f=await nativeFixture(t);let calls=0;await assert.rejects(exportNativeFork({codex:{async call(){calls++;return {thread:f.original};}},fork:f.fork,codeHome:f.source,directory:f.staging,maxAncestors:1}),/too deep/);assert.equal(calls,1);});
