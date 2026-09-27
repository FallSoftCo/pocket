import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {openStore} from '../server/store.mjs';
import {PushDelivery,pushData} from '../server/push.mjs';

test('FCM payload stays bounded and retains task routing',()=>{
 const data=pushData({id:9,thread_id:'original-thread',title:'🌿'.repeat(1000),body:'🌿'.repeat(10000),created_at:100});
 assert.ok(Buffer.byteLength(JSON.stringify(data))<4096);
 assert.equal(data.thread_id,'original-thread');assert.equal(data.id,'9');assert.ok(!data.body.includes('\uFFFD'));
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
