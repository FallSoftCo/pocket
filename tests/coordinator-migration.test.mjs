import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {EventEmitter} from 'node:events';
import {VoiceController,VOICE_TOOL_REVISION} from '../server/voice-controller.mjs';

class MigrationCodex extends EventEmitter {
  constructor(){super();this.calls=[];this.starts=0;this.injects=0;this.failInject=0;this.failArchive=false;this.oldStatus='idle';}
  async connect(){}
  async call(method,params){
    this.calls.push({method,params});
    if(method==='thread/read')return {thread:{id:params.threadId,status:{type:this.oldStatus}}};
    if(method==='thread/start')return {thread:{id:`candidate_${++this.starts}`}};
    if(method==='thread/inject_items'){
      this.injects++;if(this.failInject===this.injects)throw Error('Synthetic injection unavailable');
    }
    if(method==='thread/archive'&&this.failArchive)throw Error('Synthetic cleanup unavailable');
    return {};
  }
}
function setup(t){
  const db=new DatabaseSync(':memory:'),codex=new MigrationCodex();
  const controller=new VoiceController({db,codex,cwd:'/fixture',host:'fixture',api:async()=>{throw Error('No work-session API is needed for migration.');}});
  db.prepare("INSERT INTO voice_sessions(device,thread_id,full,selected,focus,refs,proactive,tool_revision) VALUES('phone','legacy_controller',0,'dns_work','selected',?,1,0)").run(JSON.stringify([{threadId:'dns_work',state:'queued'}]));
  const put=db.prepare('INSERT INTO voice_turns(device,id,hash,state,transcript,response,error,created_at) VALUES(?,?,?,?,?,?,?,?)');
  const history=(count=130)=>{for(let i=0;i<count;i++)put.run('phone',`history_${i}`,'hash','completed',`Historical user ${i}`,`Historical answer ${i}`,null,1000);};
  t.after(()=>db.close());return {db,codex,controller,put,history};
}
const injected=(codex,id)=>codex.calls.filter(c=>c.method==='thread/inject_items'&&c.params.threadId===id).flatMap(c=>c.params.items);
const messageText=item=>item.content.map(part=>part.text).join('');

test('legacy coordinator gains current tools while preserving every history row and durable focus/reference state',async t=>{
  const {controller,db,codex,history,put}=setup(t);history();
  put.run('phone','uncertain_turn','hash','unknown','Unknown earlier request','Partial old result','Connection lost',1001);
  put.run('phone','failed_turn','hash','failed','Failed earlier request',null,'Transport failed',1002);
  put.run('phone','current_turn','hash','thinking','Current request must not be injected twice',null,null,1003);
  put.run('other','private_turn','hash','completed','Other device private request','Other device private response',null,1004);
  const before=db.prepare('SELECT * FROM voice_turns ORDER BY rowid').all();
  const result=await controller.ensure('phone');
  assert.equal(result.thread_id,'candidate_1');assert.equal(result.tool_revision,VOICE_TOOL_REVISION);
  assert.equal(result.selected,'dns_work');assert.equal(result.focus,'selected');assert.equal(result.proactive,1);
  assert.equal(result.refs,JSON.stringify([{threadId:'dns_work',state:'queued'}]));
  assert.deepEqual(db.prepare('SELECT * FROM voice_turns ORDER BY rowid').all(),before);
  const start=codex.calls.find(c=>c.method==='thread/start');
  assert.equal(start.params.sandbox,'read-only');assert.equal(start.params.approvalPolicy,'never');
  assert.equal(start.params.config['features.multi_agent'],false);
  assert.ok(start.params.dynamicTools[0].inputSchema.properties.operation.enum.includes('discover'));
  assert.ok(start.params.dynamicTools[0].inputSchema.properties.operation.enum.includes('catchup'));
  const items=injected(codex,'candidate_1'),users=items.filter(item=>item.role==='user').map(messageText);
  assert.equal(users.length,132);assert.equal(users[0],'Historical user 0');assert.equal(users[129],'Historical user 129');
  assert.deepEqual(users.slice(-2),['Unknown earlier request','Failed earlier request']);
  assert.doesNotMatch(JSON.stringify(items),/Other device private|Current request must not/);
  assert.match(JSON.stringify(items),/Historical coordinator delivery status: unknown/);
  assert.match(JSON.stringify(items),/never automatically resubmit/);
  assert.equal(controller.owns('legacy_controller'),true);assert.equal(controller.owns('candidate_1'),true);
  for(const call of codex.calls.filter(c=>c.method==='thread/inject_items')){
    assert.ok(call.params.items.length<=32);assert.ok(Buffer.byteLength(JSON.stringify(call.params.items))<257*1024);
  }
  assert.equal(codex.calls.some(c=>c.method==='turn/start'||c.method==='turn/interrupt'),false);
  assert.equal(codex.calls.some(c=>c.method==='thread/archive'&&c.params.threadId==='legacy_controller'),false);
});

