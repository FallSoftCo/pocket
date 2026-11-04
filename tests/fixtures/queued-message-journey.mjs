// Actual server/index HTTP, SQLite and WebSocket journey. Provider inference is synthetic.
import {mkdtempSync,mkdirSync,writeFileSync,readFileSync} from 'node:fs';
import {tmpdir} from 'node:os';import {join} from 'node:path';
import {spawn,spawnSync} from 'node:child_process';import {once} from 'node:events';
import {createServer} from 'node:net';import WebSocket from 'ws';
import {SessionStore} from '../../web/session-store.mjs';
const native=process.argv.includes('--native'),port=19999,thread='11111111-1111-4111-8111-111111111111';
const dir=mkdtempSync(join(tmpdir(),'nextcomp-real-backend-queue-')),state=join(dir,'provider.json');
mkdirSync(join(dir,'home'));mkdirSync(join(dir,'codex'));
writeFileSync(state,JSON.stringify({threads:[{id:thread,name:'Queued message study',cwd:'/synthetic',source:'cli',historyMode:'paginated',status:{type:'active'},createdAt:1,updatedAt:1,turns:[{id:'old-abandoned-turn',status:'inProgress',items:[]}]}],starts:[]}));
const probe=createServer();await new Promise((ok,no)=>{probe.once('error',no);probe.listen(port,'127.0.0.1',ok);});await new Promise(ok=>probe.close(ok));
let child,ws,token,logs='';const events=[];
const wait=async(fn,label)=>{const until=Date.now()+20000;while(Date.now()<until){if(await fn())return;await new Promise(r=>setTimeout(r,50));}throw Error('Timed out: '+label);};
const launch=async()=>{
 const offset=logs.length;child=spawn(process.execPath,['server/index.mjs'],{env:{PATH:process.env.PATH,HOME:join(dir,'home'),CODEX_HOME:join(dir,'codex'),PORT:String(port),POCKET_DATA:join(dir,'data'),POCKET_LOCAL:'0',POCKET_VOICE_API_KEY:'',POCKET_CODEX_COMMAND:JSON.stringify([process.execPath,'tests/fixtures/real-backend-provider.mjs']),NEXTCOMP_QA_PROVIDER_STATE:state},stdio:['ignore','pipe','pipe']});
 child.stdout.on('data',x=>logs+=x);child.stderr.on('data',x=>logs+=x);
 await wait(()=>{if(child.exitCode!==null)throw Error('Candidate backend exited; retained private log');return logs.slice(offset).includes('NextComp listening on 127.0.0.1:'+port);},'backend readiness');
};
const stop=async()=>{if(child&&child.exitCode===null&&child.signalCode===null){const done=once(child,'exit');child.kill();await done;}};
const api=async(path,body,credential=token)=>{const r=await fetch('http://127.0.0.1:'+port+path,{headers:{Authorization:'Bearer '+credential,'Content-Type':'application/json',Connection:'close'},...(body?{method:'POST',body:JSON.stringify(body)}:{})});const x=await r.json();if(!r.ok)throw Error('HTTP '+r.status+' '+path+': '+x.error);return x;};
const connect=async()=>{ws=new WebSocket('ws://127.0.0.1:'+port+'/events',{headers:{Authorization:'Bearer '+token}});ws.on('message',b=>events.push(JSON.parse(b)));await once(ws,'open');};
try{
 await launch();const owner=JSON.parse(readFileSync(join(dir,'data/secrets.json'))).adminToken;
 let pair=await api('/api/pairing',{},owner);const device=await api('/api/pair',{code:pair.code,name:'Queue observer'},owner);token=device.token;
 pair=await api('/api/pairing',{},owner);const other=await api('/api/pair',{code:pair.code,name:'Other queue writer'},owner);
 await connect();await wait(async()=> (await api('/api/status')).connected,'runtime metadata');
 const rows=['First queued request','Second queued request','Third queued request'];let first;
 for(let i=0;i<3;i++){
  const result=await api('/api/threads/'+thread+'/reply',{id:'queued-ui-'+(i+1),text:rows[i],mode:'queue'},other.token);
  if(result.state!=='queued'||result.outbox.items.length!==i+1)throw Error('Admission response omitted queue snapshot');if(!i)first=result.outbox;
 }
 await wait(()=>events.some(e=>e.type==='reply'&&e.outbox?.items.length===3),'other-device live queue');
 const calls=readFileSync(state+'.calls.jsonl','utf8').trim().split('\n').map(JSON.parse);
 if(calls.some(c=>['thread/turns/list','thread/items/list'].includes(c.method)||c.method==='thread/read'&&c.params.includeTurns!==false))throw Error('Busy queue admission loaded history');
 if(JSON.parse(readFileSync(state)).starts.length)throw Error('Queued message executed while active');
 if(native){
  const ownerReceipt=JSON.parse(readFileSync(process.env.NEXTCOMP_QA_OWNER_RECEIPT,'utf8'));
  const ticks=readFileSync('/proc/'+ownerReceipt.emulatorPid+'/stat','utf8').split(') ').at(-1).split(' ')[19];
  if(ownerReceipt.owner!=='01a0f2bc-ff5a-70c2-b682-10f0987dab80'||ticks!==ownerReceipt.emulatorStartTicks)throw Error('Emulator owner mismatch');
  const adb=process.env.ANDROID_HOME+'/platform-tools/adb',serial=ownerReceipt.serial;
  if(!/^emulator-[0-9]{4,5}$/.test(serial))throw Error('Missing owned emulator serial');
  const run=args=>{const r=spawnSync(adb,['-s',serial,...args],{encoding:'utf8',timeout:90000});if(r.status!==0)throw Error('Owned emulator command failed');return r.stdout;};
  if(run(['shell','getprop','ro.boot.qemu.avd_name']).trim()!=='nextcomp_env_qa')throw Error('Wrong AVD');
  run(['reverse','tcp:18880','tcp:'+port]);
  run(['shell','am','force-stop','co.fallsoft.pocket']);
  const result=run(['shell','am','instrument','-w','-e','class','co.fallsoft.pocket.QueuedMessagesNativeTest#queuedMessagesStayVisibleAndRemoveWithoutHistory','-e','pairedToken',token,'-e','pairedDevice',device.id,'co.fallsoft.pocket.test/androidx.test.runner.AndroidJUnitRunner']);
  writeFileSync(join(dir,'native.log'),result);
  if(!/^OK \(1 test\)$/m.test(result)||/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed/.test(result))throw Error('Native queue journey failed; original log retained');
  if(process.env.NEXTCOMP_QA_REVIEW_HOLD==='1'){
   writeFileSync(join(dir,'UI-READY.json'),JSON.stringify({dir,port,thread,queued:2,noProviderStarts:true}));
   console.log(JSON.stringify({stage:'native-passed-awaiting-optimized-ui',dir,port}));
   const deadline=Date.now()+15*60*1000;
   while(true){try{readFileSync(join(dir,'CONTINUE'));break;}catch{if(Date.now()>deadline)throw Error('Bounded optimized review hold expired');await new Promise(r=>setTimeout(r,250));}}
  }
 }else await api('/api/threads/'+thread+'/replies/queued-ui-3',{action:'remove'},other.token);
 const removed=await api('/api/replies/queued-ui-3');if(removed.state!=='cancelled'||removed.outbox.items.length!==2)throw Error('Removal was not preserved');
 await api('/api/threads/'+thread+'/replies/queued-ui-1',{action:'edit',text:'Revised first queued request'},other.token);
 await wait(()=>events.some(e=>e.outbox?.items[0]?.text==='Revised first queued request'),'live edit');
 const latest=(await api('/api/replies/queued-ui-1')).outbox,cache=new SessionStore();
 const head=outbox=>({thread:{id:thread},timeline:{rows:[],hasEarlier:false},outbox,outgoing:outbox.items});
 cache.head(thread,head(first));cache.outbox(thread,latest);cache.head(thread,head(first));
 if(cache.get(thread).snapshot.outgoing.length!==2||cache.get(thread).snapshot.outgoing[0].text!=='Revised first queued request')throw Error('Late history rolled back live queue');
 ws.terminate();await stop();await launch();await connect();
 const restored=await api('/api/replies/queued-ui-1');if(JSON.stringify(restored.outbox)!==JSON.stringify(latest))throw Error('Restart changed queue revision or content');
 const data=JSON.parse(readFileSync(state));data.threads[0].status={type:'idle'};writeFileSync(state,JSON.stringify(data));
 await wait(async()=> (await api('/api/replies/queued-ui-2')).state==='accepted','normal next-turn delivery');
 const final=JSON.parse(readFileSync(state));if(JSON.stringify(final.starts.map(x=>x.clientId))!==JSON.stringify(['queued-ui-1','queued-ui-2']))throw Error('FIFO/duplicate/cancelled delivery failure');
 if((await api('/api/replies/queued-ui-1')).outbox.items.length)throw Error('Accepted queue remains visible');
 const result={passed:true,native,dir,real:['server/index HTTP and human pairing','SQLite durable queue','cross-device WebSocket updates','queue edit/remove','web stale-history merge','real backend restart','FIFO delivery after actual metadata idle'],provider:'deterministic stdio; no tools/model/network',providerStarts:final.starts.length,removedNotExecuted:true,historyReadRequiredForQueue:false};
 writeFileSync(join(dir,'RESULT.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}catch(e){writeFileSync(join(dir,'FAILURE.json'),JSON.stringify({error:e.message,at:Date.now()}));throw e;}
finally{ws?.terminate();await stop();writeFileSync(join(dir,'backend.log'),logs);}
