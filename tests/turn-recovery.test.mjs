import {mkdtempSync,rmSync} from 'node:fs';import {tmpdir} from 'node:os';import {join} from 'node:path';
import test from 'node:test';import assert from 'node:assert/strict';import {DatabaseSync} from 'node:sqlite';
import {TurnRecovery,transientFailure,RECOVERY_INPUT,permissionRecoveryAudit} from '../server/turn-recovery.mjs';
function fixture(){const db=new DatabaseSync(':memory:');let time=0,healthy=true,calls=[];let latest={id:'original',status:'failed',error:{message:'Selected model is at capacity.'},items:[{type:'agentMessage',text:'Saved useful progress'}]};const args={db,clock:()=>time,random:()=>.5,read:async()=>({turns:[latest]}),health:async()=>healthy,start:async(id,request,text)=>{calls.push({id,request,text});latest={id:'retry-'+calls.length,status:'inProgress',items:[{type:'userMessage',clientId:request}]};return {turn:latest};}};return {db,args,get latest(){return latest;},set latest(v){latest=v;},calls,advance:(delta=1e6)=>time+=delta,unhealthy:()=>healthy=false};}
test('definitive runtime resource failures can recover without reclassifying user stops',()=>{
 for(const message of ['No file descriptors available (os error 24)','No space left on device','ENOSPC','Too many open files'])assert.equal(transientFailure({message}),'transient');
 assert.equal(transientFailure({message:'User stopped task for usage policy'}),null);
});
test('classification distinguishes capacity/runtime from user revocation and generic errors',()=>{assert.equal(transientFailure({codexErrorInfo:'serverOverloaded'}),'transient');assert.equal(transientFailure({message:'Error running remote compact task: Fatal error: application network permission was revoked'}),'permission');assert.equal(transientFailure({message:'User revoked access'}),null);assert.equal(transientFailure({message:'invalid paginated history lineage'}),null);});
test('repeated failure events and restart preserve one backoff continuation chain',async()=>{const f=fixture();let r=new TurnRecovery(f.args);r.observe('thread',f.latest);r.observe('thread',f.latest);f.advance();await Promise.all([r.tick(),r.tick()]);assert.equal(f.calls.length,1);assert.equal(f.calls[0].text,RECOVERY_INPUT);assert.match(RECOVERY_INPUT,/Do not replay/);for(let i=1;i<=4;i++){f.latest={...f.latest,status:'failed',error:{message:'Selected model is at capacity.'}};r.observe('thread',f.latest);r=new TurnRecovery(f.args);f.advance();await r.tick();}assert.equal(f.calls.length,4);assert.equal(r.get('thread').state,'waiting');});
test('stop/manual change and real health failure never launch a worker',async()=>{const f=fixture();const r=new TurnRecovery(f.args);r.observe('thread',f.latest);r.cancel('thread');r.observe('thread',f.latest);f.advance();await r.tick();assert.equal(f.calls.length,0);f.latest={...f.latest,id:'new-failure'};r.observe('thread',f.latest);f.unhealthy();for(let i=0;i<4;i++){f.advance();await r.tick();}assert.equal(f.calls.length,0);assert.equal(r.get('thread').state,'waiting');});
test('uncertain acknowledgement is reconciled but never resent after restart',async()=>{const f=fixture();f.args.start=async()=>{f.calls.push('uncertain');throw Error('Lost acknowledgement');};let r=new TurnRecovery(f.args);r.observe('thread',f.latest);f.advance();await r.tick();assert.equal(r.get('thread').state,'unknown');r=new TurnRecovery(f.args);f.advance();await r.tick();assert.equal(r.get('thread').state,'reconcile');assert.equal(f.calls.length,1);});
test('permission audit is mandatory and new user turns supersede pending recovery',async()=>{const f=fixture();let kind;f.args.health=async(id,k)=>{kind=k;return false;};const r=new TurnRecovery(f.args);f.latest={...f.latest,error:{message:'application network permission was revoked'}};r.observe('thread',f.latest);f.advance();await r.tick();assert.equal(kind,'permission');assert.equal(f.calls.length,0);f.latest={id:'user-new',status:'inProgress'};f.advance();await r.tick();assert.equal(r.get('thread').state,'cancelled');});
test('accepted turn with lost acknowledgement is recovered by exact request identity without a second start',async()=>{const f=fixture();f.args.start=async(id,request)=>{f.calls.push(request);f.latest={id:'accepted',status:'inProgress',items:[{type:'userMessage',clientId:request}]};throw Error('lost ACK');};let r=new TurnRecovery(f.args);r.observe('thread',f.latest);f.advance();await r.tick();r=new TurnRecovery(f.args);f.advance();await r.tick();assert.equal(f.calls.length,1);assert.equal(r.get('thread').current_turn,'accepted');assert.equal(r.get('thread').state,'running');f.latest={...f.latest,status:'completed'};r.observe('thread',f.latest);assert.equal(r.get('thread').state,'completed');});
test('cancellation during asynchronous health audit prevents dispatch',async()=>{const f=fixture();let r;f.args.health=async()=>{r.cancel('thread');return true;};r=new TurnRecovery(f.args);r.observe('thread',f.latest);f.advance();await r.tick();assert.equal(f.calls.length,0);assert.equal(r.get('thread').state,'cancelled');});
test('failed audit after concurrent cancellation never resurrects retry',async()=>{const f=fixture();let r;f.args.health=async()=>{r.cancel('thread');throw Error('health request failed');};r=new TurnRecovery(f.args);r.observe('thread',f.latest);f.advance();await r.tick();assert.equal(r.get('thread').state,'cancelled');f.advance();await r.tick();assert.equal(f.calls.length,0);});
test('cancellation during accepted start interrupts that exact new turn',async()=>{const f=fixture();let r;const stopped=[];f.args.start=async()=>{r.cancel('thread');return {turn:{id:'accepted-after-cancel',status:'inProgress'}};};f.args.stop=async(id,turn)=>stopped.push({id,turn});r=new TurnRecovery(f.args);r.observe('thread',f.latest);f.advance();await r.tick();assert.deepEqual(stopped,[{id:'thread',turn:'accepted-after-cancel'}]);assert.equal(r.get('thread').state,'cancelled');});
test('network policy unavailability requires the same permission audit',()=>assert.equal(transientFailure({message:'application network policy is unavailable'}),'permission'));
test('hours away: prolonged runtime and capacity outage survives restarts and quietly completes later',async()=>{
 const db=new DatabaseSync(':memory:');let now=0,latest={id:'initial',status:'failed',error:{message:'application network permission was revoked'}},reads=0,starts=0;const notices=[];
 const args={db,clock:()=>now,random:()=>.5,read:async()=>{reads++;return {turns:[latest]};},health:async()=>now>=3*3600000,start:async()=>{starts++;latest={id:'retry-'+starts,status:now<8*3600000?'failed':'completed',error:now<8*3600000?{message:'Selected model is at capacity.'}:null,items:[]};return {turn:latest};},publish:(_id,event)=>{if(event.message)notices.push(event.message);}};
 let recovery=new TurnRecovery(args);recovery.observe('thread',latest);
 for(now=0;now<=24*3600000;now+=60000){if(now%3600000===0)recovery=new TurnRecovery(args);await recovery.tick();if(now===6*3600000)assert.equal(recovery.get('thread').state,'waiting');}
 assert.equal(recovery.get('thread').state,'completed');assert.ok(starts>=2&&starts<=6);assert.ok(reads<=15,`only due health/history reads, got ${reads}`);assert.deepEqual(notices,[]);
});
test('actual permission revocation blocks without inference; quota reset waits without user interaction',async()=>{
 const f=fixture();f.args.health=async()=>({ok:false,blocked:true,reason:'Permissions revoked'});const r=new TurnRecovery(f.args);r.observe('thread',f.latest);f.advance();await r.tick();assert.equal(r.get('thread').state,'blocked');for(let i=0;i<20;i++){f.advance(3600000);await r.tick();}assert.equal(f.calls.length,0);
 const g=fixture();g.args.health=async()=>({ok:false,nextAt:7*24*3600000});const q=new TurnRecovery(g.args);q.observe('thread',g.latest);g.advance();await q.tick();assert.equal(q.get('thread').next_at,7*24*3600000);assert.equal(q.get('thread').state,'waiting');assert.equal(g.calls.length,0);
});
test('backoff jitter is capped at four hours and duplicates/restarts never reset sparse due time',async()=>{
 const f=fixture();const r=new TurnRecovery({...f.args,random:()=>1});assert.equal(r.delay(100),4*3600000);r.observe('thread',f.latest);f.unhealthy();for(let i=0;i<4;i++){f.advance(3600000);await r.tick();}const due=r.get('thread').next_at;r.observe('thread',f.latest);const restarted=new TurnRecovery(f.args);assert.equal(restarted.get('thread').next_at,due);assert.equal(restarted.get('thread').state,'waiting');
});
test('replayed retry failure preserves sparse due time and durable quiet identity',async()=>{const f=fixture();let r=new TurnRecovery(f.args);r.observe('thread',f.latest);f.advance();await r.tick();f.latest={...f.latest,status:'failed',error:{message:'Selected model is at capacity.'}};r.observe('thread',f.latest);const due=r.get('thread').next_at;f.advance(1000);r.observe('thread',f.latest);assert.equal(r.get('thread').next_at,due);r=new TurnRecovery(f.args);assert.ok(r.recoverableFailure('thread','original'));assert.ok(r.recoverableFailure('thread',f.latest.id));assert.equal(r.get('thread').next_at,due);});
test('temporarily missing history waits sparsely instead of reading every scheduler tick',async()=>{const f=fixture();let reads=0;f.args.read=async()=>{reads++;return {turns:[]};};const r=new TurnRecovery(f.args);r.observe('thread',f.latest);f.advance();await r.tick();const next=r.get('thread').next_at;for(let n=0;n<5;n++){f.advance(1000);await r.tick();}assert.equal(reads,1);assert.equal(r.get('thread').next_at,next);assert.equal(r.get('thread').state,'waiting');});

