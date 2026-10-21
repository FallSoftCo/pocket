import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {once} from 'node:events';
import {WebSocketServer} from 'ws';

test('HTTP bounded heads preserve complete-turn ordering evidence across reopen without submitting work',async t=>{
 const dir=await mkdtemp(join(tmpdir(),'nextcomp-order-http-')),socket=join(dir,'codex.sock');
 const upstream=http.createServer(),wss=new WebSocketServer({server:upstream});
 await new Promise(r=>upstream.listen(socket,r));
 const allocator=http.createServer();await new Promise(r=>allocator.listen(0,'127.0.0.1',r));
 const port=allocator.address().port;await new Promise(r=>allocator.close(r));
 const calls=[];
 const metadata={id:'ordering-thread',historyMode:'paginated',status:{type:'idle'},turns:[]};
 const items=[{id:'canonical-input',type:'userMessage',clientId:'send',content:[{type:'text',text:'Synthetic input'}]},
 ...Array.from({length:450},(_,i)=>({id:'answer-'+i,type:'agentMessage',text:'Synthetic answer '+i}))];
 wss.on('connection',ws=>ws.on('message',raw=>{
  const m=JSON.parse(raw);if(!m.id)return;calls.push(m.method);
  let result={};
  if(m.method==='thread/read')result={thread:metadata};
  if(m.method==='thread/list')result={data:[]};
  if(m.method==='thread/turns/list')result={data:[{id:'ordering-turn',status:'completed',items:m.params.itemsView==='full'?items:[]}],backwardsCursor:'anchor'};
  if(m.method==='thread/items/list'){ws.send(JSON.stringify({id:m.id,error:{code:-32601,message:'thread/items/list is not supported'}}));return;}
  ws.send(JSON.stringify({id:m.id,result}));
 }));
 const child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:String(port),POCKET_DATA:dir,CODEX_SOCKET:socket},stdio:'ignore'});
 t.after(async()=>{if(child.exitCode===null){const done=once(child,'exit');child.kill();await done;}
 for(const ws of wss.clients)ws.terminate();wss.close();await new Promise(r=>upstream.close(r));await rm(dir,{recursive:true,force:true});});
 for(let i=0;i<100;i++){try{if((await fetch('http://127.0.0.1:'+port+'/health')).ok)break;}catch{}await new Promise(r=>setTimeout(r,30));}
 const {adminToken}=JSON.parse(await readFile(join(dir,'secrets.json')));
 const api=async(path,body)=>{const r=await fetch('http://127.0.0.1:'+port+path,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+adminToken,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});assert.equal(r.status,200);return r.json();};
 for(let n=0;n<2;n++){
  const page=await api('/api/threads/ordering-thread/head');
  const rows=page.timeline.rows;
  assert.ok(!rows.some(row=>row.kind==='user'),'Test must actually exclude the input from its bounded head');
  const known=rows.filter(row=>row.kind==='message').map(row=>row.itemId);
  assert.ok(known.length>0);
  const before=calls.length;
  const position=await api('/api/threads/ordering-thread/timeline/position',{turnId:'ordering-turn',itemId:'live-input-id',clientId:'send',knownItemIds:known});
  assert.deepEqual(position,{available:true,insertBefore:'ordering-turn/'+known[0],cursor:null});
  assert.equal(calls.length,before,'Ordering lookup must use retained evidence without provider reads');
 }
 assert.ok(!calls.includes('thread/resume')&&!calls.includes('turn/start')&&!calls.includes('turn/steer'));
});
