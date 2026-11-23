import {createRequire} from 'node:module';import assert from 'node:assert/strict';import {mkdir,writeFile} from 'node:fs/promises';import {fixture} from '../tests/web-browser-fixture.mjs';
const require=createRequire(import.meta.url),{chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const dir=process.env.WEB_EVIDENCE_DIR;await mkdir(dir,{recursive:true});
const factory=process.env.WEB_TEST_GATEWAY_MODULE?(await import(process.env.WEB_TEST_GATEWAY_MODULE)).createWebClient:undefined;
const f=await fixture({extraThreads:800,...(factory?{gatewayFactory:factory}:{})});let browser;
const result={state:'starting',baseline:!!factory,errors:[],peakAtlasRequests:0,atlasBeforeCatalog:0};
try{
 browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
 const context=await browser.newContext({viewport:{width:1440,height:900}});
 const paired=await context.request.post(f.url+'/web/pair',{headers:{Origin:new URL(f.url).origin},data:{code:'SYNTHETIC'}});assert.equal(paired.status(),200);
 const page=await context.newPage();page.setDefaultTimeout(25000);
 const cdp=await context.newCDPSession(page);await cdp.send('Network.enable');await cdp.send('Network.emulateNetworkConditions',{offline:false,latency:100,downloadThroughput:128*1024,uploadThroughput:128*1024});
 const active=new Set();let catalog=false;
 page.on('pageerror',e=>result.errors.push(e.message));
 page.on('request',r=>{if(r.url().includes('/symbols/motion/')&&r.url().endsWith('.webp')){active.add(r);result.peakAtlasRequests=Math.max(result.peakAtlasRequests,active.size);if(!catalog)result.atlasBeforeCatalog++;}});
 page.on('requestfinished',r=>active.delete(r));page.on('requestfailed',r=>active.delete(r));
 page.on('response',r=>{if(new URL(r.url()).pathname==='/api/threads'){catalog=true;result.catalogEncoding=r.headers()['content-encoding']||'identity';}});
 const at=Date.now();await page.goto(f.url,{waitUntil:'domcontentloaded'});await page.locator('[data-id=parent-123]').waitFor();result.catalogPaintMs=Date.now()-at;
 const clicked=Date.now();await page.locator('[data-id=parent-123]').click();await page.locator('#transcript .message').first().waitFor();result.clickToMessageMs=Date.now()-clicked;
 if(!factory){await page.evaluate(async()=>{window.__motionStats=(await import('/symbol-motion.mjs')).symbolMotionStats;});await page.waitForFunction(()=>window.__motionStats().draws>0,null,{timeout:12000});result.motion=await page.evaluate(async()=>{const m=await import('/symbol-motion.mjs');return m.symbolMotionStats();});}
 await page.waitForTimeout(1500);result.catalogReads=f.reads.filter(r=>r.path==='/api/threads').length;
 if(!factory){assert.equal(result.catalogEncoding,'gzip');assert.equal(result.atlasBeforeCatalog,0);assert.ok(result.peakAtlasRequests<=2);assert.ok(result.catalogPaintMs<8000);assert.ok(result.clickToMessageMs<2500);assert.deepEqual(result.errors,[]);}
 result.state='complete';
}catch(e){result.state='failed';result.error=e.message;if(!factory)process.exitCode=1;}
finally{await writeFile(dir+'/receipt.json',JSON.stringify(result,null,2));await browser?.close();await f.close();console.log(JSON.stringify(result));}
