import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {downloadAtlas} from '../web/atlas-download.mjs';
async function host(t,handler){
 const server=http.createServer(handler);server.listen(0,'127.0.0.1');await once(server,'listening');
 t.after(()=>{server.closeAllConnections();server.close();});return 'http://127.0.0.1:'+server.address().port+'/atlas.webp';
}
test('range transfer preserves original bytes and bounds every background response',async t=>{
 const source=Buffer.alloc(338920);for(let n=0;n<source.length;n++)source[n]=n%251;
 const requests=[];let active=0,peak=0;
 const url=await host(t,(req,res)=>{
  requests.push({range:req.headers.range,validator:req.headers['if-range']});active++;peak=Math.max(peak,active);res.on('close',()=>active--);
  const match=/^bytes=(\d+)-(\d+)$/.exec(req.headers.range);assert.ok(match);const start=Number(match[1]),end=Math.min(Number(match[2]),source.length-1);
  res.writeHead(206,{'Content-Range':'bytes '+start+'-'+end+'/'+source.length,'Content-Length':end-start+1,ETag:'"original"','Last-Modified':'Mon, 19 Oct 2026 00:00:00 GMT'});res.end(source.subarray(start,end+1));
 });
 const blob=await downloadAtlas(url);assert.deepEqual(Buffer.from(await blob.arrayBuffer()),source);
 assert.equal(peak,1);assert.equal(requests.length,Math.ceil(source.length/32768));assert.ok(requests.slice(1).every(r=>r.validator==='Mon, 19 Oct 2026 00:00:00 GMT'));
});
test('changed or oversized atlas representation is rejected instead of mixed or fully downloaded',async t=>{
 let n=0;
 const url=await host(t,(req,res)=>{n++;const start=n===1?0:32768,end=start+32767;res.writeHead(206,{'Content-Range':'bytes '+start+'-'+end+'/65536','Content-Length':32768,ETag:n===1?'"first"':'"changed"'});res.end(Buffer.alloc(32768));});
 await assert.rejects(downloadAtlas(url),/changed/);assert.equal(n,2);
 const full=await host(t,(_req,res)=>{res.writeHead(200,{'Content-Length':1000000});res.end(Buffer.alloc(1000000));});
 await assert.rejects(downloadAtlas(full),/bounded range/);
 const huge=await host(t,(_req,res)=>{res.writeHead(206,{'Content-Range':'bytes 0-32767/9000000',ETag:'"too-big"'});res.end(Buffer.alloc(32768));});
 await assert.rejects(downloadAtlas(huge),/budget/);
});
test('small complete assets work while incomplete range pages do not advance the cursor',async t=>{
 const small=Buffer.from('small immutable atlas');
 const url=await host(t,(_req,res)=>{res.writeHead(200,{'Content-Length':small.length});res.end(small);});
 assert.deepEqual(Buffer.from(await (await downloadAtlas(url)).arrayBuffer()),small);
 const incomplete=await host(t,(_req,res)=>{res.writeHead(206,{'Content-Range':'bytes 0-3/65536','Content-Length':4,ETag:'"short"'});res.end('part');});
 await assert.rejects(downloadAtlas(incomplete),/budget/);
});

test('healthy paced ranges survive the per-chunk stall budget while a stalled response aborts',async t=>{
 const source=Buffer.alloc(4096,7);let reads=0;
 const paced=await host(t,(req,res)=>{
  reads++;const start=Number(/^bytes=(\d+)-/.exec(req.headers.range)[1]);setTimeout(()=>{res.writeHead(206,{'Content-Range':'bytes '+start+'-'+(start+1023)+'/4096','Content-Length':1024,ETag:'"paced"'});res.end(source.subarray(start,start+1024));},60);
 });
 const blob=await downloadAtlas(paced,{chunkBytes:1024,stallMs:150,deadlineMs:1200});
 assert.deepEqual(Buffer.from(await blob.arrayBuffer()),source);assert.equal(reads,4);
 const stalled=await host(t,()=>{});
 await assert.rejects(downloadAtlas(stalled,{stallMs:30,deadlineMs:250}),{name:'TimeoutError'});
});
