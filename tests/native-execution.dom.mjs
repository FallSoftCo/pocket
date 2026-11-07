// Explicit DOM tier; requires the existing external JSDOM installation.
import test from 'node:test';
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import {openTerminals,commandOutputButton} from '../web/native-execution.mjs';
if(!process.env.NEXTCOMP_JSDOM_MODULE)throw Error('NEXTCOMP_JSDOM_MODULE is required (no skip fallback).');
const {JSDOM}=await import(pathToFileURL(process.env.NEXTCOMP_JSDOM_MODULE));
const settle=()=>new Promise(resolve=>setImmediate(resolve));
function fixture(t){
 const dom=new JSDOM('<!doctype html><body></body>');const previous=globalThis.document;globalThis.document=dom.window.document;
 dom.window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};dom.window.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new dom.window.Event('close'));};
 t.after(()=>{globalThis.document=previous;dom.window.close();});
 const create=(tag,cls='',text='')=>{const node=document.createElement(tag);node.className=cls;node.textContent=text;return node;};
 const button=(label,action)=>{const node=create('button','',label);node.onclick=action;return node;};
 const find=label=>[...document.querySelectorAll('button')].find(node=>node.textContent===label);
 const click=label=>{const node=find(label);assert.ok(node,label);assert.equal(node.disabled,false,label);node.click();return node;};
 return {create,button,find,click};
}
const page=(canTerminate,process='process-a',nextCursor=null)=>({threadId:'thread-a',data:[{processId:process,command:'Synthetic watcher '+process,cwd:'/work',cpuPercent:0}],nextCursor,features:{backgroundTerminals:{state:'available',readOnly:!canTerminate,mutation:{state:canTerminate?'available':'unavailable',reason:'Read-only inspection. Termination is unqualified.'}},executionOutput:{state:'unavailable'}}});
test('Mac terminal inspection keeps paging and refresh usable without any termination controls',async t=>{
 const ui=fixture(t),calls=[];openTerminals({...ui,threadId:'mac-thread',api:async(path,body)=>{calls.push({path,body});return page(false,calls.length===1?'first':'second',calls.length===1?'page-2':null);}});await settle();
 assert.match(document.body.textContent,/Synthetic watcher first/);assert.match(document.body.textContent,/Read-only inspection/);assert.equal(ui.find('Terminate'),undefined);
 ui.click('Next terminals');await settle();assert.match(calls[1].path,/mac-thread\/terminals\?cursor=page-2$/);assert.match(document.body.textContent,/Synthetic watcher second/);assert.equal(ui.find('Terminate'),undefined);
 ui.click('Refresh terminals');await settle();assert.equal(calls.length,3);assert.ok(calls.every(call=>call.body===undefined));
});
test('qualified termination preserves false receipt and a source switch blocks the original confirmation',async t=>{
 const ui=fixture(t),calls=[];let selected='thread-a';const api=async(path,body)=>{calls.push({path,body});return body?{ok:true,terminated:false}:page(true);};
 openTerminals({...ui,threadId:'thread-a',isCurrent:()=>selected==='thread-a',api});await settle();ui.click('Terminate');const confirm=ui.click('Confirm terminate');await settle();assert.match(document.body.textContent,/No matching background process was terminated/);assert.doesNotMatch(document.body.textContent,/Termination accepted/);assert.deepEqual(calls[1],{path:'/api/threads/thread-a/terminals/terminate',body:{processId:'process-a'}});
 ui.click('Refresh terminals');await settle();ui.click('Terminate');selected='thread-b';ui.click('Confirm terminate');await settle();assert.equal(calls.filter(call=>call.body).length,1);assert.match(document.body.textContent,/selected session changed/);
 // Detached controls from an earlier page also cannot submit again.
 selected='thread-a';confirm.disabled=false;confirm.click();await settle();assert.equal(calls.filter(call=>call.body).length,1);
});
test('late terminal lists cannot populate a different source or a newly opened dialog',async t=>{
 const ui=fixture(t);let selected='thread-a',release;const calls=[];
 const old=openTerminals({...ui,threadId:'thread-a',isCurrent:()=>selected==='thread-a',api:path=>{calls.push(path);return new Promise(resolve=>{release=resolve;});}});
 selected='thread-b';release(page(true));await settle();assert.equal(ui.find('Terminate'),undefined);assert.match(old.textContent,/selected session changed/);old.close();
 selected='thread-a';let stale;const first=openTerminals({...ui,threadId:'thread-a',api:()=>new Promise(resolve=>{stale=resolve;})});first.close();openTerminals({...ui,threadId:'thread-a',api:async()=>page(false,'new')});await settle();stale(page(true,'stale'));await settle();assert.match(document.body.textContent,/watcher new/);assert.doesNotMatch(document.body.textContent,/watcher stale/);assert.equal(ui.find('Terminate'),undefined);
});
test('output checks the originating runtime before reading, rejects stale qualification and preserves qualified paging',async t=>{
 const ui=fixture(t),calls=[];let available=false,selected='thread-a',release;
 const api=async(path)=>{calls.push(path);if(path.startsWith('/api/native/'))return release?new Promise(resolve=>{release=resolve;}):{features:{executionOutput:{state:available?'available':'unavailable',reason:'Output is unqualified on this runtime.'}}};return {revision:'revision-a',text:'page',source:'Native output',totalCharacters:8,nextOffset:path.includes('offset=0')?4:null};};
 const add=()=>document.body.append(commandOutputButton({...ui,row:{turnId:'turn-original',itemId:'item-original'},threadId:'thread-a',isCurrent:()=>selected==='thread-a',api}));
 add();ui.click('Read command output');await settle();assert.match(document.body.textContent,/Output is unqualified/);assert.equal(calls.length,1);ui.click('Close');
 available=true;release=true;ui.click('Read command output');await settle();selected='thread-b';release({features:{executionOutput:{state:'available'}}});await settle();assert.equal(calls.length,2);assert.match(document.body.textContent,/selected session changed/);ui.click('Close');
 selected='thread-a';release=null;ui.click('Read command output');await settle();ui.click('Next page');await settle();assert.equal(calls.length,5);assert.match(calls[3],/thread-a\/command-output\?turnId=turn-original&itemId=item-original&offset=0/);assert.match(calls[4],/offset=4&revision=revision-a/);
});

test('missing or malformed terminal and output capability never enables an action or a native output read',async t=>{
 const ui=fixture(t);
 for(const features of [undefined,{backgroundTerminals:{mutation:{state:true}},executionOutput:{state:true}},{backgroundTerminals:{mutation:{state:'unsupported'}},executionOutput:{state:'unsupported'}}]){
  const dialog=openTerminals({...ui,threadId:'thread-a',api:async()=>({...page(true),features})});await settle();assert.equal(ui.find('Terminate'),undefined);assert.match(dialog.textContent,/Read-only inspection/);dialog.close();
  let calls=0;const control=commandOutputButton({...ui,row:{turnId:'turn-a',itemId:'item-a'},threadId:'thread-a',api:async()=>{calls++;return {features};}});document.body.append(control);control.click();await settle();assert.equal(calls,1);assert.match(document.body.textContent,/Retained command output is unavailable/);ui.click('Close');control.remove();
 }
});