test('incomplete permission health evidence waits; explicit changed permissions block without restoring access',()=>{assert.equal(permissionRecoveryAudit({sandbox:{type:'dangerFullAccess'}}),'waiting');assert.equal(permissionRecoveryAudit({sandbox:{type:'workspaceWrite'},activePermissionProfile:{id:':workspace-write'}}),'blocked');assert.equal(permissionRecoveryAudit({sandbox:{type:'dangerFullAccess'},activePermissionProfile:{id:':danger-full-access'}}),'verified');});
test('reconnect recovers only new latest failures inside an existing watch boundary',()=>{const f=fixture();const r=new TurnRecovery(f.args),checkpoint={since:1000,initialized:1};assert.equal(r.observeMissed({id:'old',turns:[{...f.latest,completedAt:.5}]},checkpoint),false);assert.equal(r.observeMissed({id:'seen',turns:[{...f.latest,completedAt:2}]},checkpoint,true),false);assert.equal(r.observeMissed({id:'superseded',turns:[f.latest,{id:'new',status:'completed'}]},checkpoint),false);assert.equal(r.observeMissed({id:'unwatched',turns:[f.latest]},null),false);assert.equal(r.observeMissed({id:'new-failure',turns:[{...f.latest,completedAt:2}]},checkpoint),true);assert.equal(r.get('new-failure').state,'waiting');assert.equal(f.calls.length,0);});
test('a stale unrelated interruption cannot cancel a current recovery chain',()=>{const f=fixture();const r=new TurnRecovery(f.args);r.observe('thread',f.latest);r.observe('thread',{id:'older-interruption',status:'interrupted'});assert.equal(r.get('thread').state,'waiting');r.observe('thread',{id:'original',status:'interrupted'});assert.equal(r.get('thread').state,'cancelled');});

