import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';

test('push registration is bound to the paired device and configured project',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'pocket-push-api-')),port=23000+Math.floor(Math.random()*1000);
 await writeFile(join(dir,'firebase-client.json'),JSON.stringify({projectId:'pocket-test',senderId:'123',applicationId:'1:123:android:test',apiKey:'public-test-config'}));
 const child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,POCKET_DATA:dir,PORT:String(port),CODEX_SOCKET:join(dir,'missing.sock')},stdio:'ignore'});t.after(()=>child.kill());
 for(let i=0;i<60;i++){try{if((await fetch(`http://127.0.0.1:${port}/health`)).ok)break;}catch{}await new Promise(r=>setTimeout(r,50));}
 const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json'),'utf8'));
 const api=async(path,body,token=adminToken)=>{const r=await fetch(`http://127.0.0.1:${port}${path}`,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});return {status:r.status,data:await r.json()};};
 const {data:pair}=await api('/api/pairing',{});const {data:device}=await api('/api/pair',{code:pair.code},'');
 const registration={projectId:'pocket-test',token:'test-registration-token-1234567890'};
 assert.equal((await api('/api/device/push',registration,'unknown')).status,401);
 assert.equal((await api('/api/device/push',registration)).status,403);
 assert.equal((await api('/api/device/push',{...registration,projectId:'someone-else'},device.token)).status,400);
 assert.equal((await api('/api/device/push',registration,device.token)).status,200);
 const {data:status}=await api('/api/status',null,device.token);assert.equal(status.push.registered,true);assert.equal(status.deviceId,device.id);
 assert.equal((await api('/api/notifications/1/delivery',null,device.token)).status,403);
 await api('/api/device/disconnect',{},device.token);
 assert.equal((await api('/api/status',null,device.token)).status,401);
});
