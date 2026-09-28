import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {once} from 'node:events';
import {WebSocketServer} from 'ws';

test('HTTP conversation loading uses metadata-only resume and opaque history pages; outages identify the failed connection',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'pocket-history-http-')),socket=join(dir,'codex.sock');
 const upstream=http.createServer(),wss=new WebSocketServer({server:upstream});await new Promise(r=>upstream.listen(socket,r));
 const allocator=http.createServer();await new Promise(r=>allocator.listen(0,'127.0.0.1',r));const port=allocator.address().port;await new Promise(r=>allocator.close(r));
 const calls=[];let disconnect=false;
 const metadata={id:'large-thread',historyMode:'paginated',name:'Large conversation',status:{type:'idle'},turns:[]};
 wss.on('connection',ws=>ws.on('message',raw=>{
   const m=JSON.parse(raw);if(!m.id)return;calls.push(m);
   if(disconnect&&m.method==='thread/read'){disconnect=false;ws.terminate();return;}
   if(m.method==='thread/read'&&m.params.includeTurns||m.method==='thread/resume'&&!m.params.excludeTurns){ws.close(1009,'Full history is too large');return;}
   let result={};
   if(['thread/read','thread/resume'].includes(m.method))result={thread:metadata};
   if(m.method==='thread/list')result={data:[metadata]};
   if(m.method==='thread/turns/list'){
     const end=m.params.cursor?8:16;result={data:Array.from({length:8},(_,i)=>({id:'turn-'+(end-i-1),status:'completed',items:[]})),nextCursor:end===16?'older+/=':null};
   }
   if(m.method==='thread/items/list')result={data:[{turnId:m.params.turnId,item:{id:'answer',type:'agentMessage',text:m.params.turnId}}]};
   ws.send(JSON.stringify({id:m.id,result}));
 }));
 const child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});
 t.after(async()=>{if(child.exitCode===null){const done=once(child,'exit');child.kill();await done;}for(const ws of wss.clients)ws.terminate();wss.close();await new Promise(r=>upstream.close(r));await rm(dir,{recursive:true,force:true});});
 for(let i=0;i<100;i++){try{if((await fetch(`http://127.0.0.1:${port}/health`)).ok)break;}catch{}await new Promise(r=>setTimeout(r,30));}
 const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json')));
 const api=async path=>{const r=await fetch(`http://127.0.0.1:${port}${path}`,{headers:{Authorization:`Bearer ${adminToken}`}});return {status:r.status,data:await r.json()};};
 const recent=await api('/api/threads/large-thread?view=timeline');assert.equal(recent.status,200);assert.equal(recent.data.timeline.rows[0].turnId,'turn-8');assert.ok(recent.data.timeline.hasEarlier);
 const older=await api('/api/threads/large-thread?view=timeline&before='+encodeURIComponent(recent.data.timeline.before));assert.equal(older.status,200);assert.equal(older.data.timeline.rows[0].turnId,'turn-0');assert.equal(older.data.timeline.hasEarlier,false);
 assert.ok(calls.filter(c=>c.method==='thread/resume').every(c=>c.params.excludeTurns));
 assert.ok(calls.filter(c=>c.method==='thread/read').every(c=>!c.params.includeTurns));
 disconnect=true;const failed=await api('/api/threads/large-thread?view=timeline');assert.equal(failed.status,503);assert.equal(failed.data.code,'CODEX_DISCONNECTED');assert.match(failed.data.error,/phone can reach the workstation/);
 assert.equal((await api('/api/status')).data.connected,false);
 assert.equal((await api('/api/threads')).status,200);assert.equal((await api('/api/status')).data.problem,null);
});
