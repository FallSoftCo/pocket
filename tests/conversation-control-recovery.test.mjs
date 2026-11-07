import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {EventEmitter} from 'node:events';
import {ConversationTools} from '../server/conversation-tools.mjs';
import {SessionStore} from '../web/session-store.mjs';

function fixture(t){
 const db=new DatabaseSync(':memory:');t.after(()=>db.close());
 db.exec("CREATE TABLE devices(id TEXT PRIMARY KEY);INSERT INTO devices VALUES('paired-origin'),('paired-recovery')");
 const calls=[],codex=new EventEmitter();codex.connect=async()=>{};
 codex.call=async(method,params)=>{
  calls.push({method,params});
  if(method==='thread/read'||method==='thread/resume')return {thread:{id:'thread-source',cwd:'/fixture',historyMode:'paginated',status:{type:'idle'}}};
  if(method==='thread/turns/list')return {data:[{id:'turn-latest',status:'completed',items:[]}]};
  if(method==='thread/fork')return {thread:{id:'thread-forked',forkedFromId:'thread-source'}};
  if(method==='thread/compact/start')throw Error('Disconnected before acknowledgement');
  throw Error('Unexpected method');
 };
 return {db,calls,tools:new ConversationTools({db,codex,supports:()=>true})};
}
test('native fork explicitly defers inherited goal continuation and never sends a turn',async t=>{
 const f=fixture(t);
 const r=await f.tools.operate('paired-origin','thread-source',{id:'operation-fork',kind:'fork',turnId:'turn-latest',expectedLastTurnId:'turn-latest'});
 assert.equal(r.state,'accepted');assert.deepEqual(f.calls.find(c=>c.method==='thread/fork').params,{threadId:'thread-source',lastTurnId:'turn-latest',excludeTurns:true,deferGoalContinuation:true});
 assert.ok(!f.calls.some(c=>c.method.startsWith('turn/')||c.method.includes('/goal/')));
});
test('another active paired device can inspect and acknowledge an orphaned blocker without replay or actor substitution',async t=>{
 const f=fixture(t),request={id:'operation-compact',kind:'compact'};
 const unknown=await f.tools.operate('paired-origin','thread-source',request);assert.equal(unknown.state,'unknown');
 f.db.prepare('DELETE FROM devices WHERE id=?').run('paired-origin');
 assert.throws(()=>f.tools.assertInputAllowed('thread-source'),/uncertain/);
 const view=await f.tools.describe('thread-source','paired-recovery');assert.equal(view.operations[0].id,request.id);assert.equal(view.operations[0].sharedRecovery,true);
 assert.equal(f.tools.receipt('owner','thread-source',request.id),null);assert.equal(f.tools.receipt('not-paired','thread-source',request.id),null);
 assert.equal((await f.tools.describe('thread-source','not-paired')).operations.length,0);
 assert.equal(f.tools.receipt('paired-recovery','other-thread',request.id),null);
 assert.throws(()=>f.tools.acknowledge('owner','thread-source',request.id),/paired device/);
 const before=f.calls.length,result=f.tools.acknowledge('paired-recovery','thread-source',request.id);
 assert.equal(result.state,'acknowledged');assert.equal(result.acknowledgedBy,'paired-recovery');assert.equal(f.calls.length,before);
 assert.doesNotThrow(()=>f.tools.assertInputAllowed('thread-source'));
 assert.equal(f.db.prepare('SELECT device FROM conversation_operations WHERE id=?').get(request.id).device,'paired-origin');
 assert.equal(f.calls.filter(c=>c.method==='thread/compact/start').length,1);
 await assert.rejects(f.tools.operate('paired-recovery','thread-source',request),/another request/);
});
const snapshot=(id,epoch,text)=>({thread:{id},historyHead:{epoch},timeline:{rows:[{id:'message-'+text,text,kind:'message'}],hasEarlier:false,before:null}});
test('cold foreground and background reads captured before replacement cannot repopulate deleted history',()=>{
 for(const id of ['cold-foreground','evicted-background']){
  const cache=new SessionStore(),ticket=cache.ticket();
  if(id==='evicted-background'){cache.head(id,snapshot(id,'old','cached'),{ticket:cache.ticket()});cache.entity(id).page=null;delete cache.entity(id).nativeHistory;}
  cache.replaceHistory(id,{epoch:'replacement'});
  assert.throws(()=>cache.head(id,snapshot(id,'old','removed'),{ticket}),/history changed/);
  assert.equal(cache.get(id),null);
  const currentTicket=cache.ticket();cache.head(id,snapshot(id,'replacement','retained'),{ticket:currentTicket});
  assert.equal(cache.get(id).rows[0].text,'retained');
  cache.replaceHistory(id,{epoch:'replacement'}); // Native event plus same HTTP receipt.
  assert.equal(cache.get(id).rows[0].text,'retained');
  cache.head(id,snapshot(id,'backend-restarted','new'),{ticket:cache.ticket()});
  assert.equal(cache.get(id).snapshot.historyHead.epoch,'backend-restarted');
 }
});
