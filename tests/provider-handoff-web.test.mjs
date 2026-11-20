import test from 'node:test';
import assert from 'node:assert/strict';
import {JSDOM} from 'jsdom';
import {ConversationToolsPane} from '../web/conversation-tools.mjs';

test('web continue retains original provider/source and checks the lost receipt without resubmitting',async()=>{
 const dom=new JSDOM('<body/>',{url:'https://fixture.invalid'});
 const previous={document:global.document,localStorage:global.localStorage,confirm:global.confirm};
 Object.assign(global,{document:dom.window.document,localStorage:dom.window.localStorage,confirm:()=>true});
 dom.window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
 dom.window.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new dom.window.Event('close'));};
 try{
  const source='env_remote__c291cmNl',destination='env_remote__ZGVzdGluYXRpb24';let scope=source,saved,opened;
  const calls=[];
  const api=async(path,body,options)=>{
   calls.push({path,body,options});
   if(path==='/api/model-providers')return {state:'available',defaultProviderId:'openai',providers:[{id:'openai',name:'OpenAI'},{id:'deepseek',name:'DeepSeek',baseUrl:'https://api.deepseek.com'}]};
   if(body){saved=structuredClone(body);throw Error('Lost response');}
   if(path.endsWith('/conversation-tools'))return {cwd:'/synthetic',active:false,features:{continue:{available:true}},turns:[{id:'settled-turn',status:'completed',userText:'Original task'}],operations:[],worktree:{reason:''}};
   if(saved&&path.endsWith(saved.id))return {id:saved.id,threadId:source,kind:'continue',state:'accepted',thread:{id:destination},result:{message:'Continuation queued'}};
   throw Error('Unexpected request '+path);
  };
  const pane=new ConversationToolsPane({api,source:()=>scope,device:()=> 'paired-browser',appendDraft:()=>assert.fail('Original draft must not change'),applied:async r=>{opened=r.thread.id;scope=opened;pane.dialog.close();}});
  const button=text=>[...pane.dialog.querySelectorAll('button')].find(b=>b.textContent===text);
  await pane.show();await button('Continue with another provider').onclick();
  const choice=pane.dialog.querySelector('#continue-provider');choice.value='deepseek';choice.onchange();
  pane.dialog.querySelector('textarea').value='Do not repeat the original receipt.';
  await button('Save handoff & continue').onclick();
  assert.equal(saved.kind,'continue');assert.equal(saved.expectedLastTurnId,'settled-turn');assert.equal(saved.selection.providerId,'deepseek');assert.equal(saved.selection.model,'deepseek-flash');
  assert.equal(calls.find(c=>c.path==='/api/model-providers').options.scope,source);
  await pane.refresh();await button('Check saved operation').onclick();
  assert.equal(opened,destination);assert.equal(calls.filter(c=>c.body).length,1);assert.equal(JSON.parse(localStorage.getItem('nextcomp.web.v1.conversation-operation.paired-browser.'+source)).id,saved.id);
 }finally{Object.assign(global,previous);dom.window.close();}
});
