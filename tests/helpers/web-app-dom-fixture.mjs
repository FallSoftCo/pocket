// Runs the actual web/index.html and complete web/app.mjs ESM graph, unchanged.
// JSDOM has no layout/popover/IndexedDB implementation: these browser seams are
// explicit adapters, not substitutes for app menu logic or model request state.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {resolve,relative} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash,randomUUID} from 'node:crypto';
import {SourceTextModule} from 'node:vm';
import {JSDOM,VirtualConsole} from 'jsdom';
import WebSocket from 'ws';
import {fixture} from '../web-browser-fixture.mjs';

export const settle=()=>new Promise(resolve=>setTimeout(resolve,0));
export async function waitFor(read,label){const end=Date.now()+5000;while(Date.now()<end){const value=read();if(value)return value;await new Promise(r=>setTimeout(r,10));}throw Error('Timed out: '+label);}
export async function webAppFixture(t){
 const webRoot=resolve(process.env.NEXTCOMP_TEST_WEB_SOURCE_ROOT||fileURLToPath(new URL('../../web/',import.meta.url)));
 const f=await fixture({port:0,origin:'http://browser.test'}),errors=[],models=[],reads=[],modules=new Map(),digests={};let socket;
 const virtualConsole=new VirtualConsole();virtualConsole.on('jsdomError',error=>errors.push(error.message));
 const dom=new JSDOM(await readFile(resolve(webRoot,'index.html'),'utf8'),{url:f.url,runScripts:'outside-only',pretendToBeVisual:true,virtualConsole});
 const w=dom.window,d=w.document,$=id=>d.getElementById(id);
 t.after(async()=>{
  for(const request of models)if(!request.settled)request.reply(503,{error:'Fixture shutting down'});
  await settle();await settle();
  if(socket){socket.onclose=null;socket.onmessage=null;socket.onopen=null;socket.onerror=()=>{};socket.terminate();}
  dom.window.close();await f.close();
 });
 const paired=await fetch(f.gatewayAddress+'/web/pair',{method:'POST',headers:{Origin:f.url,'Content-Type':'application/json'},body:'{"code":"synthetic"}'});
 assert.equal(paired.status,200);const cookie=paired.headers.get('set-cookie').split(';')[0];
 Object.assign(w,{AbortController,AbortSignal,structuredClone,TextEncoder,TextDecoder});w.crypto.randomUUID=randomUUID;
 w.matchMedia=query=>({matches:query.includes('prefers-reduced-motion'),addEventListener(){},removeEventListener(){}});
 w.IntersectionObserver=class{observe(){}unobserve(){}disconnect(){}};w.ResizeObserver=class{observe(){}unobserve(){}disconnect(){}};
 w.HTMLCanvasElement.prototype.getContext=()=>null;
 w.HTMLElement.prototype.scrollIntoView=function(){};w.HTMLElement.prototype.scrollBy=function(){};
 w.requestIdleCallback=fn=>w.setTimeout(()=>fn({didTimeout:false,timeRemaining:()=>50}),0);w.cancelIdleCallback=id=>w.clearTimeout(id);
 w.Notification=class{static permission='denied';};w.confirm=()=>true;w.prompt=()=>null;
 // Empty, read-only voice stores permit ordinary boot. These tests never record,
 // persist media, or claim IndexedDB qualification; all writes fail explicitly.
 w.indexedDB={open(){const request={};queueMicrotask(()=>{
  request.result={close(){},transaction(_name,mode){assert.equal(mode,'readonly','Unexpected fixture IndexedDB write');const tx={};tx.objectStore=()=>({getAll:()=>read([]),get:()=>read(undefined)});function read(value){const r={result:value};queueMicrotask(()=>{r.onsuccess?.();queueMicrotask(()=>tx.oncomplete?.());});return r;}return tx;}};
  request.onsuccess?.();});return request;}};
 for(const proto of [w.Element.prototype,w.Document.prototype])for(const name of ['matches','closest','querySelector','querySelectorAll'])if(proto[name]){const native=proto[name];proto[name]=function(selector){return native.call(this,selector.replaceAll(':popover-open','[data-fixture-popover-open]'));};}
 const toggle=(element,state)=>{const event=new w.Event('beforetoggle');Object.defineProperty(event,'newState',{value:state});element.dispatchEvent(event);};
 w.HTMLElement.prototype.showPopover=function(){for(const node of d.querySelectorAll('[data-fixture-popover-open]'))if(node!==this)node.hidePopover();toggle(this,'open');this.setAttribute('data-fixture-popover-open','');};
 w.HTMLElement.prototype.hidePopover=function(){if(this.hasAttribute('data-fixture-popover-open')){toggle(this,'closed');this.removeAttribute('data-fixture-popover-open');}};
 w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
 const json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
 w.fetch=async(input,options={})=>{
  const url=new URL(input,f.url),path=url.pathname;reads.push({path,method:options.method||'GET',body:options.body});
  assert.equal(url.origin,f.url,'App fetch escaped the disposable authority');
  if(path==='/api/models')return new Promise(resolve=>{const request={settled:false,reply(status,data){assert.equal(request.settled,false,'Model response released twice');request.settled=true;resolve(json(data,status));}};models.push(request);});
  const tools=/^\/api\/threads\/([^/]+)\/conversation-tools$/.exec(path);
  if(tools)return json({threadId:tools[1],cwd:'/synthetic/'+tools[1],active:false,features:{},turns:[],operations:[],worktree:{available:false,reason:'Fixture only'},warning:'Synthetic retained workspace.'});
  if(/^\/api\/threads\/[^/]+\/terminals$/.test(path))return json({data:[],nextCursor:null});
  return fetch(f.gatewayAddress+url.pathname+url.search,{...options,headers:{...options.headers,Origin:f.url,Cookie:cookie}});
 };
 w.WebSocket=class extends WebSocket{constructor(url){assert.equal(new URL(url).host,'browser.test');super(f.gatewayAddress.replace('http','ws')+new URL(url).pathname,{headers:{Origin:f.url,Cookie:cookie}});socket=this;}};
 w.addEventListener('error',event=>errors.push(event.message));
 const context=dom.getInternalVMContext();
 async function load(url){
  if(!modules.has(url))modules.set(url,(async()=>{
   const path=new URL(url).pathname.slice(1),filename=resolve(webRoot,path);
   assert.ok(!relative(webRoot,filename).startsWith('..'),'Module escaped selected web source');
   const source=await readFile(filename,'utf8');digests[path]=createHash('sha256').update(source).digest('hex');
   return new SourceTextModule(source,{context,identifier:url,initializeImportMeta(meta){meta.url=url;}});
  })());
  return modules.get(url);
 }
 const app=await load(f.url+'/app.mjs');await app.link((specifier,ref)=>{assert.ok(specifier.startsWith('./'),'Only actual local web modules allowed');return load(new URL(specifier,ref.identifier).href);});await app.evaluate();
 await waitFor(()=>!$('app').hidden&&d.querySelector('[data-id="parent-123"]'),'authenticated actual app startup');
 await waitFor(()=>reads.some(read=>read.path==='/api/voice/native/capabilities'),'completed empty voice-store restoration');
 const click=element=>{assert.ok(element,'Missing click target');assert.notEqual(element.disabled,true,'Disabled click target');element.click();};
 const select=async id=>{click(d.querySelector(`[data-id="${id}"]`));await waitFor(()=>$('title').textContent===(id==='parent-123'?'Prepare the field guide':'Compare lighting studies')&&!$('draft').disabled,'selected '+id);};
 await select('parent-123');
 return {d,w,$,models,reads,errors,digests,click,select,isOpen:()=>$('controls-dialog').matches(':popover-open'),assertClean(){assert.deepEqual(errors,[]);assert.equal($('error').hidden,true,$('error').textContent);assert.equal(reads.filter(r=>r.method==='POST'&&/\/(?:reply|turn-settings|conversation-operations|terminate)$/.test(r.path)).length,0,'Menu inspection submitted work or changed settings');}};
}
