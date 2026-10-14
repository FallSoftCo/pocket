import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {openStore} from '../server/store.mjs';
import {PushDelivery,pushData} from '../server/push.mjs';
import {TeamAttention} from '../server/losangelex-attention.mjs';

test('FCM payload stays bounded and retains task routing',()=>{
 const data=pushData({id:9,thread_id:'original-thread',title:'🌿'.repeat(1000),body:'🌿'.repeat(10000),created_at:100});
 assert.ok(Buffer.byteLength(JSON.stringify(data))<4096);
 assert.equal(data.thread_id,'original-thread');assert.equal(data.id,'9');assert.ok(!data.body.includes('\uFFFD'));
});
test('FCM question payload retains original authority, room and waiting agent',()=>{
 const provenance={question_provider:'losangelex',question_authority:'source',question_team:'team',question_task:'room',question_agent:'waiting-agent',question_event:'61'};
 const data=pushData({id:9,thread_id:'lx-source',kind:'question',body:'Answer '.repeat(2000),...provenance});
 for(const [key,value] of Object.entries(provenance))assert.equal(data[key],value);
 assert.ok(Buffer.byteLength(JSON.stringify(data))<4096);
});
test('queued questions recover their original authority after sender and registration changes',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'question-push-')),{db}=openStore(dir);t.after(()=>{db.close();rmSync(dir,{recursive:true,force:true});});
 const at=Date.now(),config={projectId:'test-project'};
 db.prepare('INSERT INTO devices VALUES(?,?,?,?,?)').run('phone','Phone','paired',at,0);
 db.prepare('INSERT INTO push_tokens VALUES(?,?,?,?)').run('phone','token-phone',config.projectId,at);
 const queue=new PushDelivery(db,{config,send:async()=>assert.fail('Publication should not flush yet')});queue.busy=true;
 const client={config:{environmentId:'original-authority'},call:async()=>({data:[{id:81,team_id:'team',task_id:'room',author:'waiting-agent',kind:'attention'}]})};
 const observer=new TeamAttention({db,client,resolve:()=>{},publish:(thread,commit)=>{
   const id=Number(db.prepare('INSERT INTO notifications(thread_id,title,body,kind,created_at) VALUES(?,?,?,?,?)').run(thread,'Question','Which option?','question',at).lastInsertRowid);
   commit({id});queue.enqueue(id,{flush:false});
 }});
 await observer.poll();client.config.environmentId='new-authority';let sent;
 await new PushDelivery(db,{config,send:async message=>{sent=message;return 'fcm-receipt';}}).flush();
 assert.equal(sent.data.question_authority,'original-authority');assert.equal(sent.data.question_event,'81');assert.equal(sent.data.question_agent,'waiting-agent');assert.equal(sent.data.device_id,'phone');
});
test('FCM queues persist, retry transient errors, invalidate tokens, and honor device revocation',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'pocket-push-')),{db}=openStore(dir);t.after(()=>{db.close();rmSync(dir,{recursive:true,force:true});});
 let at=Date.now();
 for(const id of ['phone','revoked']){db.prepare('INSERT INTO devices VALUES(?,?,?,?,?)').run(id,id,id,at,0);db.prepare('INSERT INTO push_tokens VALUES(?,?,?,?)').run(id,`token-${id}`,'test-project',at);}
 const add=()=>Number(db.prepare('INSERT INTO notifications(thread_id,title,body,kind,created_at) VALUES(?,?,?,?,?)').run('original-thread','Done','Test result','complete',at).lastInsertRowid);
 const config={projectId:'test-project'};
 const queue=new PushDelivery(db,{config,send:async()=>{},clock:()=>at});queue.busy=true;
 const id=add();queue.enqueue(id);db.prepare('DELETE FROM devices WHERE id=?').run('revoked');
 assert.equal(db.prepare('SELECT count(*) AS count FROM push_tokens').get().count,1);
 let calls=0;
 // A fresh sender instance replays the durable queue after a restart.
 const restarted=new PushDelivery(db,{config,clock:()=>at,send:async m=>{calls++;assert.equal(m.data.thread_id,'original-thread');assert.equal(m.data.device_id,'phone');assert.equal(m.android.priority,'high');throw Object.assign(Error('Temporary outage'),{code:'messaging/server-unavailable'});}});
 await restarted.flush();assert.equal(calls,1);assert.equal(db.prepare('SELECT state FROM push_deliveries WHERE notification_id=?').get(id).state,'retry');
 await restarted.flush();assert.equal(calls,1,'respect retry backoff');
 at+=30001;restarted.send=async()=>{calls++;return 'projects/test-project/messages/test';};await restarted.flush();
 assert.equal(calls,2);assert.equal(db.prepare('SELECT state FROM push_deliveries WHERE notification_id=?').get(id).state,'accepted_by_fcm');
 await restarted.flush();assert.equal(calls,2,'accepted sends do not repeat');
 const next=add();queue.enqueue(next);restarted.send=async()=>{throw Object.assign(Error('Unregistered'),{code:'messaging/registration-token-not-registered'});};await restarted.flush();
 assert.equal(db.prepare('SELECT state FROM push_deliveries WHERE notification_id=?').get(next).state,'failed');
 assert.equal(db.prepare('SELECT count(*) AS count FROM push_tokens').get().count,0);
});


test('scheduled report envelope cannot be mistaken for ordinary automatic speech by older clients',()=>{
 const data=pushData({id:42,thread_id:null,title:'Work report',body:'Useful outcome '.repeat(600),spoken_text:'Do not automatically speak this report',kind:'coordinator_report',created_at:123456});
 assert.equal(data.id,undefined);assert.equal(data.report_id,'42');assert.equal(data.coordinator_report,'1');assert.equal(data.kind,'coordinator_report');
 assert.equal(data.spoken_text,undefined);assert.equal(data.spoken_summary,undefined);assert.equal(data.speech_pending,undefined);
 assert.equal(data.created_at,'123456');assert.ok(Buffer.byteLength(JSON.stringify(data))<4096);
});
