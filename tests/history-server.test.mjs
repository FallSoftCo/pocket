import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {once} from 'node:events';
import {DatabaseSync} from 'node:sqlite';
import {WebSocketServer,WebSocket} from 'ws';

test('HTTP conversation loading stays read-only with opaque history pages; outages identify the failed connection',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'pocket-history-http-')),socket=join(dir,'codex.sock');
 const upstream=http.createServer(),wss=new WebSocketServer({server:upstream});await new Promise(r=>upstream.listen(socket,r));
 const allocator=http.createServer();await new Promise(r=>allocator.listen(0,'127.0.0.1',r));const port=allocator.address().port;await new Promise(r=>allocator.close(r));
 const calls=[];let disconnect=false,holdRead=null;const blockedRecovery=[],readFaults=new Map(),readMetadata=new Map();
 const metadata={id:'large-thread',historyMode:'paginated',name:'Large conversation',status:{type:'idle'},turns:[]};
 wss.on('connection',ws=>ws.on('message',raw=>{
   const m=JSON.parse(raw);if(!m.id)return;calls.push(m);
   if(m.method==='thread/read'&&holdRead?.threadId===m.params.threadId){
    const held=holdRead,thread=structuredClone(readMetadata.get(m.params.threadId));holdRead=null;
    held.release=()=>ws.send(JSON.stringify({id:m.id,result:{thread}}));held.started();return;
   }
   const fault=readFaults.get(m.method+':'+m.params?.threadId);
   if(fault){ws.send(JSON.stringify({id:m.id,error:fault}));return;}
   if(m.method==='thread/turns/list'&&m.params.itemsView==='summary'){blockedRecovery.push(m);return;}
   if(disconnect&&m.method==='thread/read'){disconnect=false;ws.terminate();return;}
   if(m.method==='thread/read'&&m.params.includeTurns||m.method==='thread/resume'&&!m.params.excludeTurns){ws.close(1009,'Full history is too large');return;}
   if(m.method==='thread/timeline/list'){ws.send(JSON.stringify({id:m.id,error:{code:-32601,message:'thread/timeline/list is not supported yet'}}));return;}
   let result={};
   if(['thread/read','thread/resume'].includes(m.method))result={thread:readMetadata.get(m.params.threadId)||(m.params.threadId==='cold-thread'?{...metadata,id:'cold-thread',historyMode:undefined}:metadata)};
   if(m.method==='thread/list')result={data:m.params.archived?[{...metadata,id:'archived-thread',name:'Archived conversation'}]:[metadata]};
   if(m.method==='thread/turns/list'){
     const end=m.params.cursor?Number(m.params.cursor):16;const limit=m.params.limit;result={data:Array.from({length:Math.min(limit,end)},(_,i)=>({id:'turn-'+(end-i-1),status:'completed',items:[]})),nextCursor:end>limit?String(end-limit):null,backwardsCursor:String(end)};
   }
   if(m.method==='thread/items/list')result={data:[{turnId:m.params.turnId,item:{id:'answer',type:'agentMessage',text:m.params.turnId}}]};
   ws.send(JSON.stringify({id:m.id,result}));
 }));
 const child=spawn(process.execPath,['server/index.mjs'],{env:{PATH:process.env.PATH,HOME:dir,CODEX_HOME:dir,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});
 t.after(async()=>{if(child.exitCode===null){const done=once(child,'exit');child.kill();await done;}for(const ws of wss.clients)ws.terminate();wss.close();await new Promise(r=>upstream.close(r));await rm(dir,{recursive:true,force:true});});
 for(let i=0;i<100;i++){try{if((await fetch(`http://127.0.0.1:${port}/health`)).ok)break;}catch{}await new Promise(r=>setTimeout(r,30));}
 const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json')));
 const watchDb=new DatabaseSync(join(dir,'pocket.sqlite'));watchDb.prepare('INSERT INTO watches(thread_id,name,enabled) VALUES(?,?,1)').run(metadata.id,'Synthetic watched conversation');watchDb.prepare('INSERT INTO watches(thread_id,name,enabled) VALUES(?,?,1)').run('cold-thread','Synthetic watched cold conversation');watchDb.close();
 const api=async path=>{const r=await fetch(`http://127.0.0.1:${port}${path}`,{headers:{Authorization:`Bearer ${adminToken}`}});return {status:r.status,data:await r.json()};};
 const phone=new WebSocket('ws://127.0.0.1:'+port+'/events',{headers:{Authorization:'Bearer '+adminToken}});
 const live=[];phone.on('message',raw=>live.push({at:Date.now(),value:JSON.parse(raw)}));await once(phone,'open');t.after(()=>phone.terminate());
 // No HTTP open/resume: raw app-server output must reach the phone immediately.
 const beforeUnopened=calls.filter(c=>['thread/resume','thread/turns/list','thread/items/list','turn/start'].includes(c.method)).length;
 const sentAt=Date.now();
 for(const ws of wss.clients){
  ws.send(JSON.stringify({method:'item/started',params:{threadId:'unopened-thread',turnId:'live-turn',item:{id:'live-answer',type:'agentMessage',text:'Fast'}}}));
  ws.send(JSON.stringify({method:'item/agentMessage/delta',params:{threadId:'unopened-thread',turnId:'live-turn',itemId:'live-answer',delta:' response'}}));
 }
 for(let n=0;n<100&&!live.some(e=>e.value.type==='timeline'&&e.value.threadId==='unopened-thread');n++)await new Promise(r=>setTimeout(r,10));
 const forwarded=live.find(e=>e.value.type==='timeline'&&e.value.threadId==='unopened-thread');
 assert.ok(forwarded,'Unopened session output was gated on an attached viewport');
 assert.equal(forwarded.value.row.text,'Fast response');
 assert.ok(forwarded.at-sentAt<1000,'Forwarding waited for history/runtime reads');
 assert.equal(calls.filter(c=>['thread/resume','thread/turns/list','thread/items/list','turn/start'].includes(c.method)).length,beforeUnopened,'Public forwarding started history or model work');
 const nativeRefusal=await api('/api/threads/large-thread/native-timeline');assert.equal(nativeRefusal.status,409);assert.equal(nativeRefusal.data.code,'NATIVE_TIMELINE_UNSUPPORTED');
 const recent=await api('/api/threads/large-thread?view=timeline');assert.equal(recent.status,200);assert.equal(recent.data.timeline.rows[0].turnId,'turn-15');assert.ok(recent.data.timeline.hasEarlier);
 assert.equal(blockedRecovery.length,0,'A read-only recent projection must not start an unrelated summary recovery');
 const historyCount=()=>calls.filter(c=>/^thread\/(read|resume|turns\/list|items\/list)$/.test(c.method)).length;
 const beforeProbe=historyCount();const headStatus=await api('/api/status?threadId=large-thread');
 assert.equal(headStatus.status,200);assert.deepEqual(headStatus.data.historyHead,recent.data.historyHead);
 assert.equal(historyCount(),beforeProbe,'status head must not read or resume Codex history');
 for(const ws of wss.clients)ws.send(JSON.stringify({method:'item/completed',params:{threadId:'large-thread',turnId:'turn-15',item:{id:'answer',type:'agentMessage',text:'Synthetic changed answer'}}}));
 let changedHead;
 for(let n=0;n<20;n++){changedHead=(await api('/api/status?threadId=large-thread')).data.historyHead;if(changedHead.version!==null)break;await new Promise(resolve=>setTimeout(resolve,10));}
 assert.equal(changedHead.epoch,recent.data.historyHead.epoch);assert.ok(changedHead.version>0,'actual runtime event must advance selected history head');
 assert.equal(historyCount(),beforeProbe,'event freshness probes must not read history or resume work');
 assert.deepEqual((await api('/api/status?threadId=large-thread')).data.historyHead,changedHead,'unchanged status cannot invent another update');
 for(const ws of wss.clients)ws.send(JSON.stringify({method:'item/reasoning/textDelta',params:{threadId:'large-thread',turnId:'turn-15',itemId:'uncached-private-item',delta:'Synthetic private event'}}));
 await new Promise(resolve=>setTimeout(resolve,30));
 assert.deepEqual((await api('/api/status?threadId=large-thread')).data.historyHead,changedHead,'unsupported private events must not invent display freshness');
 assert.equal(historyCount(),beforeProbe,'unsupported events cannot request history work');


 assert.equal((await api('/api/status?threadId=invalid%20thread')).status,400);
 const denied=await fetch(`http://127.0.0.1:${port}/api/status?threadId=large-thread`);assert.equal(denied.status,401);

 assert.equal(calls.filter(c=>c.method==='thread/items/list').length,1,'opening must read only the newest item batch');
 let page=recent.data.timeline;const seen=['turn-15'];
 while(page.hasEarlier){
   const older=await api('/api/threads/large-thread?view=timeline&before='+encodeURIComponent(page.before));assert.equal(older.status,200);page=older.data.timeline;
   seen.push(page.rows[0].turnId);
 }
 assert.deepEqual(seen,Array.from({length:16},(_,i)=>'turn-'+(15-i)),'older pages must remain complete and ordered');
 assert.equal(calls.filter(c=>c.method==='thread/resume').length,0,'Read-only paging must never attach or resume the thread');
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

 // Exercise the production route, native RPC transport and retained catalog:
 // unavailable history must never become an empty successful transcript.
 const evidenceDb=new DatabaseSync(join(dir,'pocket.sqlite'));t.after(()=>evidenceDb.close());
 const cached=(id,ephemeral,path=null)=>{
  const thread={...metadata,id,ephemeral,path};readMetadata.set(id,thread);
  evidenceDb.prepare('INSERT INTO pocket_discovered_threads VALUES(?,?,?,?)').run(id,JSON.stringify(thread),path?.includes('/archived_sessions/')?1:0,1);return thread;
 };
 cached('temporary-thread',true);cached('durable-thread',false,'/missing/source.jsonl');
 cached('archived-readable',false,'/fixture/archived_sessions/source.jsonl');
 const failedReadCount=calls.length;
 for(const [id,code,kind] of [['temporary-thread','THREAD_EPHEMERAL_UNAVAILABLE','ephemeralUnavailable'],['durable-thread','THREAD_HISTORY_UNAVAILABLE','historyUnavailable'],['unknown-thread','THREAD_HISTORY_UNAVAILABLE','historyUnavailable']]){
  readFaults.set('thread/read:'+id,{code:-32600,message:`thread not loaded: ${id}`});
  for(const suffix of ['/native-timeline','/head','?view=timeline']){
   const response=await api('/api/threads/'+id+suffix);assert.equal(response.status,409);assert.equal(response.data.code,code);
   assert.deepEqual(response.data.readAvailability,{threadId:id,kind,operation:'thread/read'});
   assert.equal(response.data.timeline,undefined);assert.equal(response.data.catchup,undefined);assert.ok(!JSON.stringify(response.data).includes('/missing/'));
  }
 }
 assert.ok(calls.slice(failedReadCount).every(c=>c.method==='thread/read'),'metadata refusal must not dispatch page, resume, or work RPCs');
 assert.equal(evidenceDb.prepare('SELECT count(*) AS n FROM pocket_discovered_threads WHERE thread_id IN (?,?)').get('durable-thread','temporary-thread').n,2);
 // A later successful native read is immediately usable; no terminal cache or
 // inferred permanent loss may suppress it.
 readFaults.delete('thread/read:durable-thread');
 const recovered=await api('/api/threads/durable-thread/head');assert.equal(recovered.status,200);assert.equal(recovered.data.thread.id,'durable-thread');assert.ok(recovered.data.timeline.rows.length>0);
 // The runtime can unload between metadata and page reads. Preserve the
 // failure's phase instead of classifying the already successful metadata.
 readFaults.delete('thread/read:temporary-thread');
 readFaults.set('thread/timeline/list:temporary-thread',{code:-32600,message:'thread not loaded: temporary-thread'});
 const pageUnavailable=await api('/api/threads/temporary-thread/native-timeline');assert.equal(pageUnavailable.status,409);assert.equal(pageUnavailable.data.code,'THREAD_EPHEMERAL_UNAVAILABLE');assert.equal(pageUnavailable.data.readAvailability.operation,'thread/timeline/list');
 readFaults.set('thread/turns/list:durable-thread',{code:-32600,message:'thread not loaded: durable-thread'});
 const legacyUnavailable=await api('/api/threads/durable-thread/head');assert.equal(legacyUnavailable.status,409);assert.equal(legacyUnavailable.data.code,'THREAD_HISTORY_UNAVAILABLE');assert.equal(legacyUnavailable.data.readAvailability.operation,'history/read');assert.equal(legacyUnavailable.data.timeline,undefined);
 readFaults.delete('thread/turns/list:durable-thread');
 // Native chronology refuses an archive, but the ordinary read-only history
 // path still returns its rows without a forbidden catch-up capture.
 readFaults.set('thread/timeline/list:archived-readable',{code:-32600,message:'thread archived-readable is archived'});
 const archivedNative=await api('/api/threads/archived-readable/native-timeline');assert.equal(archivedNative.status,409);assert.equal(archivedNative.data.code,'THREAD_ARCHIVED');
 assert.deepEqual(archivedNative.data.readAvailability,{threadId:'archived-readable',kind:'archived',operation:'thread/timeline/list'});
 const captureCount=()=>evidenceDb.prepare('SELECT count(*) AS n FROM session_catchup_captures WHERE thread_id=?').get('archived-readable').n;
 const beforeCapture=captureCount();
 for(const suffix of ['/head','?view=timeline']){
  const readable=await api('/api/threads/archived-readable'+suffix);assert.equal(readable.status,200);assert.equal(readable.data.thread.id,'archived-readable');assert.ok(readable.data.timeline.rows.length>0);assert.equal(readable.data.catchup,undefined);
 }
 assert.equal(captureCount(),beforeCapture);assert.equal(evidenceDb.prepare('SELECT archived FROM pocket_discovered_threads WHERE thread_id=?').get('archived-readable').archived,1);
 // An ordinary read started before an explicit archive must not undo the
 // confirmed archive when its old metadata finally arrives.
 cached('archive-during-read',false,'/fixture/sessions/source.jsonl');
 let started;const readStarted=new Promise(resolve=>started=resolve);
 const held={threadId:'archive-during-read',started};holdRead=held;
 const staleHead=api('/api/threads/archive-during-read/head');
 let timer;try{await Promise.race([readStarted,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('Metadata read did not reach barrier')),2000);})]);}finally{clearTimeout(timer);}
 const archiveResponse=await fetch(`http://127.0.0.1:${port}/api/threads/archive-during-read/archive`,{method:'POST',headers:{Authorization:`Bearer ${adminToken}`,'Content-Type':'application/json'},body:'{}'});
 assert.equal(archiveResponse.status,200);assert.equal(evidenceDb.prepare('SELECT archived FROM pocket_discovered_threads WHERE thread_id=?').get('archive-during-read').archived,1);
 held.release();const staleReadable=await staleHead;
 assert.equal(staleReadable.status,200);assert.equal(staleReadable.data.thread.id,'archive-during-read');assert.ok(staleReadable.data.timeline.rows.length>0);assert.equal(staleReadable.data.catchup,undefined);
 assert.equal(evidenceDb.prepare('SELECT archived FROM pocket_discovered_threads WHERE thread_id=?').get('archive-during-read').archived,1);
 assert.equal(evidenceDb.prepare('SELECT count(*) AS n FROM session_catchup_captures WHERE thread_id=?').get('archive-during-read').n,0);
 assert.equal(calls.filter(c=>c.method==='thread/archive'&&c.params.threadId==='archive-during-read').length,1,'only the explicit synthetic action may archive');
 // Auth failures and mismatched identities are not lifecycle evidence.
 readFaults.set('thread/read:durable-thread',{code:-32001,message:'thread not loaded: durable-thread'});
 let other=await api('/api/threads/durable-thread/head');assert.equal(other.status,400);assert.equal(other.data.code,undefined);assert.equal(other.data.readAvailability,undefined);
 readFaults.set('thread/read:durable-thread',{code:-32600,message:'thread not loaded: another-thread'});
 other=await api('/api/threads/durable-thread/native-timeline');assert.equal(other.status,400);assert.equal(other.data.code,undefined);assert.equal(other.data.readAvailability,undefined);
 assert.ok(calls.slice(failedReadCount).every(c=>!['thread/resume','thread/unarchive','turn/start','turn/steer'].includes(c.method)));
});
