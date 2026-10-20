import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdir,writeFile} from 'node:fs/promises';
import {fixture} from '../tests/web-browser-fixture.mjs';
const require=createRequire(import.meta.url),{chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const directory=process.env.WEB_EVIDENCE_DIR;await mkdir(directory,{recursive:true});
const f=await fixture();let browser;const result={state:'running',humanSubmissions:0,errors:[]};
try{
 browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
 const page=await browser.newPage({viewport:{width:390,height:800}});
 page.on('pageerror',e=>result.errors.push(e.message));
 page.on('request',r=>{if(r.method()==='POST'&&r.url().endsWith('/reply'))result.humanSubmissions++;});
 // This is an interaction/correctness check, not a cache or performance benchmark.
 await page.route('**/app.mjs',r=>r.fulfill({contentType:'text/javascript',body:''}));
 await page.goto(f.url);
 result.journey=await page.evaluate(async()=>{
  const {ImmersionLayer}=await import('/immersion.mjs');
  const source='Can you open the latest message in this conversation?';
  const phrases=[['Puoi aprire','Can-you open'],['l’ultimo messaggio','the latest message'],['in questa conversazione','in this conversation']];
  const segments=[];let offset=0;
  for(const [i,[target,meaning]]of phrases.entries()){
   if(i){segments.push({start:offset,end:offset+1,target:' ',meaning:''});offset++;}
   segments.push({start:offset,end:offset+target.length,target,meaning});offset+=target.length;
  }
  const target=phrases.map(p=>p[0]).join(' ');
  const plan={original:source,text:target+'?',planVersion:'transgrammatic-english-italian-v3',spans:[{start:0,end:source.length-1,source:source.slice(0,-1),target,targetSegments:segments}]};
  document.body.replaceChildren();const element=document.createElement('div');element.className='body';element.dataset.immersionText=source;element.dataset.immersionId='phrase-fixture';element.textContent=source;document.body.append(element);
  const requests=[];const layer=new ImmersionLayer(async(path,body)=>{requests.push({path,sources:body?.sources?.length||0});return {enabled:true,planVersion:plan.planVersion,translations:[plan]};},location.origin+'/',()=>{});
  layer.accept({enabled:true,planVersion:'transgrammatic-english-italian-v2',translations:[]});
  // Simulate a retained word-by-word generation before the server announces its new revision.
  layer.submitted.set(source,true);layer.plans.set(source,{...plan,planVersion:'transgrammatic-english-italian-v2'});
  await layer.scan();const before=layer.nodes.get(element)?.pair.eligible;
  layer.accept({enabled:true,planVersion:plan.planVersion,translations:[]});
  await layer.scan();
  const deadline=performance.now()+4000;
  while(!layer.nodes.has(element)&&performance.now()<deadline){await new Promise(r=>setTimeout(r,100));await layer.scan();}
  const first=element.textContent;layer.flip();const second=element.textContent;
  const slots=layer.nodes.get(element)?.pair.eligible;
  layer.accept({enabled:false,planVersion:plan.planVersion,translations:[]});await layer.scan();
  return {before,slots,forms:[first,second],requests,restored:element.textContent,source,phrases};
 });
 assert.equal(result.journey.slots,3);
 for(const [it,en]of result.journey.phrases){
  assert.equal(result.journey.forms.filter(f=>f.includes(it)).length,1);
  assert.equal(result.journey.forms.filter(f=>f.includes(en)).length,1);
 }
 assert.equal(result.journey.requests.length,1);
 assert.equal(result.journey.requests[0].sources,1);
 assert.equal(result.journey.restored,result.journey.source);
 assert.deepEqual(result.errors,[]);assert.equal(result.humanSubmissions,0);result.state='complete';
}catch(e){result.state='failed';result.error=e.stack;process.exitCode=1;}
finally{await browser?.close();await f.close();await writeFile(directory+'/receipt.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));}
