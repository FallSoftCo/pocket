import test from 'node:test';
import assert from 'node:assert/strict';
import {ThreadHistory} from '../server/history.mjs';
import {LiveTimeline,timelinePage} from '../server/timeline.mjs';

test('anchored ordering finds a loaded successor beyond recent page without reading or resuming full history',async()=>{
 const calls=[];
 const history=new ThreadHistory({async call(method,p){calls.push({method,p});assert.equal(method,'thread/items/list');assert.equal(p.sortDirection,'asc');assert.equal(p.limit,8);
  if(typeof p.cursor==='object'){assert.deepEqual(p.cursor,{type:'item',itemId:'late-user'});return {data:Array.from({length:8},(_,n)=>({turnId:'turn1',item:{id:'tool-'+n,type:'commandExecution',command:'Synthetic'}})),nextCursor:'next'};}
  assert.equal(p.cursor,'next');return {data:[{turnId:'turn1',item:{id:'answer',type:'agentMessage',text:'Reply'}},{turnId:'turn1',item:{id:'later',type:'agentMessage',text:'Later'}}]};
 }});
 assert.deepEqual(await history.locateItemSuccessor('thread1',{turnId:'turn1',itemId:'late-user',knownItemIds:['prior-commentary','late-user','answer','later']}),{available:true,insertBefore:'turn1/answer',cursor:null});
 assert.equal(calls.length,2);
});

test('position continuation bounds work and binds exact thread turn and input',async()=>{
 let calls=0;const history=new ThreadHistory({async call(method,p){calls++;return {data:[],nextCursor:'cursor-'+calls};}});
 const args={turnId:'turn1',itemId:'input',knownItemIds:['answer']};
 const first=await history.locateItemSuccessor('thread1',args);assert.equal(calls,4);assert.ok(first.cursor);
 await assert.rejects(history.locateItemSuccessor('other', {...args,cursor:first.cursor}),/Invalid item position/);
 await assert.rejects(history.locateItemSuccessor('thread1',{...args,turnId:'other',cursor:first.cursor}),/Invalid item position/);
 await assert.rejects(history.locateItemSuccessor('thread1',{...args,itemId:'other',cursor:first.cursor}),/Invalid item position/);
 assert.equal(calls,4);
 const next=await history.locateItemSuccessor('thread1',{...args,cursor:first.cursor});assert.equal(calls,8);assert.notEqual(next.cursor,first.cursor);
});

test('position ignores raw reasoning and unrelated-turn successors, and exposes honest compatibility',async()=>{
 const history=new ThreadHistory({async call(){return {data:[{turnId:'turn1',item:{id:'raw',type:'reasoning',content:['PRIVATE']}},{turnId:'turn1',item:{id:'answer',type:'agentMessage',text:'Visible'}}]};}});
 assert.equal((await history.locateItemSuccessor('thread1',{turnId:'turn1',itemId:'input',knownItemIds:['raw','answer']})).insertBefore,'turn1/answer');
 const wrong=new ThreadHistory({async call(){return {data:[{turnId:'another',item:{id:'answer',type:'agentMessage',text:'Wrong'}}]};}});
 await assert.rejects(wrong.locateItemSuccessor('thread1',{turnId:'turn1',itemId:'input',knownItemIds:['answer']}),/outside the requested turn/);
 const old=new ThreadHistory({async call(){throw Error('invalid type: map, expected a string');}});
 assert.deepEqual(await old.locateItemSuccessor('thread1',{turnId:'turn1',itemId:'input',knownItemIds:['answer']}),{available:false,insertBefore:null,cursor:null});
});

test('position does not guess when no known successor exists or a transport fails',async()=>{
 const history=new ThreadHistory({async call(){return {data:[],nextCursor:null};}});
 assert.equal((await history.locateItemSuccessor('thread1',{turnId:'turn1',itemId:'input',knownItemIds:['input','earlier']})).insertBefore,null);
 const broken=new ThreadHistory({async call(){throw Error('network timeout');}});
 await assert.rejects(broken.locateItemSuccessor('thread1',{turnId:'turn1',itemId:'input',knownItemIds:['answer']}),/network timeout/);
 await assert.rejects(history.locateItemSuccessor('thread1',{turnId:'turn1',itemId:'input',knownItemIds:Array(257).fill('answer')}),/Invalid item position/);
});

