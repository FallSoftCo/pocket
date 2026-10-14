/** Real NextComp auth + Hollywood PG + finite native LAX approval. Private inputs only. */
import assert from 'node:assert/strict';
import {randomBytes,randomUUID,createHash} from 'node:crypto';
import {mkdirSync,readFileSync,writeFileSync,existsSync,openSync,closeSync} from 'node:fs';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn,execFileSync} from 'node:child_process';
import {once} from 'node:events';
import {createServer} from 'node:net';
import {openStore,hash} from '../server/store.mjs';

const repo=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const root=resolve(process.argv[2]);
const fixture=resolve(process.argv[3]);
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
process.umask(0o077);
mkdirSync(root,{recursive:true,mode:0o700});
const save=(name,value)=>writeFileSync(resolve(root,name),JSON.stringify(value,null,2),{mode:0o600});
if(existsSync(resolve(root,'scenario-intent.json')))throw Error('Original scenario intent exists; inspect its exact receipts without replay.');
const ids={owner:randomUUID(),viewer:randomUUID(),installation:randomUUID()};
const tokens={owner:randomBytes(32).toString('hex'),viewer:randomBytes(32).toString('hex')};
save('scenario-intent.json',{ids,tokens,fixture,nativeUnit:'lax-hollywood-gateway-approval-20261014'});
const prior=JSON.parse(readFileSync(resolve(fixture,'scenario-intent.json')));
const installation=JSON.parse(readFileSync(resolve(fixture,'installation/installation-intent.json')));
const originalConfig=JSON.parse(readFileSync(resolve(fixture,'connector.json')));
const data=resolve(root,'gateway-data');
const {db,secrets}=openStore(data);
for(const name of ['owner','viewer'])db.prepare('INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)').run(ids[name],'Qualification '+name,hash(tokens[name]),Date.now());
db.close();
const tokenFiles={owner:resolve(data,'hollywood-owner.key'),viewer:resolve(data,'hollywood-viewer.key')};
writeFileSync(tokenFiles.owner,installation.credentials.Owner.token,{mode:0o600});
writeFileSync(tokenFiles.viewer,prior.keys.bob,{mode:0o600});
const binding=which=>({url:'http://127.0.0.1:18768',tokenFile:tokenFiles[which],authorityId:installation.authorityId,
  principalId:which==='owner'?installation.credentials.Owner.principalId:prior.ids.bob,defaultRoomId:prior.ids.room});
writeFileSync(resolve(data,'hollywood.json'),JSON.stringify({format:'hollywood-nextcomp-binding/v1',installationId:ids.installation,
  devices:{[ids.owner]:binding('owner'),[ids.viewer]:binding('viewer')}}),{mode:0o600});
