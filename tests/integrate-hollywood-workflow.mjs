/** Actual PG/API + authenticated NextComp gateway, reading a completed native enterprise.
 * This probe never submits execution. Original task/answer/goal receipts are retained.
 */
import assert from 'node:assert/strict';
import {randomBytes,randomUUID} from 'node:crypto';
import {readFileSync,writeFileSync,existsSync,openSync,closeSync} from 'node:fs';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {openStore,hash} from '../server/store.mjs';

process.umask(0o077);
const repo=resolve(dirname(fileURLToPath(import.meta.url)),'..'),workroot=dirname(repo),root=resolve(process.argv[2]);
const save=(name,value)=>writeFileSync(resolve(root,name),JSON.stringify(value,null,2),{mode:0o600});
const resume=process.argv.includes('--resume-known-api-health-failure');
const intent=resolve(root,'scenario-intent.json');
if(existsSync(intent)&&!resume)throw Error('Inspect original qualification state; do not replace paired fixture identities.');
if(resume&&(!existsSync(intent)||existsSync(resolve(root,'gateway-process.json'))))throw Error('Known pre-gateway resume requires retained pairing and no previous gateway start.');
const fixture=JSON.parse(readFileSync(resolve(root,'protected-native-fixture.json')));
const ids=resume?JSON.parse(readFileSync(intent)).ids:{device:randomUUID(),viewer:randomUUID(),installation:randomUUID()};
const tokens=resume?JSON.parse(readFileSync(intent)).tokens:{owner:randomBytes(32).toString('hex'),viewer:randomBytes(32).toString('hex')};
const data=resolve(root,'gateway-data');
if(!resume){
 save('scenario-intent.json',{ids,tokens,modelsAuthorized:0,originalExecutionResubmitted:false});
 const {db}=openStore(data);
 for(const [label,id] of [['owner',ids.device],['viewer',ids.viewer]])db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run(id,'Workflow QA '+label,hash(tokens[label]),Date.now());
 db.close();
 for(const [label,credential] of [['owner',fixture.owner.token],['viewer',fixture.viewer.enrollmentCredential]])writeFileSync(resolve(data,label+'.key'),credential,{mode:0o600});
 const binding=label=>({url:'http://127.0.0.1:18771',tokenFile:resolve(data,label+'.key'),authorityId:fixture.authorityId,
  principalId:label==='owner'?fixture.owner.principalId:fixture.viewer.id,defaultRoomId:fixture.room});
 writeFileSync(resolve(data,'hollywood.json'),JSON.stringify({format:'hollywood-nextcomp-binding/v1',installationId:ids.installation,
  devices:{[ids.device]:binding('owner'),[ids.viewer]:binding('viewer')}}),{mode:0o600});
}else save('known-health-path-recovery.json',{state:'original API exited; no gateway or execution started',originalPairingRetained:true,modelsStarted:0});
const children=[];
function launch(name,exe,args,env={}){const fd=openSync(resolve(root,name+'.log'),'a',0o600);const child=spawn(exe,args,{cwd:repo,env:{...process.env,...env},stdio:['ignore',fd,fd]});closeSync(fd);children.push(child);save(name+'-process.json',{pid:child.pid});return child;}
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function ready(url,child){for(let n=0;n<80;n++){if(child.exitCode!==null)throw Error('Owned '+url+' process exited; inspect private log.');try{const r=await fetch(url);if(r.ok)return;}catch{}await sleep(250);}throw Error('Private service did not become healthy.');}
async function stop(child){if(child.exitCode===null){const ended=once(child,'exit');child.kill('SIGTERM');await ended;}}
const trace=[];
async function call(path,{who='owner',identity,expected=200,method='GET',body}={}){
 const response=await fetch('http://127.0.0.1:18886/api/hollywood/v3/'+path,{method,headers:{Authorization:'Bearer '+tokens[who],...(identity?{'X-Hollywood-Binding':identity}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
 const value=await response.json();trace.push({path,method,who,status:response.status,value});save('private-http-trace.json',trace);assert.equal(response.status,expected,path+': '+JSON.stringify(value));return value;
}
let gateway;
function startGateway(){return launch('gateway',process.execPath,[resolve(repo,'server/index.mjs')],{PORT:'18886',POCKET_DATA:data,POCKET_HOST_NAME:'Workflow qualification',CODEX_SOCKET:resolve('/tmp','hworkflow-no-runtime.sock'),POCKET_CODEX_COMMAND:'',POCKET_LOCAL:''});}
try{
 const api=launch('api',resolve(workroot,'hollywood-backbone-venv/bin/python'),['-m','hollywood_backbone.server','--database-config',resolve(workroot,'repair-release-20261014/postgres/connection.json'),'--port','18771'],{PYTHONPATH:resolve(workroot,'hollywood-backbone-source')});
 await ready('http://127.0.0.1:18771/health',api);
 gateway=startGateway();await ready('http://127.0.0.1:18886/health',gateway);
 const identity=await call('identity'),scope={identity:identity.bindingId};
 const goal=await call('goals/'+fixture.goal,scope);assert.equal(goal.goal.state,'accepted');
 const work=await call('work/'+fixture.work,scope);assert.equal(work.work.state,'accepted');assert.equal(work.execution.state,'completed');assert.equal(work.work.evidence[0].sha256,'bb8451a0299c5162d78447ac5812dff4bfe67a3dbafdadcbb40c7041f69dcd78');
 const assignments=await call('rooms/'+fixture.workRoom+'/work?limit=1',scope);assert.equal(assignments.work[0].id,fixture.work);
 const goals=await call('rooms/'+fixture.room+'/goals?limit=1',scope);assert.equal(goals.goals[0].id,fixture.goal);
 const plan=await call('goals/'+fixture.goal+'/work?limit=1',scope);assert.equal(plan.work[0].id,fixture.work);
 await call('rooms/'+fixture.room+'/goals?after=0',{...scope,expected:400});
 await call('rooms/'+fixture.workRoom+'/work?after='+fixture.work,scope);
 await call('goals/'+fixture.goal,{identity:'0'.repeat(64),expected:409});
 await call('identity',{who:'viewer',expected:403});
 await call('goals/'+fixture.goal+'/state',{...scope,method:'POST',body:{},expected:404});
 const events=[];let cursor=0;
 for(let n=0;n<20;n++){const page=await call('rooms/'+fixture.room+'/history?after='+cursor+'&limit=100',scope);events.push(...page.events);cursor=page.nextCursor;if(page.events.length<100)break;}
 const nativePlan=events.find(e=>e.data?.nativeType==='planUpdate');assert.ok(nativePlan);assert.equal(nativePlan.data.plan.length,3);
 assert.ok(events.some(e=>e.data?.nativeType==='goalState'&&e.data.nativeGoal.status==='complete'));
 const before=await call('rooms/'+fixture.room,scope);assert.equal(before.deliveries.length,0);
 await stop(gateway);gateway=startGateway();await ready('http://127.0.0.1:18886/health',gateway);
 assert.deepEqual(await call('identity'),identity);
 assert.equal((await call('goals/'+fixture.goal,scope)).goal.state,'accepted');
 save('android-settings.json',{base:'http://127.0.0.1:18886',token:tokens.owner,deviceId:ids.device,identity,room:fixture.room,workRoom:fixture.workRoom,goal:fixture.goal,work:fixture.work,goalTitle:goal.goal.title,workTitle:work.work.specification,plan:nativePlan.data.plan});
 save('gateway-result.json',{state:'passed-actual-gateway-native-workflow-readback',actualPostgres:true,actualNativeHistory:true,goalWorkAndExecutionSeparate:true,workEvidenceVerified:true,uuidPagination:true,ungrantedHumanDenied:true,wrongBindingDenied:true,gatewayRestartPreservedIdentity:true,modelsStarted:0,originalTaskResubmitted:false,physicalPhone:false,productionChanged:false});
 console.log('Actual authenticated workflow readback passed; ready for owned Android read-only qualification.');
 if(process.argv.includes('--hold-for-android')){const until=Date.now()+900000;while(!existsSync(resolve(root,'android-finished.json'))&&Date.now()<until)await sleep(1000);if(!existsSync(resolve(root,'android-finished.json')))throw Error('Android read-only qualification deadline; services stopped.');}
 const after=await call('rooms/'+fixture.room,scope);assert.equal(after.deliveries.length,0);
 const finalHistory=await call('rooms/'+fixture.room+'/history?after='+cursor+'&limit=100',scope);assert.equal(finalHistory.events.length,0);
 save('cleanup-result.json',{state:'passed-no-new-events-or-admissions',modelsStarted:0,productionChanged:false});
}finally{for(const child of [...children].reverse())await stop(child);}

