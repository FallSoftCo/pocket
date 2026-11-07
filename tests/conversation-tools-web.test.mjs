import test from 'node:test';
import assert from 'node:assert/strict';
import {ConversationToolsPane,contextUsageLabel} from '../web/conversation-tools.mjs';
const modulePath=process.env.NEXTCOMP_JSDOM_MODULE;

test('context usage describes observed last-context and cumulative totals without fabricating unavailable data',()=>{assert.match(contextUsageLabel(null),/not been observed/);assert.match(contextUsageLabel({last:{totalTokens:500},total:{totalTokens:2000},modelContextWindow:1000}),/50%/);});

test('web pane completes fork, revert/edit, file selection, diff and uncertain receipt recovery on original source',{skip:!modulePath},async t=>{
 const {JSDOM}=await import(modulePath);const dom=new JSDOM('<!doctype html><body></body>',{url:'https://fixture.example.test'});const old={document:globalThis.document,localStorage:globalThis.localStorage,confirm:globalThis.confirm};Object.assign(globalThis,{document:dom.window.document,localStorage:dom.window.localStorage,confirm:()=>true});dom.window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};dom.window.HTMLDialogElement.prototype.close=function(){this.open=false;};t.after(()=>{Object.assign(globalThis,old);dom.window.close();});
 let selected='thread-source',draft='Existing draft',unknown=false;const applied=[],calls=[],receipts=[];
 const describe=()=>({threadId:selected,cwd:'/fixture',usage:null,active:false,warning:'Files and external actions are not undone.',features:Object.fromEntries(['fork','revert','compact','review','changeDirectory'].map(k=>[k,{available:true}])),turns:[{id:'turn-last',status:'completed',userText:'Edit me'}],operations:receipts,worktree:{available:false,reason:'Use an existing folder.'}});
 const api=async(path,body)=>{calls.push({path,body});if(path.endsWith('/conversation-tools'))return describe();if(path.includes('/workspace-files'))return {files:[{path:'/fixture/file.txt',relativePath:'file.txt',type:'file'}]};if(path.includes('/workspace-selection'))return {path:'/fixture/file.txt',start:1,end:2,text:'source selection'};if(path.endsWith('/workspace-diff'))return {status:' M file.txt',diff:'-before\n+after'};
 if(path.endsWith('/conversation-operations')){if(unknown)throw Error('Connection lost');const r={...body,state:'accepted',threadId:'thread-source',...(body.kind==='fork'?{thread:{id:'branch-thread'}}:{}),result:body.kind==='revert'?{draft:'Edit me'}:{}};receipts.unshift(r);return r;}
 if(path.includes('/conversation-operations/'))return {id:path.split('/').at(-1),kind:'compact',state:'unknown',error:'Inspect; not replayed'};
 throw Error('Unexpected request '+path);};
 const pane=new ConversationToolsPane({api,source:()=>selected,device:()=> 'phone-fixture',appendDraft:text=>draft+=text,applied:r=>{applied.push(r);if(r.kind==='revert')draft+='\n'+r.result.draft;}});
 const click=async text=>{const b=[...pane.dialog.querySelectorAll('button')].find(b=>b.textContent===text);assert(b,`Missing ${text}`);assert(!b.disabled,`Disabled ${text}`);await b.onclick();};
 await pane.show();await click('Branch after selected turn');assert.equal(applied.at(-1).kind,'fork');assert.equal(calls.filter(c=>c.body?.kind==='fork').length,1);
 await click('Revert before turn & edit');assert.equal(draft,'Existing draft\nEdit me');assert.equal(calls.filter(c=>c.body?.kind==='revert').length,1);
 pane.dialog.querySelector('[aria-label="Host file search"]').value='file';await click('Search files');await click('Select lines');await click('Preview selection');await click('Insert this selection into draft');assert.match(draft,/source selection/);assert(!pane.dialog.open);
 await pane.show();await click('Show current diff');assert.match(pane.dialog.querySelector('.workspace-diff').textContent,/\+after/);
 unknown=true;await click('Compact context');assert.match(pane.error.textContent,/uncertain/);await pane.refresh();await click('Check saved operation');assert.equal(calls.filter(c=>c.body?.kind==='compact').length,1);
 const previous=draft;selected='different-thread';assert.throws(()=>pane.insert('wrong source'),/original conversation/);assert.equal(draft,previous);
 assert(!calls.some(c=>c.path.includes('/reply')||String(c.body?.text||'').startsWith('/')));
});
