import test from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import {mountNativeExecution} from '../server/native-execution.mjs';
import {QuestionActions} from '../server/question-actions.mjs';
import {requestPresentation} from '../server/native-request-contract.mjs';
import {VoiceController,VOICE_TOOL,VOICE_TOOL_REVISION} from '../server/voice-controller.mjs';
import {LiveTimeline,itemRow} from '../server/timeline.mjs';

test('paired execution journey pages exact native output, detects revision change and controls only selected terminal',async t=>{
 const calls=[];let output='A'.repeat(23999)+'😀'+'B'.repeat(51000),wrongIdentity=false,unsupported=false,terminated=true;
 const codex={initializeResult:{userAgent:'codex/0.167.0'},connect:async()=>{},call:async(method,p)=>{calls.push([method,p]);if(unsupported)throw {rpc:{code:-32601}};
  if(method==='thread/items/read')return {data:[{turnId:wrongIdentity?'other-turn':p.turnId,item:{id:p.itemIds[0],type:'commandExecution',aggregatedOutput:output,status:'completed',exitCode:0}}]};
  if(method.endsWith('/list'))return {data:[{itemId:'cmd',processId:'process-23',command:'sleep 60',cwd:'/work',cpuPercent:0}],nextCursor:null};
  if(method.endsWith('/terminate'))return terminated===null?{}:{terminated};throw Error('Unexpected method');}};
 const app=express();app.use(express.json());app.use((req,res,next)=>req.get('Authorization')==='Bearer fixture'?next():res.sendStatus(401));
 mountNativeExecution(app,{codex,route:fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next),requireId:id=>id});app.use((error,req,res,next)=>res.status(error.status||500).json({error:error.message}));
 const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));t.after(()=>new Promise(r=>server.close(r)));
 const base=`http://127.0.0.1:${server.address().port}`,get=async(path)=>{const r=await fetch(base+path,{headers:{Authorization:'Bearer fixture'}});return [r.status,await r.json()];};
 const path='/api/threads/thread-a/command-output?turnId=turn-7&itemId=cmd';
 assert.equal((await fetch(base+path)).status,401);
 let offset=0,revision=null,collected='';do{const [status,page]=await get(path+`&offset=${offset}`+(revision?'&revision='+revision:''));assert.equal(status,200);assert.ok(page.text.length<=24000);assert.equal(page.threadId,'thread-a');assert.equal(page.turnId,'turn-7');assert.equal(page.itemId,'cmd');collected+=page.text;revision=page.revision;offset=page.nextOffset;}while(offset!==null);assert.equal(collected,output);
 output+='changed';assert.equal((await get(path+'&revision='+revision))[0],409);
 wrongIdentity=true;assert.equal((await get(path))[0],404);wrongIdentity=false;
 const terminalPage=(await get('/api/threads/thread-a/terminals'))[1];assert.equal(terminalPage.data[0].processId,'process-23');assert.equal(terminalPage.features.backgroundTerminals.mutation.state,'available');assert.equal(terminalPage.features.executionOutput.state,'available');
 const stopped=await fetch(base+'/api/threads/thread-a/terminals/terminate',{method:'POST',headers:{Authorization:'Bearer fixture','Content-Type':'application/json'},body:JSON.stringify({processId:'process-23'})});assert.equal(stopped.status,200);
 assert.deepEqual(calls.at(-1),['thread/backgroundTerminals/terminate',{threadId:'thread-a',processId:'process-23'}]);
 assert.equal((await stopped.json()).terminated,true);
 const terminate=()=>fetch(base+'/api/threads/thread-a/terminals/terminate',{method:'POST',headers:{Authorization:'Bearer fixture','Content-Type':'application/json'},body:JSON.stringify({processId:'process-23'})});
 terminated=false;const absent=await terminate();assert.equal(absent.status,200);assert.equal((await absent.json()).terminated,false);
 terminated=null;assert.equal((await terminate()).status,502);terminated=true;

 codex.initializeResult.userAgent='codex_pocket/0.163.0 (Mac OS 26.6.0; arm64)';
 const beforeNegotiation=calls.length;
 for(const query of ['', '?terminalPresentation=', '?terminalPresentation=2', '?terminalPresentation=01', '?terminalPresentation=true', '?terminalPresentation=1&terminalPresentation=1', '?terminalPresentation=1&terminalPresentation=2', '?terminalPresentation[version]=1']){
  const [status,response]=await get('/api/threads/thread-a/terminals'+query);assert.equal(status,409,query);assert.match(response.error,/Update this client/);assert.equal(response.data,undefined);assert.equal(calls.length,beforeNegotiation,'unsupported presentation must not dispatch a native listing');
 }
 const [macStatus,macPage]=await get('/api/threads/thread-a/terminals?terminalPresentation=1&cursor=next-page');assert.equal(macStatus,200);assert.equal(macPage.data[0].processId,'process-23');assert.equal(macPage.features.backgroundTerminals.state,'available');assert.equal(macPage.features.backgroundTerminals.readOnly,true);assert.equal(macPage.features.backgroundTerminals.mutation.state,'unavailable');assert.equal(macPage.features.executionOutput.state,'unavailable');
 assert.deepEqual(calls.at(-1),['thread/backgroundTerminals/list',{threadId:'thread-a',limit:30,cursor:'next-page'}]);
 const macCalls=calls.length;
 assert.equal((await terminate()).status,409,'previously available controls cannot carry write capability to a replacement runtime');assert.equal((await get(path))[0],409);assert.equal(calls.length,macCalls,'unqualified termination and output must never reach native transport');
 unsupported=true;assert.equal((await get('/api/threads/thread-a/terminals?terminalPresentation=1'))[0],409);
 unsupported=false;codex.initializeResult.userAgent='codex/0.167.0';
 codex.connect=async()=>{codex.initializeResult.userAgent='codex_pocket/0.163.0 (Mac OS 26.6.0; arm64)';};
 const reconnectCount=calls.length;assert.equal((await get('/api/threads/thread-a/terminals'))[0],409);assert.equal(calls.length,reconnectCount);codex.connect=async()=>{};
 codex.initializeResult.userAgent='codex/0.168.0';const count=calls.length;
 const unqualified=await fetch(base+'/api/threads/thread-a/terminals/terminate',{method:'POST',headers:{Authorization:'Bearer fixture','Content-Type':'application/json'},body:JSON.stringify({processId:'process-23'})});assert.equal(unqualified.status,409);assert.equal(calls.length,count);
 assert.ok(calls.every(([method])=>!['turn/start','thread/resume','command/exec','process/kill'].includes(method)));
});

