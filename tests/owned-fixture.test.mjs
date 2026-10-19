import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,readFileSync,existsSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawn} from 'node:child_process';
import {setTimeout as pause} from 'node:timers/promises';
import {startOwnedFixture,stopOwnedFixture,acquireOwnedRunLock} from '../scripts/owned-fixture.mjs';
const wait=async predicate=>{for(let i=0;i<120;i++){if(predicate())return;await pause(25);}throw Error('Lifecycle deadline exceeded');};
const running=pid=>{try{return readFileSync('/proc/'+pid+'/stat','utf8').split(' ')[2]!=='Z';}catch{return false;}};
test('owned fixture is stopped and reaped on normal cleanup',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'nextcomp-owned-fixture-'));
 let child;
 try{const script=join(dir,'fixture.mjs');writeFileSync(script,'setInterval(()=>{},1000);');
 child=startOwnedFixture(script,{cwd:dir,env:process.env,fd:'ignore'});
 await wait(()=>running(child.pid));assert.equal((await stopOwnedFixture(child)).stopped,true);
 assert.equal(running(child.pid),false);
 }finally{await stopOwnedFixture(child);rmSync(dir,{recursive:true,force:true});}
});
test('fixture lifetime ends when its parent is abruptly killed',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'nextcomp-owned-fixture-'));let parent,pid;
 try{
 const script=join(dir,'fixture.mjs'),marker=join(dir,'ready');
 writeFileSync(script,`import {writeFileSync} from 'node:fs';writeFileSync(${JSON.stringify(marker)},String(process.pid));setInterval(()=>{},1000);`);
 const module=new URL('../scripts/owned-fixture.mjs',import.meta.url).href;
 parent=spawn(process.execPath,['--input-type=module','-e',`import {startOwnedFixture} from ${JSON.stringify(module)};startOwnedFixture(${JSON.stringify(script)},{cwd:${JSON.stringify(dir)},env:process.env,fd:'ignore'});`],{stdio:'ignore'});
 await wait(()=>existsSync(marker));pid=Number(readFileSync(marker,'utf8'));assert.ok(running(pid));
 parent.kill('SIGKILL');await wait(()=>!running(pid));
 }finally{parent?.kill('SIGKILL');if(pid&&running(pid))process.kill(-pid,'SIGKILL');rmSync(dir,{recursive:true,force:true});}
});

test('kernel ownership excludes a concurrent runner and permits the successor after release',()=>{
 const dir=mkdtempSync(join(tmpdir(),'nextcomp-run-lock-'));let release;
 try{
  const path=join(dir,'owner.lock');release=acquireOwnedRunLock(path);
  assert.throws(()=>acquireOwnedRunLock(path),/ownership lock unavailable/);
  release();const successor=acquireOwnedRunLock(path);successor();
 }finally{release?.();rmSync(dir,{recursive:true,force:true});}
});

test('release storage refuses RAM-backed source, output, cache or stage without reducing the disk reserve',async()=>{
 const {checkBuildStorage}=await import('../scripts/build-storage.mjs');
 const disk={bavail:100*1024**3,bsize:1,type:0xef53};
 for(const ram of ['/source','/outputs','/cache','/stage']){
  assert.throws(()=>checkBuildStorage('/stage',path=>({...disk,type:path===ram?0x01021994:disk.type}),['/source','/outputs','/cache']),/RAM-backed build path/);
 }
 const result=checkBuildStorage('/stage',()=>disk,['/source','/outputs','/cache']);assert.ok(result.rootRequired>80*1024**3);
});
