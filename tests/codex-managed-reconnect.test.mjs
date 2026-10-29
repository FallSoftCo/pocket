import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createServer} from 'node:http';
import {WebSocketServer} from 'ws';
import {Codex} from '../server/codex.mjs';

test('actual refused socket recovers through one managed start then initializes; no turn admission',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'nextcomp-managed-recovery-'));
 const path=join(dir,'control.sock'),http=createServer(),ws=new WebSocketServer({server:http});
 const methods=[];let starts=0;
 ws.on('connection',connection=>connection.on('message',raw=>{
  const request=JSON.parse(raw);methods.push(request.method);
  if(request.id!==undefined)connection.send(JSON.stringify({id:request.id,result:{}}));
 }));
 const client=new Codex(path,{managedStart:'/synthetic/codex'});
 client.managedRecovery.run=async()=>{starts++;await new Promise((resolve,reject)=>{http.once('error',reject);http.listen(path,resolve);});};
 try{
  await Promise.all([client.connect(),client.connect()]);
  assert.equal(client.ready,true);assert.equal(starts,1);
  assert.equal(methods.includes('initialize'),true);
  assert.equal(methods.some(method=>method?.startsWith('turn/')||method?.startsWith('thread/')),false);
 }finally{
  for(const connection of ws.clients)connection.terminate();
  await new Promise(resolve=>ws.close(resolve));
  if(http.listening)await new Promise(resolve=>http.close(resolve));
  await rm(dir,{recursive:true,force:true});
 }
});
