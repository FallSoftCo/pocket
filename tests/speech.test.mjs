import test from 'node:test';
import assert from 'node:assert/strict';
import {speechText,spokenSummary,spokenText,promptContext,turnPrompt} from '../server/speech.mjs';
import {pushData} from '../server/push.mjs';
const longSentence='The fix is in place and the tests all pass, so you can now open the app and read the full update when you have a moment, with no need to change any notification or connection settings on your phone.';
test('a complete summary beyond 28 words and 180 characters reaches the phone intact',()=>{
 assert.ok(longSentence.split(' ').length>28);assert.ok(longSentence.length>180);assert.ok(longSentence.length<=240);
 assert.equal(spokenSummary('Title','Body',longSentence),longSentence);
 assert.equal(pushData({id:1,created_at:1,spoken_summary:longSentence}).spoken_text,longSentence);
});
test('long fallback excerpts finish a sentence instead of pretending a clipped clause is complete',()=>{
 const input='The update is installed. '+('We still need to check the rest of this very long detail ').repeat(15)+'.';
 assert.equal(speechText(input),'The update is installed. Open Pocodex for the full update.');
 assert.equal(speechText('word '.repeat(200)),'Open Pocodex for the full update.');
 const joined=spokenSummary('Build ready',input);
 assert.equal(joined,'Build ready. The update is installed. Open Pocodex for the full update.');
});
test('UTF-8 transport budget preserves complete speech rather than clipping a multibyte sentence',()=>{
 const input='完了しました。 '+('詳しい報告内容を確認してください').repeat(30)+'。';
 const output=speechText(input);
 assert.ok(Buffer.byteLength(output)<=600);assert.ok(output.length<=360);
 assert.match(output,/^完了しました。/);assert.match(output,/full update\.$/);
 assert.equal(pushData({id:1,created_at:1,spoken_summary:output}).spoken_text,output);
});

test('full spoken messages are preserved beyond push limits and retrieved by notification ID',()=>{
 const full=('The build finished successfully and the full result is ready for review. ').repeat(90).trim();
 assert.equal(spokenText('Title','Other',full),full);
 const data=pushData({id:42,created_at:1,title:'Build ready',body:full,spoken_summary:speechText(full),spoken_text:full});
 assert.equal(data.speech_pending,'1');assert.equal(data.id,'42');assert.equal(data.spoken_text,undefined);
 assert.ok(Buffer.byteLength(JSON.stringify(data))<4096);
});

test('short full speech and compatibility preview both fit even with escaped notification text',()=>{
 const text='A complete message with a quoted \"result\" and a final sentence.';
 const data=pushData({id:1,thread_id:'thread',created_at:1,title:'"'.repeat(180),body:'"'.repeat(32000),spoken_summary:text,spoken_text:text});
 assert.equal(data.spoken_text,text);assert.equal(data.spoken_summary,text);
 assert.ok(Buffer.byteLength(JSON.stringify(data))<=3700);
});

test('speech opens with the actual prompt topic rather than the conversation title',()=>{
 const turn={items:[{type:'userMessage',content:[{type:'text',text:'Build the application'}]},{type:'userMessage',content:[{type:'text',text:'Can you please fix notification speech?'}]},{type:'agentMessage',text:'Done.'}]};
 const context=promptContext(turnPrompt(turn));assert.equal(context,'fix notification speech');
 assert.equal(turnPrompt({items:[{type:'userMessage',_pocketRow:{text:'Please add dark mode'}}]}),'Please add dark mode');
 assert.equal(spokenText('Generic chat title','The update is installed.',null,context),'fix notification speech. The update is installed.');
 assert.equal(spokenText('Generic chat title','Body','Tests pass.',context),'fix notification speech. Tests pass.');
 assert.equal(promptContext('ignored','Notification speech context extra words'),'Notification speech context');
 assert.equal(promptContext('please token=secret https://example.com'), 'Task update');
});
test('contextual full responses remain complete when push requires deferred retrieval',()=>{
 const response='The requested change is finished. '.repeat(100).trim();
 const full=spokenText('Do not read this title',response,null,'Fix notification speech');
 assert.ok(full.startsWith('Fix notification speech. '));assert.ok(full.endsWith(response));
 const data=pushData({id:91,created_at:1,spoken_text:full,spoken_summary:speechText(full)});
 assert.equal(data.speech_pending,'1');assert.equal(data.spoken_text,undefined);
});
