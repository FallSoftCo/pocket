import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {NotificationRequests} from '../server/notification-requests.mjs';
const setup=()=>{const db=new DatabaseSync(':memory:');db.exec('CREATE TABLE notifications(id INTEGER PRIMARY KEY,body TEXT);CREATE TABLE pushes(notification_id INTEGER PRIMARY KEY)');return db};
const publish=(db,text,persist)=>{db.exec('BEGIN IMMEDIATE');try{const n=db.prepare('INSERT INTO notifications(body) VALUES(?)').run(text);const result={notification:{id:Number(n.lastInsertRowid),body:text}};db.prepare('INSERT INTO pushes VALUES(?)').run(result.notification.id);persist(result);db.exec('COMMIT');return result;}catch(e){db.exec('ROLLBACK');throw e;}};
test('concurrent retries, lost HTTP response and service restart preserve one notification and one push receipt',async()=>{
 const db=setup(),requests=new NotificationRequests({db});let calls=0,release;const gate=new Promise(r=>release=r);
 const send=persist=>{calls++;return gate.then(()=>publish(db,'Requested result',persist))};
 const first=requests.run('requested-alert-001',{message:'Requested result',kind:'complete'},send);
 const same=requests.run('requested-alert-001',{kind:'complete',message:'Requested result'},send);release();
 assert.deepEqual(await first,await same);assert.equal(calls,1);
 const restart=new NotificationRequests({db});const receipt=await restart.run('requested-alert-001',{message:'Requested result',kind:'complete'},()=>{throw Error('duplicate delivery')});
 assert.equal(receipt.notification.id,1);assert.equal(db.prepare('SELECT count(*) n FROM notifications').get().n,1);assert.equal(db.prepare('SELECT count(*) n FROM pushes').get().n,1);
 assert.throws(()=>restart.run('requested-alert-001',{message:'changed'},send),/different content/);
});
test('an interrupted local transaction rolls back both notification and receipt; same identity safely prepares once after restart',async()=>{
 const db=setup(),requests=new NotificationRequests({db});
 // The row was admitted before a process crash; SQLite rolled back its entire
 // notification/queue transaction. No external send can exist without that queue.
 const pending=requests.run('interrupted-001',{message:'Ready'},()=>new Promise(()=>{}));void pending;
 db.exec('BEGIN');db.exec("INSERT INTO notifications(body) VALUES('uncommitted')");db.exec('ROLLBACK');
 const restart=new NotificationRequests({db});
 const result=await restart.run('interrupted-001',{message:'Ready'},persist=>publish(db,'Ready',persist));
 assert.equal(result.notification.id,1);assert.equal(db.prepare('SELECT count(*) n FROM pushes').get().n,1);
});
test('post-commit exception returns original receipt, pre-commit failure never repeats, and receipts require the local transaction',async()=>{
 const db=setup(),requests=new NotificationRequests({db});
 const result=await requests.run('postcommit-001',{message:'Done'},persist=>{publish(db,'Done',persist);throw Error('response lost')});assert.equal(result.notification.id,1);
 await assert.rejects(requests.run('precommit-001',{message:'Bad'},()=>{throw Object.assign(Error('Invalid source'),{status:400})}),/Invalid source/);
 assert.throws(()=>requests.run('precommit-001',{message:'Bad'},()=>{throw Error('repeated')}),/Invalid source/);
 await assert.rejects(requests.run('outside-transaction-001',{},persist=>persist({notification:{id:99}})),/no notification was committed/);
 assert.equal(db.prepare('SELECT count(*) n FROM notifications').get().n,1);
});

test('independent request handlers cannot commit duplicate notification/queue rows',async()=>{
 const db=setup(),first=new NotificationRequests({db}),second=new NotificationRequests({db});let release;const gate=new Promise(r=>release=r);
 const slow=first.run('shared-handler-001',{message:'Ready'},persist=>gate.then(()=>publish(db,'Ready',persist)));
 const fast=await second.run('shared-handler-001',{message:'Ready'},persist=>publish(db,'Ready',persist));release();
 assert.deepEqual(await slow,fast);assert.equal(db.prepare('SELECT count(*) n FROM notifications').get().n,1);assert.equal(db.prepare('SELECT count(*) n FROM pushes').get().n,1);
});

test('actual HTTP operator route keeps authorization and metadata reads separate from atomic duplicate-safe pushes',async t=>{
 const {default:express}=await import('express');const {mountOperatorNotifications}=await import('../server/notification-requests.mjs');
 const db=setup();db.exec('CREATE TABLE watches(thread_id TEXT PRIMARY KEY,name TEXT,enabled INTEGER)');
 const app=express();app.use(express.json());let metadata=0,notifications=0;
 const codex={async connect(){},async call(method,params){assert.equal(method,'thread/read');assert.equal(params.includeTurns,false);metadata++;await new Promise(r=>setTimeout(r,10));return {thread:{id:params.threadId,name:'Synthetic work'}}}};
 mountOperatorNotifications(app,{db,codex,owner:(req,res,next)=>req.get('Authorization')==='Bearer synthetic-operator'?next():res.sendStatus(403),route:fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next),requireId:id=>{if(!/^[a-zA-Z0-9_-]{5,100}$/.test(id))throw Object.assign(Error('Invalid source'),{status:400});return id},saveAttachment:()=>{throw Error('No files expected')},promptContext:()=>null,notify:(id,title,body,kind,files,request,speech,turn,context,persist)=>{
  notifications++;db.exec('BEGIN IMMEDIATE');try{const n=db.prepare('INSERT INTO notifications(body) VALUES(?)').run(body);const item={id:Number(n.lastInsertRowid),thread_id:id,title,body,kind,attachments:files};db.prepare('INSERT INTO pushes VALUES(?)').run(item.id);persist(item);db.exec('COMMIT');return item;}catch(e){db.exec('ROLLBACK');throw e;}
 }});
 app.use((error,req,res,next)=>res.status(error.status||500).json({error:error.message}));
 const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));t.after(async()=>{await new Promise(r=>server.close(r));db.close()});
 const url='http://127.0.0.1:'+server.address().port+'/api/notify',body={thread_id:'synthetic_worker',message:'The requested artifact is ready.',kind:'complete'};
 const send=(value=body,authorized=true)=>fetch(url,{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':'http-requested-result-001',...(authorized?{Authorization:'Bearer synthetic-operator'}:{})},body:JSON.stringify(value)});
 assert.equal((await send(body,false)).status,403);
 const [a,b]=await Promise.all([send(),send()]);assert.equal(a.status,200);assert.deepEqual(await a.json(),await b.json());
 const original=await send();assert.equal(original.status,200);assert.equal((await original.json()).notification.id,1);
 assert.equal((await send({...body,message:'Different result'})).status,409);
 assert.equal(metadata,1);assert.equal(notifications,1);assert.equal(db.prepare('SELECT count(*) n FROM pushes').get().n,1);
});
