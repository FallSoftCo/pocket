import test from 'node:test';
import assert from 'node:assert/strict';
import {webAppFixture,waitFor} from './helpers/web-app-dom-fixture.mjs';

test('actual app follows a provider continuation from the source to its successor without resubmitting',{timeout:15000},async t=>{
 const f=await webAppFixture(t);
 assert.equal(f.$('title').textContent,'Prepare the field guide');
 f.broadcast({type:'conversationContinued',threadId:'other-123',sourceThreadId:'parent-123',reason:'payload-budget'});
 await waitFor(()=>f.$('title').textContent==='Compare lighting studies'&&f.$('transcript').textContent.includes('warm light study'),'successor transcript rendered');
 assert.ok(f.reads.some(read=>read.path==='/api/threads/other-123/native-timeline'),'successor was read');
 assert.equal(f.reads.some(read=>read.path==='/api/threads/other-123/reply'),false,'continuation must not resubmit the message');
 f.assertClean();
});
