import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import express from 'express';
import {registerModelProviderRoutes} from '../server/model-providers.mjs';
import {ProviderModels} from '../server/provider-models.mjs';

test('authenticated HTTP provider catalog is projected, cached and never starts a model or exposes credentials',async t=>{
 let reads=0;const secret='fixture-only-secret';
 const upstream=createServer((req,res)=>{reads++;assert.equal(req.method,'GET');assert.equal(req.url,'/v1/models');assert.equal(req.headers.authorization,'Bearer '+secret);res.setHeader('Content-Type','application/json');res.end(JSON.stringify({data:[{id:'local-long:latest'},{id:'small:20b',name:'Small 20B'},{id:'small:20b'},{id:'invalid model'},{id:'../bad'}]}));});
 await new Promise(r=>upstream.listen(0,'127.0.0.1',r));t.after(()=>new Promise(r=>upstream.close(r)));
 const baseUrl=`http://127.0.0.1:${upstream.address().port}/v1`,calls=[];
 const codex={initializeResult:{userAgent:'codex_pocket/0.171.0'},async connect(){},async call(method){calls.push(method);if(method==='modelProvider/list')return {data:[{id:'local',name:'Local',baseUrl}],defaultProviderId:'local'};if(method==='config/read')return {config:{model_provider:'local',model:'small:20b',model_providers:{local:{base_url:baseUrl,experimental_bearer_token:secret}}},layers:[]};throw Error('Unexpected runtime call');}};
 const app=express();registerModelProviderRoutes(app,{codex,route:fn=>(req,res,next)=>Promise.resolve(fn(req,res)).catch(next)});app.use((e,_req,res,_next)=>res.status(e.status||500).json({error:e.message}));
 const api=await new Promise(r=>{const s=app.listen(0,'127.0.0.1',()=>r(s));});t.after(()=>new Promise(r=>api.close(r)));
 const url=`http://127.0.0.1:${api.address().port}/api/model-providers`;
 const view=await(await fetch(url)).json();assert.equal(view.providers[0].canListModels,true);assert.equal(reads,0);
 const results=await Promise.all(Array.from({length:4},async()=>{const r=await fetch(url+'?modelsFor=local');assert.equal(r.status,200);return r.json();}));
 assert.equal(reads,1);assert.equal(results[0].defaultModel,'small:20b');assert.deepEqual(results[0].models,[{id:'local-long:latest',name:'local-long:latest'},{id:'small:20b',name:'Small 20B'}]);
 assert(!JSON.stringify(results).includes(secret));assert(calls.every(m=>['config/read','modelProvider/list'].includes(m)));
 assert.equal((await fetch(url+'?modelsFor=foreign')).status,404);assert.equal(reads,1);
});

test('redirect, timeout, malformed and oversized catalogs remain unavailable; no credential fallback or error echo',async()=>{
 const p={id:'local',baseUrl:'http://127.0.0.1:1234/v1'},config={model_providers:{local:{base_url:p.baseUrl,env_key:'LOCAL_KEY'}}};
 for(const mode of ['redirect','timeout','malformed','oversized']){
  let calls=0;const m=new ProviderModels({env:{LOCAL_KEY:'private-synthetic'},timeoutMs:10,fetchImpl:async(_url,options)=>{calls++;assert.equal(options.redirect,'error');assert.equal(options.headers.Authorization,'Bearer private-synthetic');if(mode==='timeout')return new Promise((_r,reject)=>options.signal.addEventListener('abort',()=>reject(Error('private-synthetic'))));if(mode==='redirect')throw Error('private-synthetic');return new Response(mode==='oversized'?'x'.repeat(262145):'{bad');}});
  const result=await m.read(p,config);assert.equal(result.state,'unavailable');assert(!JSON.stringify(result).includes('private-synthetic'));await m.read(p,config);assert.equal(calls,1);
 }
 const m=new ProviderModels({fetchImpl:()=>assert.fail('must not request mismatched provider')});assert.equal((await m.read(p,{model_providers:{local:{base_url:'https://foreign.invalid'}}})).state,'unavailable');
});
