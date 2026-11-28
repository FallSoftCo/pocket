import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {DatabaseSync} from 'node:sqlite';
import {coordinatorProvider} from '../server/coordinator-provider.mjs';
import {VoiceController,VOICE_TOOL_CONTRACT,VOICE_TOOL_REVISION} from '../server/voice-controller.mjs';

class ProviderCodex extends EventEmitter {
  constructor(config,threads=[]){
    super();this.config=config;this.calls=[];this.started=0;
    this.threads=new Map(threads.map(thread=>[thread.id,thread]));
  }
  async connect(){}
  async call(method,params={}){
    this.calls.push({method,params});
    if(method==='config/read')return {config:this.config};
    if(method==='thread/read')return {thread:this.threads.get(params.threadId)||{id:params.threadId,status:{type:'idle'}}};
    if(method==='thread/start'){
      const id=`coordinator_${++this.started}`;
      const thread={id,modelProvider:params.modelProvider||'openai',model:params.model||null,status:{type:'idle'}};
      this.threads.set(id,thread);return {thread};
    }
    if(method==='thread/resume')return {thread:this.threads.get(params.threadId)};
    return {};
  }
  answer(){}
}

const deepseekConfig={model:'deepseek-flash',model_provider:'deepseek',model_providers:{deepseek:{name:'DeepSeek',base_url:'https://api.deepseek.com/v1',wire_api:'responses'}}};

test('coordinator provider comes from environment configuration and never substitutes OpenAI',async()=>{
  const deepseek=new ProviderCodex(deepseekConfig);
  assert.deepEqual(await coordinatorProvider(deepseek,{cwd:'/work'}),{providerId:'deepseek',model:'deepseek-flash'});
  assert.equal(deepseek.calls[0].params.cwd,'/work');

  await assert.rejects(coordinatorProvider(new ProviderCodex({model:'deepseek-flash',model_provider:'missing'}),{cwd:'/work'}),/not configured/);
  await assert.rejects(coordinatorProvider(new ProviderCodex({model_provider:'deepseek',model_providers:{deepseek:{name:'DeepSeek'}}}),{cwd:'/work'}),/Choose a model/);
  assert.deepEqual(await coordinatorProvider(new ProviderCodex({model:'gpt-6.1-sol'}),{cwd:'/work'}),{providerId:'openai',model:'gpt-6.1-sol'});
});

test('coordinator creates its hidden native thread with the configured DeepSeek provider and model',async t=>{
  const db=new DatabaseSync(':memory:'),codex=new ProviderCodex(deepseekConfig);
  t.after(()=>db.close());
  const controller=new VoiceController({db,codex,cwd:'/work',host:'fixture',api:async()=>({threads:[]}),provider:options=>coordinatorProvider(codex,options)});
  const session=await controller.ensure('phone');
  const start=codex.calls.find(call=>call.method==='thread/start');
  assert.equal(start.params.modelProvider,'deepseek');
  assert.equal(start.params.model,'deepseek-flash');
  assert.equal(start.params.allowProviderModelFallback,false);
  assert.equal(session.coordinator_provider,'deepseek');
  assert.equal(session.coordinator_model,'deepseek-flash');
  assert.equal(session.tool_revision,VOICE_TOOL_REVISION);
  await controller.ensure('phone');
  assert.equal(codex.started,1);
});

test('an existing coordinator on another provider is replaced without crossing an active turn',async t=>{
  const db=new DatabaseSync(':memory:'),codex=new ProviderCodex(deepseekConfig,[{id:'legacy',modelProvider:'openai',model:'gpt-6.1-sol',status:{type:'idle'}}]);
  t.after(()=>db.close());
  const controller=new VoiceController({db,codex,cwd:'/work',host:'fixture',api:async()=>({threads:[]}),provider:options=>coordinatorProvider(codex,options)});
  db.prepare("INSERT INTO voice_sessions(device,thread_id,full,selected,focus,refs,proactive,tool_revision,tool_contract,coordinator_provider,coordinator_model) VALUES('phone','legacy',1,NULL,'coordinator','[]',0,?,?, 'openai','gpt-6.1-sol')").run(VOICE_TOOL_REVISION,VOICE_TOOL_CONTRACT);
  const session=await controller.ensure('phone');
  assert.equal(session.thread_id,'coordinator_1');
  const start=codex.calls.find(call=>call.method==='thread/start');
  assert.equal(start.params.modelProvider,'deepseek');
  assert.equal(start.params.model,'deepseek-flash');
  assert.equal(db.prepare('SELECT reason FROM voice_retired_controllers WHERE thread_id=?').get('legacy').reason,'superseded');
  assert.equal(controller.owns('legacy'),true);
});

test('an active coordinator on another provider is left untouched',async t=>{
  const db=new DatabaseSync(':memory:'),codex=new ProviderCodex(deepseekConfig,[{id:'legacy',modelProvider:'openai',model:'gpt-6.1-sol',status:{type:'active'}}]);
  t.after(()=>db.close());
  const controller=new VoiceController({db,codex,cwd:'/work',host:'fixture',api:async()=>({threads:[]}),provider:options=>coordinatorProvider(codex,options)});
  db.prepare("INSERT INTO voice_sessions(device,thread_id,full,selected,focus,refs,proactive,tool_revision,tool_contract,coordinator_provider,coordinator_model) VALUES('phone','legacy',1,NULL,'coordinator','[]',0,?,?, 'openai','gpt-6.1-sol')").run(VOICE_TOOL_REVISION,VOICE_TOOL_CONTRACT);
  await assert.rejects(controller.ensure('phone'),/still working/);
  assert.equal(codex.calls.some(call=>call.method==='thread/start'),false);
  assert.equal(controller.session('phone').thread_id,'legacy');
});
