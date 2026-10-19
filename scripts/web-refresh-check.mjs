import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {fixture} from '../tests/web-browser-fixture.mjs';
const require=createRequire(import.meta.url),{chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const dir=process.env.WEB_EVIDENCE_DIR;await mkdir(dir,{recursive:true});
const f=await fixture();let browser,page;const evidence=[],errors=[];
try{
 browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
 page=await browser.newPage({viewport:{width:1440,height:900},colorScheme:'dark'});page.setDefaultTimeout(5000);page.on('pageerror',e=>errors.push(e.message));
 await page.goto(f.url);await page.locator('#pairing').waitFor({state:'visible'});await page.locator('input[name=code]').fill('SYNTHETIC');await page.locator('#pair-form button[type=submit]').click();
 await page.locator('[data-id=parent-123]').click();await page.getByText('I found two useful options. Which material should we use?',{exact:true}).waitFor();await page.locator('#message-loading').waitFor({state:'hidden'});
 const heads=id=>f.reads.filter(r=>r.path==='/api/threads/'+id&&!r.query.includes('before=')).length;
 let initial=heads('parent-123');
 await page.evaluate(()=>{window.headerChanges=0;new MutationObserver(records=>headerChanges+=records.length).observe(document.getElementById('title'),{childList:true,subtree:true});window.loadingChanges=[];const node=document.getElementById('message-loading');new MutationObserver(records=>{for(const r of records)if(r.attributeName==='hidden')loadingChanges.push(!node.hidden);}).observe(node,{attributes:true,attributeFilter:['hidden']});});
 for(let n=0;n<60;n++){
  f.broadcast({type:'sessionPreview',threadId:'other-123',preview:'Other active preview '+n});
  f.broadcast({type:'sessionPreview',threadId:'parent-123',preview:'Current list preview '+n});
  f.broadcast({type:'timeline',threadId:'other-123',row:{id:'noise-'+n,kind:'message',text:'Other work'}});
  f.broadcast({type:'activity',items:[]});f.broadcast({type:'status',connected:true});f.broadcast({type:'future-heartbeat'});
  await page.waitForTimeout(15);
 }
 await page.waitForTimeout(650);assert.equal(heads('parent-123'),initial);assert.deepEqual(await page.evaluate(()=>loadingChanges),[]);assert.equal(await page.evaluate(()=>headerChanges),0);
 await page.locator('[data-id=other-123] .preview').filter({hasText:'Other active preview 59'}).waitFor();
 evidence.push('360 real WebSocket events for previews, other threads, status and aggregates do not read or flash the selected transcript');
 f.emit({id:'t1/update',turnId:'t1',kind:'message',text:'Known streamed row updates immediately',version:900});
 await page.getByText('Known streamed row updates immediately',{exact:true}).waitFor();assert.equal(heads('parent-123'),initial);
 evidence.push('known current row streams directly without a head reread');
 f.broadcast({type:'reply',threadId:'parent-123',state:'accepted'});
 for(let attempt=0;attempt<60&&heads('parent-123')===initial;attempt++)await page.waitForTimeout(50);
 assert.equal(heads('parent-123'),initial+1);await page.waitForTimeout(650);
 assert.deepEqual(await page.evaluate(()=>loadingChanges),[]);initial++;
 evidence.push('a genuine fast cached-head read completes without a loading flash');

 const bounds=await page.locator('#transcript').boundingBox();f.holdHead('parent-123');
 f.emit({id:'fresh-one',kind:'message',text:'A real new row needs canonical head',version:901});
 await page.waitForFunction(()=>document.querySelector('#message-loading').getAttribute('hidden')!==null);
 await page.locator('#message-loading').waitFor({state:'visible'});
 assert.deepEqual(await page.locator('#transcript').boundingBox(),bounds);
 for(let n=0;n<5;n++){f.emit({id:'fresh-extra-'+n,kind:'message',text:'Canonical burst '+n,version:902+n});await page.waitForTimeout(50);}
 await page.waitForTimeout(650);assert.equal(heads('parent-123'),initial+1);f.releaseHead('parent-123');
 await page.getByText('Canonical burst 4',{exact:true}).waitFor();await page.locator('#message-loading').waitFor({state:'hidden'});await page.waitForTimeout(200);
 assert.equal(heads('parent-123'),initial+2);assert.deepEqual(await page.evaluate(()=>loadingChanges),[true,false]);
 evidence.push('slow logical head plus coalesced follow-up shows one continuous loading interval and never resizes the reader');
 await page.locator('#draft').fill('Draft survives background reconciliation');
 f.holdHead('parent-123');f.failHead('parent-123');f.emit({id:'held-parent',kind:'message',text:'Held parent head',version:920});await page.locator('#message-loading').waitFor({state:'visible'});
 f.holdHead('other-123');await page.locator('[data-id=other-123]').click();await page.locator('#message-loading').waitFor({state:'visible'});
 f.releaseHead('parent-123');await page.waitForTimeout(700);assert.equal(await page.locator('#message-loading').isVisible(),true);assert.equal(await page.locator('#error').isVisible(),false);
 f.releaseHead('other-123');await page.locator('#transcript table').waitFor();await page.locator('#message-loading').waitFor({state:'hidden'});
 await page.locator('[data-id=parent-123]').click();assert.equal(await page.locator('#draft').inputValue(),'Draft survives background reconciliation');await page.locator('#message-loading').waitFor({state:'hidden'});
 evidence.push('late failure from another scope cannot hide current loading or inject its error; navigation preserves drafts');
 f.failHead('parent-123');f.emit({id:'failure-probe',kind:'message',text:'Preserved after failing refresh',version:930});
 await page.locator('#error').filter({hasText:'Synthetic current-head unavailable'}).waitFor();await page.locator('#message-loading').waitFor({state:'hidden'});
 await page.getByText('Known streamed row updates immediately',{exact:true}).waitFor();
 assert.equal(f.writes.filter(r=>r.path.endsWith('/reply')).length,0);assert.deepEqual(errors,[]);
 evidence.push('genuine read failure remains actionable while retained messages and unsent input remain intact');
 await page.screenshot({path:dir+'/refresh.png',fullPage:true});await writeFile(dir+'/receipt.json',JSON.stringify({passed:true,evidence,headReads:heads('parent-123'),pageErrors:errors},null,2));console.log(JSON.stringify({passed:true,evidence}));
}catch(error){await writeFile(dir+'/failure.json',JSON.stringify({message:error.message,pageErrors:errors,evidence,reads:f.reads},null,2));if(page){await page.screenshot({path:dir+'/failure.png',fullPage:true}).catch(()=>{});await writeFile(dir+'/failure.html',await page.content()).catch(()=>{});}throw error;}finally{await browser?.close();await f.close();}
