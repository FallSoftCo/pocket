import test from 'node:test';
import assert from 'node:assert/strict';
import {ThreadHistory} from '../server/history.mjs';
import {LiveTimeline,timelinePage} from '../server/timeline.mjs';

test('paged history preserves chronology, strips binary output and never requests full history',async()=>{
  const calls=[],metadata={id:'large-thread',historyMode:'paginated',turns:[]};
  const codex={async call(method,p){
    calls.push({method,p});
    if(method==='thread/turns/list'){
      assert.equal(p.itemsView,'notLoaded');const end=p.cursor?Number(p.cursor.replace('at:',''))+(p.sortDirection==='asc'?1:0):19;
      return {data:Array.from({length:Math.min(p.limit,end)},(_,i)=>({id:'turn-'+(end-i-1),status:'completed',items:[]})),nextCursor:end>p.limit?String(end-p.limit):null,backwardsCursor:'at:'+(end-1)};
    }
    assert.equal(method,'thread/items/list');assert.ok(p.limit<=8);assert.equal(p.sortDirection,'desc');
    return p.cursor?{data:[{item:{id:'user',type:'userMessage',content:[{type:'text',text:p.turnId},{type:'image',url:'PRIVATE IMAGE DATA'}]}}]}:{data:[
      {item:{id:'answer',type:'agentMessage',phase:'final_answer',text:'Finished'}},
      {item:{id:'reason',type:'reasoning',content:['PRIVATE REASONING'],summary:['Checked']}},
      {item:{id:'tool',type:'commandExecution',command:'test',aggregatedOutput:'x'.repeat(20000)}}
    ],nextCursor:'next-items'};
  }};
  const reader=new ThreadHistory(codex),all=[];let before=null;
  do{
    const page=await reader.read(metadata,{before});all.unshift(...page.turns.map(t=>t.id));
    const rows=timelinePage(page).rows;
    assert.deepEqual(rows.slice(0,6).map(r=>r.kind),['turn','user','activity','activity','message','turnEnd']);
    assert.equal(rows[2].detail.length,12000);
    assert.ok(!JSON.stringify(page).includes('PRIVATE'));
    before=page._pocketPage.before;
  }while(before);
  assert.deepEqual(all,Array.from({length:19},(_,i)=>'turn-'+i));
  assert.ok(calls.every(c=>!['thread/read','thread/resume'].includes(c.method)));
});

test('paged snapshots accept live deltas without replaying stale display rows or inserting other pages',async()=>{
  const metadata={id:'thread',historyMode:'paginated'};
  const reader=new ThreadHistory({async call(method){return method==='thread/turns/list'?{data:[{id:'turn',status:'inProgress'}]}:{data:[
    {item:{id:'tool',type:'commandExecution',command:'test',aggregatedOutput:'one '}},
    {item:{id:'message',type:'agentMessage',text:'Hello '}}
  ]};}});
  const thread=await reader.read(metadata);const live=new LiveTimeline();live.seed(thread);
  live.ingest({method:'item/agentMessage/delta',params:{threadId:'thread',turnId:'turn',itemId:'message',delta:'world'}});
  live.ingest({method:'item/commandExecution/outputDelta',params:{threadId:'thread',turnId:'turn',itemId:'tool',delta:'two'}});
  live.turn('thread','other-page');
  const merged=live.merge(thread,{includeMissing:false});assert.equal(merged.turns.length,1);
  const rows=timelinePage(merged).rows;assert.equal(rows[1].text,'Hello world');assert.equal(rows[2].detail,'one two');
});

test('legacy history remains supported and repeated item cursors fail visibly',async()=>{
  const reader=new ThreadHistory({async call(method,p){assert.equal(method,'thread/read');assert.equal(p.includeTurns,true);return {thread:{id:p.threadId,turns:[]}};}});
  assert.equal((await reader.read({id:'legacy'})).id,'legacy');
  const looping=new ThreadHistory({async call(method){return method==='thread/turns/list'?{data:[{id:'turn'}]}:{data:[],nextCursor:'same'};}});
  await assert.rejects(looping.read({id:'thread',historyMode:'paginated'}),/repeated history cursor/);
});

test('older app-server falls back when paginated metadata precedes list support',async()=>{
  const calls=[];const codex={async call(method,p){calls.push(method);if(method==='thread/turns/list')throw Error('list_turns is not supported yet');return {thread:{id:p.threadId,historyMode:'paginated',turns:[{id:'turn',items:[]}]}};}};
  const reader=new ThreadHistory(codex);const first=await reader.read({id:'phone',historyMode:'paginated'});
  assert.equal(first.turns.length,1);assert.deepEqual(first._pocketPage,{hasEarlier:false,before:null});
  const earlier=await reader.read({id:'phone',historyMode:'paginated'},{before:'page:any'});
  assert.equal(earlier.turns.length,0);assert.deepEqual(calls,['thread/turns/list','thread/read']);
});
test('list preview reads latest public thinking items instead of message-only turn summaries',async()=>{
 const calls=[];
 const h=new ThreadHistory({async call(method,p){
  calls.push({method,p});
  if(method==='thread/turns/list')return {data:[{id:'turn',status:'inProgress',items:[{id:'user',type:'userMessage',content:'old request'}]}]};
  return {data:[{item:{id:'thought',type:'reasoning',summary:['Comparing two designs'],content:['hidden']}},{item:{id:'user',type:'userMessage',content:'old request'}}]};
 }});
 const {latestActivity}=await import('../server/thread-previews.mjs');
 const t=await h.preview({id:'desktop',historyMode:'paginated'});
 assert.equal(latestActivity(t).preview,'Thinking · Comparing two designs');
 assert.ok(!JSON.stringify(t).includes('hidden'));
 assert.equal(calls.at(-1).p.limit,6);
 assert.ok(calls.every(c=>!['thread/read','thread/resume'].includes(c.method)));
});
