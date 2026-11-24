import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {mkdtempSync,rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {startDeepSeekFilesProxy} from '../server/deepseek-files-proxy.mjs';

const PNG=Buffer.from('89504e470d0a1a0a0000000d49484452','hex');
const pngUrl=()=>`data:image/png;base64,${PNG.toString('base64')}`;

async function fixture({responses=null,uploadDelay=0}={}){
  const uploads=[],requests=[];let nextId=1;
  const upstream=http.createServer(async(req,res)=>{
    const chunks=[];for await(const chunk of req)chunks.push(chunk);
    const body=Buffer.concat(chunks);
    if(req.url==='/files'&&req.method==='POST'){
      uploads.push({headers:req.headers,body});
      if(uploadDelay)await new Promise(resolve=>setTimeout(resolve,uploadDelay));
      res.writeHead(200,{'Content-Type':'application/json'});
      res.end(JSON.stringify({id:`file-api-test${nextId++}`,object:'file',bytes:PNG.length,purpose:'user_data'}));
      return;
    }
    let parsed=null;try{parsed=JSON.parse(body.toString('utf8'));}catch{}
    requests.push({url:req.url,headers:req.headers,body:parsed,raw:body});
    if(responses){await responses(req,res,body);return;}
    res.writeHead(200,{'Content-Type':'application/json'});
    res.end(JSON.stringify({id:'response-test',ok:true}));
  });
  await new Promise((resolve,reject)=>{upstream.once('error',reject);upstream.listen(0,'127.0.0.1',resolve);});
  const origin=`http://127.0.0.1:${upstream.address().port}`;
  return {origin,uploads,requests,close:()=>new Promise(resolve=>upstream.close(resolve))};
}

async function proxyFor(upstreamBase,dbPath,options={}){
  return startDeepSeekFilesProxy({port:0,upstreamBase,dbPath,...options});
}

async function post(proxy,body,{authorization='Bearer secret-a',path='/v1/responses'}={}){
  return fetch(`${proxy.url}${path}`,{method:'POST',headers:{'Content-Type':'application/json',Authorization:authorization},body:JSON.stringify(body)});
}

test('offloads inline images once and preserves input order, text and detail',async()=>{
  const root=mkdtempSync(join(tmpdir(),'nextcomp-files-'));
  const upstream=await fixture();let proxy=await proxyFor(upstream.origin,join(root,'files.sqlite'));
  try{
    const payload={model:'deepseek-flash',input:[{role:'user',content:[{type:'input_text',text:'before'},{type:'input_image',image_url:pngUrl(),detail:'high'},{type:'input_text',text:'after'}]}]};
    const first=await post(proxy,payload),firstBody=await first.json();
    assert.equal(first.status,200);assert.equal(firstBody.ok,true);
    assert.equal(upstream.uploads.length,1);
    assert.equal(upstream.requests[0].headers.authorization,'Bearer secret-a');
    const sent=upstream.requests[0].body;
    assert.deepEqual(sent.input[0].content.map(part=>part.type),['input_text','input_image','input_text']);
    assert.equal(sent.input[0].content[0].text,'before');
    assert.equal(sent.input[0].content[1].file_id,'file-api-test1');
    assert.equal(sent.input[0].content[1].image_url,undefined);
    assert.equal(sent.input[0].content[1].detail,'high');
    assert.equal(sent.input[0].content[2].text,'after');
    assert.ok(upstream.uploads[0].body.includes(Buffer.from('name="purpose"')));
    assert.ok(upstream.uploads[0].body.includes(Buffer.from('user_data')));
    assert.ok(upstream.uploads[0].body.includes(PNG));
    assert.equal(first.headers.get('x-nextcomp-images-offloaded'),'1');

    const second=await post(proxy,payload);assert.equal(second.status,200);
    assert.equal(upstream.uploads.length,1);
    assert.equal(upstream.requests[1].body.input[0].content[1].file_id,'file-api-test1');

    await proxy.close();proxy=await proxyFor(upstream.origin,join(root,'files.sqlite'));
    const third=await post(proxy,payload);assert.equal(third.status,200);
    assert.equal(upstream.uploads.length,1);
    assert.equal(upstream.requests[2].body.input[0].content[1].file_id,'file-api-test1');
  }finally{await proxy.close();await upstream.close();rmSync(root,{recursive:true,force:true});}
});

test('deduplicates concurrent uploads and scopes the stable cache by credential',async()=>{
  const root=mkdtempSync(join(tmpdir(),'nextcomp-files-'));
  const upstream=await fixture({uploadDelay:75});const proxy=await proxyFor(upstream.origin,join(root,'files.sqlite'));
  try{
    const payload={model:'deepseek-flash',input:[{role:'user',content:[{type:'input_image',image_url:pngUrl()}]}]};
    const [a,b]=await Promise.all([post(proxy,payload),post(proxy,payload)]);
    assert.equal(a.status,200);assert.equal(b.status,200);assert.equal(upstream.uploads.length,1);
    assert.equal(upstream.requests[0].body.input[0].content[0].file_id,'file-api-test1');
    assert.equal(upstream.requests[1].body.input[0].content[0].file_id,'file-api-test1');
    const other=await post(proxy,payload,{authorization:'Bearer secret-b'});
    assert.equal(other.status,200);assert.equal(upstream.uploads.length,2);
    assert.equal(upstream.requests[2].body.input[0].content[0].file_id,'file-api-test2');
  }finally{await proxy.close();await upstream.close();rmSync(root,{recursive:true,force:true});}
});

test('converts file_data blocks without retaining an incompatible filename',async()=>{
  const root=mkdtempSync(join(tmpdir(),'nextcomp-files-'));
  const upstream=await fixture();const proxy=await proxyFor(upstream.origin,join(root,'files.sqlite'));
  try{
    const response=await post(proxy,{input:[{type:'function_call_output',call_id:'call-1',output:[{type:'file',file_data:pngUrl(),filename:'screen.png'}]}]});
    assert.equal(response.status,200);
    const part=upstream.requests[0].body.input[0].output[0];
    assert.equal(part.file_id,'file-api-test1');assert.equal(part.file_data,undefined);assert.equal(part.filename,undefined);
  }finally{await proxy.close();await upstream.close();rmSync(root,{recursive:true,force:true});}
});

test('passes streaming responses and upstream status through unchanged',async()=>{
  const root=mkdtempSync(join(tmpdir(),'nextcomp-files-'));
  const upstream=await fixture({responses:async(req,res)=>{
    if(req.url.endsWith('/reject')){res.writeHead(413,{'Content-Type':'application/json'});res.end('{"error":{"message":"request body too large"}}');return;}
    res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache'});
    res.write('event: response.created\ndata: {"type":"response.created"}\n\n');
    await new Promise(resolve=>setTimeout(resolve,20));
    res.end('event: response.completed\ndata: {"type":"response.completed"}\n\n');
  }});
  const proxy=await proxyFor(upstream.origin,join(root,'files.sqlite'));
  try{
    const streamed=await fetch(`${proxy.url}/v1/responses`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({input:'hello'})});
    assert.equal(streamed.status,200);assert.match(streamed.headers.get('content-type'),/text\/event-stream/);
    assert.equal(await streamed.text(),'event: response.created\ndata: {"type":"response.created"}\n\nevent: response.completed\ndata: {"type":"response.completed"}\n\n');
    const rejected=await fetch(`${proxy.url}/v1/responses/reject`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({input:'hello'})});
    assert.equal(rejected.status,413);assert.match(await rejected.text(),/request body too large/);
  }finally{await proxy.close();await upstream.close();rmSync(root,{recursive:true,force:true});}
});

