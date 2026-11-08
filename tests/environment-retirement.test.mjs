import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {CoordinatorSources} from '../server/coordinator-sources.mjs';
import {purgeRetiredSources} from '../server/retired-sources.mjs';
import {purgeRetiredStorage,retiredValue} from '../web/retirement.mjs';

test('retirement removes unknown original commands and sealed credentials without contacting any host; same thread on Mac survives',()=>{
 const db=new DatabaseSync(':memory:');let calls=0;
 new CoordinatorSources({db,key:'fixture',fetchImpl:()=>{calls++;throw Error('Must not send')}});
 db.exec('CREATE TABLE coordinator_room_intents(device TEXT,source TEXT,payload TEXT)');
 for(const [source,base]of [['old','https://old.example.ts.net'],['mac','https://mac.example.ts.net']]){
  db.prepare('INSERT INTO coordinator_sources VALUES(?,?,?,?,?,?,NULL,NULL)').run('phone',source,source,base,'credential','hash');
  db.prepare('INSERT INTO coordinator_remote_commands VALUES(?,?,?,?,?,?,?,?,?,?)').run(source,'phone',source,'voice','/api/threads/same/reply','hash','unknown','private receipt',1,'request');
  db.prepare('INSERT INTO coordinator_room_intents VALUES(?,?,?)').run('phone',source,'saved kickoff');
 }
 const allowed=base=>!base.includes('old.example');
 assert.deepEqual(purgeRetiredSources(db,allowed),{coordinator_room_reconciliations:0,coordinator_room_intents:1,coordinator_remote_commands:1,coordinator_sources:1});
 assert.deepEqual(db.prepare('SELECT source_id FROM coordinator_sources').all().map(r=>r.source_id),['mac']);
 assert.deepEqual(db.prepare('SELECT source_id FROM coordinator_remote_commands').all().map(r=>r.source_id),['mac']);
 assert.deepEqual(purgeRetiredSources(db,allowed),{});assert.equal(calls,0);db.close();
});

test('browser retirement removes only typed source-owned drafts, selections and receipts, never text mentioning another environment',()=>{
 const storage={};Object.defineProperties(storage,{getItem:{value:key=>storage[key]},removeItem:{value:key=>delete storage[key]}});
 const put=(key,value)=>storage['nextcomp.web.v1.'+key]=JSON.stringify(value);
 put('draft.phone.env_oldhost__c2FtZQ','old draft');put('revision.draft.phone.env_oldhost__c2FtZQ',2);
 put('draft.phone.env_mac__c2FtZQ','keep');put('pending',{scope:'env_oldhost__c2FtZQ',state:'unknown'});
 put('scope','env_oldhost__c2FtZQ');put('draft.phone.coordinator','env_oldhost__c2FtZQ is just text');
 put('delivered.phone.receipt',{intent:{transport:{environment:'oldhost'}}});put('newPending',{id:'current-receipt'});
 assert.equal(purgeRetiredStorage(storage,['oldhost']).length,5);
 assert.equal(JSON.parse(storage['nextcomp.web.v1.draft.phone.env_mac__c2FtZQ']),'keep');
 assert.ok(storage['nextcomp.web.v1.draft.phone.coordinator']);assert.ok(storage['nextcomp.web.v1.newPending']);
 assert.equal(retiredValue({transport:{environment:'oldhost'}},new Set(['oldhost'])),true);
 assert.deepEqual(purgeRetiredStorage(storage,['oldhost']),[]);
});
