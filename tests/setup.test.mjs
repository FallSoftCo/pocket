import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {WebSocketServer} from 'ws';
const run=(script,args,env)=>new Promise(resolve=>{
 const p=spawn(process.execPath,[script,...args],{env:{...process.env,...env},stdio:['ignore','pipe','pipe']});let output='';
 p.stdout.on('data',b=>output+=b);p.stderr.on('data',b=>output+=b);p.on('exit',code=>resolve({code,output}));
});
test('preflight checks a fresh runtime without requiring Firebase or a Pocodex server',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'pocket-preflight-')),socket=join(dir,'codex.sock');
 const h=createServer(),wss=new WebSocketServer({server:h});
 wss.on('connection',ws=>ws.on('message',raw=>{const m=JSON.parse(raw);if(m.id)ws.send(JSON.stringify({id:m.id,result:m.method==='thread/list'?{data:[]}: {}}));}));
 await new Promise(r=>h.listen(socket,r));
 writeFileSync(join(dir,'codex'),'#!/bin/sh\necho codex-cli 0.157.1\n',{mode:0o700});
 try{
  const r=await run('scripts/doctor.mjs',['--preflight'],{CODEX_SOCKET:socket,POCKET_DATA:join(dir,'missing'),POCKET_URL:'http://127.0.0.1:1',PATH:dir+':'+process.env.PATH});
  assert.equal(r.code,0,r.output);assert.match(r.output,/Preflight passed/);assert.doesNotMatch(r.output,/FAIL|Firebase client configured|Pocodex backend/);
  writeFileSync(join(dir,'not-a-socket'),'');
  const bad=await run('scripts/doctor.mjs',['--preflight'],{CODEX_SOCKET:join(dir,'not-a-socket'),PATH:dir+':'+process.env.PATH});
  assert.equal(bad.code,1);assert.match(bad.output,/Start an interactive Codex CLI/);assert.doesNotMatch(bad.output,/Preflight passed/);
 }finally{for(const ws of wss.clients)ws.terminate();wss.close();await new Promise(r=>h.close(r));rmSync(dir,{recursive:true,force:true});}
});
test('Firebase setup failures never print supplied credential contents',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'pocket-firebase-'));
 try{
  const path=join(dir,'invalid.json'),marker='synthetic-private-marker-should-not-appear';
  writeFileSync(path,JSON.stringify({type:'service_account',private_key:marker,client_email:'invalid@example.org'}));
  const r=await run('scripts/configure-firebase.mjs',['synthetic-project',path],{POCKET_DATA:join(dir,'state')});
  assert.equal(r.code,1);assert.match(r.output,/Firebase setup failed/);assert.ok(!r.output.includes(marker));assert.doesNotMatch(r.output,/Authorization|Bearer/);
 }finally{rmSync(dir,{recursive:true,force:true});}
});