test('rejects unsupported image bytes before contacting the Responses endpoint',async()=>{
  const root=mkdtempSync(join(tmpdir(),'nextcomp-files-'));
  const upstream=await fixture();const proxy=await proxyFor(upstream.origin,join(root,'files.sqlite'));
  try{
    const response=await post(proxy,{input:[{role:'user',content:[{type:'input_image',image_url:`data:image/png;base64,${Buffer.from('not an image').toString('base64')}`}]}]});
    assert.equal(response.status,415);assert.match((await response.json()).error.message,/JPEG, PNG, GIF or WebP/);
    assert.equal(upstream.uploads.length,0);assert.equal(upstream.requests.length,0);
  }finally{await proxy.close();await upstream.close();rmSync(root,{recursive:true,force:true});}
});

test('reports health without exposing credentials',async()=>{
  const root=mkdtempSync(join(tmpdir(),'nextcomp-files-'));
  const upstream=await fixture();const proxy=await proxyFor(upstream.origin,join(root,'files.sqlite'));
  try{
    const response=await fetch(`${proxy.url}/healthz`);const body=await response.json();
    assert.equal(response.status,200);assert.equal(body.ok,true);assert.equal(body.cachedFiles,0);assert.equal(body.upstreamOrigin,upstream.origin);
    assert.doesNotMatch(JSON.stringify(body),/secret|authorization/i);
  }finally{await proxy.close();await upstream.close();rmSync(root,{recursive:true,force:true});}
});
