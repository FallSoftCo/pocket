import {fixture} from './web-browser-fixture.mjs';
const row=(n,text,kind='message')=>({id:'item:'+n,key:[n,1,'i'+n],row:{id:'turn/i'+n,turnId:'turn',kind,text,version:1,_nativePosition:n,_nativeRank:1,_nativeId:'i'+n,...(kind==='user'?{_inputOrder:'canonical'}:{})}});
const entries=[row(9,'Current question, not the October cached island.','user'),row(10,'Current canonical answer follows its original question.')];
const page={thread:{id:'parent-123',name:'Prepare the field guide',status:{type:'active'}},historyHead:{epoch:'qa-one'},timeline:{native:1,entries,rows:entries.map(e=>e.row),before:null,hasEarlier:false},pending:[],outgoing:[],notes:[]};
// Reuse this rendered journey through the trusted browser's existing context.
export async function initialEmptyJourney(context,{evidenceDir}={}){
 const assert=(ok,message)=>{if(!ok)throw Error(message);};
 const observed=structuredClone(page);
 const f=await fixture({port:18987,observationPages:{'parent-123':observed}});
 const append=(n,text)=>{const entry=row(n,text);observed.timeline.entries.push(entry);observed.timeline.rows.push(entry.row);};
 const sourceBPage=structuredClone(page);sourceBPage.timeline.entries[1].row.text='Other source retained canonical answer.';sourceBPage.timeline.rows=sourceBPage.timeline.entries.map(e=>e.row);
 const sourceB=await fixture({port:18988,origin:'http://localhost:18988',observationPages:{'parent-123':sourceBPage}});
 const tab=await context.newPage(),otherTab=await context.newPage(),stages=[];
 const count=()=>f.reads.filter(r=>/^\/api\/threads\/parent-123(?:\/native-timeline|\/head)?$/.test(r.path)).length;
 const text=()=>tab.locator('#transcript').innerText();
 const select=async id=>tab.locator('.work-card[data-id="'+id+'"]').evaluate(n=>n.click());
 const contains=async value=>tab.waitForFunction(v=>document.querySelector('#transcript')?.innerText.includes(v),value);
 const settle=()=>tab.waitForTimeout(1500); // bounded UI scheduler window, no model polling
 const shot=async name=>{if(evidenceDir)await tab.screenshot({path:evidenceDir+'/'+name+'.png',fullPage:true});};
 try{
  await tab.setViewportSize({width:1280,height:900});await otherTab.setViewportSize({width:1280,height:900});
  await otherTab.goto(sourceB.url);await otherTab.locator('#pair-form input[name="code"]').fill('synthetic');await otherTab.locator('#pair-form').evaluate(n=>n.requestSubmit());await otherTab.locator('.work-card[data-id="parent-123"]').waitFor();await otherTab.locator('.work-card[data-id="parent-123"]').evaluate(n=>n.click());
  await otherTab.waitForFunction(()=>document.querySelector('#transcript')?.innerText.includes('Other source retained canonical answer.'));
  const sourceBText=await otherTab.locator('#transcript').innerText();
  // Exact ephemeral loopback origin; only synthetic fixture credentials.
  await tab.goto(f.gatewayAddress);
  await tab.locator('#pair-form input[name="code"]').fill('synthetic');
  await tab.locator('#pair-form').evaluate(n=>n.requestSubmit());
  await tab.locator('.work-card[data-id="parent-123"]').waitFor();
  await select('other-123');await contains('warm light');const other=await text();
  await select('parent-123');await contains('Current canonical answer');await settle();
  f.setReadRefusal('parent-123',true);
  f.broadcast({type:'conversationChanged',threadId:'parent-123'});
  await tab.waitForFunction(()=>document.body.innerText.includes('Codex has not stored a first message'));
  assert((await text()).includes('Current canonical answer'),'Typed refusal erased cached message');
  assert(!(await text()).includes('No messages yet.'),'Typed refusal fabricated empty transcript');
  await shot('initial-empty-retained');const refused=count();stages.push({stage:'retained',reads:refused});
  for(let i=0;i<3;i++){f.broadcast({type:'attention',threadId:'parent-123'});await settle();}
  assert(count()===refused,'Unchanged observations repeated history transport');stages.push({stage:'unchanged-suppressed',reads:count()});
  append(11,'Recovered by explicit reopen.');f.setReadRefusal('parent-123',false);
  await select('other-123');await contains('warm light');assert(await text()===other,'Other session changed during refusal');
  await select('parent-123');await contains('Recovered by explicit reopen.');await settle();
  assert(count()>refused,'Explicit reopen failed to perform a recovery read');stages.push({stage:'reopen-recovered',reads:count()});
  f.setReadRefusal('parent-123',true);f.broadcast({type:'conversationChanged',threadId:'parent-123'});
  await tab.waitForFunction(()=>document.body.innerText.includes('Codex has not stored a first message'));await settle();const second=count();
  append(12,'Recovered by native progress.');f.setReadRefusal('parent-123',false);
  f.broadcast({type:'codex',threadId:'parent-123',event:{method:'item/completed',params:{threadId:'parent-123'}}});
  await contains('Recovered by native progress.');await settle();assert(count()>second,'Native progress failed to admit a recovery read');
  assert((await text()).includes('Current canonical answer'),'Progress recovery lost cached content');await shot('initial-empty-recovered');
  stages.push({stage:'progress-recovered',reads:count()});
  assert(await otherTab.locator('#transcript').innerText()===sourceBText,'Same-ID rows in other paired source changed');
  const work=[...f.writes,...sourceB.writes].filter(w=>w.path!=='/api/pair');assert(work.length===0,'Read journey submitted work');
  return {status:'passed',stages,reads:f.reads,workSubmissions:work.length,otherSessionUnchanged:true,sameIdOtherSourceUnchanged:true,limits:['Successful reads may also occur through the existing 10-second UI reconciliation; exact suppression is asserted only while typed refusal is retained.','Two separately paired synthetic origins verified; aggregated multi-environment gateway identity routing remains outside this journey.']};
 }catch(error){await shot('initial-empty-failed');return {status:'failed',error:error.stack,stages,reads:f.reads,writes:f.writes.map(w=>({path:w.path})),body:await tab.locator('body').innerText(),otherSourceBody:await otherTab.locator('body').innerText()};}
 finally{await tab.close();await otherTab.close();await f.close();await sourceB.close();}
}
if(process.argv[1]&&import.meta.url===new URL('file://'+process.argv[1]).href){
 const f=await fixture({port:18985,observationPages:{'parent-123':page}});
 console.log('READY 18985');process.on('SIGTERM',async()=>{await f.close();process.exit(0)});
}
