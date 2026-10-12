import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {recoverReply,pendingOutgoing} from '../server/reply-recovery.mjs';

test('routine conversation outbox omits accepted history but retains all actionable sends and stored receipts',t=>{
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());
 db.exec('CREATE TABLE outgoing(id TEXT PRIMARY KEY,thread_id TEXT,text TEXT,mode TEXT,state TEXT,result TEXT,created_at INTEGER,updated_at INTEGER)');
 const insert=db.prepare('INSERT INTO outgoing VALUES(?,?,?,?,?,?,?,?)');
 for(let n=0;n<229;n++)insert.run('accepted-'+n,'current','Historical request '.repeat(30),'auto','accepted','Accepted receipt',n,0);
 const states=['queued','held','sending','failed','unknown'];
 for(const [n,state] of states.entries())insert.run(state,'current','Full actionable input '+state,'queue',state,'Outcome '+state,1000+n,0);
 insert.run('cancelled','current','Removed','auto','cancelled',null,0,0);
 insert.run('other','other-thread','Other pending input','auto','unknown',null,1,0);
 const before=db.prepare('SELECT COUNT(*) AS n FROM outgoing').get().n;
 const rows=pendingOutgoing(db,'current');assert.deepEqual(rows.map(x=>x.state),states);
 assert.equal(rows.at(-1).text,'Full actionable input unknown');assert.equal(rows.at(-1).result,'Outcome unknown');
 assert.equal(db.prepare('SELECT COUNT(*) AS n FROM outgoing').get().n,before);
 assert.equal(db.prepare('SELECT state,result FROM outgoing WHERE id=?').get('accepted-0').state,'accepted');
 assert.equal(recoverReply(db,'current','unknown',{action:'retry'},2).status,409,'projection must not relax uncertain-delivery protection');
 assert.ok(Buffer.byteLength(JSON.stringify(rows))<Buffer.byteLength(JSON.stringify(db.prepare('SELECT * FROM outgoing').all()))/20);
});

test('pending outbox retains large drafts and stable creation order without a last-N truncation',t=>{
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());
 db.exec('CREATE TABLE outgoing(id TEXT PRIMARY KEY,thread_id TEXT,text TEXT,mode TEXT,state TEXT,result TEXT,created_at INTEGER)');
 const insert=db.prepare('INSERT INTO outgoing VALUES(?,?,?,?,?,?,?)');
 for(let n=0;n<120;n++)insert.run('draft-'+n,'current',n===0?'Long retained draft '.repeat(10000):'Pending '+n,'queue',n%2?'held':'failed',null,n<2?1:n);
 const rows=pendingOutgoing(db,'current');assert.equal(rows.length,120);
 assert.equal(rows[0].id,'draft-0');assert.equal(rows[1].id,'draft-1');assert.equal(rows[0].text.length,'Long retained draft '.length*10000);
});

function store(t,state,mode='steer'){
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());
 db.exec('CREATE TABLE outgoing(id TEXT PRIMARY KEY,thread_id TEXT,text TEXT,mode TEXT,state TEXT,result TEXT,updated_at INTEGER)');
 db.prepare('INSERT INTO outgoing VALUES(?,?,?,?,?,?,?)').run('reply','thread','Original input',mode,state,'Rejected',1);
 return db;
}
test('a failed reply can be edited then retried with its stable identity and mode',t=>{
 const db=store(t,'failed','queue');
 assert.equal(recoverReply(db,'thread','reply',{action:'edit',text:'Corrected input'},2).state,'failed');
 assert.deepEqual(recoverReply(db,'thread','reply',{action:'retry'},3),{status:200,id:'reply',state:'queued',dispatch:true});
 const row=db.prepare('SELECT * FROM outgoing').get();
 assert.equal(row.text,'Corrected input');assert.equal(row.mode,'queue');assert.equal(row.result,null);
 assert.equal(recoverReply(db,'thread','reply',{action:'retry'},4).status,409,'repeated retry does not create a second delivery');
});
test('unknown delivery requires explicit acknowledgement before retry, even after editing',t=>{
 const db=store(t,'unknown');
 assert.equal(recoverReply(db,'thread','reply',{action:'retry'},2).status,409);
 assert.equal(recoverReply(db,'thread','reply',{action:'edit',text:'Edited'},3).state,'unknown');
 assert.equal(recoverReply(db,'thread','reply',{action:'send',confirmUnknown:'true'},4).status,409);
 assert.equal(recoverReply(db,'thread','reply',{action:'retry',confirmUnknown:true},5).state,'queued');
});
test('failed and uncertain rows can be removed without sending or deleting accepted history',t=>{
 for(const state of ['failed','unknown','held','queued']){
  const db=store(t,state);assert.equal(recoverReply(db,'thread','reply',{action:'remove'},2).state,'cancelled');
  assert.equal(recoverReply(db,'thread','reply',{action:'retry'},3).status,409);
 }
 for(const state of ['sending','accepted']){
  const db=store(t,state);for(const action of ['remove','edit','retry'])assert.equal(recoverReply(db,'thread','reply',{action,text:'Changed'},2).status,409);
  assert.equal(db.prepare('SELECT state FROM outgoing').get().state,state);
 }
});
test('recovery is scoped to its conversation and validates edited input',t=>{
 const db=store(t,'failed');
 assert.equal(recoverReply(db,'other','reply',{action:'remove'},2).status,404);
 for(const text of ['','   ','x'.repeat(32001)])assert.equal(recoverReply(db,'thread','reply',{action:'edit',text},2).status,400);
 assert.equal(db.prepare('SELECT text FROM outgoing').get().text,'Original input');
});
