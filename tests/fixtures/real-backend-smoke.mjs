import {mkdtempSync,writeFileSync,readFileSync,mkdirSync} from 'node:fs';
import {tmpdir} from 'node:os';import {join} from 'node:path';import {spawn,spawnSync} from 'node:child_process';
import {DatabaseSync} from 'node:sqlite';
import {createHash} from 'node:crypto';
import {once} from 'node:events';import {createServer} from 'node:net';import WebSocket from 'ws';
const native=process.argv.includes('--native');
const adb=process.env.ANDROID_HOME+'/platform-tools/adb';
const digest=p=>createHash('sha256').update(readFileSync(p)).digest('hex');
const adbRun=args=>{const r=spawnSync(adb,['-s','emulator-5620',...args],{encoding:'utf8',timeout:90000,maxBuffer:4*1024*1024});if(r.status!==0)throw Error('Owned emulator command failed: '+args[0]);return r.stdout;};
if(native){
 const owner=JSON.parse(readFileSync(process.env.NEXTCOMP_QA_OWNER_RECEIPT,'utf8'));
 const ticks=readFileSync('/proc/'+owner.emulatorPid+'/stat','utf8').split(') ').at(-1).split(' ')[19];
 if(owner.owner!=='01a0f2bc-ff5a-70c2-b682-10f0987dab80'||ticks!==owner.emulatorStartTicks||!readFileSync('/proc/'+owner.emulatorPid+'/cmdline','utf8').includes('nextcomp_env_qa'))throw Error('Native ownership mismatch');
 if(adbRun(['shell','getprop','ro.boot.qemu.avd_name']).trim()!=='nextcomp_env_qa')throw Error('Wrong AVD');
 const base=adbRun(['shell','pm','path','co.fallsoft.pocket']).trim().replace(/^package:/,'');
 if(!/^\/data\/app\/[a-zA-Z0-9_~.+/=-]+\/base\.apk$/.test(base))throw Error('Unexpected package layout');
 if(adbRun(['shell','sha256sum',base]).split(/\s/)[0]!==digest('android/app/build/outputs/apk/qualification/app-qualification.apk'))throw Error('Qualification app identity mismatch');
 adbRun(['install','-r','-t','android/app/build/outputs/apk/androidTest/qualification/app-qualification-androidTest.apk']);
 adbRun(['reverse','tcp:18880','tcp:19999']);
}
const dir=mkdtempSync(join(tmpdir(),'nextcomp-real-backend-')),state=join(dir,'provider.json');
mkdirSync(join(dir,'home'));mkdirSync(join(dir,'codex'));
const tid='11111111-1111-4111-8111-111111111111';
writeFileSync(state,JSON.stringify({threads:[{id:tid,name:'Synthetic integration work',cwd:'/synthetic',source:'cli',status:{type:'idle'},createdAt:1,updatedAt:1,turns:[]}],starts:[]}));
const reservation=createServer();
await new Promise((resolve,reject)=>{reservation.once('error',reject);reservation.listen(19999,'127.0.0.1',resolve);});
await new Promise(resolve=>reservation.close(resolve));
let child,logs='';
const launch=()=>{
 const processChild=spawn(process.execPath,['server/index.mjs'],{env:{PATH:process.env.PATH,HOME:join(dir,'home'),CODEX_HOME:join(dir,'codex'),POCKET_DATA:join(dir,'data'),PORT:'19999',POCKET_LOCAL:'0',POCKET_VOICE_API_KEY:'',POCKET_CODEX_COMMAND:JSON.stringify([process.execPath,'tests/fixtures/real-backend-provider.mjs']),NEXTCOMP_QA_PROVIDER_STATE:state},stdio:['ignore','pipe','pipe']});
 processChild.stdout.on('data',x=>logs+=x);processChild.stderr.on('data',x=>logs+=x);
 writeFileSync(join(dir,'owner.json'),JSON.stringify({pid:processChild.pid,actor:'01a0f2bc-ff5a-70c2-b682-10f0987dab80',state:'started'}));return processChild;
};
const stop=async()=>{
 if(child.exitCode!==null||child.signalCode!==null)return;
 const exited=once(child,'exit');child.kill();await exited;
};
const ready=async()=>{
 const offset=logs.length;child=launch();
 for(let n=0;n<100;n++){
  if(child.exitCode!==null||child.signalCode!==null)throw Error('Backend exited: '+logs);
  if(logs.slice(offset).includes('NextComp listening on 127.0.0.1:19999')){
   try{if((await fetch('http://127.0.0.1:19999/health',{signal:AbortSignal.timeout(1000),headers:{Connection:'close'}})).ok)return;}catch{}
  }
  await new Promise(r=>setTimeout(r,50));
 }
 throw Error('Owned backend not ready');
};
let ws;
writeFileSync(join(dir,'journey.json'),JSON.stringify({state:'running',native,startedAt:Date.now()}));
try{
 await ready();
 let token=JSON.parse(readFileSync(join(dir,'data/secrets.json'))).adminToken;
 const api=async(path,body)=>{const r=await fetch('http://127.0.0.1:19999'+path,{headers:{Authorization:'Bearer '+token,'Content-Type':'application/json',Connection:'close'},...(body?{method:'POST',body:JSON.stringify(body)}:{})});const d=await r.json();if(!r.ok)throw Error(path+': '+JSON.stringify(d));return d;};
 const pairing=await api('/api/pairing',{});
 const device=await api('/api/pair',{code:pairing.code,name:'Synthetic integration device'});token=device.token;
 const nativePhase=phase=>{
  adbRun(['shell','am','force-stop','co.fallsoft.pocket']);
  const out=adbRun(['shell','am','instrument','-w','-e','class','co.fallsoft.pocket.RealBackendJourneyNativeTest#sendEchoAndColdRestore','-e','qaOwner','01a0f2bc-ff5a-70c2-b682-10f0987dab80','-e','realBackendPhase',phase,'-e','pairedToken',token,'-e','pairedDevice',device.id,'co.fallsoft.pocket.test/androidx.test.runner.AndroidJUnitRunner']);
  writeFileSync(join(dir,'native-'+phase+'.log'),out,{mode:0o600});
  if(!/^OK \(1 test\)$/m.test(out)||/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=/.test(out))throw Error('Native phase failed: '+phase+'; private log retained');
 };
 const events=[];ws=new WebSocket('ws://127.0.0.1:19999/events',{headers:{Authorization:'Bearer '+token}});ws.on('message',x=>events.push(JSON.parse(x)));await once(ws,'open');
 await api('/api/threads/'+tid+'?view=timeline');
 let id='synthetic-real-backend-send';
 if(native){nativePhase('send');id=JSON.parse(readFileSync(state)).starts.at(-1)?.clientId;if(!id)throw Error('Native UI did not submit');}
 else await api('/api/threads/'+tid+'/reply',{id,text:'Integration input',mode:'auto'});
 let receipt;for(let n=0;n<100;n++){receipt=await api('/api/replies/'+id);if(receipt.state==='accepted'||receipt.state==='failed')break;await new Promise(r=>setTimeout(r,50));}
 if(receipt.state!=='accepted')throw Error('Reply not accepted: '+JSON.stringify(receipt));
 const history=await api('/api/threads/'+tid+'?view=timeline');
 const users=history.timeline.rows.filter(r=>r.kind==='user');
 if(users.length!==1||users[0].clientId!==id)throw Error('Canonical identity missing: '+JSON.stringify(users));
 if(!events.some(e=>e.type==='timeline'&&e.row?.clientId===id))throw Error('No real backend websocket echo');
 const calls=JSON.parse(readFileSync(state));if(calls.starts.length!==1)throw Error('Duplicate provider start');
 // Simulate provider metadata loss across a genuine backend process restart.
 const persisted=JSON.parse(readFileSync(state));
 for(const thread of persisted.threads)for(const turn of thread.turns)for(const item of turn.items)delete item.clientId;
 writeFileSync(state,JSON.stringify(persisted));
 ws.terminate();await stop();await ready();
 if(native)nativePhase('restore');
 const afterReceipt=await api('/api/replies/'+id);
 if(afterReceipt.state!=='accepted')throw Error('Accepted receipt lost after restart');
 const afterHistory=await api('/api/threads/'+tid+'?view=timeline');
 const afterUsers=afterHistory.timeline.rows.filter(r=>r.kind==='user');
 if(afterUsers.length!==1||afterUsers[0].clientId!==id)throw Error('Restart lost durable send identity');
 // Exact accepted-ID reconciliation is safe; no new logical send is created.
 const db=new DatabaseSync(join(dir,'data/pocket.sqlite'),{readOnly:true});
 let original;try{original=db.prepare('SELECT thread_id,text,mode FROM outgoing WHERE id=?').get(id);}finally{db.close();}
 if(original?.thread_id!==tid||original.text!=='Integration input'||!['auto','steer','queue'].includes(original.mode))throw Error('Unexpected persisted outgoing intent');
 const duplicate=await api('/api/threads/'+tid+'/reply',{id,text:original.text,mode:original.mode});
 if(duplicate.state!=='accepted')throw Error('Accepted-ID reconciliation changed state');
 const finalCalls=JSON.parse(readFileSync(state));
 if(finalCalls.starts.length!==1)throw Error('Restart or reconciliation duplicated provider work');
 const result={state:'passed',dir,nativeUi:native,real:['server/index.mjs','HTTP authentication/routes','SQLite outgoing and send identity','WebSocket event delivery'],substituted:'Deterministic stdio provider',providerStarts:calls.starts.length,replyState:receipt.state,canonicalUsers:users.length,restart:{pairingPreserved:true,acceptedReceiptPreserved:true,providerIdentityRestored:true,noDuplicateStart:true}};
 writeFileSync(native?'/tmp/nextcomp-real-backend-native-result.json':'/tmp/nextcomp-real-backend-smoke-result.json',JSON.stringify(result,null,2));writeFileSync(join(dir,'journey.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}catch(error){
 writeFileSync(join(dir,'journey.json'),JSON.stringify({state:'failed',native,error:error.message,finishedAt:Date.now()}));throw error;
}finally{
 ws?.terminate();if(child)await stop();writeFileSync(join(dir,'backend.log'),logs);writeFileSync(join(dir,'owner.json'),JSON.stringify({pid:child.pid,state:'stopped'}));
}
