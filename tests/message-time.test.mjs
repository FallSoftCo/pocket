import test from 'node:test';
import assert from 'node:assert/strict';
import {itemRow,displayItem,LiveTimeline} from '../server/timeline.mjs';
import {projectNativeTimeline} from '../server/native-timeline.mjs';
import {messageTime} from '../web/message-time.mjs';
test('absolute message time follows viewer timezone and DST, never fetch time',()=>{
 const startedAtMs=Date.parse('2026-11-07T02:05:00Z');
 assert.equal(messageTime({startedAtMs},{locale:'en-US',timeZone:'America/New_York'}).label,'Nov 6 · 21:05');
 assert.equal(messageTime({startedAtMs},{locale:'en-US',timeZone:'Asia/Kolkata'}).label,'Nov 7 · 07:35');
 for(const [at,zone] of [['2026-11-01T05:30:00Z','EDT'],['2026-11-01T06:30:00Z','EST']])assert.match(messageTime({startedAtMs:Date.parse(at)},{locale:'en-US',timeZone:'America/New_York'}).full,new RegExp(zone));
 assert.equal(messageTime({startedAtMs:0,completedAtMs:startedAtMs},{locale:'en-US',timeZone:'UTC'}).iso,'2026-11-07T02:05:00.000Z');
 for(const value of [null,0,-1,NaN,Infinity,'1794017100000',8640000000000001])assert.equal(messageTime({startedAtMs:value}),null);
});
test('native item timestamps survive bounded caching, partial live updates and canonical history without reordering',()=>{
 const startedAtMs=Date.parse('2026-11-07T02:05:00Z');
 const item={id:'answer',type:'agentMessage',text:'One',startedAtMs,completedAtMs:startedAtMs+800};
 assert.equal(itemRow(displayItem(item,'turn'),'turn').startedAtMs,startedAtMs);
 const live=new LiveTimeline();
 const event=(method,params)=>live.ingest({method,params:{threadId:'thread',turnId:'turn',...params}});
 assert.equal(event('item/started',{item:{...item,completedAtMs:null}}).row.startedAtMs,startedAtMs);
 assert.equal(event('item/agentMessage/delta',{itemId:'answer',delta:' Two'}).row.startedAtMs,startedAtMs);
 const final=event('item/completed',{item:{id:'answer',type:'agentMessage',text:'One Two',completedAtMs:startedAtMs+800}}).row;
 assert.equal(final.startedAtMs,startedAtMs);assert.equal(final.completedAtMs,startedAtMs+800);
 assert.equal(event('item/started',{item:{id:'other',type:'agentMessage',text:'No time'}}).row.startedAtMs,undefined);
 const projected=projectNativeTimeline({data:[{type:'item',position:1,turnId:'turn',item:{id:'user',type:'userMessage',startedAtMs:startedAtMs+900,content:[{text:'Question'}]}},{type:'item',position:2,turnId:'turn',item}],nextCursor:null},{threadId:'thread'});
 assert.deepEqual(projected.rows.map(r=>r.itemId),['user','answer']);assert.equal(projected.rows[1].startedAtMs,startedAtMs);
});
