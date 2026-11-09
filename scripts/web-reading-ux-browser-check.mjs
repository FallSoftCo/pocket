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
 let failAcceptedRefresh=false;const faultEvents=[];
 const answerUrl=new URL('/api/requests/question-123/answer',f.url).href;
 const headUrl=url=>url.origin===new URL(f.url).origin&&url.pathname==='/api/threads/parent-123'&&url.searchParams.get('view')==='timeline'&&!url.searchParams.has('before');
 const answerRoute=async route=>{const response=await route.fetch();assert.equal(response.status(),200,'Original answer must be accepted before injecting refresh outage');faultEvents.push({kind:'answer-accepted',status:response.status(),at:Date.now()});failAcceptedRefresh=true;await route.fulfill({response});};
 const headRoute=async route=>{if(!failAcceptedRefresh)return route.continue();faultEvents.push({kind:'refresh-outage',status:503,at:Date.now()});await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({error:'Synthetic accepted-answer refresh unavailable'})});};
 await page.route(answerUrl,answerRoute);await page.route(headUrl,headRoute);
 try{
  await page.locator('#requests').getByRole('button',{name:'Submit answers',exact:true}).click();
  await page.waitForFunction(()=>window.__decisionObservation.observations.some(o=>o.status==='Decision submitted. Live status is reconnecting.'&&o.controlsDisabled));
  report.decisionObservations=await page.evaluate(()=>window.__decisionObservation.observations);
  failAcceptedRefresh=false;faultEvents.push({kind:'outage-released',at:Date.now()});f.broadcast({type:'request',threadId:'parent-123'});await page.waitForFunction(()=>!document.querySelector('#requests input'));
  assert.equal(faultEvents[0]?.kind,'answer-accepted');assert.ok(faultEvents.some(e=>e.kind==='refresh-outage'));
  const recovered=report.httpResponses.find(r=>r.path==='/api/threads/parent-123'&&r.status===200&&r.at>=faultEvents.at(-1).at);assert.ok(recovered,'Reconciled original head must return200 after outage release');faultEvents.push({kind:'refresh-reconciled',status:200,at:recovered.at});
  const answers=f.writes.filter(w=>w.path==='/api/requests/question-123/answer');assert.equal(answers.length,1);assert.equal(answers[0].body.answers.material,'Wood');
  report.decisionRefresh={acceptedOnce:true,failedRefreshObservedWithDisabledControls:true,reconciled:true};
 }finally{report.decisionFaultEvents=faultEvents;failAcceptedRefresh=false;await page.unroute(answerUrl,answerRoute);await page.unroute(headUrl,headRoute);await page.evaluate(()=>{window.__decisionObservation?.observer.disconnect();delete window.__decisionObservation;});}
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
 await page.locator('.message .immersion-phrase').first().scrollIntoViewIfNeeded();report.desktopFrame=await saveViewportFrame(page,dir+'/desktop.png');
 await page.setViewportSize({width:390,height:844});if(!await page.locator('#review-pane').isVisible())await page.locator('#review-toggle').click();assert.equal(await page.locator('#review-pane').isVisible(),true);const drawer=await page.locator('#review-pane').boundingBox();assert.ok(drawer.x>=0&&drawer.width<=390);report.mobileFrame=await saveViewportFrame(page,dir+'/mobile.png');await page.locator('#review-pane .review-heading').getByRole('button',{name:'Close',exact:true}).click();assert.match(await page.locator('#draft').inputValue(),/Synthetic unfinished draft/);
 await captureLayoutMatrix(page,dir,report,widths,fontScales);
 report.state='passed';Object.assign(report,{pendingQuestionImmediatelyVisible:true,answerSubmittedOnce:true,acceptedAnswerSurvivesRefreshFailure:true,draftPreserved:true,userInputNotAResult:true,resultReplyNavigateDismiss:true,galleryLinkInline:true,noLayoutShiftOnFlip:true,directPhraseTap:true,mobileDrawer:true});assert.deepEqual(report.errors,[]);
}catch(error){report.state='failed';report.failure=error.message;report.fixtureWrites=f.writes;report.fixtureReads=f.reads;report.failureDom=await page?.evaluate(()=>({statuses:[...document.querySelectorAll('.request-status')].map(n=>n.textContent),requests:document.querySelector('#requests')?.textContent,error:document.querySelector('#error')?.textContent})).catch(()=>null);throw error;}finally{await page?.close();await f.close();report.ownedFixturesStopped=true;await writeFile(dir+'/review.json',JSON.stringify(report,null,2)+'\n');}
 return report;
}
async function clearVisualFontScale(page){await page.evaluate(()=>{if(window.__qaFontRules){const {sheet,index}=window.__qaFontRules;while(sheet.cssRules.length>index)sheet.deleteRule(index);delete window.__qaFontRules;}for(const n of document.querySelectorAll('[data-qa-font-size]'))delete n.dataset.qaFontSize;});}
async function applyVisualFontScale(page,fontScale){
  if(fontScale===2)await page.evaluate(()=>{
   const all=[...document.querySelectorAll('body *')], sizes=new Set();
   const values=all.map(n=>({n,size:parseFloat(getComputedStyle(n).fontSize)}));
   for(const {n,size}of values){n.dataset.qaFontSize=String(size);sizes.add(size);}
   const sheet=document.styleSheets[0],index=sheet.cssRules.length;window.__qaFontRules={sheet,index};
   for(const size of sizes)sheet.insertRule(`[data-qa-font-size="${size}"]{font-size:${size*2}px!important}`,sheet.cssRules.length);
  });
}
async function viewportPng(page,cdp){
 const {data}=await cdp.send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false,fromSurface:true});const bytes=Buffer.from(data,'base64');
 assert.equal(bytes.subarray(0,8).toString('hex'),'89504e470d0a1a0a');assert.equal(bytes.toString('ascii',12,16),'IHDR');
 const viewport=await page.evaluate(()=>({width:innerWidth,height:innerHeight,ratio:devicePixelRatio}));
 assert.equal(bytes.readUInt32BE(16),Math.round(viewport.width*viewport.ratio));assert.equal(bytes.readUInt32BE(20),Math.round(viewport.height*viewport.ratio));return bytes;
}
async function saveViewportFrame(page,path){
 const cdp=await page.context().newCDPSession(page);
 try{await page.evaluate(()=>document.fonts.ready);const bytes=await viewportPng(page,cdp);await writeFile(path,bytes);return {path,width:bytes.readUInt32BE(16),height:bytes.readUInt32BE(20),sha256:createHash('sha256').update(bytes).digest('hex')};}finally{await cdp.detach();}
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
    await clearVisualFontScale(page);
    if(state==='drawer')await page.locator('#review-toggle').click();
    if(state==='focused-short'){await page.locator('#review-pane .review-heading').getByRole('button',{name:'Close',exact:true}).click();await page.setViewportSize({width,height:width===1440?650:520});await page.locator('#draft').focus();}
  await applyVisualFontScale(page,fontScale);
    await page.evaluate(()=>document.fonts.ready);await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    const path=`layout-${width}-${fontScale}x-${state}.png`;const capture=await viewportPng(page,cdp);await writeFile(dir+'/'+path,capture);
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
   if(fontScale===2)await clearVisualFontScale(page);
  }
 }
 }finally{await cdp.detach();}
}
export async function runCatalogTitleCheck({context,dir,cases=null}){
 await mkdir(dir,{recursive:true});const f=await fixture();let page;const report={state:'running',syntheticOnly:true,frames:[],actions:{}};
 const audit=async step=>{(report.actionTrace??=[]).push({step,at:Date.now()});report.actionStep=step;report.fixtureWrites=structuredClone(f.writes);await writeFile(dir+'/action-trace.json',JSON.stringify(report,null,2)+'\n');};
 const pending=[{id:'question-123',method:'item/tool/requestUserInput',params:{threadId:'parent-123',questions:[{id:'material',question:'Which material?',options:[{label:'Wood',description:'Warm surface'},{label:'Metal',description:'Cool surface'}]}]}}];
 try{
  page=await context.newPage();await page.bringToFront();page.setDefaultTimeout(10000);await page.setViewportSize({width:1440,height:1000});
  await page.goto(f.url);await page.locator('#pair-form input[name=code]').fill('synthetic');await page.locator('#pair-form button[type=submit]').click();
  const card=page.locator('.work-card[data-id="parent-123"]');await card.locator('.preview').click();await page.locator('#requests input').waitFor();await page.locator('#draft').fill('Unsent catalog routing draft');await page.locator('#settings').click();await page.locator('#immersion-enabled').check();await page.locator('#settings-dialog .close').click();
  for(const {width,fontScale,drawer}of cases??[1440,390,320].flatMap(width=>[1,2].flatMap(fontScale=>(width===1440?[false,true]:[false]).map(drawer=>({width,fontScale,drawer}))))){
   assert.ok([1440,390,320].includes(width)&&[1,2].includes(fontScale)&&typeof drawer==='boolean');
   await clearVisualFontScale(page);await page.setViewportSize({width,height:width===1440?1000:844});
   if(await page.locator('#review-pane').isVisible())await page.locator('#review-pane .review-heading').getByRole('button',{name:'Close',exact:true}).click();
   if(width===1440&&drawer)await page.locator('#review-toggle').click();
   if(width<760&&await page.locator('#back').isVisible())await page.locator('#back').click();
   await applyVisualFontScale(page,fontScale);await card.scrollIntoViewIfNeeded();
   const geometry=await card.evaluate(n=>{const rect=x=>{const r=x.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom};};const title=n.querySelector('.card-title');return {card:rect(n),heading:rect(n.querySelector('.card-heading')),sidebar:rect(document.querySelector('#sidebar')),title:{...rect(title),text:title.textContent,font:parseFloat(getComputedStyle(title).fontSize)},actions:[...n.querySelectorAll('.card-heading>button:not(.card-title)')].map(x=>{const box=rect(x),walker=document.createTreeWalker(x,NodeFilter.SHOW_TEXT),glyphs=[];while(walker.nextNode()){const text=walker.currentNode;if(!text.textContent.trim()||getComputedStyle(text.parentElement).visibility==='hidden')continue;const range=document.createRange();range.selectNodeContents(text);for(const r of range.getClientRects())glyphs.push({x:r.x,y:r.y,right:r.right,bottom:r.bottom});}return {class:x.className,text:x.textContent,...box,glyphs};})};});
   const frame=await saveViewportFrame(page,dir+`/catalog-${width}-${fontScale}x-${drawer?'open':'closed'}.png`);report.frames.push({...frame,width,fontScale,drawer,geometry});
   assert.equal(Math.round(geometry.sidebar.width),width===1440?(drawer?285:325):width);
   assert.ok(geometry.title.width>=geometry.heading.width-1,'Title must retain the full reserved heading width');assert.ok(geometry.title.x>=geometry.card.x&&geometry.title.right<=geometry.card.right,'Reserved heading must fit the card');assert.equal(geometry.title.font,14*fontScale);assert.match(geometry.title.text,/Prepare the field guide/);
   assert.equal(geometry.actions.length,3);for(const a of geometry.actions){assert.ok(a.width>=44&&a.height>=28);assert.ok(a.y>=geometry.title.bottom-1&&a.bottom<=geometry.card.bottom+1&&a.x>=geometry.card.x&&a.right<=geometry.card.right);for(const glyph of a.glyphs)assert.ok(glyph.x>=a.x-1&&glyph.right<=a.right+1&&glyph.y>=a.y-1&&glyph.bottom<=a.bottom+1,'Action text must fit its actual button');}
   const initialHeight=geometry.card.height;
   f.setSummary('parent-123',{preview:'Updated live preview. '.repeat(30),status:{type:'idle'}});f.setRequests([]);f.broadcast({type:'conversationChanged',threadId:'parent-123'});f.broadcast({type:'request',threadId:'parent-123'});
   await page.waitForFunction(()=>document.querySelector('.work-card[data-id="parent-123"] .preview')?.textContent.startsWith('Updated live preview')&&!document.querySelector('.work-card[data-id="parent-123"] .question-badge'));
   assert.equal((await card.boundingBox()).height,initialHeight,'Live preview/status/badge changes must not resize the card');
   f.setSummary('parent-123',{preview:'Open conversation',status:{type:'active'}});f.setRequests(pending);f.broadcast({type:'conversationChanged',threadId:'parent-123'});f.broadcast({type:'request',threadId:'parent-123'});await card.locator('.question-badge').waitFor();
   assert.equal((await card.boundingBox()).height,initialHeight,'Restoring attention must not resize the card');report.frames.at(-1).liveHeightStable=true;
   await writeFile(dir+'/progress.json',JSON.stringify(report,null,2)+'\n');
  }
  await audit('clear-font');await clearVisualFontScale(page);
  await audit('rename-start');await card.locator('.card-title').click();assert.equal(await card.getByRole('textbox',{name:'Session name'}).inputValue(),'Prepare the field guide');await card.getByRole('textbox',{name:'Session name'}).press('Escape');assert.equal(f.writes.filter(w=>w.path.endsWith('/rename')).length,0);report.actions.renameCancelOriginal=true;await audit('rename-complete');
  await audit('child-start');await card.locator('.child-badge').click();await page.locator('.work-card[data-id="child-123"]').waitFor();report.actions.originalChildExpanded=true;await audit('child-complete');
  await audit('attention-start');await card.locator('.question-badge').click();await page.locator('#requests input').waitFor();assert.equal(await page.locator('#draft').inputValue(),'Unsent catalog routing draft');assert.match(await page.locator('#requests').innerText(),/Which material/);report.actions.originalAttentionAndDraft=true;await audit('attention-complete');
  await page.locator('#review-pane .review-heading').getByRole('button',{name:'Close',exact:true}).click();if(await page.locator('#back').isVisible())await page.locator('#back').click();
  await audit('archive-start');let archiveConfirmation;page.once('dialog',dialog=>{report.archiveDialog={type:dialog.type(),message:dialog.message()};archiveConfirmation=dialog.accept();archiveConfirmation.catch(()=>{});});await card.locator('.card-archive').click();assert.ok(archiveConfirmation,'Original archive confirmation must be shown');await archiveConfirmation;await audit('archive-confirmed');await page.waitForFunction(()=>!document.querySelector('.work-card[data-id="parent-123"]'));
  assert.equal(f.writes.filter(w=>w.path==='/api/threads/parent-123/archive').length,1);assert.equal(f.writes.filter(w=>w.path.endsWith('/reply')||w.path.startsWith('/api/requests/')).length,0);report.actions.originalArchiveOnce=true;report.actions.noWorkSubmitted=true;await audit('archive-complete');
  report.state='checks-passed-awaiting-visual-review';return report;
 }catch(e){report.state='failed';report.failure=e.message;await audit('failed-at-'+report.actionStep);throw e;}finally{await audit('closing-page');await page?.close();await audit('closing-fixture');await f.close();report.ownedResourcesClosed=true;await writeFile(dir+'/review.json',JSON.stringify(report,null,2)+'\n');}
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE||'playwright');const browser=await chromium.launch({headless:true});
 try{const context=await browser.newContext();console.log(JSON.stringify(await runReadingVisualCheck({context,dir:process.env.WEB_EVIDENCE_DIR})));}finally{await browser.close();}
}
