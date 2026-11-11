import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {readFile} from 'node:fs/promises';
import {SourceTextModule} from 'node:vm';
import {JSDOM} from 'jsdom';
import {galleryRoute} from '../web/media-previews.mjs';
const wait=async condition=>{const end=Date.now()+5000;while(Date.now()<end){if(condition())return;await new Promise(r=>setTimeout(r,10));}assert.fail('Preview did not settle');};
async function fixture(t,{legacy=false}={}){
 const reads=[];let fail=false;
 const server=http.createServer((req,res)=>{reads.push({path:req.url,auth:req.headers.authorization,cookie:req.headers.cookie});res.setHeader('content-type','application/json');
  if(req.url==='/8bar/showcase-previews.json'&&legacy){res.writeHead(404).end('{}');return;}
  if(fail){res.writeHead(503).end('{}');return;}
  const cards=[{id:'first',title:'First render',poster:'first.png',width:800,height:600},{id:'second',title:'Motion',poster:'second.png',video:'second.mp4'}];
  res.end(JSON.stringify(legacy?cards:{version:1,cards}));
 });server.listen(0,'127.0.0.1');await once(server,'listening');
 const dom=new JSDOM('<main><div class="body"></div></main>',{url:'https://work.tail.example:8448/',runScripts:'outside-only'}),w=dom.window;
 Object.assign(w,{TextDecoder,AbortSignal,fetch:async(url,options)=>{assert.equal(options.credentials,'omit');assert.equal(new URL(url).origin,w.location.origin);return fetch('http://127.0.0.1:'+server.address().port+new URL(url).pathname,options);}});
 const module=new SourceTextModule(await readFile(new URL('../web/media-previews.mjs',import.meta.url),'utf8'),{context:dom.getInternalVMContext()});await module.link(()=>{throw Error('Unexpected import');});await module.evaluate();
 const root=w.document.querySelector('main');const media=new module.namespace.InlineMedia(root);
 t.after(()=>{media.observer.disconnect();dom.window.close();server.close();server.closeAllConnections();});
 return {w,root,reads,setFail:v=>fail=v,body:root.querySelector('.body')};
}
test('canonical443 gallery links on compatibility web render coalesced compact metadata and local media without credentials',async t=>{
 const f=await fixture(t);f.body.innerHTML='<a href="https://work.tail.example/8bar/#first">First</a><a href="https://work.tail.example/8bar/#second">Second</a><a href="https://other.example/8bar/#first">Other</a>';
 await wait(()=>f.root.querySelectorAll('.inline-gallery img,.inline-gallery video').length===2);
 assert.equal(f.reads.length,1);assert.equal(f.reads[0].path,'/8bar/showcase-previews.json');assert.equal(f.reads[0].auth,undefined);assert.equal(f.reads[0].cookie,undefined);
 assert.equal(f.root.querySelector('img').src,'https://work.tail.example:8448/8bar/first.png');assert.equal(f.root.querySelector('video').src,'https://work.tail.example:8448/8bar/second.mp4');assert.equal(f.root.querySelectorAll('.inline-gallery').length,2);
 assert.equal(f.root.querySelector('figcaption a').href,'https://work.tail.example/8bar/#first');
});
test('failed preview is visible and explicit retry replaces it without duplicate figures or background loops',async t=>{
 const f=await fixture(t);f.setFail(true);f.body.innerHTML='<a href="https://work.tail.example/8bar/#first">First</a>';
 await wait(()=>f.root.querySelector('.inline-gallery button'));assert.match(f.root.textContent,/Preview unavailable/);assert.equal(f.reads.length,1);
 f.setFail(false);f.root.querySelector('.inline-gallery button').click();await wait(()=>f.root.querySelector('img'));
 assert.equal(f.reads.length,2);assert.equal(f.root.querySelectorAll('.inline-gallery').length,1);
});
test('old gallery projection404 falls back once to existing catalog',async t=>{
 const f=await fixture(t,{legacy:true});f.body.innerHTML='<a href="https://work.tail.example/8bar/#first">First</a>';await wait(()=>f.root.querySelector('img'));
 assert.deepEqual(f.reads.map(r=>r.path),['/8bar/showcase-previews.json','/8bar/showcase-catalog.json']);
});
test('gallery routing never rewrites foreign hosts, credentials or arbitrary service ports',()=>{
 for(const value of ['https://other.example/8bar/#x','https://work.tail.example:9000/8bar/#x','https://user:password@work.tail.example/8bar/#x','https://work.tail.example/8bar/#%XX'])assert.equal(galleryRoute(value,'https://work.tail.example:8448'),null);
});