test('explicit audit continuation survives restart without inferring old idle work',async()=>{
 const f=fixture();f.latest={id:'partial',status:'completed'};let r=new TurnRecovery(f.args);
 assert.equal(r.observe('thread',f.latest),false);assert.equal(r.get('thread'),undefined);
 const evidence={turnId:'partial',authorized:true,unfinished:true,evidence:'Latest user task remains unfinished; files and delivery inspected'};
 assert.equal(r.authorizeContinuation({id:'thread',turns:[f.latest]},evidence),true);
 const due=r.get('thread').next_at;assert.equal(r.observe('thread',f.latest),true);
 r=new TurnRecovery(f.args);assert.equal(r.get('thread').next_at,due);f.advance();await r.tick();assert.equal(f.calls.length,1);
 f.latest={...f.latest,status:'completed'};r.reportContinuation('thread',f.latest.id,{status:'completed',evidence:'Requested outcome verified'});r.observe('thread',f.latest);f.advance(24*3600000);await r.tick();assert.equal(f.calls.length,1);assert.equal(r.get('thread').state,'completed');
});
test('runtime interruption admission requires current evidence and preserves stops and input blockers',async()=>{
 const f=fixture();f.latest={id:'cut',status:'interrupted'};const r=new TurnRecovery(f.args),thread={id:'thread',turns:[f.latest]};
 const assessment={turnId:'cut',authorized:true,unfinished:true,runtimeInterrupted:true,evidence:'Runtime interrupted unfinished authorized task; no stop or outstanding request'};
 for(const key of ['explicitStop','cancelled','needsInput','usageStopped','managedChild','uncertainDelivery','completed'])assert.equal(r.authorizeContinuation(thread,{...assessment,[key]:true}),false,key);
 assert.equal(r.authorizeContinuation(thread,{...assessment,turnId:'stale'}),false);
 assert.equal(r.authorizeContinuation(thread,{...assessment,runtimeInterrupted:false}),false);
 assert.equal(r.authorizeContinuation(thread,assessment),true);r.cancel('thread');assert.equal(r.authorizeContinuation(thread,assessment),false);f.advance();await r.tick();assert.equal(f.calls.length,0);
});
test('authorized running recovery interruption can resume gently after hours and service restarts',async()=>{
 const f=fixture();let r=new TurnRecovery(f.args);r.observe('thread',f.latest);f.advance();await r.tick();f.latest={...f.latest,status:'interrupted'};
 assert.equal(r.observe('thread',f.latest,{authorized:true,unfinished:true,runtimeInterrupted:true,evidence:'Audited daemon interruption, no explicit cancellation or input'}),true);
 f.unhealthy();let reads=0;const read=f.args.read;f.args.read=async()=>{reads++;return read();};
 for(let n=0;n<12;n++){f.advance(3600000);r=new TurnRecovery(f.args);await r.tick();}
 assert.equal(f.calls.length,1);assert.equal(r.get('thread').state,'waiting');assert.ok(reads<=9,`sparse reads: ${reads}`);
});
test('authorized terminal continuation is superseded by new user work before dispatch',async()=>{
 const f=fixture();f.latest={id:'partial',status:'completed'};const r=new TurnRecovery(f.args);r.authorizeContinuation({id:'thread',turns:[f.latest]},{turnId:'partial',authorized:true,unfinished:true,evidence:'Audited incomplete task'});
 f.latest={id:'new-user',status:'inProgress'};f.advance();await r.tick();assert.equal(f.calls.length,0);assert.equal(r.get('thread').state,'cancelled');
});
test('repeated audited interruptions retain original chain and sparse retry budget',async()=>{
 const f=fixture();let r=new TurnRecovery(f.args);r.observe('thread',f.latest);
 for(let n=0;n<5;n++){f.advance(5*3600000);await r.tick();f.latest={...f.latest,status:'interrupted'};r.observe('thread',f.latest,{authorized:true,unfinished:true,runtimeInterrupted:true,evidence:'Verified runtime interruption, not a user stop'});r=new TurnRecovery(f.args);assert.equal(r.get('thread').source_turn,'original');assert.equal(r.get('thread').attempts,n+1);}
 assert.ok(r.get('thread').next_at>=f.args.clock()+30*60000);
});
test('file-backed continuation remains due across database close and new service instance',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'nextcomp-recovery-'));let db;try{
  let now=0,starts=0;const latest={id:'saved-partial',status:'completed'};
  const args={clock:()=>now,random:()=>.5,read:async()=>({turns:[latest]}),health:async()=>true,start:async()=>{starts++;return {turn:{id:'resumed',status:'inProgress'}};}};
  db=new DatabaseSync(join(dir,'recovery.sqlite'));let r=new TurnRecovery({...args,db});r.authorizeContinuation({id:'thread',turns:[latest]},{turnId:latest.id,authorized:true,unfinished:true,evidence:'Exact latest task and receipts audited'});const due=r.get('thread').next_at;db.close();
  now=12*3600000;db=new DatabaseSync(join(dir,'recovery.sqlite'));r=new TurnRecovery({...args,db});assert.equal(r.get('thread').next_at,due);await Promise.all([r.tick(),r.tick()]);assert.equal(starts,1);assert.equal(r.get('thread').state,'running');
 }finally{db?.close();rmSync(dir,{recursive:true,force:true});}
});
test('durable worker checkpoints continue partial finals until completion or real input',async()=>{
 const f=fixture();f.latest={id:'partial',status:'completed'};let r=new TurnRecovery(f.args);r.authorizeContinuation({id:'thread',turns:[f.latest]},{turnId:'partial',authorized:true,unfinished:true,evidence:'Audited unfinished work'});
 f.advance();await r.tick();assert.match(f.calls[0].text,/task-checkpoint.mjs/);
 assert.equal(r.reportContinuation('thread','wrong',{status:'continue',evidence:'stale'}),false);
 r.reportContinuation('thread',f.latest.id,{status:'continue',evidence:'One requested deliverable remains; independent build in progress'});f.latest={...f.latest,status:'completed'};assert.equal(r.observe('thread',f.latest),true);r=new TurnRecovery(f.args);f.advance();await r.tick();assert.equal(f.calls.length,2);assert.equal(r.get('thread').source_turn,'partial');
 f.latest={...f.latest,status:'completed'};r.observe('thread',f.latest);assert.equal(r.get('thread').state,'awaitingAssessment');f.advance(24*3600000);await r.tick();assert.equal(f.calls.length,2);
 r.reportContinuation('thread',f.latest.id,{status:'needsInput',evidence:'User must provide a missing account credential'});assert.equal(r.get('thread').state,'blocked');
});
test('checkpoint latest-turn binding, duplicate receipts and cancellation cannot rewrite status',async()=>{
 const f=fixture();f.latest={id:'partial',status:'completed'};const r=new TurnRecovery(f.args);r.authorizeContinuation({id:'thread',turns:[f.latest]},{turnId:'partial',authorized:true,unfinished:true,evidence:'Audited incomplete task'});f.advance();await r.tick();
 const checkpoint={turnId:f.latest.id,state:'continue',evidence:'Still implementing independently'};assert.equal(r.recordCheckpoint({id:'thread',turns:[{id:'other'}]},checkpoint),false);
 assert.equal(r.recordCheckpoint({id:'thread',turns:[f.latest]},checkpoint),true);assert.equal(r.recordCheckpoint({id:'thread',turns:[f.latest]},checkpoint),true);assert.equal(r.recordCheckpoint({id:'thread',turns:[f.latest]},{...checkpoint,state:'completed'}),false);
 r.cancel('thread');assert.equal(r.recordCheckpoint({id:'thread',turns:[f.latest]},checkpoint),false);
});
test('worker checkpoint reconciles exact accepted receipt after lost acknowledgement without resend',async()=>{
 const f=fixture();f.latest={id:'partial',status:'completed'};f.args.start=async(id,request)=>{f.calls.push(request);f.latest={id:'accepted-no-ack',status:'completed',items:[{clientUserMessageId:request}]};throw Error('lost acknowledgement');};const r=new TurnRecovery(f.args);r.authorizeContinuation({id:'thread',turns:[f.latest]},{turnId:'partial',authorized:true,unfinished:true,evidence:'Audited unfinished scope'});f.advance();await r.tick();assert.equal(r.get('thread').state,'unknown');
 assert.equal(r.recordCheckpoint({id:'thread',turns:[{...f.latest,items:[]}]},{turnId:f.latest.id,state:'completed',evidence:'Verified result'}),false);
 assert.equal(r.recordCheckpoint({id:'thread',turns:[f.latest]},{turnId:f.latest.id,state:'completed',evidence:'Verified result'}),true);r.observe('thread',f.latest);assert.equal(r.get('thread').state,'completed');f.advance(24*3600000);await r.tick();assert.equal(f.calls.length,1);
});
test('five successful progress chunks stay quick while health outages retain sparse backoff',async()=>{
 const f=fixture();let healthy=true;f.args.health=async()=>healthy;f.latest={id:'partial',status:'completed'};let r=new TurnRecovery(f.args);r.authorizeContinuation({id:'thread',turns:[f.latest]},{turnId:'partial',authorized:true,unfinished:true,evidence:'Audited incomplete work'});
 for(let n=0;n<5;n++){
  assert.equal(r.get('thread').next_at-f.args.clock(),30000);f.advance(30000);await r.tick();assert.equal(f.calls.length,n+1);
  f.latest={...f.latest,status:'completed'};const checkpoint={turnId:f.latest.id,state:'continue',evidence:'Completed concrete step '+n+'; remaining independent work'};
  assert.equal(r.recordCheckpoint({id:'thread',turns:[f.latest]},checkpoint),true);const due=r.get('thread').next_at;
  assert.equal(r.recordCheckpoint({id:'thread',turns:[f.latest]},checkpoint),true);assert.equal(r.get('thread').next_at,due);assert.equal(r.get('thread').attempts,n+1);assert.equal(r.get('thread').backoff_attempts,0);assert.equal(r.get('thread').source_turn,'partial');r=new TurnRecovery(f.args);
 }
 healthy=false;for(let n=0;n<5;n++){f.advance(5*3600000);await r.tick();}const row=r.get('thread');assert.equal(row.backoff_attempts,5);assert.equal(row.attempts,10);assert.ok(row.next_at>=f.args.clock()+3600000);
 r.observe('thread',f.latest);assert.equal(r.get('thread').next_at,row.next_at);assert.equal(r.get('thread').backoff_attempts,5);r=new TurnRecovery(f.args);assert.equal(r.get('thread').next_at,row.next_at);
});
test('original audited scope survives worker progress checkpoints with bounded prompt context',async()=>{
 const f=fixture();f.latest={id:'partial',status:'completed'};const r=new TurnRecovery(f.args);r.authorizeContinuation({id:'thread',turns:[f.latest]},{turnId:'partial',authorized:true,unfinished:true,evidence:'One Jobsearcher repair owner. No emails or applications authorized.'});f.advance();await r.tick();
 f.latest={...f.latest,status:'completed'};r.recordCheckpoint({id:'thread',turns:[f.latest]},{turnId:f.latest.id,state:'continue',evidence:'Service repair step completed; validation remains'});f.advance(30000);await r.tick();assert.match(f.calls[1].text,/No emails or applications authorized/);assert.match(f.calls[1].text,/validation remains/);
});
test('restart never labels authorized partial finals as failures; actual failure observations remain durable',async()=>{
 const f=fixture();f.latest={id:'partial-final',status:'completed'};let r=new TurnRecovery(f.args);r.authorizeContinuation({id:'thread',turns:[f.latest]},{turnId:f.latest.id,authorized:true,unfinished:true,evidence:'Incomplete authorized deliverable'});r=new TurnRecovery(f.args);assert.equal(r.recoverableFailure('thread','partial-final'),false);f.advance();await r.tick();
 const completedTurn=f.latest.id;f.latest={...f.latest,status:'completed'};r.recordCheckpoint({id:'thread',turns:[f.latest]},{turnId:completedTurn,state:'continue',evidence:'Useful completed step; deliverable remains'});r=new TurnRecovery(f.args);assert.equal(r.recoverableFailure('thread',completedTurn),false);f.advance(30000);await r.tick();
 const failed=f.latest.id;f.latest={...f.latest,status:'failed',error:{message:'Selected model is at capacity'}};r.observe('thread',f.latest);r=new TurnRecovery(f.args);assert.equal(r.recoverableFailure('thread',failed),true);assert.equal(r.recoverableFailure('thread','partial-final'),false);assert.equal(r.recoverableFailure('thread',completedTurn),false);
});
