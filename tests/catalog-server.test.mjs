import test from 'node:test';import assert from 'node:assert/strict';import {spawn} from 'node:child_process';import {mkdtemp,readFile,rm} from 'node:fs/promises';import {tmpdir} from 'node:os';import {join} from 'node:path';import http from 'node:http';import {once} from 'node:events';import {DatabaseSync} from 'node:sqlite';import {WebSocketServer} from 'ws';
test('catalog HTTP preserves identity, paginates history and reviews unsupported children without resume or reply admission',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'nextcomp-catalog-http-')),socket=join(dir,'codex.sock');const upstream=http.createServer(),wss=new WebSocketServer({server:upstream});await new Promise(r=>upstream.listen(socket,r));
 const allocator=http.createServer();await new Promise(r=>allocator.listen(0,'127.0.0.1',r));const port=allocator.address().port;await new Promise(r=>allocator.close(r));
 const root={id:'root',name:'Recent user task',createdAt:100,recencyAt:200,status:{type:'idle'},historyMode:'paginated',source:'cli',updatedAt:9999};
 const childThread={...root,id:'child',name:'Delegated explorer',canAcceptDirectInput:null,source:{subAgent:{thread_spawn:{parent_thread_id:'root',agent_nickname:'Scout'}}}};
 const old={...root,id:'old',name:'Dormant history',recencyAt:110};const calls=[];
 wss.on('connection',ws=>ws.on('message',raw=>{const m=JSON.parse(raw);if(!m.id)return;calls.push(m);let result={};
  if(m.method==='thread/list')result=m.params.cursor?{data:[old],nextCursor:null}:{data:[root,childThread],nextCursor:'next'};
  if(m.method==='thread/read')result={thread:m.params.threadId==='child'?childThread:m.params.threadId==='old'?old:root};
  if(m.method==='thread/turns/list')result={data:[{id:'finished',status:'completed',items:[]}],nextCursor:null};
  if(m.method==='thread/items/list')result={data:[{turnId:'finished',item:{id:'result',type:'agentMessage',text:'Useful delegated result'}}],nextCursor:null};
  ws.send(JSON.stringify({id:m.id,result}));}));
 const processChild=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});
 t.after(async()=>{if(processChild.exitCode===null){const done=once(processChild,'exit');processChild.kill();await done;}for(const ws of wss.clients)ws.terminate();wss.close();await new Promise(r=>upstream.close(r));await rm(dir,{recursive:true,force:true});});
 for(let i=0;i<100;i++){try{if((await fetch(`http://127.0.0.1:${port}/health`)).ok)break;}catch{}await new Promise(r=>setTimeout(r,30));}
 const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json')));const api=async(path,body)=>{const response=await fetch(`http://127.0.0.1:${port}${path}`,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${adminToken}`,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});return {status:response.status,data:await response.json()};};
 const listing=await api('/api/threads');assert.equal(listing.status,200);const c=listing.data.threads.find(x=>x.id==='child');assert.equal(c.parentThreadId,'root');assert.equal(c.canAcceptDirectInput,false);assert.equal(c.isChild,true);assert.equal(c.recencyAt,200000);assert.equal(listing.data.threads.find(x=>x.id==='old').recencyAt,110000);
 const before=calls.length;const review=await api('/api/threads/child?view=timeline');assert.equal(review.status,200);assert.ok(review.data.timeline.rows.some(x=>x.text==='Useful delegated result'));assert.ok(calls.slice(before).every(x=>x.method!=='thread/resume'));assert.equal(review.data.thread.canAcceptDirectInput,false);assert.equal(review.data.thread.parentThreadId,'root');assert.equal(review.data.thread.recencyAt,200000);
 const reply=await api('/api/threads/child/reply',{id:'child-intent',text:'Unsupported direct guidance',mode:'steer'});assert.equal(reply.status,409);assert.match(reply.data.error,/parent task/);
 const voice=await api('/api/voice/start',{threadId:'child'});assert.equal(voice.status,409);assert.match(voice.data.error,/parent task/);assert.ok(calls.every(x=>x.method!=='thread/start'));
 const db=new DatabaseSync(join(dir,'pocket.sqlite'));assert.equal(db.prepare('SELECT count(*) AS n FROM outgoing').get().n,0);db.close();
 const page=await api('/api/threads?cursor=next');assert.equal(page.status,200);assert.equal(page.data.threads[0].id,'old');assert.equal(page.data.nextCursor,null);
 const coordinator=await api('/api/threads?view=coordinator');assert.equal(coordinator.data.threads.find(t=>t.id==='child').parentThreadId,'root');assert.equal(coordinator.data.threads.find(t=>t.id==='child').recencyAt,200000);
 const search=await api('/api/threads?search=Dormant');assert.equal(search.status,200);assert.equal(search.data.threads.find(t=>t.id==='child').parentThreadId,'root');assert.equal(search.data.threads.find(t=>t.id==='child').canAcceptDirectInput,false);assert.ok(calls.some(x=>x.params.searchTerm==='Dormant'));assert.ok(calls.filter(x=>x.method==='thread/list').every(x=>x.params.sortKey==='recency_at'));assert.ok(calls.every(x=>!['turn/start','turn/steer','turn/interrupt','thread/settings/update'].includes(x.method)));
});
