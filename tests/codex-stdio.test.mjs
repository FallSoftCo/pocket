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
