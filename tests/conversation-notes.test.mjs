import test from 'node:test';import assert from 'node:assert/strict';import {DatabaseSync} from 'node:sqlite';import {ConversationNotes,interimExcerpt} from '../server/conversation-notes.mjs';
test('useful interim answer persists after final response and across reopening',()=>{
 const db=new DatabaseSync(':memory:');const n=new ConversationNotes(db);
 n.remember({id:'t',turns:[{id:'turn',items:[{id:'u',type:'userMessage',content:'Fix the UI. Is GitHub up to date?'},{id:'a',type:'agentMessage',phase:'commentary',text:'GitHub contains the previous release. The latest layout changes are pending.'},{id:'f',type:'agentMessage',phase:'final_answer',text:'Done.'}]}]});
 assert.equal(n.list('t').length,1);assert.match(n.list('t')[0].question,/GitHub/);
 assert.match(new ConversationNotes(db).list('t')[0].text,/previous release/);
 assert.equal(n.list('other').length,0);
});
test('status announcements and raw reasoning are excluded; source words preserved',()=>{
 assert.equal(interimExcerpt({type:'reasoning',summary:['private?']}),null);
 assert.equal(interimExcerpt({type:'agentMessage',phase:'commentary',text:'I’ll check the server.'}),null);
 const answer='The allowance resets on Tuesday.';
 assert.equal(interimExcerpt({type:'agentMessage',phase:'commentary',text:'I’ll check the server.\n\n'+answer}),answer);
});
test('dismissals survive history backfill; manual pins restore exact text without duplication',()=>{
 const n=new ConversationNotes(new DatabaseSync(':memory:'));
 const item={id:'a',type:'agentMessage',phase:'commentary',text:'The answer is available before the final response.'};
 n.observe('t','turn',item);n.remove('t','a');n.observe('t','turn',item);assert.equal(n.list('t').length,0);
 n.put('t',{id:'a',text:item.text,manual:true});n.observe('t','turn',item);
 assert.equal(n.list('t').length,1);assert.equal(n.list('t')[0].manual,1);
});
