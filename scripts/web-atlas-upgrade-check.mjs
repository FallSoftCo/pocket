import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {mkdir,writeFile} from 'node:fs/promises';
import {fixture} from '../tests/web-browser-fixture.mjs';
const require=createRequire(import.meta.url),{chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const dir=process.env.WEB_EVIDENCE_DIR;await mkdir(dir,{recursive:true});
const f=await fixture();let browser;const result={state:'starting',errors:[],humanSubmissions:0};
try{
 browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
 const context=await browser.newContext(),page=await context.newPage();
 page.on('pageerror',e=>result.errors.push(e.message));
 page.on('request',r=>{if(r.method()==='POST'&&r.url().endsWith('/reply'))result.humanSubmissions++;});
 await page.goto(f.url+'/icon.svg');
 result.warmCache=await page.evaluate(async()=>{
  const url='/symbols/motion/nextcomp-0.webp';
  const original=await (await fetch(url)).arrayBuffer();
  const {downloadAtlas}=await import('/atlas-download.mjs');
  const ranges=[];const assembled=await (await downloadAtlas(url,{fetcher:async(u,options)=>{ranges.push(options.headers.Range);return fetch(u,options);}})).arrayBuffer();
  const hash=async value=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',value))).join(',');
  return {originalBytes:original.byteLength,reconstructedBytes:assembled.byteLength,byteExact:await hash(original)===await hash(assembled),ranges:ranges.length};
 });
 assert.equal(result.warmCache.byteExact,true);
 await page.goto(f.url);await page.locator('input[name=code]').fill('SYNTHETIC');await page.locator('#pair-form button[type=submit]').click();
 await page.locator('[data-id=parent-123]').waitFor();
 await page.locator('#coordinator canvas:visible').waitFor({timeout:12000});
 assert.deepEqual(result.errors,[]);assert.equal(result.humanSubmissions,0);result.state='complete';
}catch(e){result.state='failed';result.error=e.message;process.exitCode=1;}
finally{await writeFile(dir+'/receipt.json',JSON.stringify(result,null,2));await browser?.close();await f.close();console.log(JSON.stringify(result));}