test('successful migration is idempotent and resumes the new tool contract on reconnection',async t=>{
  const {controller,codex,history}=setup(t);history(3);
  await Promise.all([controller.ensure('phone'),controller.ensure('phone')]);
  const count=codex.calls.length;
  await controller.ensure('phone');assert.equal(codex.calls.length,count);
  controller.resumed.clear();await controller.ensure('phone');
  assert.equal(codex.starts,1);
  assert.equal(codex.calls.filter(c=>c.method==='thread/inject_items').length,1);
  const resume=codex.calls.find(c=>c.method==='thread/resume');assert.equal(resume.params.threadId,'candidate_1');
  assert.equal('dynamicTools' in resume.params,false);
});

test('partial injection failure preserves original mapping/history and retries into a fresh hidden candidate without duplicates',async t=>{
  const {controller,codex,history,db}=setup(t);history();codex.failInject=2;
  const before=db.prepare('SELECT * FROM voice_turns ORDER BY rowid').all();
  await assert.rejects(controller.ensure('phone'),/Synthetic injection unavailable/);
  assert.equal(controller.session('phone').thread_id,'legacy_controller');
  assert.equal(controller.session('phone').tool_revision,0);
  assert.deepEqual(db.prepare('SELECT * FROM voice_turns ORDER BY rowid').all(),before);
  assert.equal(controller.owns('candidate_1'),true);
  assert.ok(codex.calls.some(c=>c.method==='thread/archive'&&c.params.threadId==='candidate_1'));
  codex.failInject=0;await controller.ensure('phone');
  assert.equal(controller.session('phone').thread_id,'candidate_2');
  const users=injected(codex,'candidate_2').filter(item=>item.role==='user').map(messageText);
  assert.equal(users.length,130);assert.equal(new Set(users).size,130);
  assert.equal(users[0],'Historical user 0');assert.equal(users.at(-1),'Historical user 129');
  assert.equal(controller.owns('candidate_1'),true);assert.equal(controller.owns('legacy_controller'),true);
});

test('failed native cleanup retains hidden ownership and cannot replace the original coordinator',async t=>{
  const {controller,codex,history,db}=setup(t);history(2);codex.failInject=1;codex.failArchive=true;
  await assert.rejects(controller.ensure('phone'),/Synthetic injection unavailable/);
  assert.equal(controller.session('phone').thread_id,'legacy_controller');
  assert.equal(controller.owns('candidate_1'),true);
  assert.equal(db.prepare('SELECT reason FROM voice_retired_controllers WHERE thread_id=?').get('candidate_1').reason,'failed-candidate');
});

test('an active old coordinator is allowed to finish without interruption or replacement',async t=>{
  const {controller,codex}=setup(t);codex.oldStatus='active';
  await assert.rejects(controller.ensure('phone'),/still working/);
  assert.equal(controller.session('phone').thread_id,'legacy_controller');assert.equal(codex.starts,0);
  assert.equal(codex.calls.some(c=>c.method==='turn/interrupt'||c.method==='thread/archive'),false);
});

test('large historical messages are streamed without truncation or broken surrogate pairs',async t=>{
  const {controller,codex,put}=setup(t);const original='🙂'.repeat(40000)+' last words';
  put.run('phone','large_history','hash','completed',original,original,null,1000);
  await controller.ensure('phone');const items=injected(codex,'candidate_1');
  for(const role of ['user','assistant'])assert.equal(items.filter(item=>item.role===role).map(messageText).join(''),original);
  assert.ok(items.every(item=>messageText(item).length<=32000));
  assert.ok(items.every(item=>!/[\uD800-\uDBFF]$/.test(messageText(item))));
});
