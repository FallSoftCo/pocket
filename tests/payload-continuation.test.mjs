import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {PayloadBudget,encodedImageBytes} from '../server/payload-budget.mjs';
import {ContinuationRouter,buildContinuationHandoff,continuationInput,continuationReceipt} from '../server/continuation-router.mjs';
import {ReplyDelivery} from '../server/reply-delivery.mjs';

const page=(items,next=null)=>({data:items.map((item,index)=>({turnId:'turn',item:{id:'item-'+index,...item}})),nextCursor:next});
const scanCodex=pages=>{let index=0;return {call:async(method,params)=>{assert.equal(method,'thread/items/list');assert.equal(params.sortDirection,'desc');return pages[index++]||page([],null);}};};

test('payload budget allows a context without active images',async()=>{
  const budget=new PayloadBudget({codex:scanCodex([page([{type:'agentMessage',text:'done'},{type:'contextCompaction'}])])});
  const result=await budget.scan('thread');
  assert.equal(result.exceeded,false);assert.equal(result.boundaryFound,true);assert.equal(result.count,0);
});

test('payload budget ignores images older than the latest compaction',async()=>{
  const budget=new PayloadBudget({codex:scanCodex([page([{type:'contextCompaction'},{type:'imageView',path:'/old/missing.png'}])])});
  const result=await budget.scan('thread');
  assert.equal(result.exceeded,false);assert.equal(result.count,0);assert.equal(result.missingImages,0);
});

test('payload budget enforces the active image count',async()=>{
  const root=mkdtempSync(join(tmpdir(),'nextcomp-budget-'));
  try{
    const images=Array.from({length:13},(_,index)=>{const path=join(root,index+'.png');writeFileSync(path,'x');return {type:'imageView',path};});
    const budget=new PayloadBudget({env:{NEXTCOMP_MAX_ACTIVE_IMAGES:'12'},codex:scanCodex([page([...images,{type:'contextCompaction'}])])});
    const result=await budget.scan('thread');
    assert.equal(result.count,13);assert.equal(result.countExceeded,true);assert.equal(result.exceeded,true);
  }finally{rmSync(root,{recursive:true,force:true});}
});

test('payload budget enforces estimated encoded bytes',async()=>{
  const root=mkdtempSync(join(tmpdir(),'nextcomp-budget-'));
  try{
    const paths=['a.png','b.png'].map(name=>{const path=join(root,name);writeFileSync(path,Buffer.alloc(30));return path;});
    const budget=new PayloadBudget({env:{NEXTCOMP_MAX_IMAGE_BYTES:String(encodedImageBytes(30)*2-1)},codex:scanCodex([page([...paths.map(path=>({type:'imageView',path})),{type:'contextCompaction'}])])});
    const result=await budget.scan('thread');
    assert.equal(result.estimatedEncodedBytes,encodedImageBytes(30)*2);assert.equal(result.bytesExceeded,true);assert.equal(result.exceeded,true);
  }finally{rmSync(root,{recursive:true,force:true});}
});

test('payload budget continues conservatively when the boundary scan is truncated',async()=>{
  const budget=new PayloadBudget({maxPages:1,codex:scanCodex([page([{type:'agentMessage',text:'text only'}],'next')])});
  const result=await budget.scan('thread');
  assert.equal(result.truncated,true);assert.equal(result.boundaryFound,false);assert.equal(result.exceeded,true);
});

test('payload budget allows a fully scanned text-only thread without a compaction',async()=>{
  const budget=new PayloadBudget({codex:scanCodex([page([{type:'agentMessage',text:'long text context'}])])});
  const result=await budget.scan('thread');
  assert.equal(result.exceeded,false);assert.equal(result.truncated,false);assert.equal(result.boundaryFound,false);
});

test('payload guard is provider scoped by default',()=>{
  const budget=new PayloadBudget({codex:{call:async()=>page([])}});
  assert.equal(budget.applies({modelProvider:'deepseek'}),true);
  assert.equal(budget.applies({modelProvider:'openai'}),false);
});

function routerFixture(){
  const db=new DatabaseSync(':memory:');
  db.exec(`
    CREATE TABLE watches(thread_id TEXT PRIMARY KEY,name TEXT,enabled INTEGER DEFAULT 0);
    CREATE TABLE turn_settings(thread_id TEXT PRIMARY KEY,settings TEXT NOT NULL);
    CREATE TABLE thread_permissions(thread_id TEXT PRIMARY KEY,permissions TEXT NOT NULL);
  `);
  const calls=[];let fail=false,nextId=1;
  const codex={executionOptions:()=>({sandbox:'danger-full-access',approvalPolicy:'never'}),call:async(method,params)=>{
    calls.push({method,params});
    if(method==='thread/start'){
      if(fail){fail=false;throw Object.assign(Error('start failed'),{rpc:{code:-1}});}
      return {thread:{id:'successor-'+(nextId++),cwd:params.cwd,modelProvider:params.modelProvider,model:params.model,reasoningEffort:params.config?.model_reasoning_effort,status:{type:'idle'}}};
    }
    if(method==='thread/name/set')return {ok:true};
    throw Error('unexpected '+method);
  }};
  return {db,calls,codex,setFail:value=>{fail=value;}};
}

