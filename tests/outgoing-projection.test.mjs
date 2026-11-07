import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {mkdtempSync,rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {OutgoingProjection} from '../server/outgoing-projection.mjs';
import {SessionStore} from '../web/session-store.mjs';
import {queuedDestination} from '../server/queued-destination.mjs';

test('durable SQLite outbox revisions include cancellation and ignore rolled-back changes',t=>{
 const dir=mkdtempSync(join(tmpdir(),'nextcomp-outbox-revision-')),file=join(dir,'test.sqlite');t.after(()=>rmSync(dir,{recursive:true,force:true}));
 let db=new DatabaseSync(file);
 db.exec('CREATE TABLE outgoing(id TEXT PRIMARY KEY,thread_id TEXT,text TEXT,mode TEXT,state TEXT,result TEXT,created_at INTEGER,updated_at INTEGER)');
 let outbox=new OutgoingProjection(db);
 db.prepare('INSERT INTO outgoing VALUES(?,?,?,?,?,?,?,?)').run('q1','a','Original','queue','queued',null,1,1);
 const first=outbox.fields('a').outbox;assert.equal(first.revision,1);assert.equal(first.items[0].text,'Original');
 db.exec("BEGIN;UPDATE outgoing SET text='Rolled back' WHERE id='q1';ROLLBACK;");assert.deepEqual(outbox.fields('a').outbox,first);
 db.exec("UPDATE outgoing SET text='Revised' WHERE id='q1';UPDATE outgoing SET state='cancelled' WHERE id='q1'");
 const cancelled=outbox.fields('a').outbox;assert.equal(cancelled.revision,3);assert.deepEqual(cancelled.items,[]);db.close();
 db=new DatabaseSync(file);outbox=new OutgoingProjection(db);assert.deepEqual(outbox.fields('a').outbox,cancelled);db.close();
});
test('late history cannot resurrect a removed message and equal thread IDs keep source ownership',()=>{
 const store=new SessionStore(),row={id:'q1',text:'Queued original',mode:'queue',state:'queued',created_at:1};
 const head=(id,outbox)=>({thread:{id},timeline:{rows:[],hasEarlier:false},outbox,outgoing:outbox.items});
 store.head('source-a/thread',head('source-a/thread',{version:1,revision:1,items:[row]}));
 store.outbox('source-a/thread',{version:1,revision:3,items:[]});
 store.head('source-a/thread',head('source-a/thread',{version:1,revision:1,items:[row]}));
 assert.deepEqual(store.get('source-a/thread').snapshot.outgoing,[]);
 store.head('source-b/thread',head('source-b/thread',{version:1,revision:1,items:[row]}));
 assert.equal(store.get('source-b/thread').snapshot.outgoing.length,1);
 assert.deepEqual(store.get('source-a/thread').snapshot.outgoing,[]);
});
test('busy queued destination reads current status without history or resume',async()=>{
 const calls=[],attached=new Set(),thread={id:'same-thread',status:{type:'active'}};
 const codex={connect:async()=>{},call:async(method,params)=>{calls.push({method,params});return {thread};}};
 assert.equal(await queuedDestination(codex,attached,'same-thread',()=>{}),thread);
 assert.deepEqual(calls,[{method:'thread/read',params:{threadId:'same-thread',includeTurns:false}}]);assert.equal(attached.size,0);
});
test('idle queued destination retains native resume defaults and excludes transcript data',async()=>{
 const calls=[],attached=new Set(),thread={id:'same-thread',status:{type:'idle'}};let checked=0;
 const codex={connect:async()=>{},call:async(method,params)=>{calls.push({method,params});return {thread};}};
 await queuedDestination(codex,attached,'same-thread',()=>checked++);
 assert.deepEqual(calls,[{method:'thread/read',params:{threadId:'same-thread',includeTurns:false}},{method:'thread/resume',params:{threadId:'same-thread',excludeTurns:true}}]);
 assert.equal(checked,2);assert.ok(attached.has('same-thread'));
});

test('reconnect bootstrap keeps complete groups and omits oversized groups without truncating them',()=>{
 const db=new DatabaseSync(':memory:');db.exec('CREATE TABLE outgoing(id TEXT PRIMARY KEY,thread_id TEXT,text TEXT,mode TEXT,state TEXT,result TEXT,created_at INTEGER,updated_at INTEGER)');const projection=new OutgoingProjection(db),insert=db.prepare('INSERT INTO outgoing VALUES(?,?,?,?,?,?,?,?)');
 insert.run('small-one','small','First','queue','queued',null,1,1);insert.run('small-two','small','Second','queue','held',null,2,2);insert.run('oversized','large','x'.repeat(100000),'queue','queued',null,3,3);
 const boot=[...projection.bootstrap()];assert.equal(boot.length,1);assert.equal(boot[0].threadId,'small');assert.deepEqual(boot[0].outbox.items.map(x=>x.id),['small-one','small-two']);assert.equal(projection.fields('large').outbox.items[0].text.length,100000);db.close();
});
