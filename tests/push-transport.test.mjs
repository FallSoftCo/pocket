import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {generateKeyPairSync} from 'node:crypto';
import {getApp,deleteApp} from 'firebase-admin/app';
import {openStore} from '../server/store.mjs';
import {loadPush,PushDelivery,pushFailureCode} from '../server/push.mjs';

test('actual Firebase file credential forwards scoped agent to OAuth client and FCM without fetching token or sending',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'push-agent-')),{db}=openStore(dir),old=process.env.GOOGLE_APPLICATION_CREDENTIALS;
 delete process.env.GOOGLE_APPLICATION_CREDENTIALS;
 t.after(async()=>{if(old===undefined)delete process.env.GOOGLE_APPLICATION_CREDENTIALS;else process.env.GOOGLE_APPLICATION_CREDENTIALS=old;const app=getApp('pocket');app.options.httpAgent.destroy();await deleteApp(app);db.close();rmSync(dir,{recursive:true,force:true});});
 writeFileSync(join(dir,'firebase-client.json'),JSON.stringify({projectId:'synthetic-project',senderId:'123',applicationId:'synthetic-app',apiKey:'synthetic-not-valid'}));
 const key=generateKeyPairSync('rsa',{modulusLength:2048}).privateKey.export({type:'pkcs8',format:'pem'});
 writeFileSync(join(dir,'firebase-admin.json'),JSON.stringify({type:'service_account',project_id:'synthetic-project',client_email:'synthetic@synthetic-project.iam.gserviceaccount.com',private_key:key}),{mode:0o600});
 const delivery=loadPush(db,dir),app=getApp('pocket'),agent=app.options.httpAgent;
 assert.equal(delivery.enabled,true);assert.equal(agent.options.autoSelectFamilyAttemptTimeout,2000);assert.equal(agent.options.family,undefined);
 // SDK construction/file parsing only. No getAccessToken()/send() call.
 const auth=app.options.credential.getGoogleAuth(),client=await auth.getClient();
 assert.equal(client.transporter.defaults.agent,agent,'OAuth must use the exact FCM-scoped agent; object-form cert loses it');
 assert.equal(app.options.credential.serviceAccountPathOrObject,join(dir,'firebase-admin.json'));
 assert.deepEqual(Object.keys(agent.sockets),[]);
});

test('nested OAuth network failure keeps original notification/backoff/token and later exact acceptance; credential rejection remains distinct',async t=>{
 const dir=mkdtempSync(join(tmpdir(),'push-network-')),{db}=openStore(dir);t.after(()=>{db.close();rmSync(dir,{recursive:true,force:true});});
 let at=Date.now();db.prepare('INSERT INTO devices VALUES(?,?,?,?,?)').run('phone','Phone','paired',at,0);db.prepare('INSERT INTO push_tokens VALUES(?,?,?,?)').run('phone','synthetic-token','synthetic-project',at);
 const id=Number(db.prepare('INSERT INTO notifications(thread_id,title,body,kind,created_at) VALUES(?,?,?,?,?)').run('thread-original','Update','Retain original notification','app_update',at).lastInsertRowid);
 const timeout=Object.assign(Error('private network detail'),{code:'ETIMEDOUT'}),unreachable=Object.assign(Error('private address'),{code:'ENETUNREACH'});
 const wrapped=Object.assign(Error('private credential/token data must not be retained'),{code:'app/invalid-credential',cause:{cause:new AggregateError([timeout,unreachable])}});
 let calls=0;const queue=new PushDelivery(db,{config:{projectId:'synthetic-project'},clock:()=>at,send:async message=>{calls++;assert.equal(message.data.id,String(id));throw wrapped;}});
 queue.enqueue(id,{flush:false});await queue.flush();
 const receipt=db.prepare('SELECT * FROM push_deliveries WHERE notification_id=?').get(id);
 assert.equal(receipt.state,'retry');assert.equal(receipt.error,'push/oauth-network-error');assert.equal(receipt.attempts,1);assert.equal(receipt.next_attempt_at,at+30000);
 assert.equal(db.prepare('SELECT count(*) AS n FROM push_tokens').get().n,1);assert.equal(db.prepare('SELECT count(*) AS n FROM notifications').get().n,1);
 await queue.flush();assert.equal(calls,1);
 at+=30001;queue.send=async message=>{calls++;assert.equal(message.data.id,String(id));return 'synthetic-original-acceptance';};await queue.flush();await queue.flush();
 assert.equal(calls,2);assert.equal(db.prepare('SELECT state FROM push_deliveries WHERE notification_id=?').get(id).state,'accepted_by_fcm');
 assert.equal(pushFailureCode({code:'app/invalid-credential',cause:{code:'invalid_grant'}}),'app/invalid-credential');
 assert.equal(pushFailureCode({code:'app/invalid-credential',message:'ETIMEDOUT'}),'app/invalid-credential','do not classify untrusted free text');
 assert.equal(pushFailureCode({code:'messaging/mismatched-credential',cause:timeout}),'messaging/mismatched-credential');
 const cycle={};cycle.cause=cycle;assert.equal(pushFailureCode({code:'app/invalid-credential',cause:cycle}),'app/invalid-credential');
});
