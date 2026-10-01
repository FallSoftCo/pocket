import test from 'node:test';
import assert from 'node:assert/strict';
import {GitHub} from '../maintainer/github.mjs';
import {recover} from '../maintainer/recovery.mjs';
const root='/repos/FallSoftCo/pocodex',collection=root+'/hooks/123/deliveries';
const response=(body,link)=>new Response(body,{status:200,headers:link?{link}:undefined});

test('real request adapter follows numeric repository cursors and recovers an exact delivery ID on page two',async t=>{
 const seen=[],id='3845170374765461504';
 t.mock.method(globalThis,'fetch',async(url,options)=>{
  seen.push({url,method:options.method});
  if(url==='https://api.github.com'+root)return response('{"id":42}');
  if(url.endsWith('/attempts')){assert.equal(options.method,'POST');assert.ok(url.endsWith('/'+id+'/attempts'));return response('{}');}
  assert.equal(options.method,'GET');
  if(url.includes('cursor=')){
   assert.equal(url,'https://api.github.com'+collection+'?cursor=opaque%2Bvalue&per_page=100');
   return response(`[{"id":${id},"guid":"missed","status_code":503,"delivered_at":"${new Date(Date.now()-120000).toISOString()}"}]`);
  }
  assert.equal(url,'https://api.github.com'+collection+'?per_page=100');
  return response('[]','<https://api.github.com/repositories/42/hooks/123/deliveries?cursor=opaque%2Bvalue&per_page=100>; rel="next"');
 });
 assert.equal(await recover(new GitHub('synthetic'),123),1);
 assert.equal(seen.length,4);assert.ok(seen.every(x=>!x.url.includes('/repositories/')));
});

test('pagination refuses other repositories, collections, credentials, fragments and origins before following them',async t=>{
 for(const link of [
  'https://api.github.com/repositories/43/hooks/123/deliveries?cursor=x',
  'https://api.github.com/repositories/42/hooks/999/deliveries?cursor=x',
  'https://api.github.com/repos/Other/project/hooks/123/deliveries?cursor=x',
  'https://api.github.com'+root+'/issues?cursor=x',
  'https://evil.example/repositories/42/hooks/123/deliveries?cursor=x',
  'https://user:pass@api.github.com'+collection+'?cursor=x',
  'https://api.github.com'+collection+'?cursor=x#unexpected'
 ]){
  const seen=[];
  const mock=t.mock.method(globalThis,'fetch',async(url)=>{
   seen.push(url);
   if(url==='https://api.github.com'+root)return response('{"id":42}');
   assert.equal(url,'https://api.github.com'+collection+'?per_page=100');
   return response('[]',`<${link}>; rel="next"`);
  });
  await assert.rejects(new GitHub('synthetic').cursorPages(collection),/Invalid pagination target/);
  assert.ok(seen.length<=2);mock.mock.restore();
 }
});

test('named repository cursor needs no identity lookup and unbounded histories still fail closed',async t=>{
 let count=0;
 t.mock.method(globalThis,'fetch',async(url)=>{
  count++;assert.ok(url.startsWith('https://api.github.com'+collection+'?'));
  return response('[]',`<https://api.github.com${collection}?cursor=${count}>; rel="next"`);
 });
 await assert.rejects(new GitHub('synthetic').cursorPages(collection),/exceeds recovery limit/);
 assert.equal(count,10);
});
