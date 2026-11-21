import test from 'node:test';import assert from 'node:assert/strict';
import http from 'node:http';import {WebSocketServer}from'ws';import{mkdtemp,rm}from'node:fs/promises';import{tmpdir}from'node:os';import{join}from'node:path';
import{Codex}from'../server/codex.mjs';import{ImmersionRuntime}from'../server/immersion-runtime.mjs';
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function fixture(t,{lateStart=0,lateTurn=0,holdUnload=false,loseStartup=false}={}){
 const dir=await mkdtemp(join(tmpdir(),'immersion-wire-')),socket=join(dir,'rpc.sock'),server=http.createServer(),wsServer=new WebSocketServer({server});await new Promise(r=>server.listen(socket,r));
 const calls=[],loaded=new Set();let n=0,lastSocket;
 const event=(method,params)=>lastSocket.send(JSON.stringify({method,params}));
 wsServer.on('connection',ws=>{lastSocket=ws;ws.on('message',async raw=>{const m=JSON.parse(raw);if(m.id===undefined)return;calls.push(m);let result={};
 if(m.method==='config/read')result={config:{mcp_servers:{irrelevant:{url:'https://not-used.invalid',secret:'not copied'}}}};
 if(m.method==='thread/start'){await delay(lateStart);const id='translator-'+(++n);loaded.add(id);if(loseStartup){ws.close();return;}result={thread:{id}};}
 if(m.method==='thread/unsubscribe'){if(!holdUnload)loaded.delete(m.params.threadId);result={status:'unsubscribed'};}
 if(m.method==='thread/loaded/list')result={data:[...loaded]};
 if(m.method==='turn/start'){await delay(lateTurn);result={turn:{id:'turn-'+m.id}};}
 if(ws.readyState===1)ws.send(JSON.stringify({id:m.id,result}));
 if(m.method==='turn/interrupt'){event('turn/completed',{threadId:m.params.threadId,turn:{id:m.params.turnId,status:'interrupted'}});}
 });});
 const codex=new Codex(socket,{requestTimeoutMs:25}),runtime=new ImmersionRuntime(codex);
 t.after(async()=>{await runtime.close();codex.closeTransport();for(const ws of wsServer.clients)ws.terminate();await new Promise(r=>wsServer.close(r));await new Promise(r=>server.close(r));await rm(dir,{recursive:true,force:true});});
 return {runtime,codex,calls,loaded,event};
}
test('late original startup remains owned; disabling cannot submit or create a replacement',async t=>{
 const f=await fixture(t,{lateStart:100});let wanted=true;f.runtime.wanted=()=>wanted;
 const run=f.runtime.run({cwd:'/owned',ephemeral:true},{input:[]},1000);const rejected=assert.rejects(run,/disabled/);
 await delay(60);assert.equal(f.calls.filter(x=>x.method==='thread/start').length,1);
 wanted=false;f.runtime.cancel();await rejected;await f.runtime.release();
 assert.equal(f.calls.filter(x=>x.method==='turn/start').length,0);assert.equal(f.loaded.size,0);assert.equal(f.codex.pending.size,0);
 const params=f.calls.find(x=>x.method==='thread/start').params;
 assert.deepEqual(params.config.mcp_servers,{irrelevant:{enabled:false}});assert.equal(params.config.thread_unload_delay_secs,0);assert.equal(params.config['apps._default.enabled'],false);
});
test('late turn acknowledgement receives one exact interrupt and no replacement inference',async t=>{
 const f=await fixture(t,{lateTurn:120});
 await assert.rejects(f.runtime.run({cwd:'/owned',ephemeral:true},{input:[]},30),/timed out/);
 assert.equal(f.runtime.active.cancelled,true);
 await assert.rejects(f.runtime.run({cwd:'/owned'},{input:[]},30),/settling/);
 for(let i=0;i<60&&f.runtime.active;i++)await delay(5);
 if(f.runtime.releasing)await f.runtime.releasing;
 assert.equal(f.runtime.active,null);assert.equal(f.loaded.size,0);
 assert.equal(f.calls.filter(x=>x.method==='turn/start').length,1);
 const sent=f.calls.find(x=>x.method==='turn/start'),interrupt=f.calls.filter(x=>x.method==='turn/interrupt');assert.equal(interrupt.length,1);assert.equal(interrupt[0].params.turnId,'turn-'+sent.id);
});
test('rotation releases native residency before allocating another thread',async t=>{
 const f=await fixture(t);const first=await f.runtime.ensure({cwd:'/owned',ephemeral:true});f.runtime.turns=20;
 const second=await f.runtime.ensure({cwd:'/owned',ephemeral:true});assert.notEqual(first,second);assert.deepEqual([...f.loaded],[second]);
 const unsub=f.calls.findIndex(x=>x.method==='thread/unsubscribe'),starts=f.calls.map((x,i)=>x.method==='thread/start'?i:-1).filter(x=>x>=0);assert.ok(unsub>starts[0]&&unsub<starts[1]);await f.runtime.release();assert.equal(f.loaded.size,0);
});
test('acknowledged unsubscribe with delayed unload cannot be reused or replaced',async t=>{
 const f=await fixture(t,{holdUnload:true});const id=await f.runtime.ensure({cwd:'/owned',ephemeral:true});
 await assert.rejects(f.runtime.release(),/still unloading/);
 await assert.rejects(f.runtime.run({cwd:'/owned'},{input:[]},30),/still unloading/);
 assert.equal(f.calls.filter(x=>x.method==='thread/start').length,1);
 assert.equal(f.calls.filter(x=>x.method==='turn/start').length,0);
 f.loaded.delete(id);await f.runtime.release();
 assert.equal(f.runtime.thread,null);
});
test('lost startup reply does not authorize another native thread',async t=>{
 const f=await fixture(t,{loseStartup:true});
 await assert.rejects(f.runtime.ensure({cwd:'/owned',ephemeral:true}));
 await assert.rejects(f.runtime.ensure({cwd:'/owned',ephemeral:true}),/startup is unresolved/);
 assert.equal(f.calls.filter(x=>x.method==='thread/start').length,1);
});
