import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {WebSocketServer} from 'ws';
import {Codex} from '../server/codex.mjs';
import {createTimelineConnection} from '../server/timeline-connection.mjs';
import {NativeTimelineReader} from '../server/native-timeline.mjs';

test('interactive history remains readable while a lifecycle connection is blocked',async()=>{
 const server=http.createServer(),wss=new WebSocketServer({server});
 let release,admitted;
 const blocked=new Promise(r=>{release=r}),started=new Promise(r=>{admitted=r});
 const calls=[];
 wss.on('connection',ws=>{
  let queue=Promise.resolve();
  ws.on('message',raw=>{queue=queue.then(async()=>{
   const m=JSON.parse(raw);if(m.id===undefined)return;
   calls.push(m.method);
   if(m.method==='thread/resume'){admitted();await blocked;}
   const result=m.method==='thread/read'?{thread:{id:'vipi'}}:
    m.method==='thread/timeline/list'?{data:[],nextCursor:null,activeRealtimeSessionAtPageStart:null}:{};
   ws.send(JSON.stringify({id:m.id,result}));
  });});
 });
 server.listen(0,'127.0.0.1');await once(server,'listening');
 const path=`/tmp/nextcomp-history-regression-${process.pid}.sock`;
 await new Promise(r=>server.close(r));server.listen(path);await once(server,'listening');
 const control=new Codex(path),client=new Codex(path);
 try{
  await control.connect();const pending=control.call('thread/resume',{threadId:'background'});await started;
  const reads=createTimelineConnection({client});
  const result=await Promise.race([
   (async()=>{const metadata=await reads.call('thread/read',{threadId:'vipi',includeTurns:false});const page=await new NativeTimelineReader(reads).read('vipi');return {metadata,page};})(),
   new Promise((_,reject)=>setTimeout(()=>reject(Error('Interactive history waited for lifecycle operation')),1000).unref())
  ]);
  assert.equal(result.metadata.thread.id,'vipi');assert.deepEqual(result.page.data,[]);
  await assert.rejects(reads.call('turn/start',{}),/history reads only/);
  assert.equal(calls.filter(x=>x==='thread/resume').length,1);
  release();await pending;
 }finally{release();control.ws?.terminate();client.ws?.terminate();wss.close();await new Promise(r=>server.close(r));}
});
