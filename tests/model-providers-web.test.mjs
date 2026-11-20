import test from 'node:test';
import assert from 'node:assert/strict';
import {JSDOM} from 'jsdom';
import {ProviderPanel,NewTaskProvider} from '../web/model-providers.mjs';

test('provider form saves only to its original source, clears the key and selects the provider for a new task',async()=>{
 const dom=new JSDOM('<body/>',{url:'https://fixture.invalid'}),previous=global.document;global.document=dom.window.document;
 dom.window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
 dom.window.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new dom.window.Event('close'));};
 try{
  let scope='env_mac__dGhyZWFk',saved=false;const calls=[];
  const api=async(path,body,options)=>{
   calls.push({path,body:body?structuredClone(body):null,options});
   if(body){saved=true;return {saved:true,notice:'Saved. No inference started.'};}
   return {state:'available',canConfigure:true,configVersion:'first',defaultProviderId:'openai',providers:[{id:'openai',name:'OpenAI'},...(saved?[{id:'deepseek',name:'DeepSeek',baseUrl:'https://api.deepseek.com'}]:[])]};
  };
  const panel=new ProviderPanel({api,getScope:()=>scope});await panel.open();
  const inputs=[...panel.dialog.querySelectorAll('input')];inputs.find(i=>i.type==='password').value='SYNTHETIC-SECRET';
  const save=[...panel.dialog.querySelectorAll('button')].find(b=>b.textContent==='Save provider');await save.onclick();
  assert.equal(calls.filter(c=>c.body).length,1);assert.equal(calls.find(c=>c.body).options.scope,scope);assert.equal(calls.find(c=>c.body).body.apiKey,'SYNTHETIC-SECRET');
  assert(!panel.dialog.innerHTML.includes('SYNTHETIC-SECRET'));assert.equal(dom.window.localStorage.length,0);
  const holder=document.createElement('div');document.body.append(holder);const choice=new NewTaskProvider({holder,api,manage:()=>{}});await choice.load();
  choice.select.value='deepseek';choice.select.onchange();assert.deepEqual(choice.value(),{providerId:'deepseek',model:'deepseek-flash',effort:'high'});
  const original=JSON.stringify(choice.value());choice.select.value='openai';choice.select.onchange();assert.equal(JSON.parse(original).providerId,'deepseek');
  await panel.open();const stale=[...panel.dialog.querySelectorAll('button')].find(b=>b.textContent==='Save provider');scope='another-thread';await stale.onclick();assert.equal(calls.filter(c=>c.body).length,1);
 }finally{global.document=previous;dom.window.close();}
});

test('a lost provider-save reply cannot be repeatedly submitted by the same form',async()=>{
 const dom=new JSDOM('<body/>'),previous=global.document;global.document=dom.window.document;
 dom.window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
 try{
  let writes=0;const panel=new ProviderPanel({getScope:()=> 'same',api:async(_path,body)=>{if(body){writes++;throw Error('Reply lost');}return {state:'available',providers:[],canConfigure:true,configVersion:'version'};}});
  await panel.open();panel.dialog.querySelector('input[type=password]').value='SYNTHETIC';const save=[...panel.dialog.querySelectorAll('button')].find(b=>b.textContent==='Save provider');
  await save.onclick();await save.onclick();assert.equal(writes,1);assert(save.disabled);assert.equal(panel.dialog.querySelector('input[type=password]').value,'');assert.match(panel.dialog.textContent,/Reload to inspect/);
 }finally{global.document=previous;dom.window.close();}
});
