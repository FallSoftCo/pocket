// Focused rendered Chromium check against the synthetic browser fixture.
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fixture} from '../tests/web-browser-fixture.mjs';
const require=createRequire(import.meta.url),{chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const f=await fixture();let browser;
try{
 browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});const page=await browser.newPage({viewport:{width:1280,height:800}});
 await page.goto(f.url);await page.locator('#pairing').waitFor({state:'visible'});await page.locator('input[name=code]').fill('SYNTHETIC');await page.locator('#pair-form button[type=submit]').click();await page.locator('#app').waitFor({state:'visible'});
 await page.locator('[data-id=parent-123]').click();await page.locator('#title').getByText('Prepare the field guide').waitFor();
 f.emit({id:'t1/compaction',turnId:'t1',kind:'compaction',status:'completed',title:'Context compacted',text:'Earlier context was condensed. The conversation continues here.',version:98});
 await page.locator('.compaction[data-id="t1/compaction"][data-status="completed"]').getByText('Context compacted',{exact:true}).waitFor();
 f.emit({id:'t1/compaction-live',turnId:'t1',kind:'compaction',status:'inProgress',title:'Compacting conversation…',text:'Earlier context is being condensed so this work can continue.',version:101});
 const live=page.locator('.compaction[data-id="t1/compaction-live"][data-status="inProgress"]');await live.getByText('Compacting conversation…',{exact:true}).waitFor();assert.equal(await live.getAttribute('role'),'status');assert.equal(await live.getAttribute('aria-live'),'polite');
 f.emit({id:'t1/compaction-live',turnId:'t1',kind:'compaction',status:'completed',title:'Context compacted',text:'Earlier context was condensed. The conversation continues here.',version:102});
 await page.locator('.compaction[data-id="t1/compaction-live"][data-status="completed"]').waitFor();assert.equal(await page.locator('.compaction[data-id="t1/compaction-live"]').count(),1);
 console.log(JSON.stringify({passed:true,evidenceLevel:'rendered Chromium, synthetic backend',evidence:['completed compaction in history','running compaction visible with live status','completion updates same row']}));
}finally{await browser?.close();await f.close();}
