import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {once} from 'node:events';
import {DatabaseSync} from 'node:sqlite';
import {WebSocketServer} from 'ws';

test('HTTP conversation loading uses metadata-only resume and opaque history pages; outages identify the failed connection',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'pocket-history-http-')),socket=join(dir,'codex.sock');
 const upstream=http.createServer(),wss=new WebSocketServer({server:upstream});await new Promise(r=>upstream.listen(socket,r));
 const allocator=http.createServer();await new Promise(r=>allocator.listen(0,'127.0.0.1',r));const port=allocator.address().port;await new Promise(r=>allocator.close(r));
 const calls=[];let disconnect=false;const blockedRecovery=[];
 const metadata={id:'large-thread',historyMode:'paginated',name:'Large conversation',status:{type:'idle'},turns:[]};
 wss.on('connection',ws=>ws.on('message',raw=>{
   const m=JSON.parse(raw);if(!m.id)return;calls.push(m);
   if(m.method==='thread/turns/list'&&m.params.itemsView==='summary'){blockedRecovery.push(m);return;}
   if(disconnect&&m.method==='thread/read'){disconnect=false;ws.terminate();return;}
   if(m.method==='thread/read'&&m.params.includeTurns||m.method==='thread/resume'&&!m.params.excludeTurns){ws.close(1009,'Full history is too large');return;}
   let result={};
   if(['thread/read','thread/resume'].includes(m.method))result={thread:m.params.threadId==='cold-thread'?{...metadata,id:'cold-thread',historyMode:undefined}:metadata};
   if(m.method==='thread/list')result={data:m.params.archived?[{...metadata,id:'archived-thread',name:'Archived conversation'}]:[metadata]};
   if(m.method==='thread/turns/list'){
     const end=m.params.cursor?Number(m.params.cursor):16;const limit=m.params.limit;result={data:Array.from({length:Math.min(limit,end)},(_,i)=>({id:'turn-'+(end-i-1),status:'completed',items:[]})),nextCursor:end>limit?String(end-limit):null,backwardsCursor:String(end)};
   }
   if(m.method==='thread/items/list')result={data:[{turnId:m.params.turnId,item:{id:'answer',type:'agentMessage',text:m.params.turnId}}]};
   ws.send(JSON.stringify({id:m.id,result}));
 }));
 const child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});
 t.after(async()=>{if(child.exitCode===null){const done=once(child,'exit');child.kill();await done;}for(const ws of wss.clients)ws.terminate();wss.close();await new Promise(r=>upstream.close(r));await rm(dir,{recursive:true,force:true});});
 for(let i=0;i<100;i++){try{if((await fetch(`http://127.0.0.1:${port}/health`)).ok)break;}catch{}await new Promise(r=>setTimeout(r,30));}
 const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json')));
 const watchDb=new DatabaseSync(join(dir,'pocket.sqlite'));watchDb.prepare('INSERT INTO watches(thread_id,name,enabled) VALUES(?,?,1)').run(metadata.id,'Synthetic watched conversation');watchDb.prepare('INSERT INTO watches(thread_id,name,enabled) VALUES(?,?,1)').run('cold-thread','Synthetic watched cold conversation');watchDb.close();
 const api=async path=>{const r=await fetch(`http://127.0.0.1:${port}${path}`,{headers:{Authorization:`Bearer ${adminToken}`}});return {status:r.status,data:await r.json()};};
 const recent=await api('/api/threads/large-thread?view=timeline');assert.equal(recent.status,200);assert.equal(recent.data.timeline.rows[0].turnId,'turn-15');assert.ok(recent.data.timeline.hasEarlier);
 assert.equal(blockedRecovery.length,1,'recent HTTP response must finish while missed-history recovery is still blocked');
 assert.equal(calls.filter(c=>c.method==='thread/items/list').length,1,'opening must read only the newest item batch');
 let page=recent.data.timeline;const seen=['turn-15'];
 while(page.hasEarlier){
   const older=await api('/api/threads/large-thread?view=timeline&before='+encodeURIComponent(page.before));assert.equal(older.status,200);page=older.data.timeline;
   seen.push(page.rows[0].turnId);
 }
 assert.deepEqual(seen,Array.from({length:16},(_,i)=>'turn-'+(15-i)),'older pages must remain complete and ordered');
 assert.ok(calls.filter(c=>c.method==='thread/resume').every(c=>c.params.excludeTurns));
 assert.ok(calls.filter(c=>c.method==='thread/read').every(c=>!c.params.includeTurns));
 const beforeCold=calls.length;const cold=await api('/api/threads/cold-thread?view=timeline');assert.equal(cold.status,200);assert.equal(cold.data.timeline.rows[0].turnId,'turn-15');assert.ok(calls.slice(beforeCold).every(c=>c.method!=='thread/read'||!c.params.includeTurns),'cold recent attach must not fetch full history when pagination hint is absent');
 const afterColdDb=new DatabaseSync(join(dir,'pocket.sqlite'));afterColdDb.prepare('DELETE FROM watches WHERE thread_id=?').run('cold-thread');afterColdDb.close();
 disconnect=true;const failed=await api('/api/threads/large-thread?view=timeline');assert.equal(failed.status,503);assert.equal(failed.data.code,'CODEX_DISCONNECTED');assert.match(failed.data.error,/phone can reach the workstation/);
 assert.equal((await api('/api/status')).data.connected,false);
 const listing=await api('/api/threads');assert.equal(listing.status,200);assert.ok(listing.data.threads.some(t=>t.id===metadata.id&&t.archived===false));
 const archived=await api('/api/threads?archived=true');assert.equal(archived.status,200);assert.equal(archived.data.threads[0].id,'archived-thread');assert.equal(archived.data.threads[0].archived,true);
 assert.equal((await api('/api/projects')).status,200);
 const listCalls=calls.filter(c=>c.method==='thread/list');assert.ok(listCalls.length>=3);assert.ok(listCalls.every(c=>c.params.useStateDbOnly===true),'interactive lists must bypass expensive JSONL repair scans');
 assert.ok(listCalls.some(c=>c.params.archived===false&&c.params.sourceKinds?.includes('appServer')));assert.ok(listCalls.some(c=>c.params.archived===true&&c.params.sourceKinds?.includes('exec')));assert.ok(listCalls.some(c=>c.params.limit===100&&c.params.archived===false));
 assert.equal((await api('/api/status')).data.problem,null);
});
