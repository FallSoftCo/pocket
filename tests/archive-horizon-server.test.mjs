import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {once} from 'node:events';
import http from 'node:http';
import {WebSocketServer} from 'ws';
import {archiveInactive} from '../scripts/archive-inactive-sessions.mjs';
test('real HTTP cleanup inventories delegated pages, ignores metadata churn and removes archived work from all list pages',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'nextcomp-archive-horizon-')),socket=join(dir,'native.sock');
 const upstream=http.createServer(),wss=new WebSocketServer({server:upstream});await new Promise(r=>upstream.listen(socket,r));
 const allocation=http.createServer();await new Promise(r=>allocation.listen(0,'127.0.0.1',r));const port=allocation.address().port;await new Promise(r=>allocation.close(r));
 const cutoff=Date.now()-30*86400000;
 const old={status:{type:'notLoaded'},createdAt:Math.floor(cutoff/1000)-1000,recencyAt:Math.floor(cutoff/1000)-100,updatedAt:Math.floor(Date.now()/1000)};
 const rows=[{...old,id:'old-parent',name:'Old parent'},{...old,id:'old-child',source:{subAgent:{thread_spawn:{parent_thread_id:'old-parent'}}},name:'Old child'},{...old,id:'current',recencyAt:Math.floor(Date.now()/1000),status:{type:'active'},name:'Working'}];
 const archived=new Set(),calls=[];
 wss.on('connection',ws=>ws.on('message',raw=>{const m=JSON.parse(raw);if(!m.id)return;calls.push(m);let result={};
  if(m.method==='thread/list'){
   const visible=rows.filter(r=>archived.has(r.id)===(m.params.archived===true)&&(!r.source||m.params.sourceKinds?.includes('subAgentThreadSpawn')));
   const offset=Number(m.params.cursor||0);result={data:visible.slice(offset,offset+1),nextCursor:offset+1<visible.length?String(offset+1):null};
  }else if(m.method==='thread/read')result={thread:rows.find(r=>r.id===m.params.threadId)};
  else if(m.method==='thread/archive')archived.add(m.params.threadId);
  ws.send(JSON.stringify({id:m.id,result}));
 }));
 const child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});
 t.after(async()=>{if(child.exitCode===null){const done=once(child,'exit');child.kill();await done;}for(const ws of wss.clients)ws.terminate();wss.close();await new Promise(r=>upstream.close(r));await rm(dir,{recursive:true,force:true});});
 for(let n=0;n<100;n++){try{if((await fetch(`http://127.0.0.1:${port}/health`)).ok)break;}catch{}await new Promise(r=>setTimeout(r,30));}
 const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json')));
 const request=async(path,{method='GET',body}={})=>{const r=await fetch(`http://127.0.0.1:${port}${path}`,{method,headers:{Authorization:'Bearer '+adminToken,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});const data=await r.json();if(!r.ok)throw Object.assign(Error(data.error),{status:r.status});return data;};
 const result=await archiveInactive({request,cutoff,apply:true,save:()=>{}});
 assert.equal(result.inventory,3);assert.deepEqual([...archived].sort(),['old-child','old-parent']);
 const page=await request('/api/threads');assert.deepEqual(page.threads.map(r=>r.id),['current']);assert.equal(page.nextCursor,null);
 assert.equal(calls.filter(c=>['thread/resume','turn/start','turn/steer'].includes(c.method)).length,0);
 assert.ok(calls.filter(c=>c.method==='thread/read'&&archived.has(c.params.threadId)).every(c=>c.params.includeTurns===false));
});
