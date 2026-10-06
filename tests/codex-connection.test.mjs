import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {WebSocketServer} from 'ws';
import {Codex} from '../server/codex.mjs';
import {isHistoryLineageError,historyLineageError,ambiguousDelivery} from '../server/connection-errors.mjs';

test('draining RPC exposes restart state, lets existing events finish, and recovers without replay',async t=>{
  const dir=await mkdtemp(join(tmpdir(),'pocket-drain-'));const socket=join(dir,'codex.sock');
  const server=http.createServer(),wss=new WebSocketServer({server});await new Promise(r=>server.listen(socket,r));
  let draining=true,writes=0;
  wss.on('connection',ws=>ws.on('message',raw=>{
    const m=JSON.parse(raw);if(!m.id)return;
    if(m.method==='turn/start'){
      writes++;
      if(draining){ws.send(JSON.stringify({id:m.id,error:{code:-32600,message:'Server is draining; retry after reconnecting'}}));return;}
    }
    if(m.method==='history'){ws.send(JSON.stringify({id:m.id,error:{code:-32600,message:'invalid paginated history lineage: missing source rollout'}}));return;}
    if(m.method==='invalid'){ws.send(JSON.stringify({id:m.id,error:{code:-32600,message:'Invalid thread id'}}));return;}
    ws.send(JSON.stringify({id:m.id,result:{}}));
  }));
  const c=new Codex(socket);const states=[];c.on('status',s=>states.push(s));
  t.after(async()=>{c.ws?.terminate();for(const ws of wss.clients)ws.terminate();wss.close();await new Promise(r=>server.close(r));await rm(dir,{recursive:true,force:true});});
  await c.connect();
  await assert.rejects(c.call('turn/start'),e=>e.code==='CODEX_DRAINING'&&e.status===503&&e.rpc.code===-32600&&!ambiguousDelivery(e)&&/existing work finish/.test(e.message)&&/before resending/.test(e.message));
  await assert.rejects(c.call('history'),e=>e.code==='CODEX_HISTORY_LINEAGE_UNAVAILABLE'&&e.status===409&&e.rpc.code===-32600);
  assert.equal(c.ready,true,'keep the old transport for finishing work');
  assert.equal(c.status().connected,false);assert.equal(states.at(-1).problem.code,'CODEX_DRAINING');
  const event=new Promise(resolve=>c.once('event',resolve));
  [...wss.clients][0].send(JSON.stringify({method:'turn/completed',params:{threadId:'ongoing',turn:{status:'completed'}}}));
  assert.equal((await event).method,'turn/completed');assert.equal(c.status().problem.code,'CODEX_DRAINING');
  const closed=new Promise(resolve=>c.once('disconnected',resolve));[...wss.clients][0].close();await closed;
  draining=false;await c.connect();assert.deepEqual(c.status(),{connected:true,problem:null});assert.equal(writes,1,'reconnect must not replay the rejected request');
  await assert.rejects(c.call('invalid'),e=>e.message==='Invalid thread id'&&e.rpc.code===-32600&&!e.code);
  await c.call('turn/start');assert.equal(writes,2,'only an explicit retry submits work');
});

test('oversized frames keep their real cause, reconnect cleanly, and never replay writes',async t=>{
  const dir=await mkdtemp(join(tmpdir(),'pocket-connection-'));const socket=join(dir,'codex.sock');
  const server=http.createServer(),wss=new WebSocketServer({server});await new Promise(r=>server.listen(socket,r));
  let writes=0;
  wss.on('connection',ws=>ws.on('message',raw=>{const m=JSON.parse(raw);if(!m.id)return;
    if(m.method==='turn/start'){writes++;ws.terminate();return;}
    ws.send(JSON.stringify({id:m.id,result:m.method==='oversized'?{body:'x'.repeat(2048)}:{}}));
  }));
  const c=new Codex(socket,{maxPayload:1024});
  t.after(async()=>{c.ws?.terminate();for(const ws of wss.clients)ws.terminate();wss.close();await new Promise(r=>server.close(r));await rm(dir,{recursive:true,force:true});});
  await c.connect();
  await assert.rejects(c.call('oversized'),e=>e.code==='CODEX_HISTORY_TOO_LARGE'&&/history size limit/.test(e.message)&&ambiguousDelivery(e));
  assert.equal(c.status().connected,false);assert.equal(c.status().problem.code,'CODEX_HISTORY_TOO_LARGE');
  await c.connect();assert.equal(c.status().problem,null);
  await assert.rejects(c.call('turn/start'),e=>e.code==='CODEX_DISCONNECTED'&&ambiguousDelivery(e));
  await c.connect();assert.equal(writes,1);
});

test('missing lineage is a task-specific conflict, not a disconnected transport or automatic resend',()=>{const rpc={message:'invalid paginated history lineage: missing source rollout'};assert.equal(isHistoryLineageError(rpc),true);assert.equal(isHistoryLineageError({message:'Invalid thread id'}),false);const e=historyLineageError(rpc);assert.equal(e.status,409);assert.equal(e.code,'CODEX_HISTORY_LINEAGE_UNAVAILABLE');assert.equal(ambiguousDelivery(e),false);assert.match(e.message,/may still be running/);});
