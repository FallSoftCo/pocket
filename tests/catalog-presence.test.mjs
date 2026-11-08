import test from 'node:test';import assert from 'node:assert/strict';import {DatabaseSync} from 'node:sqlite';import {CatalogPresence} from '../server/catalog-presence.mjs';
const missing=id=>Object.assign(Error(`thread not loaded: ${id}`),{rpc:{code:-32600,message:`thread not loaded: ${id}`}});
test('negative native evidence cannot overtake fresh activity or suppress unresolved human delivery',()=>{
 const db=new DatabaseSync(':memory:');db.exec('CREATE TABLE outgoing(thread_id TEXT,state TEXT);CREATE TABLE pocket_discovered_threads(thread_id TEXT PRIMARY KEY,metadata TEXT)');const p=new CatalogPresence(db);
 db.prepare('INSERT INTO pocket_discovered_threads VALUES(?,?)').run('task',JSON.stringify({id:'task',ephemeral:true}));
 const initial=p.begin('task');p.seen('task');assert.equal(p.rejectMissing('task',missing('task'),initial),false);
 const current=p.begin('task');assert.equal(p.rejectMissing('task',Error('timeout'),current),false);assert.equal(p.rejectMissing('task',missing('other'),current),false);
 db.prepare('INSERT INTO outgoing VALUES(?,?)').run('task','unknown');assert.equal(p.rejectMissing('task',missing('task'),current),false);
 db.prepare("UPDATE outgoing SET state='accepted'").run();assert.equal(p.rejectMissing('task',missing('task'),current),true);assert.equal(new CatalogPresence(db).unavailable('task'),true);
 p.seen('task');assert.equal(p.unavailable('task'),false);p.finish('task',initial);p.finish('task',current);assert.equal(p.versions.size,0);db.close();
});

test('unloaded durable or unknown identities remain discoverable, including previously suppressed catalog entries',()=>{
 const db=new DatabaseSync(':memory:');db.exec('CREATE TABLE pocket_discovered_threads(thread_id TEXT PRIMARY KEY,metadata TEXT)');const p=new CatalogPresence(db);
 for(const [id,metadata] of [['durable',{id:'durable',ephemeral:false,path:'/missing/source.jsonl'}],['unknown',{id:'unknown'}],['foreign',{id:'elsewhere',ephemeral:true}],['malformed',null]]){
  db.prepare('INSERT INTO pocket_discovered_threads VALUES(?,?)').run(id,metadata?JSON.stringify(metadata):'{');
  const ticket=p.begin(id);assert.equal(p.rejectMissing(id,missing(id),ticket),false);
  db.prepare('INSERT INTO pocket_catalog_unavailable VALUES(?,?,?)').run(id,'Old overly broad negative observation',1);
  assert.equal(p.unavailable(id),false,'old negative evidence must not hide durable or unknown history');p.finish(id,ticket);
 }
 assert.equal(db.prepare('SELECT count(*) AS n FROM pocket_discovered_threads').get().n,4,'classification cannot delete source metadata');db.close();
});
