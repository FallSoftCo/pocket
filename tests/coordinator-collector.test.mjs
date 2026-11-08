import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {CoordinatorCollector} from '../server/coordinator-collector.mjs';
import {CoordinatorMemory} from '../server/coordinator-memory.mjs';
import {SessionCatchup} from '../server/session-catchup.mjs';
test('background reconciliation indexes changed public work with no model/message calls; baseline, pagination, restart and freshness are independent of an open chat',async()=>{
 const db=new DatabaseSync(':memory:');db.exec('CREATE TABLE pocket_discovered_threads(thread_id TEXT PRIMARY KEY,metadata TEXT,archived INTEGER,seen_at INTEGER)');
 let now=1000000,reads=0,reply='Old retained result.',activity=now,pages=[];
 const thread=()=>({id:'thread_worker',name:'Worker',activityAt:activity,status:{type:'idle'}});
 const discovery={async page(params){pages.push(params);return {data:params.cursor?[]:[thread()],nextCursor:params.cursor?null:'opaque_next'}},remember(t){db.prepare('INSERT OR REPLACE INTO pocket_discovered_threads VALUES(?,?,0,?)').run(t.id,JSON.stringify(t),now);return t}};
 const catchup=new SessionCatchup({db,clock:()=>now}),memory=new CoordinatorMemory({db,clock:()=>now});
 const options={db,discovery,memory,catchup,ready:()=>true,clock:()=>now,read:async path=>{assert.match(path,/observeOnly=1$/);reads++;return {thread:thread(),timeline:{rows:[{kind:'message',type:'agentMessage',itemId:'answer_'+activity,turnId:'turn_'+activity,phase:'final_answer',status:'completed',text:reply}]}}}};
 let collector=new CoordinatorCollector(options);await collector.tick();assert.equal(reads,1);assert.equal(catchup.cursor('thread_worker'),0,'Initial history became unsolicited audio');
 now+=15000;await collector.tick();assert.equal(reads,1,'Inventory refresh timestamps caused another history read');assert.ok(pages.some(p=>p.cursor==='opaque_next'));
 activity=now+1000;now+=15000;reply='The signed update is ready.';await collector.tick();assert.equal(reads,2);
 assert.equal(db.prepare('SELECT text FROM session_catchup_events').get().text,reply);
 assert.equal(db.prepare('SELECT count(*) AS n FROM session_catchup_presented').get().n,0);
 collector=new CoordinatorCollector(options);await collector.tick();assert.equal(reads,2,'Restart unnecessarily re-read unchanged work');
 assert.equal(collector.status().modelPolling,false);assert.equal(collector.status().catalogObservedAt,now);
 assert.ok(pages.every(p=>p.useStateDbOnly===true&&p.limit===100));
});
test('evidence read failure uses bounded backoff and disconnection does not activate a runtime',async()=>{
 const db=new DatabaseSync(':memory:');db.exec('CREATE TABLE pocket_discovered_threads(thread_id TEXT PRIMARY KEY,metadata TEXT,archived INTEGER,seen_at INTEGER)');
 let now=1000000,connected=false,lists=0,reads=0;
 const catchup=new SessionCatchup({db,clock:()=>now}),memory=new CoordinatorMemory({db});
 const thread={id:'thread_active',name:'Active work',activityAt:now,status:{type:'active'}};
 const collector=new CoordinatorCollector({db,memory,catchup,clock:()=>now,ready:()=>connected,discovery:{async page(){lists++;return {data:[thread]}},remember:t=>t},read:async()=>{reads++;throw Error('offline')}});
 await collector.tick();assert.equal(lists,0);assert.equal(reads,0);
 connected=true;await Promise.all([collector.tick(),collector.tick()]);assert.equal(lists,1);assert.equal(reads,1);
 now+=15000;await collector.tick();assert.equal(reads,1);now+=30000;await collector.tick();assert.equal(reads,2);
 assert.equal(collector.status().pending,1);
});

