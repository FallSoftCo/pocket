import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {gunzipSync} from 'node:zlib';
import express from 'express';
import {nativeJsonCompression} from '../server/json-compression.mjs';
async function fixture(t){
 const app=express(),accepted=[];app.use(express.json());app.use('/api',nativeJsonCompression());
 app.use('/api',(req,res,next)=>req.headers.authorization==='Bearer fixture'?next():res.status(401).json({error:'Unauthorized'}));
 const data={id:'canonical-source',message:'Perché già così? '.repeat(1200),revision:17,receipt:'original'};
 app.get('/api/history',(_req,res)=>res.status(202).set('Cache-Control','no-store').json(data));
 app.post('/api/commands',(req,res)=>{accepted.push(req.body);res.status(202).json({...data,clientId:req.body.id});});
 const binary=Buffer.concat([Buffer.from('RIFF'),Buffer.alloc(4096,9)]);
 app.get('/api/audio',(_req,res)=>res.status(206).set('Content-Range','bytes 0-4099/4100').type('audio/wav').send(binary));
 app.get('/api/events',(_req,res)=>res.type('text/event-stream').send('data: '+data.message+'\n\n'));
 const server=http.createServer(app);server.listen(0,'127.0.0.1');await once(server,'listening');
 t.after(()=>{server.closeAllConnections();server.close();});
 const read=(path,{encoding='gzip',body,auth=true,range}={})=>new Promise((resolve,reject)=>{
  const headers={'Accept-Encoding':encoding,...(auth?{Authorization:'Bearer fixture'}:{}),...(range?{Range:range}:{}),...(body?{'Content-Type':'application/json'}:{})};
  const req=http.request({host:'127.0.0.1',port:server.address().port,path,method:body?'POST':'GET',headers},res=>{const chunks=[];res.on('data',x=>chunks.push(x));res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body:Buffer.concat(chunks)}));});
  req.on('error',reject);req.end(body?JSON.stringify(body):undefined);
 });
 return {read,data,binary,accepted};
}
test('native JSON negotiation preserves canonical UTF8 bytes, status, validators and auth',async t=>{
 const f=await fixture(t),gzip=await f.read('/api/history'),plain=await f.read('/api/history',{encoding:'identity'});
 assert.equal(gzip.status,202);assert.equal(gzip.headers['content-encoding'],'gzip');assert.equal(gzip.headers['cache-control'],'no-store');assert.match(gzip.headers.vary,/Accept-Encoding/i);assert.equal(gzip.headers.etag,plain.headers.etag);
 assert.deepEqual(gunzipSync(gzip.body),plain.body);assert.deepEqual(JSON.parse(plain.body),f.data);assert.ok(gzip.body.length<plain.body.length/4);
 const denied=await f.read('/api/history',{auth:false});assert.equal(denied.status,401);
 const disabled=await f.read('/api/history',{encoding:'gzip;q=0, identity'});assert.equal(disabled.headers['content-encoding'],undefined);assert.deepEqual(disabled.body,plain.body);
});
test('native JSON compression preserves the single submitted intent and accepted receipt',async t=>{
 const f=await fixture(t),intent={id:'original-client-id',text:'Una richiesta immutabile',mode:'queue'};
 const result=await f.read('/api/commands',{body:intent});assert.equal(result.status,202);assert.equal(result.headers['content-encoding'],'gzip');
 assert.deepEqual(f.accepted,[intent]);assert.equal(JSON.parse(gunzipSync(result.body)).clientId,intent.id);
});
test('audio byte ranges, requested ranges and event streams are never compressed or buffered as JSON',async t=>{
 const f=await fixture(t),audio=await f.read('/api/audio');assert.equal(audio.status,206);assert.equal(audio.headers['content-encoding'],undefined);assert.deepEqual(audio.body,f.binary);
 const range=await f.read('/api/history',{range:'bytes=0-20'});assert.equal(range.headers['content-encoding'],undefined);
 const events=await f.read('/api/events');assert.equal(events.headers['content-encoding'],undefined);assert.equal(events.body.toString(),'data: '+f.data.message+'\n\n');
});
