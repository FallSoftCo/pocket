import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {openStore} from '../server/store.mjs';
import {CompletionRecovery} from '../server/completions.mjs';
import {speechText,spokenSummary} from '../server/speech.mjs';
import {pushData} from '../server/push.mjs';

test('completion recovery baselines history, persists receipts and catches unseen completed/failed turns once',t=>{
 const dir=mkdtempSync(join(tmpdir(),'pocket-recovery-'));const {db}=openStore(dir);t.after(()=>{db.close();rmSync(dir,{recursive:true,force:true});});
 db.prepare('INSERT INTO watches VALUES(?,?,1)').run('thread-one','Project');
 const sent=[];let recovery=new CompletionRecovery(db,(...args)=>sent.push(args));
 const historical={id:'old',status:'completed',items:[]};
 recovery.observe({id:'thread-one',turns:[historical,{id:'active',status:'inProgress'}]});assert.equal(sent.length,0);
 recovery=new CompletionRecovery(db,(...args)=>sent.push(args));
 const completed={id:'active',status:'completed',items:[{type:'agentMessage',text:'Tests passed.'}]};
 const failed={id:'offline-turn',status:'failed',error:{message:'Build failed'}};
 recovery.observe({id:'thread-one',turns:[historical,completed,failed,{id:'stopped',status:'interrupted'}]});
 recovery.complete('thread-one',completed);recovery.observe({id:'thread-one',turns:[completed,failed]});
 assert.equal(sent.length,2);assert.equal(sent[0][2],'Tests passed.');assert.equal(sent[1][3],'error');
 db.prepare('UPDATE watches SET enabled=0').run();recovery.complete('thread-one',{id:'unfollowed',status:'completed'});assert.equal(sent.length,2);
 db.prepare('UPDATE watches SET enabled=1').run();recovery.follow('thread-one',{id:'thread-one',turns:[{id:'unfollowed',status:'completed'}]});assert.equal(sent.length,2);
});

test('a first reconnect can recover turns finished after an offline subscription began',t=>{
 const dir=mkdtempSync(join(tmpdir(),'pocket-boundary-'));const {db}=openStore(dir);t.after(()=>{db.close();rmSync(dir,{recursive:true,force:true});});
 db.prepare('INSERT INTO watches VALUES(?,?,1)').run('thread-one','Project');const sent=[];const recovery=new CompletionRecovery(db,(...a)=>sent.push(a));
 recovery.follow('thread-one');
 recovery.observe({id:'thread-one',turns:[{id:'old',status:'completed',completedAt:1},{id:'new',status:'completed',completedAt:Date.now()/1000+1}]});
 assert.equal(sent.length,1);assert.equal(sent[0][4],'new');
});

test('spoken summaries remove markup/code/URLs, remain bounded and travel within FCM limits',()=>{
 const summary=spokenSummary('Pocket','```sh\nsecret command\n```\n**Tests passed.** See [report](https://example.org/results). token=hidden /very/long/private/path');
 assert.match(summary,/Tests passed/);assert.doesNotMatch(summary,/secret command|https:|hidden|\/private/);
 assert.equal(spokenSummary('Title','Ignored','Release ready for review.'),'Release ready for review.');
 assert.ok(speechText('word '.repeat(100)).length<=181);
 const data=pushData({title:'🌿'.repeat(1000),body:'🌿'.repeat(10000),spoken_summary:'🌿'.repeat(1000),id:1,thread_id:'thread',created_at:1});
 assert.ok(Buffer.byteLength(JSON.stringify(data))<4096);assert.ok(data.spoken_summary);assert.ok(!data.spoken_summary.includes('\uFFFD'));
});

test('paged recovery continues beyond eight missed completions and stops at the subscription boundary',t=>{
 const dir=mkdtempSync(join(tmpdir(),'pocket-paged-recovery-'));const {db}=openStore(dir);t.after(()=>{db.close();rmSync(dir,{recursive:true,force:true});});
 db.prepare('INSERT INTO watches VALUES(?,?,1)').run('thread-one','Project');const sent=[];const recovery=new CompletionRecovery(db,(...a)=>sent.push(a));
 recovery.follow('thread-one');const at=Date.now()/1000+1;
 const page={id:'thread-one',turns:Array.from({length:8},(_,i)=>({id:'new-'+i,status:'completed',completedAt:at+i,items:[]}))};
 assert.equal(recovery.needsEarlier(page),true);recovery.observe(page);
 const older={id:'thread-one',turns:[{id:'baseline',status:'completed',completedAt:1},{id:'missed',status:'completed',completedAt:at}]};
 assert.equal(recovery.needsEarlier(older),false);recovery.observe(older);
 assert.equal(sent.length,9);assert.equal(recovery.needsEarlier(page),false);
});