const port=18886;
await new Promise((resolve,reject)=>{const server=createServer();server.once('error',reject);server.listen(port,'127.0.0.1',()=>server.close(resolve));});
let child;
async function launch(){
  const fd=openSync(resolve(root,'gateway.log'),'a',0o600);
  child=spawn(process.execPath,[resolve(repo,'server/index.mjs')],{cwd:repo,stdio:['ignore',fd,fd],env:{...process.env,
    PORT:String(port),POCKET_DATA:data,POCKET_HOST_NAME:'OZZZ-OS qualification',POCKET_DEFAULT_CWD:root,
    CODEX_SOCKET:resolve(root,'no-codex-runtime.sock'),POCKET_CODEX_COMMAND:'',POCKET_LOCAL:'',POCKET_VOICE_API_KEY:''}});
  closeSync(fd);save('gateway-process.json',{pid:child.pid,port});
  for(let n=0;n<80;n++){
    if(child.exitCode!==null)throw Error('Owned gateway exited; inspect its private log.');
    try{const r=await fetch(`http://127.0.0.1:${port}/health`);if(r.ok)return;}catch{}
    await sleep(250);
  }
  throw Error('Gateway observation timed out; original process may still be live.');
}
async function stop(){if(child?.exitCode===null){const ended=once(child,'exit');child.kill('SIGTERM');await ended;}}
const receipts=[];
async function call(who,method,path,body,bindingId,expected=200){
  const response=await fetch(`http://127.0.0.1:${port}/api/${path}`,{method,headers:{Authorization:`Bearer ${tokens[who]}`,
    ...(bindingId?{'X-Hollywood-Binding':bindingId}:{}),...(body===undefined?{}:{'Content-Type':'application/json'})},
    ...(body===undefined?{}:{body:JSON.stringify(body)})});
  const value=await response.json();receipts.push({who,method,path,status:response.status,result:value});save('private-receipts.json',receipts);
  assert.equal(response.status,expected,`${method} ${path}: expected ${expected}, observed ${response.status}`);return value;
}
try{
  await launch();
  const owner=await call('owner','GET','hollywood/v3/identity');
  const viewer=await call('viewer','GET','hollywood/v3/identity');
  assert.notEqual(owner.bindingId,viewer.bindingId);assert.equal(owner.authorityId,installation.authorityId);
  assert.equal(owner.principalId,installation.credentials.Owner.principalId);assert.equal(viewer.principalId,prior.ids.bob);
  await call('viewer','GET','hollywood/v3/rooms',undefined,owner.bindingId,409);
  await call('owner','POST','hollywood/v3/connector/claim',{},owner.bindingId,404);
  await call('owner','GET','hollywood/v3/rooms?url=https://example.com',undefined,owner.bindingId,400);
  const backends=await call('owner','GET','backends');
  assert.equal(backends.backends.find(x=>x.id==='codex').available,false);
  assert.equal(backends.backends.find(x=>x.id==='lax').available,true);
  const denied={operationId:'viewer-forbidden',kind:'request',body:'Must not execute',audience:'room',recipientId:prior.ids.native};
  await call('viewer','POST',`hollywood/v3/rooms/${prior.ids.room}/messages`,denied,viewer.bindingId,403);
  const parent=resolve(root,'native-approved-output');mkdirSync(parent,{mode:0o700});
  const target=resolve(parent,'proof.txt');
  const command=`printf 'HOLLYWOOD_APPROVAL_VERIFIED\\n' > '${target}'`;
  const request={operationId:'gateway-native-approval',kind:'request',audience:'room',recipientId:prior.ids.native,
    body:`This is a finite disposable approval integration. Execute exactly this command with exec_command using sandbox_permissions=require_escalated and a concise justification so the original human can approve this exact outside-workspace write: ${command}. Wait for the approval. If declined, finish without the write. If accepted, execute it once and report its result through Hollywood. Do not try a different command, bypass the approval, retry the effect, delegate or start any other work.`};
  save('native-request-intent.json',{binding:owner,request});
  const accepted=await call('owner','POST',`hollywood/v3/rooms/${prior.ids.room}/messages`,request,owner.bindingId);
  assert.equal(accepted.accepted,true);assert.equal(accepted.event.author,owner.principalId);
  assert.deepEqual(accepted.deliveries.map(x=>x.agent),[prior.ids.native]);
  const config={...originalConfig,approvalPolicy:'on-request',turnDeadlineSeconds:500};
  save('native-connector.json',config);
  const workroot=resolve(repo,'..');
  execFileSync('systemd-run',['--user','--unit=lax-hollywood-gateway-approval-20261014',
    '--property=WorkingDirectory='+root,'--property=UMask=0077','--property=MemoryMax=2G','--property=CPUQuota=200%',
    '--property=RuntimeMaxSec=550','--setenv=PYTHONPATH='+resolve(workroot,'lax-connector-source/losangelex/connector'),
    resolve(workroot,'hollywood-backbone-venv/bin/python'),'-m','lax_connector.connector','--config',resolve(root,'native-connector.json'),
    '--claim-id','gateway-native-approval-claim'],{stdio:'pipe'});
  let approval,terminal;
  const deadline=Date.now()+520000;
  while(Date.now()<deadline){
    const page=await call('owner','GET',`hollywood/v3/rooms/${prior.ids.room}/attention`,undefined,owner.bindingId);
    const pending=page.attention.filter(q=>q.attention.delivery===accepted.deliveries[0].id);
    if(pending.length&&!approval){
      assert.equal(pending.length,1);const q=pending[0];save('native-original-approval.json',q);
      assert.equal(q.attention.method,'item/commandExecution/requestApproval');assert.equal(q.attention.state,'pending');
      assert.equal(q.attention.request.nativeContext.kind,'command');assert.ok(q.attention.request.command.includes(target));
      assert.equal(existsSync(target),false);
      await call('viewer','GET','hollywood/v3/attention/'+q.attention.id,undefined,viewer.bindingId,403);
      await stop();await launch();
      assert.deepEqual(await call('owner','GET','hollywood/v3/identity'),owner);
      const current=await call('owner','GET','hollywood/v3/attention/'+q.attention.id,undefined,owner.bindingId);
      assert.deepEqual(current.scope,q.scope);assert.equal(current.attention.state,'pending');
      const reply={operationId:'native-approval-answer',scope:q.scope,response:{decision:'accept'}};
      save('native-approval-answer-intent.json',{binding:owner,reply});
      approval=await call('owner','POST','hollywood/v3/attention/'+q.attention.id+'/response',reply,owner.bindingId);
      assert.equal(approval.attention.state,'response_submitted');
    }
    const original=await call('owner','GET','hollywood/v3/commands/'+request.operationId,undefined,owner.bindingId);
    const delivery=original.deliveries[0];
    if(['completed','failed','cancelled','unknown'].includes(delivery.state)){
      terminal=original;save('native-final-receipt.json',terminal);assert.equal(delivery.state,'completed');break;
    }
    await sleep(1000);
  }
  if(!terminal)throw Error('Original native approval remains unresolved; inspect its exact unit and journals without replay.');
  assert.ok(approval);assert.equal(readFileSync(target,'utf8'),'HOLLYWOOD_APPROVAL_VERIFIED\n');
  const closed=await call('owner','GET','hollywood/v3/attention/'+approval.attention.id,undefined,owner.bindingId);
  assert.equal(closed.attention.state,'closed');
  const history=await call('owner','GET',`hollywood/v3/rooms/${prior.ids.room}/history`,undefined,owner.bindingId);
  const operations=history.events.filter(e=>e.source===accepted.event.id&&e.data.nativeType==='commandExecution');
  assert.ok(operations.some(e=>e.data.phase==='completed'&&e.data.exitCode===0));
  assert.equal(terminal.deliveries[0].native_thread,JSON.parse(readFileSync(resolve(fixture,'result.json'))).nativeThread);
  const result={state:'passed',actualNextCompGateway:true,seededDisposablePairingFixtures:true,
    twoHumanBindingIsolation:true,unknownBackendAndConnectorRoutesDenied:true,stockRuntimeAvailable:false,
    nativeLaxApprovalVerified:true,approvalSurvivesGatewayRestart:true,sameOriginalNativeContext:true,
    nativeThread:terminal.deliveries[0].native_thread,nativeTurn:terminal.deliveries[0].native_turn,
    artifactSha256:createHash('sha256').update(readFileSync(target)).digest('hex'),phoneVerified:false,productionChanged:false};
  save('result.json',result);console.log(JSON.stringify(result,null,2));
}finally{await stop();}
