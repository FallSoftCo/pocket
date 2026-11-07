import test from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import {mountNativeExecution} from '../server/native-execution.mjs';
import {QuestionActions} from '../server/question-actions.mjs';
import {requestPresentation} from '../server/native-request-contract.mjs';
import {LiveTimeline,itemRow} from '../server/timeline.mjs';

test('paired execution journey pages exact native output, detects revision change and controls only selected terminal',async t=>{
 const calls=[];let output='A'.repeat(23999)+'😀'+'B'.repeat(51000),wrongIdentity=false,unsupported=false;
 const codex={connect:async()=>{},call:async(method,p)=>{calls.push([method,p]);if(unsupported)throw {rpc:{code:-32601}};
  if(method==='thread/items/read')return {data:[{turnId:wrongIdentity?'other-turn':p.turnId,item:{id:p.itemIds[0],type:'commandExecution',aggregatedOutput:output,status:'completed',exitCode:0}}]};
  if(method.endsWith('/list'))return {data:[{itemId:'cmd',processId:'process-23',command:'sleep 60',cwd:'/work',cpuPercent:0}],nextCursor:null};
  if(method.endsWith('/terminate'))return {};throw Error('Unexpected method');}};
 const app=express();app.use(express.json());app.use((req,res,next)=>req.get('Authorization')==='Bearer fixture'?next():res.sendStatus(401));
 mountNativeExecution(app,{codex,route:fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next),requireId:id=>id});app.use((error,req,res,next)=>res.status(error.status||500).json({error:error.message}));
 const server=app.listen(0,'127.0.0.1');await new Promise(r=>server.once('listening',r));t.after(()=>new Promise(r=>server.close(r)));
 const base=`http://127.0.0.1:${server.address().port}`,get=async(path)=>{const r=await fetch(base+path,{headers:{Authorization:'Bearer fixture'}});return [r.status,await r.json()];};
 const path='/api/threads/thread-a/command-output?turnId=turn-7&itemId=cmd';
 assert.equal((await fetch(base+path)).status,401);
 let offset=0,revision=null,collected='';do{const [status,page]=await get(path+`&offset=${offset}`+(revision?'&revision='+revision:''));assert.equal(status,200);assert.ok(page.text.length<=24000);assert.equal(page.threadId,'thread-a');assert.equal(page.turnId,'turn-7');assert.equal(page.itemId,'cmd');collected+=page.text;revision=page.revision;offset=page.nextOffset;}while(offset!==null);assert.equal(collected,output);
 output+='changed';assert.equal((await get(path+'&revision='+revision))[0],409);
 wrongIdentity=true;assert.equal((await get(path))[0],404);wrongIdentity=false;
 assert.equal((await get('/api/threads/thread-a/terminals'))[1].data[0].processId,'process-23');
 const stopped=await fetch(base+'/api/threads/thread-a/terminals/terminate',{method:'POST',headers:{Authorization:'Bearer fixture','Content-Type':'application/json'},body:JSON.stringify({processId:'process-23'})});assert.equal(stopped.status,200);
 assert.deepEqual(calls.at(-1),['thread/backgroundTerminals/terminate',{threadId:'thread-a',processId:'process-23'}]);
 unsupported=true;assert.equal((await get('/api/threads/thread-a/terminals'))[0],409);
 assert.ok(calls.every(([method])=>!['turn/start','thread/resume','command/exec','process/kill'].includes(method)));
});

test('native requests preserve offered structured decisions, scope, elicitation schema and authoritative resolution',()=>{
 const pending=new Map(),writes=[],actions=new QuestionActions({pending,codex:{answer:(...args)=>writes.push(args)}});
 const enqueue=(method,params)=>{const request={id:17,method,params,_pocketUI:{token:'connection-request-1'}};pending.set('17',request);return request;};
 const amend={acceptWithExecpolicyAmendment:{execpolicy_amendment:['git','status']}};
 let request=enqueue('item/commandExecution/requestApproval',{threadId:'a',turnId:'t',itemId:'i',availableDecisions:['decline',amend]});
 assert.equal(requestPresentation(request).actions[1].label,'Allow with command rule');
 assert.throws(()=>actions.answer(17,{decision:'accept'}),{status:400});assert.throws(()=>actions.answer(17,{actionId:'1',requestToken:'stale'}),{status:409});
 actions.answer(17,{actionId:'1',requestToken:'connection-request-1'});actions.answer(17,{actionId:'1'});assert.deepEqual(writes,[[17,{decision:amend}]]);assert.equal(pending.size,1);
 enqueue('item/permissions/requestApproval',{permissions:{network:{enabled:true},fileSystem:null}});actions.answer(17,{actionId:'turn'});assert.deepEqual(writes.at(-1)[1],{permissions:{network:{enabled:true}},scope:'turn'});
 enqueue('mcpServer/elicitation/request',{mode:'form',requestedSchema:{type:'object',required:['count','allow'],properties:{count:{type:'integer',minimum:1},allow:{type:'boolean'}},additionalProperties:false}});
 assert.throws(()=>actions.answer(17,{actionId:'accept',content:{count:0,allow:true}}),{status:400});
 actions.answer(17,{actionId:'accept',content:{count:2,allow:false}});assert.deepEqual(writes.at(-1)[1],{action:'accept',content:{count:2,allow:false},_meta:null});
 enqueue('mcpServer/elicitation/request',{mode:'openai/userVerification'});assert.throws(()=>actions.answer(17,{actionId:'accept'}),{status:400});actions.answer(17,{actionId:'cancel'});assert.equal(writes.at(-1)[1].action,'cancel');
 enqueue('execCommandApproval',{});actions.answer(17,{actionId:'2'});assert.equal(writes.at(-1)[1].decision.denied.rejection,'Declined by the user.');
 enqueue('account/chatgptAuthTokens/refresh',{});assert.throws(()=>actions.answer(17,{decision:'accept'}),{status:400});
 pending.clear();assert.throws(()=>actions.answer(17,{actionId:'0'}),{status:409});
});

test('new native item kinds remain visible without leaking opaque payloads; progress retains exact item identity',()=>{
 const fallback=itemRow({id:'future',type:'newNativeOperation',secret:'DO NOT COPY'},'t');assert.equal(fallback.itemId,'future');assert.equal(fallback.unsupported,true);assert.ok(!JSON.stringify(fallback).includes('DO NOT COPY'));
 assert.equal(itemRow({id:'r',type:'reasoning',content:['PRIVATE'],summary:[]},'t'),null);
 const timeline=new LiveTimeline();timeline.ingest({method:'item/started',params:{threadId:'a',turnId:'t',item:{id:'tool',type:'mcpToolCall',server:'library',tool:'search'}}});
 const progress=timeline.ingest({method:'item/mcpToolCall/progress',params:{threadId:'a',turnId:'t',itemId:'tool',message:'Checking sources'}});assert.equal(progress.row.itemId,'tool');assert.equal(progress.row.text,'Checking sources');assert.ok(timeline.bytes<64000);
});