test('native requests preserve offered structured decisions, scope, elicitation schema and authoritative resolution',()=>{
 const pending=new Map(),writes=[],actions=new QuestionActions({pending,codex:{answer:(...args)=>writes.push(args)}});
 const enqueue=(method,params)=>{const request={id:17,method,params};pending.set('17',request);return request;};
 const amend={acceptWithExecpolicyAmendment:{execpolicy_amendment:['git','status']}};
 let request=enqueue('item/commandExecution/requestApproval',{threadId:'a',turnId:'t',itemId:'i',availableDecisions:['decline',amend]});
 request._pocketUI={token:'connection-request-1'};
 assert.equal(requestPresentation(request).actions[1].label,'Allow with command rule');
 assert.throws(()=>actions.answer(17,{decision:'accept',requestToken:'connection-request-1'}),{status:400});assert.throws(()=>actions.answer(17,{actionId:'1',requestToken:'stale'}),{status:409});assert.throws(()=>actions.answer(17,{actionId:'1'}),{status:409});
 actions.answer(17,{actionId:'1',requestToken:'connection-request-1'});actions.answer(17,{actionId:'1',requestToken:'connection-request-1'});assert.deepEqual(writes,[[17,{decision:amend}]]);assert.equal(pending.size,1);
 enqueue('item/permissions/requestApproval',{permissions:{network:{enabled:true},fileSystem:null}});actions.answer(17,{actionId:'turn'});assert.deepEqual(writes.at(-1)[1],{permissions:{network:{enabled:true}},scope:'turn'});
 enqueue('mcpServer/elicitation/request',{mode:'form',requestedSchema:{type:'object',required:['count','allow'],properties:{count:{type:'integer',minimum:1},allow:{type:'boolean'}},additionalProperties:false}});
 assert.throws(()=>actions.answer(17,{actionId:'accept',content:{count:0,allow:true}}),{status:400});
 actions.answer(17,{actionId:'accept',content:{count:2,allow:false}});assert.deepEqual(writes.at(-1)[1],{action:'accept',content:{count:2,allow:false},_meta:null});
 request=enqueue('mcpServer/elicitation/request',{mode:'openai/userVerification'});
 assert.deepEqual(requestPresentation(request).actions.map(a=>a.id),['decline','cancel']);
 const beforeVerification=writes.length,proof={credentialId:'untrusted-credential',signature:'untrusted-proof'};
 assert.throws(()=>actions.answer(17,{actionId:'accept',content:proof}),error=>error.status===400&&error.message.includes('This remote client cannot complete identity verification.')&&!error.message.includes('terminal'));
 assert.equal(writes.length,beforeVerification,'a client-authored proof must never reach native transport');
 for(const actionId of ['decline','cancel']){
  enqueue('mcpServer/elicitation/request',{mode:'openai/userVerification'});
  actions.answer(17,{actionId,content:proof});assert.deepEqual(writes.at(-1),[17,{action:actionId,content:null,_meta:null}]);
 }
 enqueue('execCommandApproval',{});actions.answer(17,{actionId:'2'});assert.equal(writes.at(-1)[1].decision.denied.rejection,'Declined by the user.');
 enqueue('item/tool/requestUserInput',{questions:[{id:'secret',isSecret:true}]});actions.answer(17,{answers:{secret:' spaced secret '}});assert.equal(writes.at(-1)[1].answers.secret.answers[0],' spaced secret ');
 enqueue('account/chatgptAuthTokens/refresh',{});assert.throws(()=>actions.answer(17,{decision:'accept'}),{status:400});
 pending.clear();assert.throws(()=>actions.answer(17,{actionId:'0'}),{status:409});
});