test('compatibility pages keep exact item and turn boundary when new output and a newer turn arrive',async()=>{
 let advanced=false;const calls=[];
 const items=()=>Array.from({length:advanced?8:6},(_,n)=>({id:'m'+n,type:'agentMessage',text:'Text '+n}));
 const history=new ThreadHistory({async call(method,p){calls.push({method,p});assert.equal(method,'thread/turns/list');
  if(p.cursor==='old-turn')return {data:[{id:'previous',items:[{id:'old',type:'agentMessage',text:'Earlier'}]}]};
  assert.equal(p.cursor,advanced?'stable-turn':undefined);
  assert.equal(p.sortDirection,advanced?'asc':'desc');
  return {data:[{id:'current',items:items()}],backwardsCursor:'stable-turn',nextCursor:advanced?'wrong-ascending-next':'old-turn'};
 }});history.turnOnlyThreads.add('thread1');
 const first=await history.read({id:'thread1'},{maxItems:2});assert.deepEqual(first.turns[0].items.map(x=>x.id),['m4','m5']);
 advanced=true;
 const second=await history.read({id:'thread1'},{maxItems:2,before:first._pocketPage.before});assert.deepEqual(second.turns[0].items.map(x=>x.id),['m2','m3']);assert.equal(second._pocketPage.insertBefore,'current/m4');
 const third=await history.read({id:'thread1'},{maxItems:2,before:second._pocketPage.before});assert.deepEqual(third.turns[0].items.map(x=>x.id),['m0','m1']);assert.equal(third._pocketPage.insertBefore,'current/m2');
 const previous=await history.read({id:'thread1'},{maxItems:2,before:third._pocketPage.before});assert.equal(previous.turns[0].id,'previous');assert.equal(previous._pocketPage.insertBefore,'current/header');
 assert.ok(calls.every(x=>!['thread/read','thread/resume'].includes(x.method)));
});

test('compatibility boundary missing from an unstable provider fails instead of skipping retained history',async()=>{
 const history=new ThreadHistory({async call(){return {data:[{id:'current',items:[]}],nextCursor:null};}});history.turnOnlyThreads.add('thread1');
 const before='turnfull:'+Buffer.from(JSON.stringify({cursor:null,offset:2,turnId:'current',beforeItemId:'removed'})).toString('base64url');
 await assert.rejects(history.read({id:'thread1'},{before}),/item boundary is no longer available/);
});

test('recent opening returns newest batch without walking older items, then resumes losslessly',async()=>{
  const calls=[];
  const reader=new ThreadHistory({async call(method,p){
    calls.push({method,p});
    if(method==='thread/turns/list')return {data:[{id:'latest',status:'completed'}],backwardsCursor:'latest-anchor',nextCursor:null};
    assert.equal(method,'thread/items/list');
    const start=p.cursor?Number(p.cursor):100;
    return {data:Array.from({length:p.limit},(_,i)=>({item:{id:'item-'+(start-i),type:'agentMessage',text:'Message '+(start-i)}})),nextCursor:String(start-p.limit)};
  }});
  const metadata={id:'very-long',historyMode:'paginated'};
  const first=await reader.read(metadata,{maxItems:8,maxBytes:512000,maxTurns:1});
  assert.equal(calls.filter(c=>c.method==='thread/items/list').length,1);
  assert.deepEqual(first.turns[0].items.map(i=>i.id),Array.from({length:8},(_,i)=>'item-'+(93+i)));
  assert.equal(first._pocketPage.hasEarlier,true);
  const older=await reader.read(metadata,{before:first._pocketPage.before,maxItems:8,maxBytes:512000,maxTurns:1});
  assert.deepEqual(older.turns[0].items.map(i=>i.id),Array.from({length:8},(_,i)=>'item-'+(85+i)));
  assert.equal(older.turns[0]._pocketHideEnd,true);
  assert.equal(older._pocketPage.insertBefore,'latest/item-93');
  const next=JSON.parse(Buffer.from(older._pocketPage.before.slice(6),'base64url'));assert.equal(next.olderThanRow,'latest/item-85');
  assert.ok(calls.every(c=>!['thread/read','thread/resume'].includes(c.method)));
});

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


test('recent history probes bounded paging even when cold metadata omits pagination hint',async()=>{
 const calls=[];const history=new ThreadHistory({async call(method,p){calls.push({method,p});if(method==='thread/turns/list')return {data:[{id:'latest',status:'completed'}],backwardsCursor:'anchor'};assert.equal(method,'thread/items/list');return {data:[{item:{id:'recent',type:'agentMessage',text:'Newest'}}],nextCursor:'older'};}});
 const page=await history.read({id:'cold'},{preferPaging:true,maxItems:1,maxTurns:1});
 assert.equal(page.turns[0].items[0].id,'recent');assert.equal(page._pocketPage.hasEarlier,true);
 assert.ok(calls.every(c=>c.method!=='thread/read'));assert.equal(calls.filter(c=>c.method==='thread/items/list').length,1);
});

