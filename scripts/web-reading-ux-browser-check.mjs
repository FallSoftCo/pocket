import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';import {pathToFileURL} from 'node:url';import {mkdir,writeFile} from 'node:fs/promises';
import {fixture} from '../tests/web-browser-fixture.mjs';
export async function runReadingVisualCheck({context,dir,visualOnly=false,decisionOnly=false,widths=[1440,390,320],fontScales=[1,2]}){
 await mkdir(dir,{recursive:true});
 if(!Array.isArray(fontScales)||!fontScales.length||fontScales.some(f=>![1,2].includes(f)))throw Error('Select supported text scales');
 if(!Array.isArray(widths)||!widths.length||widths.some(w=>![1440,390,320].includes(w))||new Set(widths).size!==widths.length)throw Error('Select supported visual matrix widths');
 const f=await fixture();let page;const report={state:'running',syntheticOnly:true,errors:[],httpResponses:[],visualMatrix:[],limits:['Text enlargement applies computed DOM fonts, not OS accessibility settings.','Reduced viewport with focused editor is not native IME proof.','Component phrase probe is separate from real message capture.']};
 try{
 page=await context.newPage();await page.bringToFront();await page.setViewportSize({width:1440,height:1000});page.setDefaultTimeout(8000);page.on('pageerror',e=>report.errors.push(e.message));page.on('response',r=>{const u=new URL(r.url());if(u.pathname.startsWith('/api/'))report.httpResponses.push({path:u.pathname,query:u.search,status:r.status(),at:Date.now()});});
 await page.goto(f.url);await page.locator('#pair-form input[name=code]').fill('synthetic');await page.locator('#pair-form button[type=submit]').click();await page.locator('.work-card[data-id="parent-123"] .preview').click();
 await page.locator('#requests input').waitFor();assert.equal(await page.locator('#review-pane').isVisible(),true);assert.match(await page.locator('#review-toggle').innerText(),/Needs you/);assert.equal(await page.locator('#answers-filter').count(),0);
 if(visualOnly){
  await page.locator('#draft').fill('Synthetic unfinished draft');
  await page.locator('#review-pane .review-heading').getByRole('button',{name:'Close',exact:true}).click();
  await page.locator('#settings').click();await page.locator('#immersion-enabled').check();await page.locator('#settings-dialog .close').click();await page.waitForSelector('.message .immersion-phrase');
  await captureLayoutMatrix(page,dir,report,widths,fontScales);report.state='captured';report.geometryState='passed';report.visualReviewRequired=true;report.visualOnly=true;assert.deepEqual(report.errors,[]);return report;
 }
 await page.locator('#requests').getByRole('button',{name:'Wood',exact:true}).click();assert.equal(await page.locator('#requests input').inputValue(),'Wood');
 await page.locator('#requests input').focus();f.addRequest({id:'second-request',method:'item/commandExecution/requestApproval',params:{threadId:'parent-123',message:'Allow the synthetic check?'}});f.broadcast({type:'request',threadId:'parent-123'});await page.waitForSelector('[data-request-id=second-request]');assert.equal(await page.locator('#requests input').inputValue(),'Wood');assert.equal(await page.locator('#requests input').evaluate(n=>document.activeElement===n),true);
 await page.locator('#draft').fill('Synthetic unfinished draft');await page.locator('#review-pane .review-heading').getByRole('button',{name:'Close',exact:true}).click();assert.equal(await page.locator('#draft').inputValue(),'Synthetic unfinished draft');await page.locator('#review-toggle').click();
 // A successful background read may remove this submitted card before the
 // browser's next animation-frame poll. Retain the actual DOM transition before
 // clicking, rather than requiring a transient recovery notice to stay visible.
 await page.evaluate(()=>{
  const observations=[];const record=()=>{const card=document.querySelector('[data-request-id="question-123"]');if(!card)return;
   observations.push({status:card.querySelector('.request-status')?.textContent,controlsDisabled:[...card.querySelectorAll('button,input')].every(n=>n.disabled)});
  };
  const observer=new MutationObserver(record);observer.observe(document.querySelector('#requests'),{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['disabled']});
  window.__decisionObservation={observations,observer};
 });
 try{
  f.failHead('parent-123');await page.locator('#requests').getByRole('button',{name:'Submit answers',exact:true}).click();
  await page.waitForFunction(()=>window.__decisionObservation.observations.some(o=>o.status==='Decision submitted. Live status is reconnecting.'&&o.controlsDisabled));
  report.decisionObservations=await page.evaluate(()=>window.__decisionObservation.observations);
  f.broadcast({type:'request',threadId:'parent-123'});await page.waitForFunction(()=>!document.querySelector('#requests input'));
  const answers=f.writes.filter(w=>w.path==='/api/requests/question-123/answer');assert.equal(answers.length,1);assert.equal(answers[0].body.answers.material,'Wood');
  report.decisionRefresh={acceptedOnce:true,failedRefreshObservedWithDisabledControls:true,reconciled:true};
 }finally{await page.evaluate(()=>{window.__decisionObservation?.observer.disconnect();delete window.__decisionObservation;});}
 if(decisionOnly){report.state='passed';report.decisionOnly=true;return report;}

 // Saved results are actionable, and duplicate user inputs never appear there.
 f.emit({id:'saved-user',kind:'user',text:'I found two useful options. Which material should we use?'});await page.waitForFunction(()=>document.querySelectorAll('.saved-result').length===0);
 f.rows.get('parent-123').splice(f.rows.get('parent-123').findIndex(r=>r.id==='saved-user'),1);await page.reload();await page.waitForSelector('.saved-result',{state:'attached'});await page.locator('#review-toggle').click();
 const result=page.locator('.saved-result').first();await result.getByRole('button',{name:'Reply',exact:true}).click();assert.match(await page.locator('#draft').inputValue(),/About this result/);assert.match(await page.locator('#draft').inputValue(),/Synthetic unfinished draft/);
 await result.getByRole('button',{name:'In conversation',exact:true}).click();assert.equal(await page.locator('.result-located').count(),1);
 await result.getByRole('button',{name:'Dismiss',exact:true}).click();await page.waitForFunction(()=>document.querySelectorAll('.saved-result').length===0);assert.ok(f.writes.some(w=>w.path.endsWith('/notes/remove')));
 // Existing gallery links, including bare pasted URLs, require no agent-specific post.
 await page.route('**/8bar/showcase-catalog.json',route=>route.fulfill({contentType:'application/json',body:JSON.stringify([{id:'synthetic-study',title:'Synthetic material study',poster:'poster.svg',width:640,height:360}])}));
 await page.route('**/8bar/poster.svg',route=>route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360"><rect width="640" height="360" fill="#ece8dc"/><rect x="24" y="24" width="280" height="252" rx="4" fill="#b69972"/><rect x="336" y="24" width="280" height="252" rx="4" fill="#476569"/><text x="24" y="321" font-family="sans-serif" font-size="24" fill="#172326">Synthetic material study</text></svg>'}));
 f.emit({id:'gallery-preview',kind:'message',text:`Review the study: ${f.url}/8bar/#synthetic-study`});await page.waitForSelector('.inline-gallery img');await page.waitForFunction(()=>document.querySelector('.inline-gallery img')?.naturalWidth>0);assert.equal(await page.locator('.inline-gallery').count(),1);
 // Real DOM/intrinsic layout: aligned grammatical units survive every phase and direct tap.
 const geometry=await page.evaluate(async()=>{
  const {flipFlopPlan}=await import('./flip-flop.mjs');const {phraseSlots}=await import('./phrase-slots.mjs');const {richText}=await import('./presentation.mjs');
  const pair=flipFlopPlan([{target:'La correzione',meaning:'The correction'},{target:' ',meaning:' ',literal:true},{target:'è pronta',meaning:'is ready'}]);const region=document.createElement('section');region.id='phrase-study';region.style.cssText='width:310px;font-size:16px;line-height:1.7;padding:12px';document.querySelector('#transcript').append(region);const slots=phraseSlots(pair,0,richText);region.append(slots.root);
  const measure=()=>[...region.querySelectorAll('.immersion-phrase')].map(n=>{const r=n.getBoundingClientRect();return {x:r.x,y:r.y,w:r.width,h:r.height};});const before=measure();for(let phase=1;phase<4;phase++)slots.setPhase(phase);const after=measure();const box=region.querySelector('.immersion-phrase'),language=box.dataset.language;box.click();const toggled=box.dataset.language!==language;const visible=[...box.querySelectorAll('.phrase-form')].filter(n=>getComputedStyle(n).visibility==='visible');return {before,after,toggled,visible:visible.length,count:slots.count};
 });assert.deepEqual(geometry.before,geometry.after);assert.equal(geometry.visible,1);assert.equal(geometry.toggled,true);assert.equal(geometry.count,2);report.phraseGeometry=geometry;
 f.setRequests([{id:'followup-question',method:'item/tool/requestUserInput',params:{threadId:'parent-123',questions:[{id:'finish',question:'Which finish should I use?',options:[{label:'Natural',description:'Keep the grain'},{label:'Painted',description:'Cover the grain'}]}]}}]);f.broadcast({type:'request',threadId:'parent-123'});await page.waitForSelector('#requests input');
 await page.locator('#settings').click();await page.locator('#immersion-enabled').check();await page.locator('#settings-dialog .close').click();await page.waitForSelector('.message .immersion-phrase');
 await page.locator('.message .immersion-phrase').first().scrollIntoViewIfNeeded();await page.screenshot({path:dir+'/desktop.png'});
 await page.setViewportSize({width:390,height:844});if(!await page.locator('#review-pane').isVisible())await page.locator('#review-toggle').click();assert.equal(await page.locator('#review-pane').isVisible(),true);const drawer=await page.locator('#review-pane').boundingBox();assert.ok(drawer.x>=0&&drawer.width<=390);await page.screenshot({path:dir+'/mobile.png'});await page.locator('#review-pane .review-heading').getByRole('button',{name:'Close',exact:true}).click();assert.match(await page.locator('#draft').inputValue(),/Synthetic unfinished draft/);
 await captureLayoutMatrix(page,dir,report,widths,fontScales);
 report.state='passed';Object.assign(report,{pendingQuestionImmediatelyVisible:true,answerSubmittedOnce:true,acceptedAnswerSurvivesRefreshFailure:true,draftPreserved:true,userInputNotAResult:true,resultReplyNavigateDismiss:true,galleryLinkInline:true,noLayoutShiftOnFlip:true,directPhraseTap:true,mobileDrawer:true});assert.deepEqual(report.errors,[]);
}catch(error){report.state='failed';report.failure=error.message;report.fixtureWrites=f.writes;report.fixtureReads=f.reads;report.failureDom=await page?.evaluate(()=>({statuses:[...document.querySelectorAll('.request-status')].map(n=>n.textContent),requests:document.querySelector('#requests')?.textContent,error:document.querySelector('#error')?.textContent})).catch(()=>null);throw error;}finally{await page?.close();await f.close();report.ownedFixturesStopped=true;await writeFile(dir+'/review.json',JSON.stringify(report,null,2)+'\n');}
 return report;
}
async function captureLayoutMatrix(page,dir,report,widths,fontScales){
 const cdp=await page.context().newCDPSession(page);
 try{
 // Retain actual production DOM at each declared layout; screenshots precede
 // geometry assertions so a failed visual case remains available for inspection.
 for(const width of widths)for(const fontScale of fontScales){
  await page.setViewportSize({width,height:width===1440?1000:844});

  try{
   for(const state of ['conversation','drawer','focused-short']){
    await page.evaluate(()=>{if(window.__qaFontRules){const {sheet,index}=window.__qaFontRules;while(sheet.cssRules.length>index)sheet.deleteRule(index);delete window.__qaFontRules;}for(const n of document.querySelectorAll('[data-qa-font-size]'))delete n.dataset.qaFontSize;});
    if(state==='drawer')await page.locator('#review-toggle').click();
    if(state==='focused-short'){await page.locator('#review-pane .review-heading').getByRole('button',{name:'Close',exact:true}).click();await page.setViewportSize({width,height:width===1440?650:520});await page.locator('#draft').focus();}
  if(fontScale===2)await page.evaluate(()=>{
   const all=[...document.querySelectorAll('body *')], sizes=new Set();
   const values=all.map(n=>({n,size:parseFloat(getComputedStyle(n).fontSize)}));
   for(const {n,size}of values){n.dataset.qaFontSize=String(size);sizes.add(size);}
   const sheet=document.styleSheets[0],index=sheet.cssRules.length;window.__qaFontRules={sheet,index};
   for(const size of sizes)sheet.insertRule(`[data-qa-font-size="${size}"]{font-size:${size*2}px!important}`,sheet.cssRules.length);
  });
    await page.evaluate(()=>document.fonts.ready);await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    const path=`layout-${width}-${fontScale}x-${state}.png`;const capture=Buffer.from((await cdp.send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false,fromSurface:true})).data,'base64');await writeFile(dir+'/'+path,capture);
    const geometry=await page.evaluate(()=>{
     const bounds=n=>{const r=n.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom};};
     const visible=n=>n&&getComputedStyle(n).visibility!=='hidden'&&n.getClientRects().length>0;
     const selectors=['#draft','#review-toggle','#review-pane','.transcript-toolbar','#conversation-usage','#conversation-usage .account-source'];
     return {viewport:{width:innerWidth,height:innerHeight},documentWidth:document.documentElement.scrollWidth,focused:document.activeElement?.id,fontSamples:['#draft','#review-pane h2','.message .phrase-form'].map(selector=>({selector,node:document.querySelector(selector)})).filter(v=>v.node).map(v=>({selector:v.selector,size:parseFloat(getComputedStyle(v.node).fontSize),base:Number(v.node.dataset.qaFontSize)||null})),controls:selectors.map(selector=>({selector,node:document.querySelector(selector)})).filter(v=>visible(v.node)).map(v=>({selector:v.selector,...bounds(v.node)})),phrases:[...document.querySelectorAll('.message .immersion-phrase')].filter(visible).map(bounds)};
    });
    report.visualMatrix.push({width,fontScale,state,path,sha256:createHash('sha256').update(capture).digest('hex'),geometry});await writeFile(dir+'/progress.json',JSON.stringify(report,null,2)+'\n');
    if(fontScale===2)for(const sample of geometry.fontSamples)assert.ok(sample.base&&Math.abs(sample.size-sample.base*2)<.1,`Text enlargement was not applied to ${sample.selector} at ${path}`);
    assert.ok(geometry.documentWidth<=width+1,`Document horizontal overflow at ${path}`);
    for(const box of geometry.controls)assert.ok(box.x>=-1&&box.right<=width+1,`${box.selector} exceeds horizontal viewport at ${path}`);
    if(state==='focused-short')assert.equal(geometry.focused,'draft');
    for(const box of geometry.controls.filter(v=>v.selector!=='#review-pane'))assert.ok(box.y>=-1&&box.bottom<=geometry.viewport.height+1,`${box.selector} exceeds vertical viewport at ${path}`);
    const toolbar=geometry.controls.find(v=>v.selector==='.transcript-toolbar');assert.ok(toolbar.height<=geometry.viewport.height*.6,`Toolbar consumes reading viewport at ${path}`);
   }
  }finally{
   if(fontScale===2)await page.evaluate(()=>{if(window.__qaFontRules){const {sheet,index}=window.__qaFontRules;while(sheet.cssRules.length>index)sheet.deleteRule(index);delete window.__qaFontRules;}for(const n of document.querySelectorAll('[data-qa-font-size]'))delete n.dataset.qaFontSize;});
  }
 }
 }finally{await cdp.detach();}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE||'playwright');const browser=await chromium.launch({headless:true});
 try{const context=await browser.newContext();console.log(JSON.stringify(await runReadingVisualCheck({context,dir:process.env.WEB_EVIDENCE_DIR})));}finally{await browser.close();}
}
