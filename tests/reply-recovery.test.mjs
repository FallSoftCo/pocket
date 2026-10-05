import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {recoverReply} from '../server/reply-recovery.mjs';

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
