import test from 'node:test';
import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {EventQueue,EventPump,eventJobs,validSignature,webhookServer} from '../maintainer/events.mjs';
const repo=1391353198,secret='synthetic-test-secret-'.repeat(3);
const payload={repository:{id:repo},action:'opened',number:7,pull_request:{base:{ref:'main'}}};
const sign=raw=>'sha256='+createHmac('sha256',secret).update(raw).digest('hex');

test('signature binds exact raw bytes and rejects malformed or missing values',()=>{
 const raw=Buffer.from(JSON.stringify(payload));assert.equal(validSignature(raw,sign(raw),secret),true);
 for(const header of [null,'sha256=bad','sha1='+'a'.repeat(40),sign(Buffer.from('changed'))])assert.equal(validSignature(raw,header,secret),false);
 assert.equal(validSignature(Buffer.concat([raw,Buffer.from(' ')]),sign(raw),secret),false);
});
test('only relevant PR, CI, apply, and base events create typed work',()=>{
 assert.deepEqual(eventJobs('pull_request',payload,repo),['pr:7']);
 assert.deepEqual(eventJobs('pull_request',{...payload,repository:{id:999}},repo),[]);
 assert.deepEqual(eventJobs('pull_request',{...payload,number:'7; command'},repo),[]);
 assert.deepEqual(eventJobs('pull_request',{...payload,action:'labeled',label:{name:'maintainer:ready'}},repo),[]);
 assert.deepEqual(eventJobs('pull_request',{...payload,action:'unlabeled',label:{name:'maintainer:hold'}},repo),['pr:7']);
 assert.deepEqual(eventJobs('issue_comment',payload,repo),[]);
 const workflow={repository:{id:repo},action:'completed',workflow:{path:'.github/workflows/check.yml'},workflow_run:{event:'pull_request',pull_requests:[{number:7}],head_sha:'a'.repeat(40)}};
 assert.deepEqual(eventJobs('workflow_run',workflow,repo),['pr:7']);
 assert.deepEqual(eventJobs('workflow_run',{...workflow,action:'requested'},repo),[]);
 assert.deepEqual(eventJobs('workflow_run',{...workflow,workflow_run:{...workflow.workflow_run,pull_requests:[]}},repo),['commit:'+'a'.repeat(40)]);
 assert.deepEqual(eventJobs('workflow_run',{...workflow,workflow:{path:'.github/workflows/maintainer.yml'},workflow_run:{event:'workflow_dispatch'}},repo),['reconcile']);
 assert.deepEqual(eventJobs('push',{repository:{id:repo},ref:'refs/heads/main'},repo),['reconcile']);
 assert.deepEqual(eventJobs('push',{repository:{id:repo},ref:'refs/heads/topic'},repo),[]);
});
test('durable acknowledgement, deduplication, and arrivals during work survive restart',()=>{
 const dir=mkdtempSync(join(tmpdir(),'pocket-queue-test-')),path=join(dir,'queue.sqlite');let q=new EventQueue(path);
 try{
  assert.equal(q.accept('delivery-a','digest-a',['pr:7']),true);const first=q.next();
  assert.equal(q.accept('delivery-a','digest-a',['pr:7']),false);
  assert.equal(q.accept('different-id','digest-a',['pr:7']),false);
  q.close();q=new EventQueue(path);assert.equal(q.next().key,'pr:7');
  q.accept('delivery-b','digest-b',['pr:7']);q.complete(first);assert.ok(q.next(),'new event must survive completion of an older generation');
  q.complete(q.next());assert.equal(q.next(),undefined);
 }finally{q.close();rmSync(dir,{recursive:true,force:true});}
});
test('failed work has bounded retries and empty queue never schedules a timer',async()=>{
 const q=new EventQueue(':memory:');let calls=0;
 const pump=new EventPump(q,async()=>{calls++;});pump.wake();assert.equal(pump.timer,null);assert.equal(calls,0);
 q.enqueue('pr:7');pump.wake();await new Promise(r=>setImmediate(r));assert.equal(calls,1);assert.equal(q.next(),undefined);assert.equal(pump.timer,null);
 q.enqueue('pr:7');for(let i=0;i<7;i++)q.fail(q.next(),Date.now());assert.equal(q.next(),undefined);
 q.enqueue('pr:7');assert.equal(q.next().attempts,0);pump.stop();q.close();
});
test('HTTP validates before persistence, acknowledges before work, and deduplicates redelivery',async()=>{
 const queue=new EventQueue(':memory:');let wakes=0;
 const server=webhookServer({secret,repositoryId:repo,queue,wake:()=>wakes++});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const url=`http://127.0.0.1:${server.address().port}/github/events`;
 const raw=JSON.stringify(payload),headers={'X-GitHub-Event':'pull_request','X-GitHub-Delivery':'delivery-1','X-Hub-Signature-256':sign(raw)};
 try{
  assert.equal((await fetch(url,{method:'POST',body:raw})).status,401);assert.equal(queue.next(),undefined);
  assert.equal((await fetch(url,{method:'POST',headers,body:raw})).status,202);assert.equal(queue.next().key,'pr:7');assert.equal(wakes,1);
  assert.equal((await fetch(url,{method:'POST',headers,body:raw})).status,202);assert.equal(wakes,1);
  const other=JSON.stringify({...payload,repository:{id:999}});
  assert.equal((await fetch(url,{method:'POST',headers:{...headers,'X-Hub-Signature-256':sign(other)},body:other})).status,403);
  assert.equal((await fetch(url.replace('/github/events','/api/notify'),{method:'POST'})).status,404);
  assert.equal((await fetch(url)).status,404);
  assert.equal((await fetch(url,{method:'POST',headers,body:'x'.repeat(2*1024*1024+1)})).status,413);
 }finally{server.closeAllConnections();await new Promise(r=>server.close(r));queue.close();}
});
