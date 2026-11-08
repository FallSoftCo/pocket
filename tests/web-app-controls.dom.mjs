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
