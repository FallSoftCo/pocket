import test from 'node:test';
import assert from 'node:assert/strict';
import {LiveTimeline,displayItem,timelinePage} from '../server/timeline.mjs';
import {foldRuns} from '../web/presentation.mjs';

test('completed compactions remain first-class rows in loaded history',()=>{
 const thread={id:'thread',turns:[{id:'turn',status:'completed',items:[{id:'compact',type:'contextCompaction'}]}]};
 const page=timelinePage(thread);
 const row=page.rows.find(row=>row.itemId==='compact');
 assert.equal(row.kind,'compaction');
 assert.equal(row.title,'Context compacted');
 assert.equal(row.status,'completed');
 assert.match(row.text,/conversation continues/);
 const cached=displayItem({id:'compact',type:'contextCompaction'},'turn');
 assert.equal(cached._pocketRow.kind,'compaction');
 assert.equal(cached._pocketRow.title,'Context compacted');
});

test('live compaction appears immediately and completes in place without duplication',()=>{
 const thread={id:'thread',turns:[{id:'turn',status:'inProgress',startedAt:1,items:[]}]};
 const live=new LiveTimeline();
 const started=live.ingest({method:'item/started',params:{threadId:'thread',turnId:'turn',item:{id:'compact',type:'contextCompaction'}}});
 assert.equal(started.row.id,'turn/compact');
 assert.equal(started.row.kind,'compaction');
 assert.equal(started.row.status,'inProgress');
 assert.equal(started.row.title,'Compacting conversation…');

 const completed=live.ingest({method:'item/completed',params:{threadId:'thread',turnId:'turn',item:{id:'compact',type:'contextCompaction'}}});
 assert.equal(completed.row.id,started.row.id);
 assert.equal(completed.row.kind,'compaction');
 assert.equal(completed.row.status,'completed');
 assert.equal(completed.row.title,'Context compacted');
 assert.ok(completed.row.version>started.row.version);

 const rows=timelinePage(live.merge(thread)).rows.filter(row=>row.kind==='compaction');
 assert.equal(rows.length,1);
 assert.equal(rows[0].id,'turn/compact');
 assert.equal(rows[0].status,'completed');
});

test('compactions stay visible instead of folding into routine activity runs',()=>{
 const rows=foldRuns([
  {id:'read',kind:'activity',title:'Read file',text:'materials.md'},
  {id:'compact',kind:'compaction',status:'inProgress',title:'Compacting conversation…',text:'Earlier context is being condensed so this work can continue.'}
 ]);
 assert.deepEqual(rows.map(row=>row.kind),['runs','compaction']);
 assert.equal(rows[1].id,'compact');
});
