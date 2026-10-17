import test from 'node:test';import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';import {DatabaseSync} from 'node:sqlite';
import {mkdtempSync,rmSync} from 'node:fs';import {join} from 'node:path';import {tmpdir} from 'node:os';
import {VoiceController} from '../server/voice-controller.mjs';
class Runtime extends EventEmitter {
 constructor(){super();this.starts=[];}
 async connect(){}
 async call(method,params){
  if(method==='turn/start'){this.starts.push(params);return {turn:{id:'model-turn-'+this.starts.length}};}
  return {thread:{id:'queue-controller',status:{type:'idle'}}};
 }
 answer(){}
 finish(){this.emit('event',{method:'item/completed',params:{threadId:'queue-controller',item:{type:'agentMessage',phase:'final_answer',text:'Result retained'}}});this.emit('event',{method:'turn/completed',params:{threadId:'queue-controller',turn:{status:'completed'}}});}
}
const wait=async predicate=>{for(let n=0;n<100;n++){if(predicate())return;await new Promise(r=>setTimeout(r,5));}assert.ok(predicate(),'Owned queue did not settle');};
test('disk-backed coordinator commands serialize, retain destinations, cancel and survive restart without replaying a native intent',async()=>{
 const folder=mkdtempSync(join(tmpdir(),'nextcomp-task-queue-'));let db=new DatabaseSync(join(folder,'queue.db')),runtime=new Runtime(),controller;
 const make=()=>{controller=new VoiceController({db,codex:runtime,cwd:'/tmp',host:'qa',api:async()=>({threads:[]}),transcribe:()=>{throw Error('Already transcribed')}});controller.discovery.initialSnapshot=async()=>({sessions:[],references:[]});};
 const origin=id=>({backend:'stock-codex-voice',version:1,focus:'direct',selected:id,full:true});
 try {
  make();const a=controller.submitText('qa-device','queue-original-0001','First command',null,origin('task-first'));
  assert.equal(a.state,'queued');await wait(()=>runtime.starts.length===1);
  const b=controller.submitText('qa-device','queue-original-0002','Second command',null,origin('task-second'));
  assert.equal(b.state,'queued');assert.equal(runtime.starts.length,1);
  controller.submitText('qa-device','queue-cancelled-0003','Cancel this queued request',null,origin('task-third'));
  assert.equal(controller.cancelQueued('qa-device','queue-cancelled-0003').state,'cancelled');
  assert.equal(controller.submitText('qa-device','queue-cancelled-0003','Cancel this queued request',null,origin('task-third')).state,'cancelled');
  runtime.finish();await wait(()=>runtime.starts.length===2);
  assert.match(runtime.starts[0].input[0].text,/"selectedSession":"task-first"/);
  assert.match(runtime.starts[1].input[0].text,/"selectedSession":"task-second"/);
  controller.submitText('qa-device','queue-restart-0004','Fourth command',null,origin('task-fourth'));
  const seed=db.prepare("INSERT INTO voice_turns(device,id,hash,state,transcript,origin,created_at,timings) SELECT device,?,hash,'preparing',transcript,origin,created_at+?,? FROM voice_turns WHERE id='queue-restart-0004'");
  seed.run('queue-preparing-0005',1,'{}');seed.run('queue-intent-0006',2,JSON.stringify({native_start_requested:1}));
  for(const active of controller.active.values())clearTimeout(active.timer);
  runtime.removeAllListeners();db.close();db=new DatabaseSync(join(folder,'queue.db'));runtime=new Runtime();make();
  assert.equal(controller.get('qa-device','queue-original-0002').state,'unknown');
  assert.equal(controller.get('qa-device','queue-intent-0006').state,'unknown');
  assert.equal(controller.get('qa-device','queue-preparing-0005').state,'queued');
  controller.restoreQueued();await wait(()=>runtime.starts.length===1);
  assert.equal(runtime.starts[0].clientUserMessageId,'voice-queue-restart-0004');runtime.finish();
  await wait(()=>runtime.starts.length===2);assert.equal(runtime.starts[1].clientUserMessageId,'voice-queue-preparing-0005');runtime.finish();
  await wait(()=>controller.get('qa-device','queue-preparing-0005').state==='completed');
  controller.authorizeQueued=async()=>{throw Object.assign(Error('Original device revoked'),{status:403});};
  controller.submitText('qa-device','queue-revoked-0007','Must not execute',null,origin('task-revoked'));
  await wait(()=>controller.get('qa-device','queue-revoked-0007').state==='failed');controller.restoreQueued();
  assert.equal(runtime.starts.length,2);assert.equal(controller.get('qa-device','queue-cancelled-0003').state,'cancelled');
  assert.equal(controller.get('qa-device','queue-original-0001').response,'Result retained');
 }finally{if(controller)for(const active of controller.active.values())clearTimeout(active.timer);runtime.removeAllListeners();db.close();rmSync(folder,{recursive:true,force:true});}
});
