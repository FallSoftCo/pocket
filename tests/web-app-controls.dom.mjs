import test from 'node:test';
import assert from 'node:assert/strict';
import {webAppFixture,waitFor,settle} from './helpers/web-app-dom-fixture.mjs';
const settings=['model','effort','turn-mode','service-tier','save-turn'];
const model=name=>({models:[{model:name,name,defaultEffort:'medium',efforts:[{reasoningEffort:'medium'}],serviceTiers:[]}]});

test('actual app opens Controls before models settle and keeps Context/files and terminals reachable during outage',{timeout:15000},async t=>{
 const f=await webAppFixture(t);f.click(f.$('session-menu'));
 assert.equal(f.isOpen(),true,'CONTROLS_MUST_OPEN_BEFORE_MODEL_RESPONSE');
 assert.equal(f.models.length,1);assert.equal(f.models[0].settled,false);
 for(const id of settings)assert.equal(f.$(id).disabled,true,id+' must wait for valid model settings');
 assert.match(f.$('models-status').textContent,/Loading/);
 assert.ok(f.$('controls-dialog').contains(f.$('conversation-tools-toggle')),'Context/files belongs to the opened controls');
 f.models[0].reply(503,{error:'Synthetic model catalog outage'});
 await waitFor(()=>f.$('models-status').textContent.includes('unavailable'),'model failure feedback');
 assert.equal(f.isOpen(),true);for(const id of settings)assert.equal(f.$(id).disabled,true);
 f.$('save-turn').click();await settle();assert.equal(f.reads.some(r=>r.path.endsWith('/turn-settings')&&r.method==='POST'),false);
 f.click(f.$('conversation-tools-toggle'));
 assert.equal(f.isOpen(),false,'Context/files must close its parent popover');
 await waitFor(()=>f.d.querySelector('.conversation-tools[open]')?.textContent.includes('/synthetic/parent-123'),'independent Context/files dialog');
 assert.equal(f.reads.filter(r=>r.path==='/api/threads/parent-123/conversation-tools').length,1);
 f.d.querySelector('.conversation-tools').close();f.click(f.$('session-menu'));assert.equal(f.isOpen(),true);
 const terminal=[...f.$('controls-dialog').querySelectorAll('button')].find(b=>b.textContent==='Background terminals');f.click(terminal);
 assert.equal(f.isOpen(),false);await waitFor(()=>f.reads.some(r=>r.path==='/api/threads/parent-123/terminals'),'independent terminals read');
 assert.equal(f.models[1].settled,false,'Unrelated controls cannot await model catalogue');f.assertClean();
 t.diagnostic(JSON.stringify({evidence:'actual unmodified web app ESM graph in JSDOM; synthetic transport/browser primitives',modules:f.digests}));
});

test('actual app ignores delayed model success and failure after source change and same-source reopen',{timeout:15000},async t=>{
 const f=await webAppFixture(t);f.click(f.$('session-menu'));assert.equal(f.isOpen(),true,'CONTROLS_MUST_OPEN_BEFORE_MODEL_RESPONSE');const first=f.models[0];
 await f.select('other-123');first.reply(200,model('OLD-SOURCE-MODEL'));await settle();await settle();
 assert.equal(f.$('model').options.length,0,'STALE_SOURCE_MODEL_MUST_NOT_POPULATE');assert.equal(f.$('save-turn').disabled,true);
 f.click(f.$('session-menu'));const second=f.models[1];assert.equal(f.isOpen(),true);
 f.click(f.d.querySelector('#controls-dialog .close'));assert.equal(f.isOpen(),false);
 f.click(f.$('session-menu'));const third=f.models[2];third.reply(200,model('CURRENT-MODEL'));
 await waitFor(()=>f.$('model').value==='CURRENT-MODEL'&&!f.$('save-turn').disabled,'current reopened model result');
 second.reply(503,{error:'STALE-REOPEN-ERROR'});await settle();await settle();
 assert.equal(f.$('model').value,'CURRENT-MODEL');assert.equal(f.$('save-turn').disabled,false);assert.equal(f.$('models-status').textContent,'','STALE_REOPEN_ERROR_MUST_BE_IGNORED');
 // A later successful response from the previous opening must also be ignored.
 f.click(f.d.querySelector('#controls-dialog .close'));f.click(f.$('session-menu'));const fourth=f.models[3];
 f.click(f.d.querySelector('#controls-dialog .close'));f.click(f.$('session-menu'));const fifth=f.models[4];fifth.reply(200,model('LATEST-MODEL'));
 await waitFor(()=>f.$('model').value==='LATEST-MODEL','latest model selection');fourth.reply(200,model('STALE-REOPEN-MODEL'));await settle();await settle();
 assert.equal(f.$('model').value,'LATEST-MODEL','STALE_REOPEN_MODEL_MUST_BE_IGNORED');assert.equal(f.$('models-status').textContent,'');f.assertClean();
});

