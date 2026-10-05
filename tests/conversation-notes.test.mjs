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

test('same-text manual repin restores a removed manual note and refreshes manual ordering',()=>{
 const db=new DatabaseSync(':memory:');let clock=0;const events=[];const n=new ConversationNotes(db,(threadId,notes)=>events.push({threadId,notes}),()=>++clock);
 n.put('t',{id:'first',text:'Keep this precise answer.',manual:true});n.put('t',{id:'second',text:'Another precise answer.',manual:true});const removed=n.remove('t','first');assert.deepEqual(removed,n.list('t'));assert.deepEqual(events.at(-1),{threadId:'t',notes:removed});assert.deepEqual(removed.map(x=>x.id),['second']);
 n.put('t',{id:'first',text:'Keep this precise answer.',manual:true});assert.deepEqual(n.list('t').map(x=>x.id),['first','second']);assert.equal(n.list('t')[0].manual,1);assert.equal(n.list('t')[0].text,'Keep this precise answer.');assert.equal(db.prepare('SELECT dismissed FROM conversation_notes WHERE item_id=?').get('first').dismissed,0);
});
test('removed identities survive more than a hundred newer notes and reopened history backfill',()=>{
 const db=new DatabaseSync(':memory:');let clock=0;let n=new ConversationNotes(db,()=>{},()=>++clock);const item={id:'removed',type:'agentMessage',phase:'commentary',text:'The interim answer should stay removed permanently.'};n.observe('t','old-turn',item);n.remove('t','removed');for(let i=0;i<130;i++)n.put('t',{id:'later-'+i,text:'A later public useful answer '+i,manual:false});
 assert.equal(db.prepare('SELECT dismissed FROM conversation_notes WHERE thread_id=? AND item_id=?').get('t','removed').dismissed,1);assert.equal(db.prepare('SELECT COUNT(*) AS n FROM conversation_notes WHERE thread_id=? AND dismissed=0').get('t').n,100);
 n=new ConversationNotes(db);n.remember({id:'t',turns:[{id:'old-turn',items:[item]}]});assert.equal(n.list('t').some(x=>x.id==='removed'),false);assert.equal(n.list('other').length,0);
});
test('removal before history arrives is durable thread-scoped and invalid IDs expose400',()=>{
 const db=new DatabaseSync(':memory:');const events=[];const n=new ConversationNotes(db,(threadId,notes)=>events.push({threadId,notes}));assert.deepEqual(n.remove('t','pending'),[]);assert.deepEqual(events.at(-1),{threadId:'t',notes:[]});
 const item={id:'pending',type:'agentMessage',phase:'commentary',text:'A later historical answer cannot undo removal.'};n.observe('t','turn',item);n.observe('other','turn',item);assert.equal(n.list('t').length,0);assert.equal(n.list('other').length,1);
 for(const id of ['', '   ', 'x'.repeat(513),null])assert.throws(()=>n.remove('t',id),e=>e.status===400);assert.equal(n.list('other').length,1);
});
test('history hydration commits one complete note snapshot and repeated visits do not replay unchanged notes',()=>{const db=new DatabaseSync(':memory:');const events=[];const n=new ConversationNotes(db,(threadId,notes)=>events.push({threadId,notes}));const thread={id:'t',turns:[{id:'turn',items:[{id:'a',type:'agentMessage',phase:'commentary',text:'The first precise answer is available.'},{id:'b',type:'agentMessage',phase:'commentary',text:'The second precise answer is available.'}]}]};n.remember(thread);assert.equal(events.length,1);assert.equal(events[0].notes.length,2);n.remember(thread);assert.equal(events.length,1);db.close();});
