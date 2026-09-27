import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {once} from 'node:events';
import {WebSocketServer} from 'ws';
const wait=async fn=>{for(let i=0;i<150;i++){if(await fn())return;await new Promise(r=>setTimeout(r,60));}throw Error('Recovery did not complete');};

test('bridge restart backfills missed completion, survives replay and keeps working past an inaccessible watch',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'pocket-restart-')),socket=join(dir,'codex.sock');
 const h=http.createServer(),wss=new WebSocketServer({server:h});await new Promise(r=>h.listen(socket,r));
 const allocator=http.createServer();await new Promise(r=>allocator.listen(0,'127.0.0.1',r));const port=allocator.address().port;await new Promise(r=>allocator.close(r));
 let completed=false,peer,child,turnId='turn-one';let resumes=0;
 const turn=()=>({id:turnId,status:completed?'completed':'inProgress',items:completed?[{id:'answer',type:'agentMessage',text:'Recovery passed.'}]:[]});
 wss.on('connection',ws=>{peer=ws;ws.on('message',raw=>{const m=JSON.parse(raw);if(!m.id)return;
 if(m.params?.threadId==='broken-thread'){ws.send(JSON.stringify({id:m.id,error:{message:'Task unavailable'}}));return;}
 if(m.method==='thread/resume')resumes++;
 ws.send(JSON.stringify({id:m.id,result:m.method.startsWith('thread/')?{thread:{id:'thread-one',status:{type:completed?'idle':'active'},turns:[turn()]}}:{}}));
 });});
 const stop=async()=>{if(child&&child.exitCode===null){const done=once(child,'exit');child.kill();await done;}};
 t.after(async()=>{await stop();for(const ws of wss.clients)ws.terminate();wss.close();await new Promise(r=>h.close(r));await rm(dir,{recursive:true,force:true});});
 const start=async()=>{child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});await wait(async()=>{try{return (await fetch(`http://127.0.0.1:${port}/health`)).ok;}catch{return false;}});};
 await start();const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json')));
 const api=async(path,body)=>{const r=await fetch(`http://127.0.0.1:${port}${path}`,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${adminToken}`,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});assert.ok(r.ok);return r.json();};
 await api('/api/threads/thread-one/watch',{enabled:true});assert.equal((await api('/api/notifications')).notifications.length,0);
 await stop();
 // Insert an inaccessible older watch to ensure it cannot block other recovery.
 const {openStore}=await import('../server/store.mjs');const {db}=openStore(dir);const watch=db.prepare('SELECT * FROM watches WHERE thread_id=?').get('thread-one');db.prepare('DELETE FROM watches WHERE thread_id=?').run('thread-one');db.prepare('INSERT INTO watches VALUES(?,?,1)').run('broken-thread','Missing');db.prepare('INSERT INTO watches VALUES(?,?,1)').run(watch.thread_id,watch.name);db.close();
 completed=true;await start();
 await wait(async()=>(await api('/api/notifications')).notifications.length===1);
 let rows=(await api('/api/notifications')).notifications;assert.equal(rows[0].body,'Recovery passed.');assert.ok(rows[0].spoken_summary);
 peer.send(JSON.stringify({method:'turn/completed',params:{threadId:'thread-one',turn:turn()}}));
 const before=resumes;await stop();await start();await wait(()=>resumes>before);
 rows=(await api('/api/notifications')).notifications;assert.equal(rows.length,1,'restart and live replay must not duplicate the completion');
 // A socket-only outage must recover without restarting the Pocket process.
 turnId='turn-during-disconnect';peer.terminate();
 await wait(async()=>(await api('/api/notifications')).notifications.length===2);
 assert.equal((await api('/api/notifications')).notifications.at(-1).source_turn_id,turnId);
});
