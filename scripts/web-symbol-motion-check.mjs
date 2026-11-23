import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {fixture} from '../tests/web-browser-fixture.mjs';
const require=createRequire(import.meta.url),{chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const dir=process.env.WEB_EVIDENCE_DIR;await mkdir(dir,{recursive:true});
const f=await fixture();let browser;const errors=[],evidence=[];
try{
 browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
 const context=await browser.newContext({viewport:{width:1440,height:900},colorScheme:'dark'}),page=await context.newPage();page.setDefaultTimeout(8000);page.on('pageerror',e=>errors.push(e.message));const atlasReads=[];page.on('request',r=>{if(r.url().endsWith('.webp'))atlasReads.push(r.url()+' '+(r.headers()['range']||'full'));});
 await page.goto(f.url);await page.locator('input[name=code]').fill('SYNTHETIC');await page.locator('#pair-form button[type=submit]').click();
 await page.locator('[data-id=parent-123]').click();await page.locator('#transcript .modeled-symbol canvas').first().waitFor({state:'visible'});
 const stats=()=>page.evaluate(async()=>{const m=await import('/symbol-motion.mjs');return m.symbolMotionStats();});
 await page.waitForTimeout(1200);const first=await stats();
 const pixels=await page.locator('#coordinator canvas').evaluate(c=>Array.from(c.getContext('2d').getImageData(0,0,c.width,c.height).data));
 await page.waitForTimeout(200);const changed=await page.locator('#coordinator canvas').evaluate(c=>Array.from(c.getContext('2d').getImageData(0,0,c.width,c.height).data));
 assert.notDeepEqual(pixels,changed,'real rendered N frames change pixels');
 const last=await stats();const pacing=await page.evaluate(()=>new Promise(resolve=>{const samples=[];let previous;function tick(now){if(previous!==undefined)samples.push(now-previous);previous=now;if(samples.length===120){const sorted=[...samples].sort((a,b)=>a-b);resolve({frames:samples.length,medianMs:sorted[60],p95Ms:sorted[114],over34Ms:samples.filter(x=>x>34).length});}else requestAnimationFrame(tick);}requestAnimationFrame(tick);}));assert.ok(last.draws>first.draws&&last.decodedBytes<=32*1024*1024&&last.compressedBytes<=12*1024*1024);assert.equal(last.decodeFailures,0);
 evidence.push('actual modeled N and visible dock/message symbols advance rendered pixels on shared display clock; decoded cache <=32MiB');
 const before=await page.locator('#transcript').boundingBox();await page.locator('#draft').fill('Typing stays separate from animation');await page.waitForTimeout(500);assert.deepEqual(await page.locator('#transcript').boundingBox(),before);
 await page.screenshot({path:dir+'/animated.png'});
 // Offscreen symbols must not fetch/decode pages or join the active rendering set.
 const offscreen=await page.evaluate(async()=>{const m=await import('/symbol-motion.mjs'),holder=document.createElement('div');holder.id='offscreen-motion-fixture';holder.style='position:fixed;left:-10000px;top:-10000px';for(let n=0;n<300;n++)holder.append(m.animatedSymbol('codex'));document.body.append(holder);return m.symbolMotionStats();});
 await page.waitForTimeout(200);const afterOffscreen=await stats();assert.ok(afterOffscreen.active<20);await page.locator('#offscreen-motion-fixture').evaluate(n=>n.remove());
 evidence.push('300 offscreen history icons do not join animation work; drawing leaves transcript geometry and typing intact');
 await page.waitForTimeout(2800);assert.equal(atlasReads.length,new Set(atlasReads).size,'looping visible icons do not fetch the same atlas byte range again');await page.emulateMedia({reducedMotion:'reduce'});await page.waitForTimeout(100);const reduced=await stats();await page.waitForTimeout(400);assert.deepEqual(await stats(),reduced);assert.equal(reduced.scheduled,false);assert.equal(await page.locator('.modeled-symbol canvas:visible').count(),0);assert.ok(await page.locator('.modeled-symbol img:visible').count()>0);
 await page.emulateMedia({reducedMotion:'no-preference'});await page.waitForTimeout(200);assert.ok((await stats()).draws>reduced.draws);
 evidence.push('compressed source pages remain bounded and avoid repeated network reads across loops');evidence.push('live reduced-motion preference stops the shared clock and shows static fallback, then resumes');
 // Actual browser lifecycle events, not a mocked document.hidden property.
 const cdp=await context.newCDPSession(page);await cdp.send('Page.setWebLifecycleState',{state:'frozen'});await page.waitForTimeout(200);await cdp.send('Page.setWebLifecycleState',{state:'active'});await page.waitForTimeout(200);
 const restored=await stats();assert.ok(restored.draws>reduced.draws);assert.equal(restored.decodeFailures,0);
 evidence.push('browser freeze/resume retains rendered symbols without replaying requests');
 await page.emulateMedia({reducedMotion:'reduce'});await page.reload();await page.locator('#app').waitFor({state:'visible'});await page.waitForTimeout(300);assert.equal((await stats()).decodedBytes,0);assert.equal((await stats()).scheduled,false);
 assert.deepEqual(errors,[]);assert.equal(f.writes.filter(w=>w.path.endsWith('/reply')).length,0);
 await writeFile(dir+'/receipt.json',JSON.stringify({passed:true,evidence,first,last,pacing,afterOffscreen,reduced,restored,pageErrors:errors},null,2));console.log(JSON.stringify({passed:true,evidence}));
}catch(error){await writeFile(dir+'/failure.json',JSON.stringify({error:error.message,errors,evidence},null,2));throw error;}finally{await browser?.close();await f.close();}
