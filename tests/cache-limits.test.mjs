import test from 'node:test';
import assert from 'node:assert/strict';
import {LiveTimeline,timelinePage} from '../server/timeline.mjs';
import {ThreadHistory} from '../server/history.mjs';

test('live display caches evict by bytes, thread count and age, and never retain binary tool payloads',()=>{
 let now=0;const cache=new LiveTimeline({maxBytes:100000,maxThreads:3,maxItems:10,ttlMs:100,clock:()=>now});
 for(let t=0;t<30;t++)for(let i=0;i<50;i++){
  cache.ingest({method:'item/completed',params:{threadId:'thread-'+t,turnId:'turn',item:{id:'item-'+i,type:'dynamicToolCall',tool:'check',contentItems:[{type:'text',text:'x'.repeat(3000)},{type:'image',data:'PRIVATE_BINARY'.repeat(10000)}]}}});
  assert.ok(cache.bytes<=100000);assert.ok(cache.threads.size<=3);
 }
 for(const turns of cache.threads.values())for(const turn of turns.values()){assert.ok(turn.items.size<=10);assert.ok(!JSON.stringify([...turn.items.values()]).includes('PRIVATE_BINARY'));}
 const measured=[...cache.threads.values()].flatMap(turns=>[...turns.values()]).flatMap(t=>[...t.items.values()]).reduce((n,x)=>n+x.bytes,0);assert.equal(cache.bytes,measured);
 now=101;cache.prune();assert.equal(cache.threads.size,0);assert.equal(cache.bytes,0);
});

test('evicted streaming prefixes request a reload and fresh snapshots replace stale cache entries',()=>{
 const cache=new LiveTimeline({maxItems:1});const thread={id:'thread',turns:[{id:'turn',status:'inProgress',items:[{id:'message',type:'agentMessage',text:'old'}]}]};
 cache.seed(thread);cache.seed({...thread,turns:[{...thread.turns[0],items:[{id:'message',type:'agentMessage',text:'fresh'}]}]});
 assert.equal(timelinePage(cache.merge(thread)).rows[1].text,'fresh');
 cache.ingest({method:'item/completed',params:{threadId:'thread',turnId:'turn',item:{id:'other',type:'agentMessage',text:'other'}}});
 const event=cache.ingest({method:'item/agentMessage/delta',params:{threadId:'thread',turnId:'turn',itemId:'message',delta:'suffix'}});assert.equal(event.reload,true);assert.equal(event.row,undefined);
 const version=cache.version;cache.clear();assert.equal(cache.bytes,0);assert.equal(cache.threads.size,0);assert.equal(cache.version,version);
});

test('a single long turn pages within item and byte budgets with stable cursors while new items arrive',async()=>{
 const items=Array.from({length:101},(_,i)=>({id:String(i),type:'agentMessage',text:i===99?'🟩'.repeat(100000):'Message '+i}));
 const reader=new ThreadHistory({async call(method,p){
  if(method==='thread/turns/list')return {data:[{id:'turn',status:'completed',items:[]}],backwardsCursor:'anchor'};
  const end=p.cursor?Number(p.cursor):items.length,start=Math.max(0,end-p.limit);
  return {data:items.slice(start,end).reverse().map(item=>({item})),nextCursor:start?String(start):null};
 }},{maxItems:16,maxBytes:64000});
 const metadata={id:'thread',historyMode:'paginated'};const pages=[];let before=null;
 do{
  const page=await reader.read(metadata,{before});const loaded=page.turns.flatMap(t=>t.items);pages.unshift(loaded.map(i=>i.id));
  assert.ok(loaded.length<=16);assert.ok(loaded.reduce((n,i)=>n+Buffer.byteLength(JSON.stringify(i)),0)<=64000);
  if(before)assert.equal(timelinePage(page).rows.some(r=>r.kind==='turnEnd'),false);
  else {assert.ok(loaded.find(i=>i.id==='99')._pocketRow.truncated);items.push({id:'new',type:'agentMessage',text:'Arrived later'});}
  before=page._pocketPage.before;
 }while(before);
 assert.deepEqual(pages.flat(),Array.from({length:101},(_,i)=>String(i)));
 assert.equal(items[99].text.length,200000,'source history is not modified');
});