test('new native item kinds remain visible without leaking opaque payloads; progress retains exact item identity',()=>{
 const fallback=itemRow({id:'future',type:'newNativeOperation',secret:'DO NOT COPY'},'t');assert.equal(fallback.itemId,'future');assert.equal(fallback.unsupported,true);assert.ok(!JSON.stringify(fallback).includes('DO NOT COPY'));
 assert.equal(itemRow({id:'r',type:'reasoning',content:['PRIVATE'],summary:[]},'t'),null);
 const timeline=new LiveTimeline();timeline.ingest({method:'item/started',params:{threadId:'a',turnId:'t',item:{id:'tool',type:'mcpToolCall',server:'library',tool:'search'}}});
 const progress=timeline.ingest({method:'item/mcpToolCall/progress',params:{threadId:'a',turnId:'t',itemId:'tool',message:'Checking sources'}});assert.equal(progress.row.itemId,'tool');assert.equal(progress.row.text,'Checking sources');assert.ok(timeline.bytes<64000);
});


test('voice answer contract forwards only the original observed token through HTTP; missing or replaced tokens never approve',async t=>{
 const writes=[],requests=[],pending=new Map();
 const install=token=>pending.set('73',{id:73,method:'item/commandExecution/requestApproval',params:{threadId:'original-thread',availableDecisions:['accept','cancel']},_pocketUI:{token}});
 install('original-observed-token');
 const actions=new QuestionActions({pending,codex:{answer:(...args)=>writes.push(args)}});
 const app=express();app.use(express.json());app.post('/api/requests/:id/answer',(req,res)=>{try{res.json(actions.answer(req.params.id,req.body));}catch(error){res.status(error.status||500).json({error:error.message});}});
 const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));t.after(()=>new Promise(r=>server.close(r)));
 const base=`http://127.0.0.1:${server.address().port}`;
 const controller=Object.assign(Object.create(VoiceController.prototype),{session:()=>({focus:'coordinator'}),sources:{call:async(device,environment,path,body,actor)=>{
  requests.push({device,environment,path,body,actor});assert.equal(path,'/api/requests/73/answer');
  const response=await fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}),result=await response.json();if(!response.ok)throw Object.assign(Error(result.error),{status:response.status});return result;
 }}});
 const active={device:'paired-device',id:'original-voice-turn',focus:'coordinator'};
 const answer=token=>controller.control(active,{operation:'answer',arguments:{environmentId:'original-environment',requestId:'73',actionId:'0',...(token?{requestToken:token}:{})}},{callId:'original-call'});
 await assert.rejects(answer(),{status:409});assert.equal(writes.length,0);
 await answer('original-observed-token');assert.equal(writes.length,1);
 install('replacement-request-token');await assert.rejects(answer('original-observed-token'),{status:409});assert.equal(writes.length,1);
 await answer('replacement-request-token');assert.equal(writes.length,2);
 assert.deepEqual(requests.map(r=>r.body.requestToken),[undefined,'original-observed-token','original-observed-token','replacement-request-token']);
 assert.ok(requests.every(r=>r.environment==='original-environment'&&r.actor.deviceId==='paired-device'&&r.actor.voiceTurnId==='original-voice-turn'));
 assert.match(VOICE_TOOL.description,/answer\(\{requestId,requestToken/);assert.match(VOICE_TOOL.description,/ORIGINAL pending request/);assert.match(VOICE_TOOL.description,/never fetch or substitute a replacement token/);assert.ok(VOICE_TOOL_REVISION>=7);
});