test('continuation creates one fresh text-only successor and resolves future messages there',async()=>{
  const f=routerFixture(),created=[];
  try{
    f.db.prepare("INSERT INTO turn_settings VALUES('source','{\"model\":\"deepseek-flash\",\"effort\":\"medium\",\"mode\":\"default\"}')").run();
    f.db.prepare("INSERT INTO thread_permissions VALUES('source','full')").run();
    const router=new ContinuationRouter({db:f.db,codex:f.codex,onCreated:(successor,source)=>created.push({successor,source}),clock:()=>100});
    const source={id:'source',name:'Vipi IG',cwd:'/workspace/vipi',modelProvider:'deepseek',model:'deepseek-flash',reasoningEffort:'medium'};
    const first=await router.ensureSuccessor(source,{usage:{reason:'too many images'},messageId:'message'});
    const second=await router.ensureSuccessor(source,{usage:{reason:'too many images'},messageId:'message'});
    const starts=f.calls.filter(call=>call.method==='thread/start');
    assert.equal(starts.length,1);assert.equal(first.destinationThreadId,second.destinationThreadId);assert.equal(router.resolve('source').threadId,first.destinationThreadId);
    assert.equal(created.length,1);
    assert.deepEqual({cwd:starts[0].params.cwd,modelProvider:starts[0].params.modelProvider,model:starts[0].params.model,sandbox:starts[0].params.sandbox,approvalPolicy:starts[0].params.approvalPolicy,effort:starts[0].params.config.model_reasoning_effort},{cwd:'/workspace/vipi',modelProvider:'deepseek',model:'deepseek-flash',sandbox:'danger-full-access',approvalPolicy:'never',effort:'medium'});
    assert.match(starts[0].params.developerInstructions,/Source thread: source/);
    assert.match(starts[0].params.developerInstructions,/must not be replayed, forked, resumed, or compacted/);
    assert.equal(f.calls.filter(call=>call.method==='thread/name/set')[0].params.name,'Vipi IG · continued');
    assert.equal(f.db.prepare('SELECT state FROM thread_continuations WHERE source_thread_id=?').get('source').state,'ready');
    assert.equal(f.db.prepare('SELECT permissions FROM thread_permissions WHERE thread_id=?').get(first.destinationThreadId).permissions,'full');
  }finally{f.db.close();}
});

test('successor creation failure remains retryable and never sends a message twice',async()=>{
  const f=routerFixture();
  try{
    const router=new ContinuationRouter({db:f.db,codex:f.codex});
    const source={id:'source',name:'Task',cwd:'/workspace',modelProvider:'deepseek',model:'deepseek-flash'};
    f.setFail(true);
    await assert.rejects(router.ensureSuccessor(source,{usage:{reason:'budget'}}),error=>error.continuationCreation===true);
    assert.equal(f.db.prepare('SELECT state FROM thread_continuations WHERE source_thread_id=?').get('source').state,'retry');
    assert.throws(()=>router.resolve('source'),error=>error.code==='CONTINUATION_PENDING');
    const result=await router.ensureSuccessor(source,{usage:{reason:'budget'}});
    assert.equal(result.created,true);assert.equal(router.resolve('source').threadId,result.destinationThreadId);
    assert.equal(f.calls.filter(call=>call.method==='thread/start').length,2);
  }finally{f.db.close();}
});

test('continuation input is exactly the original user text once',()=>{
  const row={text:'keep this exact message'};
  assert.deepEqual(continuationInput(row),[{type:'text',text:'keep this exact message'}]);
  const handoff=buildContinuationHandoff({id:'source',name:'Task',cwd:'/workspace'},{reason:'budget'});
  assert.equal(handoff.includes(row.text),false);
});

test('continuation receipt follows a durable successor even before native acceptance',()=>{
  const f=routerFixture();
  try{
    const router=new ContinuationRouter({db:f.db,codex:f.codex});
    f.db.prepare("INSERT INTO thread_continuations VALUES('source','successor','ready','budget',NULL,1,1)").run();
    assert.deepEqual(router.receiptFields('source'),{continuationThreadId:'successor',continuationSourceThreadId:'source'});
    assert.deepEqual(continuationReceipt(JSON.stringify({continuation:{sourceThreadId:'source',successorThreadId:'successor',reason:'budget'}})),{continuationThreadId:'successor',continuationSourceThreadId:'source',continuationReason:'budget'});
  }finally{f.db.close();}
});

test('a failed continuation preparation leaves the queued receipt intact with retry backoff',()=>{
  const db=new DatabaseSync(':memory:');
  try{
    db.exec("CREATE TABLE outgoing(id TEXT PRIMARY KEY,thread_id TEXT,text TEXT,state TEXT,result TEXT,created_at INTEGER,updated_at INTEGER,mode TEXT DEFAULT 'steer')");
    db.exec("INSERT INTO outgoing VALUES('message','source','hello','queued',NULL,1,1,'steer')");
    const delivery=new ReplyDelivery(db,{clock:()=>1000});
    delivery.prepare('message');
    const next=delivery.retryLater('message',Error('start failed'));
    const row=db.prepare('SELECT state,result FROM outgoing WHERE id=?').get('message');
    assert.equal(row.state,'queued');assert.match(row.result,/has not been dispatched/);
    assert.ok(next>1000);assert.equal(delivery.ready('message'),false);
  }finally{db.close();}
});
