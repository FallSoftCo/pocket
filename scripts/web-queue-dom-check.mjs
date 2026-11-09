// Actual browser application in a virtual DOM; synthetic transport only.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import {randomUUID,createHash} from 'node:crypto';
import WebSocket from 'ws';
import {fixture} from '../tests/web-browser-fixture.mjs';
const require=createRequire(import.meta.url),modules=process.env.WEB_QA_MODULES;
if(!modules)throw Error('Provide disk-backed WEB_QA_MODULES.');
const {JSDOM}=require(modules+'/jsdom'),{build}=require(modules+'/esbuild'),{indexedDB}=require(modules+'/fake-indexeddb');
const bundle=await build({entryPoints:['web/app.mjs'],bundle:true,write:false,format:'iife',define:{'import.meta.url':JSON.stringify('http://127.0.0.1:18982/app.mjs')}});
const f=await fixture(),errors=[],requests=[];let cookie='',dom,socket,release,headReads=0,headReturned=false,removals=0,promotions=0,promotionRelease;
const promotionHeld=new Promise(resolve=>promotionRelease=resolve);let promotionItems=[];
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
  requests.push({path:new URL(path,f.url).pathname,method:options.method||'GET'});
  if(path.includes('/api/threads/parent-123/replies/promote-dom')){
   assert.deepEqual(JSON.parse(options.body),{action:'send'});promotions++;
   await promotionHeld;
   return new Response(JSON.stringify({id:'promote-dom',state:'accepted',mode:'steer',outbox:{version:1,revision:4,items:promotionItems.filter(r=>r.id!=='promote-dom')}}),{headers:{'Content-Type':'application/json'}});
  }
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
 // A verified active head is required before offering promotion. Only the
 // original row is promoted; synthetic transport observes the app's exact POST.
 promotionItems=[{id:'held-other',threadId:'parent-123',text:'Keep this queue paused',state:'held',mode:'queue'},{id:'unknown-other',threadId:'parent-123',text:'Uncertain delivery',state:'unknown',mode:'queue'},{id:'sending-other',threadId:'parent-123',text:'Already dispatching',state:'sending',mode:'steer'},{id:'promote-dom',threadId:'parent-123',text:'Original queued steering text',state:'held',mode:'queue'}];
 socket.emit('message',JSON.stringify({type:'reply',threadId:'parent-123',id:'promote-dom',state:'held',outbox:{version:1,revision:3,items:promotionItems}}));
 const queued=()=>d.querySelector('[data-outgoing-id="promote-dom"]');
 await wait(()=>queued()?.textContent.includes('Steer now'),'verified active queued promotion');
 for(const id of ['unknown-other','sending-other'])assert.equal([...d.querySelector(`[data-outgoing-id="${id}"]`).querySelectorAll('button')].some(b=>/^(Steer now|Send now)$/.test(b.textContent)),false);
 $('draft').focus();fill($('draft'),'Keep the next draft');
 const steer=[...queued().querySelectorAll('button')].find(b=>b.textContent==='Steer now');click(steer);click(steer);
 await wait(()=>promotions===1,'one original promotion dispatch');
 assert.equal([...queued().querySelectorAll('button')].every(b=>b.disabled),true);assert.equal($('draft').value,'Keep the next draft');
 // Switch to another conversation while the original response is pending.
 click(d.querySelector('[data-id=other-123]'));await wait(()=>$('conversation-title')?.textContent?.includes('Compare lighting')||!$('outbox').textContent.includes('Original queued steering text'),'other source');
 fill($('draft'),'Other conversation draft');promotionRelease();await new Promise(r=>setTimeout(r,150));
 assert.equal($('draft').value,'Other conversation draft');assert.equal($('outbox').textContent.includes('Original queued steering text'),false);
 click(d.querySelector('[data-id=parent-123]'));await wait(()=>$('draft').value==='Keep the next draft','original draft restored');
 await new Promise(r=>setTimeout(r,150));assert.equal(promotions,1);assert.equal($('outbox').textContent.includes('Original queued steering text'),false);assert.ok($('outbox').textContent.includes('Keep this queue paused'));
 assert.deepEqual(errors,[]);
 // Needs you uses typed source-qualified catalog summaries, never reports.
 const alpha='env_alpha__c2FtZS10aHJlYWQ',beta='env_beta__c2FtZS10aHJlYWQ',questionId='env_alpha__bmVlZHMtcXVlc3Rpb24';
 let questionPending=true,unknownPending=true;const attentionRequests=[];
 const summary=(id)=>({id,nativeThreadId:'same-thread',environmentId:id===alpha?'alpha':'beta',environmentName:id===alpha?'Alpha source':'Beta source',name:id===alpha?'Alpha decision':'Beta uncertain delivery',status:{type:'idle'},canAcceptDirectInput:true,createdAt:1700000000,...(id===beta?{isChild:true,parentThreadId:alpha}:{}),attention:{version:1,verified:true,items:id===alpha?(questionPending?[{kind:'question',id:questionId,label:'Answer question'},{kind:'needsInput',id:'input-extra',label:'Input needed'}]:[]):(unknownPending?[{kind:'deliveryUnknown',id:'unknown-beta',label:'Review uncertain delivery'}]:[])}});
 const originalFetch=w.fetch;w.fetch=async(path,options={})=>{
  const url=new URL(path,f.url),route=url.pathname;attentionRequests.push({path:route,method:options.method||'GET',body:options.body?JSON.parse(options.body):null});
  const json=(data,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}});
  if(route==='/api/threads'&&!options.body){const response=await originalFetch(path,options),data=await response.json();return json({...data,threads:[...data.threads.map(t=>({...t,attention:{version:1,verified:true,items:[]}})),summary(alpha),summary(beta)]});}
  if(route.includes('/api/requests/')&&route.includes(questionId)||route.includes('/requests/'+questionId)){
   assert.equal(options.method,'POST');assert.ok(route.endsWith('/answer'));assert.equal(JSON.parse(options.body).answers.choice,'Proceed');questionPending=false;return json({ok:true});
  }
  const id=[alpha,beta].find(id=>route.includes('/threads/'+id));
  if(id){
   assert.equal(options.method||'GET','GET','Review must not submit or retry work');
   if(route.endsWith('/native-timeline'))return json({code:'NATIVE_TIMELINE_UNSUPPORTED',error:'Synthetic legacy history'},409);
   return json({thread:summary(id),timeline:{rows:[{id:id+'/message',kind:'message',text:id===alpha?'Alpha original question context':'Beta original unknown-delivery context'}],hasEarlier:false},revision:1,pending:id===alpha&&questionPending?[{id:questionId,method:'item/tool/requestUserInput',params:{threadId:alpha,questions:[{id:'choice',header:'Decision',question:'Alpha source decision?',options:[{label:'Proceed',description:'Synthetic answer'}]}]}}]:[],notes:[],outbox:{version:1,revision:1,items:id===beta&&unknownPending?[{id:'unknown-beta',threadId:beta,text:'Uncertain original Beta message',state:'unknown',mode:'queue'}]:[]},outgoing:id===beta&&unknownPending?[{id:'unknown-beta',threadId:beta,text:'Uncertain original Beta message',state:'unknown',mode:'queue'}]:[]});
  }
  return originalFetch(path,options);
 };
 click($('back'));$('filter').value='all';$('filter').dispatchEvent(new w.Event('change',{bubbles:true}));
 await wait(()=>$('needs-you-toggle')?.dataset.count==='2','two affected sessions, not three reasons');
 fill($('search'),'field guide');await new Promise(r=>setTimeout(r,300));$('work-list').scrollTop=37;
 if(!d.body.classList.contains('review-open'))click($('review-toggle'));assert.equal(d.body.classList.contains('review-open'),true);
 click($('needs-you-toggle'));await wait(()=>$('filter').value==='needs-you'&&d.querySelectorAll('#work-list .work-card').length===2,'Needs you filtered original list');
 assert.equal(d.body.classList.contains('review-open'),false);assert.equal(d.body.classList.contains('chat-open'),false);
 assert.ok([...d.querySelectorAll('.attention-reason')].every(n=>n.classList.contains('preview')&&!n.closest('.card-heading')),'Attention must use reserved preview row');
 assert.equal($('search').value,'');assert.ok(d.querySelector(`[data-id="${beta}"]`),'Affected child session must be directly visible');
 click(d.querySelector(`[data-id="${alpha}"] .attention-reason`));await wait(()=>$('title').textContent==='Alpha decision'&&$('requests').textContent.includes('Alpha source decision?'),'question original source');
 await wait(()=>$('review-pane').hidden===false,'question review opens');
 const input=$('requests').querySelector('input');fill(input,'Proceed');click([...$('requests').querySelectorAll('button')].find(b=>b.textContent==='Submit answers'));
 await wait(()=>!questionPending,'original question answer accepted');
 f.broadcast({type:'attentionResolved',threadId:alpha});await wait(()=>$('needs-you-toggle').dataset.count==='1','resolved question leaves filtered list');
 click($('back'));click(d.querySelector(`[data-id="${beta}"] .attention-reason`));await wait(()=>$('title').textContent==='Beta uncertain delivery'&&$('outbox').textContent.includes('Uncertain original Beta message'),'unknown original source review');
 assert.equal([...$('outbox').querySelectorAll('button')].some(b=>/^(Steer now|Send now)$/.test(b.textContent)),false);
 assert.equal(attentionRequests.filter(r=>r.method==='POST'&&/reply|replies|queue/.test(r.path)).length,0);
 assert.ok(attentionRequests.some(r=>r.path.includes('/threads/'+alpha)));assert.ok(attentionRequests.some(r=>r.path.includes('/threads/'+beta)));
 unknownPending=false;f.broadcast({type:'attentionResolved',threadId:beta});await wait(()=>$('needs-you-toggle').dataset.count==='','last attention resolved');
 click($('back'));assert.equal($('filter').value,'needs-you');assert.equal($('needs-you-toggle').hidden,false);assert.equal($('needs-you-toggle').getAttribute('aria-pressed'),'true');
 assert.equal(d.querySelectorAll('#work-list .work-card').length,0);click($('needs-you-toggle'));await wait(()=>$('filter').value==='all'&&$('search').value==='field guide'&&d.querySelector('[data-id=parent-123]')&&$('work-list').scrollTop===37,'escape restores normal work');
 assert.equal($('work-list').scrollTop,37);assert.equal(d.body.classList.contains('review-open'),false);assert.equal(d.body.classList.contains('chat-open'),false);
 assert.equal([...requests,...attentionRequests].some(r=>/coordinator\/reports|coordinator\/reporting/.test(r.path)),false,'Needs you must not fetch report feeds');
 assert.equal(attentionRequests.filter(r=>r.method==='POST'&&r.path.includes(questionId)).length,1);assert.deepEqual(errors,[]);
 const needsYou={passed:true,sourceQualifiedSessions:[alpha,beta],sameNativeThreadId:'same-thread',sessionCountNotReasonCount:true,affectedChildVisible:true,questionAnswerOnce:true,unknownReviewedWithoutSend:true,noReportFetch:true,emptyFilterEscapeRestoresView:true,requests:attentionRequests};
 const result={passed:true,bundledAppSha256:createHash('sha256').update(bundle.outputFiles[0].text).digest('hex'),needsYou,promotionOriginalId:'promote-dom',promotionOriginalText:'Original queued steering text',promotionDispatches:promotions,doubleTapOneDispatch:true,unknownSendingNonpromotable:true,heldOthersRetained:true,sourceSwitchPreservedDraft:true,latePromotionAckCannotPaintForeignSource:true,evidence:'actual app in virtual DOM; synthetic HTTP and WebSocket observations',queueVisibleBeforeHistory:true,removalFromOriginalAckWithoutSocket:true,lateHistoryCannotResurrect:true,draftPreserved:true,removals,errors};
 if(process.env.WEB_QA_OUTPUT)await writeFile(process.env.WEB_QA_OUTPUT,JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}catch(error){if(process.env.WEB_QA_OUTPUT)await writeFile(process.env.WEB_QA_OUTPUT,JSON.stringify({passed:false,error:error.stack,errors},null,2));const d=dom?.window.document;console.error(JSON.stringify({stage:'failure',errors,appHidden:d?.getElementById('app')?.hidden,pairError:d?.getElementById('pair-error')?.textContent,appError:d?.getElementById('error')?.textContent,connection:d?.getElementById('connection')?.textContent,socketState:socket?.readyState,workList:d?.getElementById('work-list')?.innerHTML.slice(0,500)}));throw error;}finally{release();promotionRelease();if(socket){socket.onclose=null;socket.onmessage=null;socket.onopen=null;socket.onerror=()=>{};if(socket.readyState!==WebSocket.CLOSED)await new Promise(resolve=>{socket.once('close',resolve);socket.terminate();});}dom?.window.close();await f.close();}
