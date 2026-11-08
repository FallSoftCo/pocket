import test from 'node:test';import assert from 'node:assert/strict';import {DatabaseSync} from 'node:sqlite';import {CatalogPresence} from '../server/catalog-presence.mjs';
const missing=id=>Object.assign(Error(`thread not loaded: ${id}`),{rpc:{code:-32600,message:`thread not loaded: ${id}`}});
test('negative native evidence cannot overtake fresh activity or suppress unresolved human delivery',()=>{
 const db=new DatabaseSync(':memory:');db.exec('CREATE TABLE outgoing(thread_id TEXT,state TEXT)');const p=new CatalogPresence(db);
 const initial=p.begin('task');p.seen('task');assert.equal(p.rejectMissing('task',missing('task'),initial),false);
 const current=p.begin('task');assert.equal(p.rejectMissing('task',Error('timeout'),current),false);assert.equal(p.rejectMissing('task',missing('other'),current),false);
 db.prepare('INSERT INTO outgoing VALUES(?,?)').run('task','unknown');assert.equal(p.rejectMissing('task',missing('task'),current),false);
 db.prepare("UPDATE outgoing SET state='accepted'").run();assert.equal(p.rejectMissing('task',missing('task'),current),true);assert.equal(new CatalogPresence(db).unavailable('task'),true);
 p.seen('task');assert.equal(p.unavailable('task'),false);p.finish('task',initial);p.finish('task',current);assert.equal(p.versions.size,0);db.close();
});
