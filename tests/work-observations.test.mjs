import test from 'node:test';import assert from 'node:assert/strict';import {DatabaseSync} from 'node:sqlite';import {WorkObservations} from '../server/work-observations.mjs';
test('checkpoint observations survive restart without requiring or creating a continuation grant',()=>{
 const db=new DatabaseSync(':memory:');let observations=new WorkObservations(db,()=>100);
 const thread={id:'original',turns:[{id:'current',status:'inProgress'}]};
 assert.equal(observations.record(thread,{turnId:'current',state:'waitingDependency',evidence:'Exact job generation not yet available'}),true);
 observations=new WorkObservations(db);assert.equal(observations.latest('original').state,'waitingDependency');
 assert.deepEqual(db.prepare("SELECT name FROM sqlite_master WHERE type='table'").all().map(r=>r.name),['work_observations']);
});
test('stale and contradictory observations fail; exact delivery retries are idempotent',()=>{
 const db=new DatabaseSync(':memory:'),observations=new WorkObservations(db),thread={id:'task',turns:[{id:'old'},{id:'latest'}]};
 assert.equal(observations.record(thread,{turnId:'old',state:'continue',evidence:'Old report'}),false);
 const report={turnId:'latest',state:'continue',evidence:'Bounded remaining work proposal'};
 assert.equal(observations.record(thread,report),true);assert.equal(observations.record(thread,report),true);
 assert.equal(observations.record(thread,{...report,state:'completed'}),false);
 assert.equal(db.prepare('SELECT count(*) n FROM work_observations').get().n,1);
});