test('turn-only runtime loads recent items and pages the same turn without full transcript reads',async()=>{
 const calls=[];const h=new ThreadHistory({async call(method,p){calls.push({method,p});if(method==='thread/items/list')throw Error('thread/items/list is not supported yet');assert.equal(method,'thread/turns/list');return {data:[{id:'t',items:p.itemsView==='full'?Array.from({length:5},(_,i)=>({id:'m'+i,type:'agentMessage',text:'Text '+i})):[]}],nextCursor:null};}});
 const first=await h.read({id:'x'},{preferPaging:true,maxItems:2});assert.deepEqual(first.turns[0].items.map(i=>i.id),['m3','m4']);
 const second=await h.read({id:'x'},{before:first._pocketPage.before,maxItems:2});assert.deepEqual(second.turns[0].items.map(i=>i.id),['m1','m2']);
 const third=await h.read({id:'x'},{before:second._pocketPage.before,maxItems:2});assert.deepEqual(third.turns[0].items.map(i=>i.id),['m0']);assert.equal(third._pocketPage.hasEarlier,false);
 assert.equal(calls.filter(c=>c.method==='thread/items/list').length,1);assert.ok(calls.every(c=>!['thread/read','thread/resume'].includes(c.method)));
});
test('item history transport failures remain failures rather than triggering compatibility reads',async()=>{
 const h=new ThreadHistory({async call(method){if(method==='thread/items/list')throw Error('network timeout');return {data:[{id:'t'}]};}});await assert.rejects(h.read({id:'x'},{preferPaging:true}),/network timeout/);assert.equal(h.turnOnlyThreads.size,0);
});

test('turn-only compatibility is scoped to the affected thread',async()=>{
 const calls=[];const h=new ThreadHistory({async call(method,p){calls.push({method,p});if(method==='thread/items/list'){if(p.threadId==='old')throw Error('thread/items/list is not supported yet');return {data:[{item:{id:'new-item',type:'agentMessage',text:'New'}}]};}return {data:[{id:'t',items:[]}],nextCursor:null};}});
 await h.read({id:'old'},{preferPaging:true});const modern=await h.read({id:'new'},{preferPaging:true});assert.equal(modern.turns[0].items[0].id,'new-item');assert.equal(h.turnOnlyThreads.has('new'),false);
});

test('native inherited archive remains visible and pages stably after new destination items arrive',async()=>{
 const inherited=[{id:'source-turn',status:'completed',items:[{id:'source-user',type:'userMessage',content:[{type:'text',text:'Original native request'}]},{id:'source-answer',type:'agentMessage',text:'Preserved useful answer'}]}];
 const live={id:'destination-thread',turns:[{id:'destination-turn',status:'inProgress',items:[{id:'new-answer',type:'agentMessage',text:'Destination progress'}]}]};
 const history=new ThreadHistory({async call(method){if(method==='thread/turns/list')return {data:live.turns};if(method==='thread/items/list')return {data:live.turns[0].items.slice().reverse().map(item=>({item}))};throw Error('Unbounded native read '+method);}},{inheritedHistory:()=>inherited});history.fullSnapshotThreads.add(live.id);
 const page=await history.read(live,{maxItems:1,maxTurns:1});assert.equal(page.turns[0].items[0].id,'new-answer');const cursor=page._pocketPage.before;
 live.turns[0].items.push({id:'later-answer',type:'agentMessage',text:'Later destination progress'});
 const older=await history.read(live,{before:cursor,maxItems:1,maxTurns:1});assert.equal(older.turns[0].items[0].id,'source-answer');const earliest=await history.read(live,{before:older._pocketPage.before,maxItems:1,maxTurns:1});assert.equal(earliest.turns[0].items[0].id,'source-user');assert.equal(earliest._pocketPage.hasEarlier,false);
});


test('a cursor entering an older turn anchors before the retained newer heading',async()=>{
 const reader=new ThreadHistory({async call(method,p){
  if(method==='thread/turns/list')return p.cursor==='older'?{data:[{id:'older-turn',status:'completed'}],backwardsCursor:'older-anchor'}:{data:[{id:'newer-turn',status:'completed'}],nextCursor:'older',backwardsCursor:'newer-anchor'};
  return {data:[{item:{id:'answer',type:'agentMessage',text:'Result'}}]};
 }});
 const metadata={id:'thread',historyMode:'paginated'};
 const recent=await reader.read(metadata,{maxItems:1,maxTurns:1});
 const older=await reader.read(metadata,{maxItems:1,maxTurns:1,before:recent._pocketPage.before});
 assert.equal(older._pocketPage.insertBefore,'newer-turn/header');
 assert.equal(older.turns[0].id,'older-turn');
});

