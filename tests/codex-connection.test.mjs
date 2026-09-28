import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import http from 'node:http';
import {WebSocketServer} from 'ws';
import {Codex} from '../server/codex.mjs';
import {ambiguousDelivery} from '../server/connection-errors.mjs';

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
