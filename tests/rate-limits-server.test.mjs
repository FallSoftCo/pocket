import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import WebSocket,{WebSocketServer} from 'ws';

const wait=async fn=>{for(let i=0;i<100;i++){const value=await fn();if(value)return value;await new Promise(r=>setTimeout(r,30));}throw Error('Condition not reached');};
test('authenticated status and live events expose actual weekly allowance without account details',async t=>{
  const dir=await mkdtemp(join(tmpdir(),'pocket-usage-')),socket=join(dir,'codex.sock');
  const host=http.createServer(),wss=new WebSocketServer({server:host});
  await new Promise(r=>host.listen(socket,r));let peer;
  const weekly=usedPercent=>({usedPercent,windowDurationMins:10080,resetsAt:2000000000});
  wss.on('connection',ws=>{peer=ws;ws.on('message',raw=>{
    const m=JSON.parse(raw);if(!m.id)return;
    const result=m.method==='account/rateLimits/read'?{rateLimits:{limitId:'codex',primary:weekly(82)},accountId:'private-account',rateLimitResetCredits:{availableCount:1}}:{};
    ws.send(JSON.stringify({id:m.id,result}));
  });});
  const port=45000+Math.floor(Math.random()*1000);
  const child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});
  t.after(()=>{child.kill();for(const ws of wss.clients)ws.terminate();wss.close();host.close();});
  await wait(async()=>{try{return (await fetch(`http://127.0.0.1:${port}/health`)).ok;}catch{return false;}});
  const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json'),'utf8'));
  assert.equal((await fetch(`http://127.0.0.1:${port}/api/status`)).status,401);
  const status=()=>fetch(`http://127.0.0.1:${port}/api/status`,{headers:{Authorization:`Bearer ${adminToken}`}}).then(r=>r.json());
  const first=await wait(async()=>{const s=await status();return s.usage?.weekly?.remainingPercent===18&&s;});
  assert.ok(!JSON.stringify(first).includes('private-account'));
  const events=[];
  const client=new WebSocket(`ws://127.0.0.1:${port}/events`,{headers:{Authorization:`Bearer ${adminToken}`}});
  client.on('message',raw=>events.push(JSON.parse(raw)));t.after(()=>client.terminate());
  await wait(()=>events.find(e=>e.type==='rateLimits'&&e.usage.weekly?.remainingPercent===18));
  peer.send(JSON.stringify({method:'account/rateLimits/updated',params:{rateLimits:{limitId:'codex',primary:weekly(84)}}}));
  await wait(()=>events.find(e=>e.type==='rateLimits'&&e.usage.weekly?.remainingPercent===16));
  assert.equal((await status()).usage.weekly.remainingPercent,16);
  assert.ok(!JSON.stringify(events).includes('private-account'));
});