test('recent conversation window crosses an operations burst and retains exact older cursor',async()=>{
 const calls=[],items=Array.from({length:80},(_,n)=>({id:'source-'+n,type:n===15?'agentMessage':'commandExecution',text:n===15?'Progress answer':undefined,command:'synthetic'}));
 const history=new ThreadHistory({async call(method,p){
  calls.push({method,p});
  if(method==='thread/turns/list')return {data:[{id:'latest',status:'inProgress'}],backwardsCursor:'latest-anchor',nextCursor:null};
  const offset=Number(p.cursor||0),part=items.slice(offset,offset+p.limit);
  return {data:part.map(item=>({item})),nextCursor:offset+part.length<items.length?String(offset+part.length):null};
 }});history.paginationSupported=true;
 const first=await history.read({id:'busy',historyMode:'paginated'},{maxItems:32,maxTurns:2,maxSourcePages:4,stopAfterConversation:true});
 assert.equal(calls.filter(c=>c.method==='thread/items/list').length,2);
 assert.equal(first.turns[0].items[0].id,'source-15');assert.equal(first.turns[0].items.at(-1).id,'source-0');
 const cursor=JSON.parse(Buffer.from(first._pocketPage.before.slice(6),'base64url'));assert.equal(cursor.itemCursor,'16');
 const older=await history.read({id:'busy',historyMode:'paginated'},{before:first._pocketPage.before,maxItems:8,maxSourcePages:2});
 assert.equal(older._pocketPage.insertBefore,'latest/source-15');
 assert.deepEqual(older.turns[0].items.map(x=>x.id),Array.from({length:8},(_,n)=>'source-'+(23-n)));
 assert.ok(calls.every(c=>!['thread/read','thread/resume','turn/start'].includes(c.method)));
});

test('filtered internal items still consume the source read budget and can be continued without a rescan',async()=>{
 const cursors=[];const history=new ThreadHistory({async call(method,p){
  if(method==='thread/turns/list')return {data:[{id:'latest',status:'inProgress'}],backwardsCursor:'latest-anchor',nextCursor:null};
  cursors.push(p.cursor||null);const offset=Number(p.cursor||0);
  return {data:Array.from({length:8},(_,n)=>({item:{id:'hidden-'+(offset+n),type:'reasoning',summary:[],content:['never returned']}})),nextCursor:String(offset+8)};
 }});history.paginationSupported=true;
 const first=await history.read({id:'internal-burst',historyMode:'paginated'},{maxItems:32,maxSourcePages:4,stopAfterConversation:true});
 assert.deepEqual(cursors,[null,'8','16','24']);assert.equal(first.turns[0].items.length,0);assert.equal(first._pocketPage.hasEarlier,true);
 assert.ok(!JSON.stringify(first).includes('never returned'));
 await history.read({id:'internal-burst',historyMode:'paginated'},{before:first._pocketPage.before,maxItems:8,maxSourcePages:2});
 assert.deepEqual(cursors,[null,'8','16','24','32','40']);
});


test('saved complete-turn cursor survives a fresh reader without protocol discovery or losing its boundary',async()=>{
 const calls=[];
 const codex={async call(method,p){calls.push({method,p});assert.equal(method,'thread/turns/list');assert.equal(p.itemsView,'full');return {data:[{id:'current',items:Array.from({length:6},(_,i)=>({id:'m'+i,type:'agentMessage',text:'Message '+i}))}],backwardsCursor:'stable-turn',nextCursor:null};}};
 const first=new ThreadHistory(codex);first.turnOnlyThreads.add('thread1');
 const page=await first.read({id:'thread1',historyMode:'paginated'},{maxItems:2});
 assert.deepEqual(page.turns[0].items.map(x=>x.id),['m4','m5']);assert.ok(page._pocketPage.before.startsWith('turnfull:'));
 calls.length=0;
 const restarted=new ThreadHistory(codex);
 const older=await restarted.read({id:'thread1',historyMode:'paginated'},{before:page._pocketPage.before,preferPaging:true,maxItems:2});
 assert.deepEqual(older.turns[0].items.map(x=>x.id),['m2','m3']);assert.equal(calls.length,1);
 assert.equal(calls[0].p.cursor,'stable-turn');assert.equal(calls[0].p.sortDirection,'asc');
 await assert.rejects(new ThreadHistory(codex).read({id:'thread1'},{before:'turnfull:not-json'}),/Invalid history position/);
});
