// Actual browser application in a virtual DOM; synthetic transport only.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {randomUUID} from 'node:crypto';
import WebSocket from 'ws';
import {fixture} from '../tests/web-browser-fixture.mjs';
const require=createRequire(import.meta.url),modules=process.env.WEB_QA_MODULES;
if(!modules)throw Error('Provide disk-backed WEB_QA_MODULES.');
const {JSDOM}=require(modules+'/jsdom'),{build}=require(modules+'/esbuild'),{indexedDB}=require(modules+'/fake-indexeddb');
const bundle=await build({entryPoints:['web/app.mjs'],bundle:true,write:false,format:'iife',define:{'import.meta.url':JSON.stringify('http://127.0.0.1:18982/app.mjs')}});
const f=await fixture(),errors=[];let cookie='',dom,socket,release,headReads=0,headReturned=false,removals=0;
const held=new Promise(resolve=>release=resolve),row={id:'queued-dom',threadId:'parent-123',text:'Queued before any history',state:'queued',mode:'queue'};
const wait=async(fn,label)=>{const end=Date.now()+10000;while(Date.now()<end){if(fn())return;await new Promise(r=>setTimeout(r,20));}throw Error('Virtual DOM timed out: '+label);};
try{
 dom=new JSDOM(await readFile('web/index.html','utf8'),{url:f.url,runScripts:'outside-only',pretendToBeVisual:true});
 const w=dom.window,d=w.document,$=id=>d.getElementById(id);
 w.AbortController=globalThis.AbortController;w.AbortSignal=globalThis.AbortSignal;w.crypto.randomUUID=randomUUID;w.indexedDB=indexedDB;w.matchMedia=()=>({matches:false,addEventListener(){},removeEventListener(){}});
 w.IntersectionObserver=class{observe(){}unobserve(){}disconnect(){}};w.ResizeObserver=class{observe(){}unobserve(){}disconnect(){}};
 w.HTMLCanvasElement.prototype.getContext=()=>null;
 w.requestIdleCallback=fn=>w.setTimeout(()=>fn({didTimeout:false,timeRemaining:()=>50}),0);w.cancelIdleCallback=id=>w.clearTimeout(id);
 w.fetch=async(path,options={})=>{
  if(path.includes('/api/threads/parent-123/replies/queued-dom')){
   assert.equal(JSON.parse(options.body).action,'remove');removals++;
   return new Response(JSON.stringify({id:row.id,state:'cancelled',outbox:{version:1,revision:2,items:[]}}),{headers:{'Content-Type':'application/json'}});
  }
  const head=path.includes('/api/threads/parent-123')&&!options.body;
  if(head){headReads++;await held;}
  const response=await fetch(new URL(path,f.url),{...options,headers:{...options.headers,Origin:f.url,...(cookie?{Cookie:cookie}:{})}});
  if(response.headers.get('set-cookie'))cookie=response.headers.get('set-cookie').split(';')[0];
  if(head){const data=await response.json();headReturned=true;return new Response(JSON.stringify({...data,outbox:{version:1,revision:1,items:[row]},outgoing:[row]}),{status:response.status,headers:{'Content-Type':'application/json'}});}
  return response;
 };
 w.WebSocket=class extends WebSocket{constructor(url){super(url,{headers:{Origin:f.url,Cookie:cookie}});socket=this;}};
 w.Notification=class{static permission='denied';};w.HTMLElement.prototype.scrollIntoView=()=>{};
 w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;};w.prompt=()=>null;w.confirm=()=>true;
 w.addEventListener('error',e=>errors.push(e.message));w.eval(await readFile('web/theme.js','utf8'));w.eval(bundle.outputFiles[0].text);
 const click=n=>n.dispatchEvent(new w.MouseEvent('click',{bubbles:true})),fill=(n,text)=>{n.value=text;n.dispatchEvent(new w.Event('input',{bubbles:true}));};
 await wait(()=>!$('pairing').hidden,'pairing');fill(d.querySelector('input[name=code]'),'SYNTHETIC');$('pair-form').dispatchEvent(new w.Event('submit',{bubbles:true,cancelable:true}));
 await wait(()=>!$('app').hidden&&socket?.readyState===WebSocket.OPEN&&d.querySelector('[data-id=parent-123]'),'authenticated boot');
 click(d.querySelector('[data-id=parent-123]'));await wait(()=>headReads>0,'cold conversation');
 socket.emit('message',JSON.stringify({type:'reply',threadId:'parent-123',id:row.id,state:'queued',outbox:{version:1,revision:1,items:[row]}}));
 await wait(()=>$('outbox').textContent.includes(row.text),'live queue before history');assert.equal(headReturned,false);
 fill($('draft'),'Keep the next draft');click([...$('outbox').querySelectorAll('button')].find(n=>n.textContent==='Remove'));
 await wait(()=>!$('outbox').textContent.includes(row.text),'ACK removal without WebSocket or history');assert.equal(removals,1);assert.equal(headReturned,false);assert.equal($('draft').value,'Keep the next draft');
 release();await wait(()=>headReturned,'original stale history settles');await new Promise(r=>setTimeout(r,250));
 assert.equal($('outbox').textContent.includes(row.text),false);assert.equal($('draft').value,'Keep the next draft');assert.deepEqual(errors,[]);
 const result={passed:true,evidence:'actual app in virtual DOM; synthetic HTTP and WebSocket observations',queueVisibleBeforeHistory:true,removalFromOriginalAckWithoutSocket:true,lateHistoryCannotResurrect:true,draftPreserved:true,removals,errors};
 if(process.env.WEB_QA_OUTPUT)await writeFile(process.env.WEB_QA_OUTPUT,JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}catch(error){const d=dom?.window.document;console.error(JSON.stringify({stage:'failure',errors,appHidden:d?.getElementById('app')?.hidden,pairError:d?.getElementById('pair-error')?.textContent,appError:d?.getElementById('error')?.textContent,connection:d?.getElementById('connection')?.textContent,socketState:socket?.readyState,workList:d?.getElementById('work-list')?.innerHTML.slice(0,500)}));throw error;}finally{release();if(socket){socket.onclose=null;socket.onmessage=null;socket.onopen=null;socket.onerror=()=>{};if(socket.readyState!==WebSocket.CLOSED)await new Promise(resolve=>{socket.once('close',resolve);socket.terminate();});}dom?.window.close();await f.close();}
