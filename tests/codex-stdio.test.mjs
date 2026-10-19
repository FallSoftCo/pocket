import test from 'node:test';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
import {Codex} from '../server/codex.mjs';

test('stdio app-server transport initializes, calls, streams, and answers',async t=>{
  const fixture=fileURLToPath(new URL('fixtures/app-server-stdio.mjs',import.meta.url));
  const codex=new Codex('/unused',{command:[process.execPath,fixture]});
  t.after(()=>codex.closeTransport());
  const events=[];codex.on('event',event=>events.push(event));
  await codex.connect();
  assert.equal(codex.ready,true);
  const result=await codex.call('thread/list',{limit:2});
  assert.deepEqual(result,{method:'thread/list',params:{limit:2}});
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(events.some(event=>event.method==='server/ready'),true);
  codex.answer(99,{decision:'accept'});
});

test('Android execution overrides are explicit and absent elsewhere',()=>{
  const oldSandbox=process.env.POCKET_CODEX_SANDBOX,oldApproval=process.env.POCKET_CODEX_APPROVAL_POLICY;
  try{
    delete process.env.POCKET_CODEX_SANDBOX;delete process.env.POCKET_CODEX_APPROVAL_POLICY;
    const codex=new Codex('/unused',{command:[process.execPath,'-e','']});
    assert.deepEqual(codex.executionOptions(),{});
    process.env.POCKET_CODEX_SANDBOX='danger-full-access';process.env.POCKET_CODEX_APPROVAL_POLICY='on-request';
    assert.deepEqual(codex.executionOptions(),{sandbox:'danger-full-access',approvalPolicy:'on-request'});
  }finally{
    if(oldSandbox===undefined)delete process.env.POCKET_CODEX_SANDBOX;else process.env.POCKET_CODEX_SANDBOX=oldSandbox;
    if(oldApproval===undefined)delete process.env.POCKET_CODEX_APPROVAL_POLICY;else process.env.POCKET_CODEX_APPROVAL_POLICY=oldApproval;
  }
});

test('failed initialization cannot overlap its retiring child with concurrent reconnects',async()=>{
 const {mkdtempSync,writeFileSync,readFileSync,rmSync}=await import('node:fs');
 const {tmpdir}=await import('node:os');const {join}=await import('node:path');
 const {once}=await import('node:events');
 const directory=mkdtempSync(join(tmpdir(),'nextcomp-runtime-owner-')),journal=join(directory,'journal');
 const script=join(directory,'runtime.mjs');
 writeFileSync(script,String.raw`import {appendFileSync,readFileSync} from 'node:fs';import {createInterface} from 'node:readline';
 const journal=${JSON.stringify(journal)};let first;try{first=!readFileSync(journal,'utf8');}catch{first=true;}
 appendFileSync(journal,'start '+process.pid+'\n');
 process.on('SIGTERM',()=>setTimeout(()=>{appendFileSync(journal,'exit '+process.pid+'\n');process.exit(0);},150));
 createInterface({input:process.stdin}).on('line',line=>{const row=JSON.parse(line);if(row.id)console.log(JSON.stringify(first?{id:row.id,error:{message:'fixture init failure'}}:{id:row.id,result:{}}));});`);
 const codex=new Codex('/unused',{command:[process.execPath,script]});
 try{
  await assert.rejects(codex.connect(),/fixture init failure/);const old=codex.child.pid;
  await Promise.all([codex.connect(),codex.connect(),codex.connect()]);assert.ok(codex.ready);
  const rows=readFileSync(journal,'utf8').trim().split('\n');
  assert.deepEqual(rows.slice(0,2),['start '+old,'exit '+old]);assert.equal(rows.filter(x=>x.startsWith('start ')).length,2);
 }finally{
  const child=codex.child;if(child&&child.exitCode===null&&child.signalCode===null){const stopped=once(child,'exit');codex.closeTransport();await stopped;}
  rmSync(directory,{recursive:true,force:true});
 }
});
