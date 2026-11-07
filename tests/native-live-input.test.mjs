import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {NativeLiveInput} from '../server/native-live-input.mjs';
test('live audio is evidence only; exact sealed recording and device bind reuse across restart',async()=>{
 const db=new DatabaseSync(':memory:');let listens=0,finals=0;
 const voice={listen(){listens++},async finalTranscript(device,connection,proof,id){finals++;assert.equal(id,'recording_original');assert.equal(proof.confirmed,true);return {text:'Synthetic complete request.',segments:1}}};
 let live=new NativeLiveInput({db,voice});live.begin('phone_a','recording_original','connection_original');
 assert.equal(live.cached({device:'phone_a',id:'recording_original',sha256:'a'.repeat(64)}),null);
 await assert.rejects(live.finalize('phone_b',{id:'recording_original',connectionId:'connection_original',audioSha256:'a'.repeat(64),bytes:3244,finalization:{confirmed:true,reason:'client_request'}}),/another recording or device/);
 const args={id:'recording_original',connectionId:'connection_original',audioSha256:'a'.repeat(64),bytes:3244,finalization:{confirmed:true,reason:'client_request'}};
 await live.finalize('phone_a',args);await live.finalize('phone_a',args);assert.equal(finals,1);assert.equal(listens,1);
 live=new NativeLiveInput({db,voice});
 assert.equal(live.cached({device:'phone_b',id:args.id,sha256:args.audioSha256}),null);
 assert.equal(live.cached({device:'phone_a',id:args.id,sha256:'b'.repeat(64)}),null);
 assert.equal(live.cached({device:'phone_a',id:args.id,sha256:args.audioSha256}).text,'Synthetic complete request.');
 await assert.rejects(live.finalize('phone_a',{...args,audioSha256:'b'.repeat(64)}),/recording changed/);
});
test('cancellation and gateway restart prevent late live finalization without affecting durable WAV fallback',async()=>{
 const db=new DatabaseSync(':memory:'),voice={listen(){},async finalTranscript(){return {text:'No execution authority.',segments:1}}};
 let live=new NativeLiveInput({db,voice});live.begin('phone_a','recording_cancel','connection_cancel');live.cancel('phone_a','recording_cancel');
 const args={id:'recording_cancel',connectionId:'connection_cancel',audioSha256:'a'.repeat(64),bytes:3244,finalization:{confirmed:true,reason:'client_request'}};
 await assert.rejects(live.finalize('phone_a',args),/no longer accepts/);
 live.begin('phone_a','recording_restart','connection_restart');live=new NativeLiveInput({db,voice});
 await assert.rejects(live.finalize('phone_a',{...args,id:'recording_restart',connectionId:'connection_restart'}),/no longer accepts/);
 assert.equal(live.cached({device:'phone_a',id:args.id,sha256:args.audioSha256}),null);
});
