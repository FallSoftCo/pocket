import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {WorkRecord} from '../server/work-record.mjs';
import {displayItem} from '../server/timeline.mjs';
const make=()=>{const db=new DatabaseSync(':memory:');return {db,record:new WorkRecord(db,{version:'test',clock:()=>500000})};};
const request={id:'u',type:'userMessage',content:[{type:'text',text:'Improve NextComp and publish the output'}]};
const answer={id:'a',type:'agentMessage',phase:'final_answer',text:'Added the work record. [Output](/project/result.md)'};
test('automatic capture deduplicates replay, preserves source and survives restart',()=>{
 const {db,record}=make();record.enroll({id:'t',cwd:'/project',name:'NextComp'});
 const thread={id:'t',turns:[{id:'one',startedAt:100,status:'completed',items:[request,answer]}]};
 record.remember(thread);record.remember(thread);
 const entry=new WorkRecord(db).list().entries[0];
 assert.equal(entry.items.length,2);assert.equal(entry.source.turnId,'one');assert.equal(entry.cwd,'/project');assert.equal(entry.source_at,100000);assert.equal(entry.version,'test');assert.equal(entry.items[1].evidence,'agent-reported');
});
test('unrelated global events, reasoning and command output are not copied',()=>{
 const account=make();assert.doesNotThrow(()=>account.record.observe({method:'account/updated',params:{}}));
 const {db,record}=make();record.observe({method:'item/completed',params:{threadId:'unrelated',turnId:'x',item:request}});assert.equal(record.list().entries.length,0);
 record.enroll({id:'t'});
 record.item('t','x',{id:'secret',type:'reasoning',summary:['private reasoning']});
 record.item('t','x',{id:'cmd',type:'commandExecution',command:'credential command',aggregatedOutput:'secret log',exitCode:1});
 record.item('t','x',{id:'files',type:'fileChange',status:'failed',changes:[{path:'/project/new.md',diff:'secret diff'}]});
 const serialized=JSON.stringify(record.list());assert.doesNotMatch(serialized,/private reasoning|credential command|secret log|secret diff/);assert.match(serialized,/failed/);assert.equal(db.prepare('SELECT COUNT(*) n FROM work_record_items').get().n,2);
});
test('history order, cursor pagination and follow-ups use source time rather than backfill order',()=>{
 const {record}=make();record.enroll({id:'t'});
 record.turn('t',{id:'new',startedAt:200,status:'completed',items:[answer]});
 record.turn('t',{id:'old',startedAt:100,status:'completed',items:[request]});
 const page=record.list({limit:1});assert.equal(page.entries[0].id,'new');assert.equal(page.entries[0].continuation,true);
 const earlier=record.list({before:page.before});assert.equal(earlier.entries[0].id,'old');assert.equal(earlier.entries[0].continuation,false);assert.equal(earlier.before,null);
 assert.throws(()=>record.list({before:'bad'}),/Invalid/);
});
test('bounded read-only backfill resumes its cursor and never downgrades completed live turns',async()=>{
 const {db,record}=make();record.enroll({id:'t'});record.turn('t',{id:'x',status:'completed',items:[answer]});
 const calls=[];const history={read:async(meta,opts)=>{calls.push({meta,opts});return {id:'t',turns:[{id:'x',status:'inProgress',items:[]}],_pocketPage:{hasEarlier:true,before:'page2'}};}};
 await record.scan(history,()=>({historyMode:'paginated'}));await record.scan(history,()=>({}));
 assert.equal(calls[0].opts.preferPaging,true);assert.equal(calls[0].opts.maxItems,80);assert.equal(calls[1].opts.before,'page2');assert.equal(record.list().entries[0].status,'completed');assert.equal(record.list().coverage.backfilling,1);
 assert.equal(db.prepare('SELECT cursor FROM work_record_sessions').get().cursor,'page2');
});
test('display-compacted history keeps user words, output and file paths without diffs',()=>{
 const {record}=make();record.enroll({id:'t'});
 for(const item of [request,answer,{id:'f',type:'fileChange',changes:[{path:'result.md',diff:'internal diff'}]}])record.item('t','one',displayItem(item,'one'));
 const items=record.list().entries[0].items;assert.equal(items[0].text,request.content[0].text);assert.deepEqual(items[2].paths,['result.md']);
});
test('team room/direct records remain device and environment scoped with observed-message provenance',()=>{
 const {record}=make(),command={path:'teams/team/history',query:{task:'task',visibility:'direct:worker'}};
 record.team('phone','env1',command,{data:[{id:1,author:'you',body:'Fix it'}]});record.team('phone','env1',command,{data:[{id:1,author:'you',body:'Fix it'}]});
 record.team('other','env1',command,{data:[{id:2,body:'private other device'}]});record.team('phone','env2',command,{data:[{id:1,body:'different environment'}]});
 assert.equal(record.teamList('phone').length,2);assert.doesNotMatch(JSON.stringify(record.teamList('phone')),/private other device/);assert.equal(record.teamList('phone')[1].visibility,'direct:worker');assert.equal(record.teamList('phone')[1].evidence,'message-observed');
});
