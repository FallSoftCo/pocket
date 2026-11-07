import {createRequire} from 'node:module';
import {mkdir,rename} from 'node:fs/promises';
import {fixture} from '../tests/web-browser-fixture.mjs';
const require=createRequire(import.meta.url),{chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const dir=process.env.WEB_EVIDENCE_DIR;await mkdir(dir,{recursive:true});const f=await fixture();let browser;
try{
 browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
 const context=await browser.newContext({viewport:{width:1280,height:800},colorScheme:'dark',recordVideo:{dir,size:{width:1280,height:800}}});
 const page=await context.newPage();await page.goto(f.url);await page.locator('input[name=code]').fill('SYNTHETIC');await page.locator('#pair-form button[type=submit]').click();await page.locator('[data-id=parent-123]').click();await page.locator('#coordinator canvas').waitFor({state:'visible'});await page.waitForTimeout(3000);await page.locator('#draft').fill('The modeled symbols stay alive while I type.');await page.screenshot({path:dir+'/modeled-symbols.png'});await page.waitForTimeout(3000);
 const video=page.video();await context.close();await rename(await video.path(),dir+'/modeled-symbols.webm');
}finally{await browser?.close();await f.close();}
