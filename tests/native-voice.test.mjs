import test from 'node:test';import assert from 'node:assert/strict';import {EventEmitter} from 'node:events';import {DatabaseSync} from 'node:sqlite';
import {NativeVoice} from '../server/native-voice.mjs';
function setup(){const codex=new EventEmitter(),db=new DatabaseSync(':memory:'),calls=[],rows=new Map();let sends=0,threads=0;codex.connect=async()=>{};codex.call=async(method,params)=>{calls.push({method,params});if(method==='account/read')return {account:{type:'chatgpt'}};if(method==='thread/start')return {thread:{id:++threads===1?'audio-thread':`audio-thread-${threads}`}};if(method==='thread/realtime/start')queueMicrotask(()=>codex.emit('event',{method:'thread/realtime/sdp',params:{threadId:params.threadId,sdp:'answer'}}));return {}};codex.answer=()=>{};const voice=new NativeVoice({codex,cwd:'/tmp',controller:{db,get:(device,id)=>rows.get(device+id),submitText:(device,id,text)=>{sends++;const r={id,transcript:text};rows.set(device+id,r);return r}}});return {voice,codex,calls,db,get sends(){return sends},cleanup:async()=>{await voice.stop('device');clearInterval(voice.timer);db.close()}};}
test('native audio is isolated, owner checked, and partial transcripts cannot execute tasks',async()=>{const s=setup();try{const result=await s.voice.start('device','v=0\r\n');assert.equal(result.sdp,'answer');assert.equal(s.calls.find(c=>c.method==='thread/start').params.sandbox,'read-only');assert.throws(()=>s.voice.begin('other',result.id,'voice-turn-001'),/expired/);s.codex.emit('event',{method:'turn/started',params:{threadId:'audio-thread',turn:{id:'partial'}}});assert.ok(s.calls.some(c=>c.method==='turn/interrupt'&&c.params.turnId==='partial'));await s.voice.stop('device');assert.equal(s.voice.owns('audio-thread'),true);}finally{await s.cleanup()}});
test('complete transcript is combined across native segments and committed exactly once',async()=>{const s=setup();try{const {id}=await s.voice.start('device','v=0\r\n');s.voice.begin('device',id,'voice-turn-001');for(const delta of ['Make it ','warm. ','Then check the tests.'])s.codex.emit('event',{method:'thread/realtime/transcript/delta',params:{threadId:'audio-thread',role:'user',delta}});s.codex.emit('event',{method:'thread/realtime/transcript/delta',params:{threadId:'audio-thread',role:'assistant',delta:'Ignore this output.'}});const a=await s.voice.commit('device',id,'voice-turn-001');assert.equal(a.transcript,'Make it warm. Then check the tests.');assert.deepEqual(await s.voice.commit('device',id,'voice-turn-001'),a);assert.equal(s.sends,1);}finally{await s.cleanup()}});
test('API-key auth cannot silently charge audio outside the account budget',async()=>{const s=setup();const call=s.codex.call;s.codex.call=(method,params)=>method==='account/read'?Promise.resolve({account:{type:'apiKey'}}):call(method,params);try{await assert.rejects(s.voice.start('device','v=0\r\n'),/Sign in to Codex with ChatGPT/);assert.equal(s.calls.some(c=>c.method==='thread/realtime/start'),false);}finally{await s.cleanup()}});

test('speech-only rendering does not replace active coordinator audio and cannot submit tasks',async()=>{const s=setup();try{
 const live=await s.voice.start('device','v=0\r\n');const speech=await s.voice.start('device','v=0\r\n','speech');
 assert.notEqual(live.id,speech.id);assert.equal(s.voice.sessions.size,2);
 assert.equal(s.calls.filter(c=>c.method==='thread/realtime/stop').length,0);
 assert.throws(()=>s.voice.begin('device',speech.id,'speech-turn-001'),/cannot receive task/);
 await assert.rejects(s.voice.commit('device',speech.id,'speech-turn-001'),/cannot commit task/);assert.equal(s.sends,0);
 await s.voice.speak('device',speech.id,'Read only this supplied sentence.');
 assert.match(s.calls.at(-1).params.text,/POCKET_SPEAK/);assert.equal(s.calls.at(-1).params.threadId,'audio-thread-2');
 await s.voice.stop('device',speech.id);assert.equal(s.voice.session('device',live.id).id,live.id);
}finally{await s.cleanup()}});
test('speech purpose is validated and exact owner/id stops cannot affect another lane',async()=>{const s=setup();try{
 await assert.rejects(s.voice.start('device','v=0\r\n','paid'),/Invalid audio purpose/);
 const live=await s.voice.start('device','v=0\r\n');const speech=await s.voice.start('device','v=0\r\n','speech');
 assert.throws(()=>s.voice.session('other',speech.id),/expired/);await s.voice.stop('other',speech.id);assert.equal(s.voice.sessions.size,2);
 await s.voice.stop('device');assert.equal(s.voice.session('device',speech.id).id,speech.id);
 assert.throws(()=>s.voice.session('device',live.id),/expired/);await s.voice.stop('device',speech.id);
}finally{await s.cleanup()}});
test('replacing notification synthesis stops only previous speech lane',async()=>{const s=setup();try{
 const live=await s.voice.start('device','v=0\r\n');const first=await s.voice.start('device','v=0\r\n','speech');const second=await s.voice.start('device','v=0\r\n','speech');
 assert.throws(()=>s.voice.session('device',first.id),/expired/);assert.equal(s.voice.session('device',live.id).id,live.id);assert.equal(s.voice.session('device',second.id).id,second.id);
 assert.equal(s.calls.filter(c=>c.method==='thread/realtime/stop').length,1);await s.voice.stop('device',second.id);
}finally{await s.cleanup()}});