test('actual Send control submits one message without cancelling its pointer gesture',{timeout:15000},async t=>{
 const f=await webAppFixture(t);
 assert.equal(f.$('send').getAttribute('type'),'submit','Send must be an explicit form submit control');
 f.$('draft').focus();
 const pointerdown=new f.w.Event('pointerdown',{bubbles:true,cancelable:true});
 f.$('send').dispatchEvent(pointerdown);
 assert.equal(pointerdown.defaultPrevented,false,'Send must not cancel pointerdown or depend on suppressing click');
 f.$('draft').value='   ';f.click(f.$('send'));
 await waitFor(()=>!f.$('error').hidden&&/message before sending/i.test(f.$('error').textContent),'empty draft feedback');
 f.$('draft').value='One exact send';f.click(f.$('send'));
 await waitFor(()=>f.reads.filter(r=>r.method==='POST'&&r.path==='/api/threads/parent-123/reply').length===1,'one reply request');
 await waitFor(()=>f.$('draft').value==='','accepted send clears draft');
 await waitFor(()=>f.$('transcript').textContent.includes('One exact send'),'accepted send renders');
 await new Promise(resolve=>setTimeout(resolve,100));
 const replies=f.reads.filter(r=>r.method==='POST'&&r.path==='/api/threads/parent-123/reply');
 assert.equal(replies.length,1,'Send must submit exactly once');
 assert.equal(JSON.parse(replies[0].body).text,'One exact send');
 assert.deepEqual(f.errors,[]);
 assert.equal(f.$('error').hidden,true,f.$('error').textContent);
});

test('an uncertain saved send in one session does not block another session',{timeout:15000},async t=>{
 const parentPending={id:'pending-parent-001',scope:'parent-123',text:'Parent send with an unknown receipt',mode:'auto',createdAt:Date.now()-1000,deviceId:'browser-fixture',state:'unknown',error:'Synthetic uncertain receipt'};
 const f=await webAppFixture(t,{storage:{'nextcomp.web.v1.pendingSends':[parentPending]}});
 const stored=()=>JSON.parse(f.d.defaultView.localStorage.getItem('nextcomp.web.v1.pendingSends')||'[]');
 await waitFor(()=>f.reads.some(read=>read.path==='/api/replies/pending-parent-001')&&f.$('send').disabled,'parent saved send recovered');
 assert.equal(JSON.parse(f.d.defaultView.localStorage.getItem('nextcomp.web.v1.pending'))?.id,parentPending.id,'legacy receipt key remains recoverable');
 assert.equal(f.$('send').disabled,true,'the uncertain session must retain its duplicate-send guard');

 await f.select('other-123');
 assert.equal(f.$('send').disabled,false,'another session must remain sendable while the parent receipt is uncertain');
 f.$('draft').value='Other session still sends';f.click(f.$('send'));
 await waitFor(()=>f.reads.filter(read=>read.method==='POST'&&read.path==='/api/threads/other-123/reply').length===1,'other session reply request');
 await waitFor(()=>f.$('transcript').textContent.includes('Other session still sends'),'other session accepted message renders');
 assert.deepEqual(stored().map(row=>[row.id,row.scope,row.state]),[[parentPending.id,'parent-123','unknown']],'the second send must not overwrite the uncertain parent receipt');
 assert.match(f.$('outbox').textContent,/Another conversation · Saved send/,'the parent receipt remains visible from another session');
 assert.ok([...f.$('outbox').querySelectorAll('button')].some(button=>button.textContent==='Check delivery'),'the parent receipt remains recoverable');
 await new Promise(resolve=>setTimeout(resolve,250));
 assert.deepEqual(f.errors,[]);
});

test('DeepSeek balance and session tokens replace generic unavailable usage in the footer and Settings',{timeout:15000},async t=>{
 const now=Date.now();
 const providerUsage={providerId:'deepseek',configured:true,available:true,currency:'USD',totalBalance:'17.11',updatedAt:now,stale:false,state:'available'};
 const conversationUsage={last:{totalTokens:164932},total:{totalTokens:136368454},modelContextWindow:258400};
 const f=await webAppFixture(t,{providerUsage,conversationUsage});
 await waitFor(()=>f.$('usage').textContent.includes('$17.11'),'provider balance in footer');
 assert.match(f.$('conversation-usage').textContent,/\$17\.11/);
 assert.match(f.$('conversation-usage').textContent,/DeepSeek/);
 assert.match(f.$('conversation-usage').textContent,/164\.9k ctx/);
 await waitFor(()=>f.$('account-usage').textContent.includes('$17.11'),'settings usage renders');
 assert.match(f.$('account-usage').textContent,/\$17\.11 · DeepSeek API balance/);
 assert.match(f.$('account-usage').textContent,/164,932 tokens in the last context/);
 assert.match(f.$('account-usage').textContent,/136,368,454 tokens total/);
 assert.match(f.$('account-usage').textContent,/not a session charge or ChatGPT quota/);
 assert.equal(f.$('conversation-usage').dataset.providerUsage,'deepseek');
 f.assertClean();
 t.diagnostic(JSON.stringify({evidence:'actual web ESM graph with provider balance and native conversation usage',provider:f.$('conversation-usage').dataset.providerUsage}));
});
