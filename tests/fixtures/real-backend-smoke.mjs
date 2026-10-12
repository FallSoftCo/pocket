import {mkdtempSync,writeFileSync,readFileSync,mkdirSync} from 'node:fs';
import {tmpdir} from 'node:os';import {join} from 'node:path';import {spawn} from 'node:child_process';
import {once} from 'node:events';import {createServer} from 'node:net';import WebSocket from 'ws';
const dir=mkdtempSync(join(tmpdir(),'nextcomp-real-backend-')),state=join(dir,'provider.json');
mkdirSync(join(dir,'home'));mkdirSync(join(dir,'codex'));
const tid='11111111-1111-4111-8111-111111111111';
writeFileSync(state,JSON.stringify({threads:[{id:tid,name:'Synthetic integration work',cwd:'/synthetic',source:'cli',status:{type:'idle'},createdAt:1,updatedAt:1,turns:[]}],starts:[]}));
const reservation=createServer();
await new Promise((resolve,reject)=>{reservation.once('error',reject);reservation.listen(19999,'127.0.0.1',resolve);});
await new Promise(resolve=>reservation.close(resolve));
const child=spawn(process.execPath,['server/index.mjs'],{env:{PATH:process.env.PATH,HOME:join(dir,'home'),CODEX_HOME:join(dir,'codex'),POCKET_DATA:join(dir,'data'),PORT:'19999',POCKET_LOCAL:'0',POCKET_VOICE_API_KEY:'',POCKET_CODEX_COMMAND:JSON.stringify([process.execPath,'tests/fixtures/real-backend-provider.mjs']),NEXTCOMP_QA_PROVIDER_STATE:state},stdio:['ignore','pipe','pipe']});
let logs='';child.stdout.on('data',x=>logs+=x);child.stderr.on('data',x=>logs+=x);
writeFileSync(join(dir,'owner.json'),JSON.stringify({pid:child.pid,actor:'01a0f2bc-ff5a-70c2-b682-10f0987dab80',state:'started'}));
let ws;
try{
 for(let n=0;n<100;n++){if(child.exitCode!==null)throw Error('Backend exited: '+logs);try{if((await fetch('http://127.0.0.1:19999/health')).ok)break;}catch{}await new Promise(r=>setTimeout(r,50));}
 if(!logs.includes('NextComp listening on 127.0.0.1:19999'))throw Error('Owned backend not ready');
 let token=JSON.parse(readFileSync(join(dir,'data/secrets.json'))).adminToken;
 const api=async(path,body)=>{const r=await fetch('http://127.0.0.1:19999'+path,{headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},...(body?{method:'POST',body:JSON.stringify(body)}:{})});const d=await r.json();if(!r.ok)throw Error(path+': '+JSON.stringify(d));return d;};
 const pairing=await api('/api/pairing',{});
 const device=await api('/api/pair',{code:pairing.code,name:'Synthetic integration device'});token=device.token;
 const events=[];ws=new WebSocket('ws://127.0.0.1:19999/events',{headers:{Authorization:'Bearer '+token}});ws.on('message',x=>events.push(JSON.parse(x)));await once(ws,'open');
 await api('/api/threads/'+tid+'?view=timeline');
 const id='synthetic-real-backend-send';
 await api('/api/threads/'+tid+'/reply',{id,text:'Integration input',mode:'auto'});
 let receipt;for(let n=0;n<100;n++){receipt=await api('/api/replies/'+id);if(receipt.state==='accepted'||receipt.state==='failed')break;await new Promise(r=>setTimeout(r,50));}
 if(receipt.state!=='accepted')throw Error('Reply not accepted: '+JSON.stringify(receipt));
 const history=await api('/api/threads/'+tid+'?view=timeline');
 const users=history.timeline.rows.filter(r=>r.kind==='user');
 if(users.length!==1||users[0].clientId!==id)throw Error('Canonical identity missing: '+JSON.stringify(users));
 if(!events.some(e=>e.type==='timeline'&&e.row?.clientId===id))throw Error('No real backend websocket echo');
 const calls=JSON.parse(readFileSync(state));if(calls.starts.length!==1)throw Error('Duplicate provider start');
 const result={state:'passed',dir,real:['server/index.mjs','HTTP authentication/routes','SQLite outgoing and send identity','WebSocket event delivery'],substituted:'Deterministic stdio provider',providerStarts:calls.starts.length,replyState:receipt.state,canonicalUsers:users.length};
 writeFileSync('/tmp/nextcomp-real-backend-smoke-result.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}finally{
 ws?.terminate();child.kill();await once(child,'exit');writeFileSync(join(dir,'backend.log'),logs);writeFileSync(join(dir,'owner.json'),JSON.stringify({pid:child.pid,state:'stopped'}));
}
