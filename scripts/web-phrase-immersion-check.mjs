import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdir,writeFile} from 'node:fs/promises';
import {grammarBoundaryPlans} from '../tests/fixtures/immersion-grammar-boundaries.mjs';
import {fixture} from '../tests/web-browser-fixture.mjs';
if(process.argv[1]&&import.meta.url===new URL('file://'+process.argv[1]).href){
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
  const forms=[];for(let phase=0;phase<4;phase++){forms.push(element.textContent);layer.flip();}
  const slots=layer.nodes.get(element)?.pair.eligible;
  layer.accept({enabled:false,planVersion:plan.planVersion,translations:[]});await layer.scan();
  return {before,slots,forms,requests,restored:element.textContent,source,phrases};
 });
 assert.equal(result.journey.slots,3);assert.equal(result.journey.forms[0],result.journey.forms[2]);
 assert.ok(result.journey.forms[3].includes('in questa conversazione'));assert.ok(result.journey.forms[3].includes('Can-you open'));
 assert.ok(result.journey.forms[0].includes('Can-you open'));assert.ok(result.journey.forms[1].includes('Puoi aprire'));assert.ok(result.journey.forms[1].includes('in this conversation'));
 for(const [it,en]of result.journey.phrases){
  assert.equal(Number(result.journey.forms.some(f=>f.includes(it))),1);
  assert.equal(Number(result.journey.forms.some(f=>f.includes(en))),1);
 }
 assert.equal(result.journey.requests.length,1);
 assert.equal(result.journey.requests[0].sources,1);
 assert.equal(result.journey.restored,result.journey.source);
 assert.deepEqual(result.errors,[]);assert.equal(result.humanSubmissions,0);result.state='complete';
}catch(e){result.state='failed';result.error=e.stack;process.exitCode=1;}
finally{await browser?.close();await f.close();await writeFile(directory+'/receipt.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));}

}

// Existing browser qualification entrypoint, supplied the trusted main context.
export async function grammarBoundaryJourney(context,{directory}={}){
 const plans=grammarBoundaryPlans(),f=await fixture({port:18989});const page=await context.newPage();
 const result={state:'running',modelCalls:0,humanSubmissions:0,errors:[],views:[]};
 page.on('pageerror',e=>result.errors.push(e.message));
 page.on('request',r=>{if(r.method()==='POST')result.humanSubmissions++;});
 try{
  await page.route('**/app.mjs',r=>r.fulfill({contentType:'text/javascript',body:''}));
  await page.goto(f.url);
  await page.evaluate(async plans=>{
   const {ImmersionLayer}=await import('/immersion.mjs');document.body.replaceChildren();
   const main=document.createElement('main');main.style.cssText='padding:24px;max-width:850px;margin:auto';document.body.append(main);
   for(const plan of plans){const article=document.createElement('article');article.className='message';const p=document.createElement('div');p.className='body';p.dataset.immersionText=plan.original;p.dataset.immersionId=plan.id;p.textContent=plan.original;article.append(p);main.append(article);}
   const layer=new ImmersionLayer(async()=>{throw Error('Synthetic plan requested inference');},location.origin+'/',()=>{});
   layer.accept({enabled:true,planVersion:plans[0].planVersion,translations:plans});await layer.scan();clearTimeout(layer.timer);
   window.grammarQA={layer};
  },plans);
  for(const width of [390,1100])for(const theme of ['light','dark']){
   await page.setViewportSize({width,height:900});
   await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;},theme);
   const observation=await page.evaluate(()=>{
    const {layer}=window.grammarQA;const rects=()=>[...document.querySelectorAll('.immersion-phrase')].map(n=>{const r=n.getBoundingClientRect();return [r.x,r.y,r.width,r.height];});
    // The production stack hides its reserve parent while making the active
    // child visible; Chromium innerText on that parent is empty. Inspect actual
    // visible text nodes, excluding the hidden reserve language explicitly.
    const visible=()=>[...document.querySelectorAll('.body')].map(n=>{const walker=document.createTreeWalker(n,NodeFilter.SHOW_TEXT);let value='';while(walker.nextNode())if(getComputedStyle(walker.currentNode.parentElement).visibility==='visible')value+=walker.currentNode.data;return value;});
    const phases=[],geometry=[];
    for(let phase=0;phase<4;phase++){
     for(const entry of layer.nodes.values())entry.slots.setPhase(phase);
     phases.push(visible());geometry.push(rects());
    }
    const pairs=[...document.querySelectorAll('.immersion-phrase')].map(box=>{
     const en=box.querySelector('[lang=en]').textContent,it=box.querySelector('[lang=it]').textContent;
     box.click();const first=box.dataset.language;box.click();const second=box.dataset.language;
     return {en,it,revealsBoth:en===it||first!==second,interactive:box.getAttribute('role')==='button'};
    });
    for(const entry of layer.nodes.values())entry.slots.setPhase(0);
    const cells=[...document.querySelectorAll('.immersion-phrase')];
    const styles=cells.map(n=>{const s=getComputedStyle(n);return {color:s.color,background:s.backgroundColor,edge:s.boxShadow,radius:s.borderRadius,font:s.fontSize};});
    return {phases,geometry,pairs,styles,overflow:document.documentElement.scrollWidth>innerWidth};
   });
   result.views.push({width,theme,...observation});
   assert.deepEqual(observation.phases[0],observation.phases[2]);
   for(const geometry of observation.geometry)assert.deepEqual(geometry,observation.geometry[0],'Phrase moved across phase');
   for(const pair of observation.pairs){assert.ok(pair.revealsBoth);assert.equal(pair.interactive,pair.en!==pair.it);}
   assert.equal(observation.overflow,false);
   assert.ok(observation.phases[0].join(' ').includes('recordings-saved'));assert.ok(observation.phases[0].join(' ').includes('to the station'));
   assert.ok(observation.phases[0].join(' ').includes('The your latest message'));
   const neverChanged=observation.pairs.filter(p=>p.en===p.it);assert.deepEqual(neverChanged.map(p=>p.en),['Menu']);
   result.views.at(-1).identicalPairs=neverChanged.length;
   if(directory){
    await page.screenshot({path:directory+'/grammar-'+theme+'-'+width+'.png',fullPage:true});
    await page.evaluate(()=>{for(const entry of window.grammarQA.layer.nodes.values())entry.slots.setPhase(1);});
    await page.screenshot({path:directory+'/grammar-mixed-'+theme+'-'+width+'.png',fullPage:true});
   }
  }
  assert.deepEqual(result.errors,[]);assert.equal(result.humanSubmissions,0);result.state='complete';
 }catch(error){result.state='failed';result.error=error.stack;if(directory)await page.screenshot({path:directory+'/failure.png',fullPage:true});}
 finally{await page.evaluate(()=>{const layer=window.grammarQA?.layer;if(layer){layer.enabled=false;clearTimeout(layer.timer);}}).catch(()=>{});await page.close();await f.close();}
 return result;
}
