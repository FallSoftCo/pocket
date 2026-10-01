import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,verify} from 'node:crypto';
import {GitHub} from '../maintainer/github.mjs';
import {appJWT,installationToken} from '../maintainer/auth.mjs';
import {EventQueue,eventJobs} from '../maintainer/events.mjs';
import {reconsider,completedRun,postMerge} from '../maintainer/jobs.mjs';
import {missedDeliveries,recover} from '../maintainer/recovery.mjs';
import {failureNotifier,sendFailurePush} from '../maintainer/alerts.mjs';
const head='a'.repeat(40),base='b'.repeat(40),root='/repos/FallSoftCo/pocket';

test('fork CI requires workflow, head, repository and branch even without PR associations',async()=>{
 const gh=new GitHub('unused');const s={number:7,head,headRepoId:55,headRef:'fix',policy:{requiredChecks:['backend','android']}};
 const run={id:9,path:'.github/workflows/check.yml',event:'pull_request',head_sha:head,head_repository:{id:55},head_branch:'fix',pull_requests:[],status:'completed',conclusion:'success'};
 let current=run;
 gh.request=async path=>path.includes('/jobs?')?{jobs:[{name:'backend',status:'completed',conclusion:'success'},{name:'android',status:'completed',conclusion:'success'}]}:{workflow_runs:[current]};
 assert.equal(await gh.checksPass(s),true);
 for(const change of [{path:'.github/workflows/unrelated.yml'},{event:'push'},{head_sha:base},{head_repository:{id:56}},{head_branch:'other'},{pull_requests:[{number:8}]},{status:'in_progress'},{conclusion:'failure'}]){
  current={...run,...change};assert.equal(await gh.checksPass(s),false);
 }
});
test('fork CI completion resolves its source branch when both associations are absent',async()=>{
 const gh=new GitHub('unused'),q=new EventQueue(':memory:');
 const run={id:9,event:'pull_request',path:'.github/workflows/check.yml',status:'completed',head_sha:head,head_repository:{id:55,owner:{login:'contributor'}},head_branch:'fix',pull_requests:[]};
 gh.pages=async path=>{assert.match(path,/head=contributor%3Afix/);return [{number:7}];};
 gh.request=async path=>path.includes('/actions/runs/')?run:{number:7,state:'open',base:{ref:'main'},head:{sha:head,repo:{id:55},ref:'fix'}};
 await completedRun(gh,q,9,async()=>{});assert.equal(q.next().key,'pr:7');q.close();
});
test('only authors and current writers can submit bounded reconsideration evidence',async()=>{
 const gh=new GitHub('unused');const c=(id,login,body='/pocket reconsider This fixes the stated behavior.')=>({id,user:{login,type:'User'},body,updated_at:'today'});
 gh.comments=async()=>[c(1,'stranger'),c(2,'author','ordinary comment'),c(3,'author'),c(4,'writer'),c(5,'author'),c(6,'author')];
 gh.request=async path=>({permission:path.includes('/writer/')?'write':'read'});
 assert.deepEqual((await gh.clarifications({number:7,user:{login:'author'}})).map(x=>x.id),[3,4,5]);
});
test('reconsideration cannot reopen human-closed, held, or merged PRs',async()=>{
 for(const extra of [{merged:true},{labels:['maintainer:hold']},{state:'closed'}]){
  const q=new EventQueue(':memory:'),writes=[];
  const gh={root,request:async(path,o)=>{if(o)writes.push(o);return {id:8,updated_at:'today',issue_url:'https://api.github.com'+root+'/issues/7'};},
   snapshot:async()=>({state:'open',labels:[],clarifications:[{id:8}],...extra}),comments:async()=>[],pages:async()=>[{event:'closed',actor:{login:'human'}}]};
  await reconsider(gh,q,8);assert.equal(q.next(),undefined);assert.equal(writes.length,0);q.close();
 }
});
test('authorized reconsideration queues fresh review and acknowledgement only once',async()=>{
 const q=new EventQueue(':memory:'),comments=[],writes=[];
 const gh={root,request:async(path,o)=>{if(!o)return {id:8,updated_at:'today',issue_url:'https://api.github.com'+root+'/issues/7'};writes.push(o);comments.push({user:{type:'Bot'},body:o.body.body});},
  snapshot:async()=>({state:'open',labels:[],clarifications:[{id:8}]}),comments:async()=>comments};
 await reconsider(gh,q,8);await reconsider(gh,q,8);assert.equal(q.next().key,'pr:7');assert.equal(writes.length,1);q.close();
});
test('post-merge verification dispatches immutable SHA once and alerts on failure',async()=>{
 const q=new EventQueue(':memory:'),writes=[],alerts=[];let runs=[];
 const gh={root,request:async(path,o)=>{if(o){writes.push(o);return null;}return path.includes('/pulls/')?{merged:true,base:{ref:'main'},merge_commit_sha:head}:{workflow_runs:runs};}};
 const alert=async(...args)=>alerts.push(args);
 await postMerge(gh,q,7,alert);await postMerge(gh,q,7,alert);assert.equal(writes.length,1);assert.deepEqual(writes[0].body,{ref:'main',inputs:{commit:head}});
 runs=[{id:9,event:'workflow_dispatch',display_title:'Post-merge '+head,head_branch:'main',status:'completed',conclusion:'failure',run_attempt:1}];
 await postMerge(gh,q,7,alert);assert.equal(alerts.length,1);q.close();
});
test('failure push is independent of Pocket backend and targets only configured devices',async()=>{
 const {privateKey}=generateKeyPairSync('rsa',{modulusLength:2048});const requests=[];
 await sendFailurePush({credentials:{client_email:'test@example.org',project_id:'synthetic',private_key:privateKey},targets:[{device_id:'phone',token:'synthetic-device-token'}]},
  {key:'failure',title:'Attention',body:'Processing failed'}, {request:async(url,o)=>{requests.push({url,...o});return {ok:true,json:async()=>({access_token:'synthetic-access'})};}});
 assert.equal(requests.length,2);const message=JSON.parse(requests[1].body).message;
 assert.equal(message.data.device_id,'phone');assert.equal(message.data.operations,'failure');assert.equal(message.android.priority,'high');assert.equal(message.notification.title,'Attention');assert.ok(!message.data.thread_id);
});
test('failure alerts deduplicate only after successful delivery',async()=>{
 const q=new EventQueue(':memory:');let calls=0,fail=true;
 const alert=failureNotifier({notifyErrors:true,alerts:{}},q,{send:async()=>{calls++;if(fail)throw Error('offline');}});
 await assert.rejects(alert('x','title','body'));fail=false;await alert('x','title','body');await alert('x','title','body');assert.equal(calls,2);q.close();
});
test('missed-delivery recovery uses delivery history, retains exact IDs, and skips successful redeliveries',async()=>{
 const now=Date.now(),d=(id,guid,status_code,age=120000)=>({id,guid,status_code,delivered_at:new Date(now-age).toISOString()});
 const rows=[d('12345678901234567890','failed',503),d('2','fixed',503),d('3','fixed',202),d('4','recent',503,1000),d('5','expired',503,4*86400000)];
 assert.deepEqual(missedDeliveries(rows,now).map(x=>x.id),['12345678901234567890']);
 const calls=[];const gh={root,cursorPages:async path=>{assert.match(path,/\/hooks\/123\/deliveries$/);return rows;},request:async(path,o)=>calls.push({path,...o})};
 assert.equal(await recover(gh,123),1);assert.match(calls[0].path,/12345678901234567890\/attempts$/);
});
test('webhook history follows cursor links without sending page parameters',async()=>{
 const gh=new GitHub('unused'),seen=[];
 gh.request=async(path,options)=>{
  seen.push(path);assert.equal(options.responseMeta,true);assert.ok(!/[?&]page=/.test(path));
  return seen.length===1?{data:[{id:'12345678901234567890'}],next:root+'/hooks/123/deliveries?cursor=abc&per_page=100'}:{data:[{id:'12345678901234567891'}],next:null};
 };
 assert.equal((await gh.cursorPages(root+'/hooks/123/deliveries')).length,2);assert.equal(seen.length,2);
});
test('installation tokens refresh before expiry and never request other repositories',async()=>{
 const {privateKey,publicKey}=generateKeyPairSync('rsa',{modulusLength:2048});let now=Date.now(),calls=0;
 const jwt=appJWT(1,privateKey,now),parts=jwt.split('.');assert.ok(verify('RSA-SHA256',Buffer.from(parts.slice(0,2).join('.')),publicKey,Buffer.from(parts[2],'base64url')));
 const token=installationToken({appId:1,installationId:2,repositoryId:3,privateKey},{now:()=>now,request:async(url,o)=>{
  calls++;assert.deepEqual(JSON.parse(o.body),{repository_ids:[3]});return {ok:true,json:async()=>({token:'synthetic-'+calls,expires_at:new Date(now+3600000).toISOString(),repositories:[{id:3}]})};
 }});
 assert.deepEqual(await Promise.all([token(),token()]),['synthetic-1','synthetic-1']);now+=3550000;assert.equal(await token(),'synthetic-2');assert.equal(calls,2);
});
test('health reports stranded work and signed events route comments, CI, and merges',()=>{
 const q=new EventQueue(':memory:');assert.equal(q.healthy(),true);q.enqueue('pr:7',Date.now()-21*60000);assert.equal(q.healthy(),false);q.complete(q.next());assert.equal(q.healthy(),true);q.close();
 const repository={id:42};assert.deepEqual(eventJobs('pull_request',{repository,number:7,action:'closed',pull_request:{merged:true,base:{ref:'main'}}},42),['postmerge:7']);
 assert.deepEqual(eventJobs('issue_comment',{repository,action:'created',issue:{pull_request:{}},comment:{id:8,body:'/pocket reconsider Evidence here'}},42),['comment:8']);
 assert.deepEqual(eventJobs('workflow_run',{repository,action:'completed',workflow_run:{id:9,path:'.github/workflows/check.yml',event:'pull_request'}},42),['run:9']);
});
test('external health includes failed or stalled webhook recovery',()=>{
 const q=new EventQueue(':memory:');q.set('recovery-health',{at:Date.now(),ok:false});assert.equal(q.healthy(),false);
 q.set('recovery-health',{at:Date.now()-46*60000,ok:true});assert.equal(q.healthy(),false);
 q.set('recovery-health',{at:Date.now(),ok:true});assert.equal(q.healthy(),true);q.close();
});
