/** Observe the actual gateway with retained fixture queues and a runtime connection trap. */
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync,existsSync,openSync,closeSync,mkdtempSync} from 'node:fs';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {createServer} from 'node:net';
import {openStore} from '../server/store.mjs';

process.umask(0o077);
const root=resolve(process.argv[2]),repo=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const save=(name,value)=>writeFileSync(resolve(root,name),JSON.stringify(value,null,2),{mode:0o600});
const resume=process.argv.includes('--resume-known-listener-failure');
if(existsSync(resolve(root,'boundary-intent.json'))&&!resume)throw Error('Original boundary intent exists; inspect original evidence without replay.');
if(resume&&existsSync(resolve(root,'boundary-process.json')))throw Error('A gateway may have launched; inspect its original process instead.');
const prior=JSON.parse(readFileSync(resolve(root,'scenario-intent.json')));
const approval=JSON.parse(readFileSync(resolve(root,'native-approval-answer-intent.json')));
if(!resume)save('boundary-intent.json',{fixtureQueue:'retained-native-boundary-fixture',modelsAuthorized:0});
const {db}=openStore(resolve(root,'gateway-data'));
if(!resume)db.prepare("INSERT INTO session_starts(id,cwd,prompt,state,created_at,updated_at) VALUES(?,?,?,'queued',?,?)")
  .run('retained-native-boundary-fixture',root,'Fixture must remain paused from native routing',Date.now(),Date.now());
if(!resume)db.prepare("INSERT INTO outgoing(id,thread_id,text,state,created_at,updated_at,mode) VALUES(?,?,?,'queued',?,?,'queue')")
  .run('retained-native-boundary-reply','fixture-not-real-thread','Do not dispatch',Date.now(),Date.now());
let connections=0;
assert.equal(db.prepare('SELECT state FROM session_starts WHERE id=?').get('retained-native-boundary-fixture').state,'queued');
assert.equal(db.prepare('SELECT state FROM outgoing WHERE id=?').get('retained-native-boundary-reply').state,'queued');
const socket=resolve(mkdtempSync('/tmp/hb-boundary-'),'runtime.sock');
save('boundary-trap-intent.json',{socket,resumedKnownPreLaunchFailure:resume});
const trap=createServer(peer=>{connections++;peer.destroy();});
await new Promise((resolve,reject)=>{trap.once('error',reject);trap.listen(socket,resolve);});
const log=openSync(resolve(root,'boundary-gateway.log'),'a',0o600);
const child=spawn(process.execPath,[resolve(repo,'server/index.mjs')],{cwd:repo,stdio:['ignore',log,log],env:{...process.env,
  PORT:'18886',POCKET_DATA:resolve(root,'gateway-data'),CODEX_SOCKET:socket,POCKET_CODEX_COMMAND:'',POCKET_LOCAL:''}});
closeSync(log);save('boundary-process.json',{pid:child.pid,trap:socket});
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function call(who,method,path,body,binding,expected=200){
 const response=await fetch('http://127.0.0.1:18886/api/'+path,{method,headers:{Authorization:`Bearer ${prior.tokens[who]}`,
  ...(binding?{'X-Hollywood-Binding':binding}:{}),...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})});
 const value=await response.json();assert.equal(response.status,expected,`${method} ${path}: ${response.status}`);return value;
}
try{
 for(let n=0;n<80;n++){if(child.exitCode!==null)throw Error('Original gateway exited; inspect private log.');try{const r=await fetch('http://127.0.0.1:18886/health');if(r.ok)break;}catch{}await sleep(250);}
 const identity=await call('owner','GET','hollywood/v3/identity');
 const viewer=await call('viewer','GET','hollywood/v3/identity');
 assert.deepEqual(identity,approval.binding);
 const original=await call('owner','GET','hollywood/v3/attention/'+approval.reply.scope.attentionId+'/commands/'+approval.reply.operationId,undefined,identity.bindingId);
 assert.equal(original.attention.response_author,identity.principalId);
 assert.deepEqual(original.scope,approval.reply.scope);assert.equal(original.attention.response.decision,'accept');
 await call('viewer','GET','hollywood/v3/attention/'+approval.reply.scope.attentionId+'/commands/'+approval.reply.operationId,undefined,viewer.bindingId,403);
 for(const [method,path,body] of [['GET','threads',undefined],['POST','threads',{prompt:'Must not start'}],['GET','notifications',undefined],['GET','attention',undefined],['POST','voice/start',{}],['GET','losangelex/teams',undefined]])
  await call('viewer',method,path,body,undefined,403);
 const backends=await call('owner','GET','backends');assert.equal(backends.defaultBackend,'lax');assert.deepEqual(backends.backends.map(x=>x.id),['lax']);
 const status=await call('owner','GET','status');assert.equal(status.backend,'lax');assert.equal(status.connected,true);
 await sleep(12000);assert.equal(connections,0);
 assert.equal(db.prepare('SELECT state FROM session_starts WHERE id=?').get('retained-native-boundary-fixture').state,'queued');
 assert.equal(db.prepare('SELECT state FROM outgoing WHERE id=?').get('retained-native-boundary-reply').state,'queued');
 const result={state:'passed',actualGateway:true,nativeRouteIsolation:true,originalApprovalReceiptReadWithoutReplay:true,
  stockRuntimeConnections:connections,retainedFixtureQueuesUndispatched:true,observedSeconds:12,modelsStarted:0,
  phoneVerified:false,productionChanged:false};save('boundary-result.json',result);console.log(JSON.stringify(result,null,2));
}finally{
 if(child.exitCode===null){const ended=once(child,'exit');child.kill('SIGTERM');await ended;}
 await new Promise(resolve=>trap.close(resolve));db.close();
}
