import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawn} from 'node:child_process';
import {WorkAdmission} from '../server/work-admission.mjs';

function fixture(db=new DatabaseSync(':memory:'),maxConcurrent=2){let now=100000;const work=new WorkAdmission({db,clock:()=>now,maxConcurrent});return {db,work,advance:()=>now+=3600000,snapshot:()=>({complete:true,observedAt:now,activeThreadIds:[]})};}
function accept(f,id='transfer',taskId='backup',threadId='owner'){return f.work.accept({id,taskId,threadId,instruction:'Reconcile exact manifest and run remaining transfer',acceptance:'verified-manifest-and-restore-receipt',eventId:'user-accepted-'+id,source:'user'});}

test('acceptance is idempotent and changed payload cannot reuse work/event identity',()=>{
 const f=fixture();const u=accept(f);assert.deepEqual(accept(f),u);
 assert.throws(()=>f.work.accept({id:'transfer',taskId:'backup',threadId:'owner',instruction:'Different task',acceptance:'different',eventId:'user-accepted-transfer',source:'user'}),/Conflicting/);
 assert.throws(()=>f.work.accept({id:'worker',source:'worker'}),/owner\/user/);
});
test('waiting for a matching versioned dependency uses no model runner, timer or restart dispatch',()=>{
 const f=fixture();const u=accept(f);assert.equal(f.work.wait(u.id,{revision:u.revision,key:'archive-job-1',version:'generation-3'}),true);
 for(let i=0;i<48;i++){f.advance();assert.equal(f.work.claim(f.snapshot()),null);}
 const restarted=new WorkAdmission({db:f.db,clock:()=>100000+48*3600000});
 assert.equal(restarted.unit(u.id).state,'waiting');
 assert.throws(()=>restarted.signal(u.id,{eventId:'worker-says-continue',source:'worker',key:'archive-job-1',version:'generation-3'}),/Worker prose/);
 assert.equal(restarted.signal(u.id,{eventId:'old-job',source:'job',key:'archive-job-1',version:'generation-2'}),false);
 assert.equal(restarted.signal(u.id,{eventId:'real-job',source:'job',key:'archive-job-1',version:'generation-3'}),true);
 assert.equal(restarted.signal(u.id,{eventId:'real-job',source:'job',key:'archive-job-1',version:'generation-3'}),false);
});
test('two owners sharing one database cannot claim the same slot or work twice',()=>{
 const dir=mkdtempSync(join(tmpdir(),'work-admission-')),file=join(dir,'state.sqlite');let a,b;
 try{a=new DatabaseSync(file);b=new DatabaseSync(file);const f=fixture(a,1),other=fixture(b,1);accept(f);accept(f,'research','different-task','different-owner');
  assert.ok(f.work.claim(f.snapshot()));assert.equal(other.work.claim(other.snapshot()),null);
  assert.equal(a.prepare('SELECT count(*) n FROM work_admission_attempts').get().n,1);
 }finally{a?.close();b?.close();rmSync(dir,{recursive:true,force:true});}
});
test('one owner per task even when two thread names are supplied',()=>{
 const f=fixture();accept(f);accept(f,'second-owner','backup','other-thread');
 assert.ok(f.work.claim(f.snapshot()));assert.equal(f.work.claim(f.snapshot()),null);
});
test('actual unrelated runtime activity consumes capacity, partial/stale inventory is not spare capacity',()=>{
 const f=fixture();accept(f);
 assert.equal(f.work.claim({...f.snapshot(),activeThreadIds:['external-1','external-2']}),null);
 assert.equal(f.work.claim({...f.snapshot(),complete:false}),null);
 assert.equal(f.work.claim({...f.snapshot(),observedAt:0}),null);
 assert.equal(f.work.claim({...f.snapshot(),observedAt:Infinity}),null);
 assert.ok(f.work.claim({...f.snapshot(),activeThreadIds:['external-1']}));
});
test('a lost acknowledgement survives restart and never frees a slot by time alone',()=>{
 const f=fixture(undefined,1);accept(f);accept(f,'next','another-task','another-owner');const a=f.work.claim(f.snapshot());
 const restarted=new WorkAdmission({db:f.db,clock:()=>100000,maxConcurrent:1});assert.equal(restarted.recoverDispatches(),1);assert.equal(restarted.attempt(a.id).state,'unknown');
 for(let i=0;i<48;i++){f.advance();assert.equal(f.work.claim(f.snapshot()),null);}
 assert.equal(f.db.prepare('SELECT count(*) n FROM work_admission_attempts').get().n,1);
 assert.equal(restarted.accepted(a.id,'actual-matching-turn'),true);
 assert.equal(restarted.accepted(a.id,'different-turn'),false);
});
test('a model final releases compute but is not a completed work result or next-turn trigger',()=>{
 const f=fixture();accept(f);const a=f.work.claim(f.snapshot());f.work.accepted(a.id,'turn');
 assert.equal(f.work.settleAttempt(a.id,{turnId:'turn',status:'completed',receiptId:'runtime-1',effectsReconciled:true}),true);
 assert.equal(f.work.unit('transfer').state,'awaitingResult');assert.equal(f.work.claim(f.snapshot()),null);
 assert.throws(()=>f.work.acceptResult('transfer',{revision:2,eventId:'self-report',source:'worker',receipt:'done'}),/propose/);
 const u=f.work.unit('transfer');assert.equal(f.work.acceptResult(u.id,{revision:u.revision,eventId:'verified-manifest',source:'job',receipt:'fullSHA+restore-generation-3'}),true);
 assert.equal(f.work.unit(u.id).state,'completed');assert.equal(f.work.claim(f.snapshot()),null);
});
test('worker failure is preserved for recovery; failure events do not create fresh work',()=>{
 const f=fixture();accept(f);const a=f.work.claim(f.snapshot());f.work.accepted(a.id,'turn');
 f.work.settleAttempt(a.id,{turnId:'turn',status:'failed',receiptId:'failed-1',effectsReconciled:true});
 assert.equal(f.work.unit('transfer').state,'failed');assert.equal(f.work.claim(f.snapshot()),null);
});
test('cancellation during unknown delivery remains fenced until exact accepted turn is interrupted',()=>{
 const f=fixture(undefined,1);accept(f);const a=f.work.claim(f.snapshot());f.work.uncertain(a.id,'Lost ACK');f.work.cancel(a.unit_id);
 accept(f,'next','another-task','another-owner');assert.equal(f.work.claim(f.snapshot()),null);
 assert.equal(f.work.accepted(a.id,'late-accepted-turn'),true);assert.equal(f.work.attempt(a.id).state,'stopping');
 assert.equal(f.work.settleAttempt(a.id,{turnId:'other',status:'interrupted',receiptId:'wrong'}),false);
 assert.equal(f.work.settleAttempt(a.id,{turnId:'late-accepted-turn',status:'interrupted',receiptId:'real-stop',effectsReconciled:true}),true);
 assert.equal(f.work.unit(a.unit_id).state,'cancelled');assert.ok(f.work.claim(f.snapshot()));
});
test('stale revisions and replayed signals cannot revive cancelled or completed units',()=>{
 const f=fixture();const u=accept(f);assert.equal(f.work.wait(u.id,{revision:100,key:'job',version:'1'}),false);
 f.work.wait(u.id,{revision:0,key:'job',version:'1'});f.work.cancel(u.id);
 assert.equal(f.work.signal(u.id,{eventId:'job-late',source:'job',key:'job',version:'1'}),false);
 assert.equal(f.work.unit(u.id).state,'cancelled');assert.equal(f.work.claim(f.snapshot()),null);
});
test('dependency completion arriving before subscription is retained, but consumed generation cannot wake again',()=>{
 const f=fixture();const u=accept(f);
 assert.equal(f.work.signal(u.id,{eventId:'fast-job',source:'job',key:'archive-job',version:'generation-1'}),false);
 assert.equal(f.work.wait(u.id,{revision:u.revision,key:'archive-job',version:'generation-1'}),true);
 assert.equal(f.work.unit(u.id).state,'ready');
 const revision=f.work.unit(u.id).revision;
 f.work.wait(u.id,{revision,key:'archive-job',version:'generation-1'});
 assert.equal(f.work.unit(u.id).state,'waiting');
 assert.equal(f.work.signal(u.id,{eventId:'duplicate-fast-job',source:'job',key:'archive-job',version:'generation-1'}),false);
});
test('independent concurrent OS processes share one durable admission slot',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'work-admission-processes-')),file=join(dir,'state.sqlite');
 const db=new DatabaseSync(file);const f=fixture(db,1);accept(f);accept(f,'other','other-task','other-thread');db.close();
 const source=`import {DatabaseSync} from 'node:sqlite';import {WorkAdmission} from ${JSON.stringify(new URL('../server/work-admission.mjs',import.meta.url).href)};const db=new DatabaseSync(${JSON.stringify(file)});db.exec('PRAGMA busy_timeout=5000');const work=new WorkAdmission({db,clock:()=>100000,maxConcurrent:1});console.log(JSON.stringify(work.claim({complete:true,observedAt:100000,activeThreadIds:[]})));db.close();`;
 const run=()=>new Promise((resolve,reject)=>{const child=spawn(process.execPath,['--input-type=module','-e',source]);let out='',err='';child.stdout.on('data',b=>out+=b);child.stderr.on('data',b=>err+=b);child.on('error',reject);child.on('exit',code=>code?reject(Error(err)):resolve(JSON.parse(out)));});
 try{const results=await Promise.all([run(),run()]);assert.equal(results.filter(Boolean).length,1);const check=new DatabaseSync(file);assert.equal(check.prepare('SELECT count(*) n FROM work_admission_attempts').get().n,1);check.close();}
 finally{rmSync(dir,{recursive:true,force:true});}
});
test('a terminal native turn cannot free an uncertain external action for a successor',()=>{
 const f=fixture(undefined,1);accept(f);accept(f,'next','next-task','next-owner');const a=f.work.claim(f.snapshot());f.work.accepted(a.id,'turn');
 assert.equal(f.work.settleAttempt(a.id,{turnId:'turn',status:'failed',receiptId:'native-failed'}),true);
 assert.equal(f.work.attempt(a.id).state,'unknown');assert.equal(f.work.claim(f.snapshot()),null);
 assert.equal(f.work.settleAttempt(a.id,{turnId:'turn',status:'failed',receiptId:'independently-settled',effectsReconciled:true}),true);
 assert.ok(f.work.claim(f.snapshot()));
});
