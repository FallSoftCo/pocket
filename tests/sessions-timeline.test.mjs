import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {openStore} from '../server/store.mjs';
import {SessionStarts,projectPath,recentProjects} from '../server/sessions.mjs';
import {LiveTimeline,timelinePage} from '../server/timeline.mjs';
const wait=async f=>{for(let i=0;i<50;i++){if(f())return;await new Promise(r=>setTimeout(r,10));}assert.fail('Condition not reached');};

test('session creation is durable, idempotent, project-scoped, and inherits Codex settings',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'pocket-start-'));const {db}=openStore(dir);const calls=[],created=[];
 const codex={ready:false,call:async(method,params)=>{calls.push({method,params});return {thread:{id:'new-thread',cwd:params.cwd,turns:[]}};}};
 const starts=new SessionStarts(db,codex,(thread,row)=>created.push({thread,row}));
 const req={id:'phone-request-1',cwd:dir,prompt:'Read the project'};
 assert.throws(()=>starts.enqueue({...req,cwd:join(dir,'missing')}),{status:400});
 assert.equal(starts.get(req.id),undefined,'Rejected input must not persist a request');
 assert.equal(starts.enqueue(req).state,'queued');assert.equal(calls.length,0);
 assert.equal(starts.enqueue(req).id,req.id);assert.throws(()=>starts.enqueue({...req,prompt:'Different task'}),/already belongs/);
 assert.throws(()=>projectPath('relative/path'),/absolute/);assert.throws(()=>projectPath(join(dir,'missing')),/does not exist/);
 codex.ready=true;await starts.flush();await starts.flush();
 assert.deepEqual(calls,[{method:'thread/start',params:{cwd:dir}}]);
 assert.equal(starts.get(req.id).state,'started');assert.equal(starts.enqueue(req).thread_id,'new-thread');assert.equal(created.length,1);
 assert.equal(created[0].row.text,req.prompt);assert.equal(created[0].row.state,'queued');
 assert.equal(db.prepare('SELECT enabled FROM watches WHERE thread_id=?').get('new-thread').enabled,1);
 assert.equal(recentProjects([{cwd:dir},{cwd:dir},{cwd:'/missing/pocket/project'}]).length,1);
 db.close();
});
test('ambiguous creation and bridge restart never create a duplicate session',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'pocket-ambiguous-'));const {db}=openStore(dir);let calls=0;
 const codex={ready:true,call:async()=>{calls++;throw Error('thread/start timed out');}};
 const starts=new SessionStarts(db,codex,()=>{});starts.enqueue({id:'ambiguous-task',cwd:dir,prompt:'Test'});
 await wait(()=>starts.get('ambiguous-task').state==='unknown');await starts.flush();assert.equal(calls,1);
 db.prepare("UPDATE session_starts SET state='creating'").run();new SessionStarts(db,codex,()=>{});assert.equal(starts.get('ambiguous-task').state,'unknown');db.close();
});
test('timeline retains interleaved messages and tools, streams without duplication, and excludes raw reasoning',()=>{
 const thread={id:'thread',turns:[{id:'turn',status:'inProgress',startedAt:100,items:[
  {id:'u',type:'userMessage',content:[{type:'text',text:'Run tests'}]},
  {id:'m',type:'agentMessage',phase:'commentary',text:'Running '},
  {id:'c',type:'commandExecution',command:'npm test',status:'inProgress',aggregatedOutput:''},
  {id:'r',type:'reasoning',content:['PRIVATE RAW REASONING'],summary:['Checking results']}
 ]}]};
 const live=new LiveTimeline();live.seed(thread);
 const event=(method,params)=>live.ingest({method,params:{threadId:'thread',turnId:'turn',...params}});
 event('item/agentMessage/delta',{itemId:'m',delta:'tests now.'});
 event('item/commandExecution/outputDelta',{itemId:'c',delta:'Tests passed'});
 event('item/completed',{item:{id:'c',type:'commandExecution',command:'npm test',status:'completed',aggregatedOutput:'Tests passed',exitCode:0}});
 event('item/completed',{item:{id:'answer',type:'agentMessage',phase:'final_answer',text:'All tests passed.'}});
 const request={id:9,method:'item/commandExecution/requestApproval',params:{threadId:'thread',turnId:'turn',itemId:'c'}};
 const page=timelinePage(live.merge(thread),[request]);
 assert.deepEqual(page.rows.map(r=>r.id),['turn/header','turn/u','turn/m','turn/c','request/9','turn/r','turn/answer']);
 assert.equal(page.rows.find(r=>r.itemId==='m').text,'Running tests now.');
 assert.equal(page.rows.find(r=>r.itemId==='c').detail,'Tests passed');
 assert.equal(page.rows.find(r=>r.itemId==='answer').title,'Codex · response');
 assert.ok(!JSON.stringify(page).includes('PRIVATE RAW REASONING'));
});
test('history paging preserves full turns and can reach the oldest message',()=>{
 const thread={turns:Array.from({length:19},(_,i)=>({id:'t'+i,status:'completed',items:[{id:'u'+i,type:'userMessage',content:[{text:'Message '+i}]}]}))};
 const recent=timelinePage(thread);assert.equal(recent.before,'t11');assert.equal(recent.hasEarlier,true);
 const earlier=timelinePage(thread,[],{before:recent.before});assert.equal(earlier.before,'t3');
 const oldest=timelinePage(thread,[],{before:earlier.before});assert.equal(oldest.hasEarlier,false);assert.equal(oldest.rows[1].text,'Message 0');
});
