// Run with JSDOM_MODULE=/absolute/path/to/jsdom/lib/api.js. No browser account or model is used.
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import {RequestList} from '../web/request-list.mjs';
import {requestPresentation} from '../server/native-request-contract.mjs';
import {QuestionActions} from '../server/question-actions.mjs';
import {commandOutputButton,openTerminals} from '../web/native-execution.mjs';
if(!process.env.JSDOM_MODULE)throw Error('Set JSDOM_MODULE to the installed test dependency.');
const {JSDOM}=await import(pathToFileURL(process.env.JSDOM_MODULE));
const dom=new JSDOM('<body><div id="requests"></div></body>',{url:'https://fixture.invalid'});globalThis.document=dom.window.document;globalThis.Event=dom.window.Event;
dom.window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};dom.window.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new dom.window.Event('close'));};
const create=(tag,cls='',text='')=>{const n=document.createElement(tag);n.className=cls;n.textContent=text;return n;},button=(label,click)=>{const n=create('button','',label);n.onclick=click;return n;};
const tick=()=>new Promise(r=>setTimeout(r,0)),click=async(label,root=document)=>{const n=[...root.querySelectorAll('button')].find(n=>n.textContent===label);assert.ok(n,`Missing button: ${label}`);n.click();await tick();};
const pending=new Map(),writes=[],actions=new QuestionActions({pending,codex:{answer:(...args)=>writes.push(args)}});
const request=(id,method,params)=>{const value={id,method,params};value._pocketUI={...requestPresentation(value),token:'token-'+id};pending.set(String(id),value);return value;};
const view=new RequestList({holder:document.querySelector('#requests'),create,button,api:async(path,body)=>actions.answer(decodeURIComponent(path.split('/')[3]),body),refresh:async()=>{}});
const approval=request(1,'item/commandExecution/requestApproval',{command:'git status',availableDecisions:[{acceptWithExecpolicyAmendment:{execpolicy_amendment:['git','status']}},'decline']});
view.update('host-a/thread-a',[approval]);assert.ok(document.body.textContent.includes('git status'));assert.ok(!document.body.textContent.includes('Allow once'));await click('Allow with command rule');assert.deepEqual(writes.at(-1),[1,{decision:approval.params.availableDecisions[0]}]);await click('Allow with command rule');assert.equal(writes.length,1);
const form=request(2,'mcpServer/elicitation/request',{mode:'form',message:'Choose limits',requestedSchema:{type:'object',required:['count'],properties:{count:{type:'integer',minimum:1},allow:{type:'boolean'}}}});
view.update('host-a/thread-a',[form]);const input=document.querySelector('[data-question="count"]');input.value='3';input.focus();view.update('host-a/thread-a',[form,request(3,'item/tool/requestUserInput',{questions:[{id:'secret',question:'Secret',isSecret:true}]})]);assert.equal(document.activeElement,input);assert.equal(input.value,'3');assert.equal(document.querySelector('[data-question="secret"]').type,'password');await click('Submit form');assert.equal(writes.at(-1)[1].content.count,3);
const multi=request(4,'mcpServer/elicitation/request',{mode:'form',requestedSchema:{type:'object',required:['colors'],properties:{colors:{type:'array',minItems:1,items:{anyOf:[{const:'red',title:'Red color'},{const:'blue',title:'Blue color'}]}}}}});
view.update('host-a/thread-a',[multi]);const select=document.querySelector('[data-question="colors"]');assert.equal(select.multiple,true);select.options[1].selected=true;await click('Submit form');assert.deepEqual(writes.at(-1)[1].content.colors,['blue']);
const legacy={id:5,method:'item/fileChange/requestApproval',params:{reason:'Legacy gateway'}};pending.set('5',legacy);view.update('host-b/thread-b',[legacy]);await click('Allow once');assert.deepEqual(writes.at(-1),[5,{decision:'accept'}]);
const secret=request(6,'item/tool/requestUserInput',{questions:[{id:'reused',question:'Reused request',isSecret:true}]});
view.update('host-a/thread-a',[secret]);document.querySelector('[data-question="reused"]').value='old secret';
const replacement={...secret,_pocketUI:{...secret._pocketUI,token:'new-connection-token'}};pending.set('6',replacement);view.update('host-a/thread-a',[replacement]);assert.equal(document.querySelector('[data-question="reused"]').value,'');
document.querySelector('[data-question="reused"]').value='new secret';await click('Submit answers');assert.equal(writes.at(-1)[1].answers.reused.answers[0],'new secret');
const nextRequest={...replacement,_pocketUI:{...replacement._pocketUI,token:'third-connection-token'}};pending.set('6',nextRequest);view.update('host-a/thread-a',[nextRequest]);assert.equal(document.querySelector('[data-question="reused"]').value,'');assert.equal(document.querySelector('[data-question="reused"]').disabled,false);
let reads=0;const output=commandOutputButton({row:{turnId:'turn-a',itemId:'command-a'},threadId:'thread-a',create,button,api:async(path)=>{if(path==='/api/native/capabilities?threadId=thread-a')return {runtime:{version:'0.167.0'},features:{executionOutput:{state:'available'}}};reads++;assert.ok(path.includes('thread-a/command-output'));return {source:'Codex retained command output',revision:'one',text:reads===1?'FIRST':'SECOND',nextOffset:reads===1?5:null,totalCharacters:11};}});document.body.append(output);output.click();await tick();await click('Next page');assert.equal(document.querySelector('dialog pre').textContent,'SECOND');assert.ok(!document.querySelector('dialog').textContent.includes('FIRST'));await click('Close');
let mutations=0;openTerminals({threadId:'original-thread',create,button,api:async(path,body)=>{assert.ok(path.includes('/original-thread/'));if(body){mutations++;assert.equal(body.processId,'exact-process');throw Error('Lost response');}return {data:[{processId:'exact-process',command:'sleep 60',cwd:'/work',cpuPercent:0}],nextCursor:null,features:{backgroundTerminals:{state:'available',mutation:{state:'available'}}}};}});await tick();await click('Terminate');assert.equal(mutations,0);await click('Confirm terminate');assert.equal(mutations,1);assert.ok(document.querySelector('dialog').textContent.includes('Termination unconfirmed'));await click('Refresh terminals');assert.equal(mutations,1);
await click('Close');
for(const terminated of [false,true,null]){
 let writes=0;openTerminals({threadId:'original-thread',create,button,api:async(path,body)=>{if(body){writes++;return terminated===null?{ok:true}:{ok:true,terminated};}return {data:[{processId:'exact-process',command:'sleep 60',cwd:'/work',cpuPercent:0}],nextCursor:null,features:{backgroundTerminals:{state:'available',mutation:{state:'available'}}}};}});
 await tick();await click('Terminate');await click('Confirm terminate');assert.equal(writes,1);
 const text=document.querySelector('dialog').textContent;
 if(terminated===false){assert.ok(text.includes('No matching background process was terminated.'));assert.ok(!text.includes('Termination accepted.'));}
 else if(terminated===true)assert.ok(text.includes('Termination accepted.'));
 else assert.ok(text.includes('Termination unconfirmed.'));
 await click('Close');
}
console.log(JSON.stringify({ok:true,journeys:['offered structured approvals and duplicate send','MCP fields and focus during competing request','masked secret question and reconnected request identity','MCP titled multi-select and older gateway approvals','output replacement paging','confirmed exact terminal, explicit false and missing outcome, unknown mutation without replay']}));dom.window.close();
