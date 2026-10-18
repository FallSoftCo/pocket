import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {readFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {DatabaseSync} from 'node:sqlite';
import {createHash} from 'node:crypto';
import {VoiceRecordings} from '../server/voice-recordings.mjs';

function wav(bytes=3244){
 const b=Buffer.alloc(bytes);b.write('RIFF');b.writeUInt32LE(b.length-8,4);b.write('WAVEfmt ',8);
 b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(16000,24);
 b.writeUInt32LE(32000,28);b.writeUInt16LE(2,32);b.writeUInt16LE(16,34);b.write('data',36);b.writeUInt32LE(b.length-44,40);return b;
}
const audio=wav(),digest=createHash('sha256').update(audio).digest('hex'),device='qa-device-one';
const request=id=>({id,destination:{room:{roomId:'original-room',recipientId:'original-agent'}},audioSha256:digest,bytes:audio.length});
const principal={version:1,kind:'paired-device',deviceId:device};
const proof=sha=>({version:1,boundary:'session.closed',reason:'client_request',confirmed:true,audioSha256:sha});
async function settle(server){for(let n=0;n<200;n++){if(!server.running.size)return;await new Promise(r=>setTimeout(r,5))}throw Error('Owned deterministic job did not settle');}
function fixture(overrides={}){
 const root=mkdtempSync(join(tmpdir(),'nextcomp-recording-')),database=join(root,'data.db'),external=new Map(),calls={transcribe:0,submit:0};
 let db=new DatabaseSync(database);
 const adapters={
  root:join(root,'audio'),resolveDestination:async(_device,d)=>structuredClone(d),authorize:async()=>{},minFreeBytes:0,
  transcribe:async input=>{calls.transcribe++;assert.deepEqual(await readFile(input.file),audio);return {text:'Original request',proof:proof(input.sha256)};},
  submit:async(_device,id,text,destination,source)=>{calls.submit++;assert.equal(destination.room.roomId,'original-room');assert.deepEqual(source,principal);const receipt={id,state:'completed',transcript:text,response:'Original result',actions:[]};external.set(id,receipt);return receipt;},
  getReceipt:async(_device,id)=>external.get(id)||null,...overrides
 };
 let server=new VoiceRecordings({db,...adapters});
 return {root,calls,external,adapters,get db(){return db},get server(){return server},
  async reopen(){await server.stop();db.close();db=new DatabaseSync(database);server=new VoiceRecordings({db,...adapters});},
  async close(){await server.stop();db.close();rmSync(root,{recursive:true,force:true})}
 };
}
test('durable audio and lost task acknowledgement survive SQLite reopen without duplicate dispatch',async()=>{
 const f=fixture();
 f.server.submit=async(...args)=>{await f.adapters.submit(...args);throw Error('Acknowledgement lost after task submission');};
 try{
  await f.server.create(device,request('original-turn-0001'),principal);
  await assert.rejects(f.server.create(device,{...request('original-turn-0001'),destination:{room:{roomId:'wrong-room'}}},principal),/another recording or destination/);
  await Promise.all([f.server.upload(device,'original-turn-0001',audio),f.server.upload(device,'original-turn-0001',audio)]);
  await f.server.start();await settle(f.server);
  assert.equal(f.server.row(device,'original-turn-0001').phase,'unknown');assert.equal(f.calls.submit,1);
  await f.reopen();await f.server.start();await settle(f.server);
  assert.equal(f.calls.submit,1);assert.equal(f.server.row('different-device','original-turn-0001'),undefined);
  const receipt=await f.server.inspect(device,'original-turn-0001');assert.equal(receipt.state,'completed');
  assert.equal(receipt.audioSha256,digest);assert.equal(f.calls.submit,1);
  await f.server.upload(device,'original-turn-0001',audio);await settle(f.server);assert.equal(f.calls.submit,1);
  assert.deepEqual(JSON.parse(f.server.row(device,'original-turn-0001').destination),request('x').destination);
 }finally{await f.close();}
});
test('cancelled transcription cannot dispatch even when a media callback returns late',async()=>{
 let finish;
 const f=fixture({transcribe:input=>new Promise(resolve=>{finish=()=>resolve({text:'Too late',proof:proof(input.sha256)});})});
 try{
  await f.server.create(device,request('cancelled-turn-0001'),principal);await f.server.upload(device,'cancelled-turn-0001',audio);
  await f.server.start();while(!finish)await new Promise(r=>setTimeout(r,5));
  await f.server.cancel(device,'cancelled-turn-0001');finish();await settle(f.server);
  assert.equal(f.server.row(device,'cancelled-turn-0001').phase,'cancelled');assert.equal(f.calls.submit,0);
 }finally{await f.close();}
});
test('interrupted upload is resumable and a completed file recovers the rename-to-row boundary',async()=>{
 const f=fixture();
 try{
  await f.server.create(device,request('upload-boundary-0001'),principal);
  f.db.prepare("UPDATE voice_recordings SET phase='sealing' WHERE id=?").run('upload-boundary-0001');
  await f.server.upload(device,'upload-boundary-0001',audio);
  f.db.prepare("UPDATE voice_recordings SET phase='sealing' WHERE id=?").run('upload-boundary-0001');
  await f.reopen();await f.server.start();await settle(f.server);
  assert.equal(f.calls.submit,1);assert.equal((await f.server.inspect(device,'upload-boundary-0001')).state,'completed');
 }finally{await f.close();}
});
test('restart after final transcript revalidates revoked human authority once without dispatch or a busy loop',async()=>{
 let checks=0;
 const f=fixture({authorize:async()=>{checks++;throw Object.assign(Error('Original device revoked'),{status:403});}});
 try {
  await f.server.create(device,request('prepared-restart-0001'),principal);
  await f.server.upload(device,'prepared-restart-0001',audio);
  f.db.prepare("UPDATE voice_recordings SET phase='transcribed',transcript='Preserved transcript',proof=?,worker='stopped-process' WHERE id=?")
    .run(JSON.stringify(proof(digest)),'prepared-restart-0001');
  await f.reopen();await f.server.start();await settle(f.server);
  assert.equal(f.server.row(device,'prepared-restart-0001').phase,'blocked');
  assert.equal(checks,1);assert.equal(f.calls.submit,0);
 }finally{await f.close();}
});
test('explicit audio retry retains original destination and never retries accepted or unknown work',async()=>{
 const f=fixture();let attempts=0;
 f.server.transcribe=async input=>{if(++attempts===1)throw Error('Media connection lost before transcript');return f.adapters.transcribe(input);};
 try {
  await f.server.create(device,request('audio-retry-original-0001'),principal);
  await f.server.upload(device,'audio-retry-original-0001',audio);await f.server.start();await settle(f.server);
  assert.equal((await f.server.inspect(device,'audio-retry-original-0001')).canRetryAudio,true);
  assert.equal(f.calls.submit,0);
  await f.server.retryAudio(device,'audio-retry-original-0001');await settle(f.server);
  assert.equal(f.calls.submit,1);assert.equal(attempts,2);
  await assert.rejects(f.server.retryAudio(device,'audio-retry-original-0001'),/pre-dispatch/);
  f.db.prepare("UPDATE voice_recordings SET phase='unknown',result=NULL WHERE id=?").run('audio-retry-original-0001');
  await assert.rejects(f.server.retryAudio(device,'audio-retry-original-0001'),/pre-dispatch/);
  assert.equal(f.calls.submit,1);
 }finally{await f.close();}
});
test('definite coordinator refusal preserves a final transcript for retry without another transcription',async()=>{
 const f=fixture();let refuse=true;
 f.server.submit=(...args)=>{if(refuse)throw Object.assign(Error('Coordinator queue is full'),{notAdmitted:true,status:429});return f.adapters.submit(...args);};
 try {
  await f.server.create(device,request('definite-refusal-0001'),principal);await f.server.upload(device,'definite-refusal-0001',audio);
  await f.server.start();await settle(f.server);
  assert.equal(f.server.row(device,'definite-refusal-0001').phase,'failed');assert.equal(f.calls.transcribe,1);assert.equal(f.calls.submit,0);
  refuse=false;await f.server.retryAudio(device,'definite-refusal-0001');await settle(f.server);
  assert.equal(f.calls.transcribe,1);assert.equal(f.calls.submit,1);assert.equal((await f.server.inspect(device,'definite-refusal-0001')).state,'completed');
 }finally{await f.close();}
});

test('cancel-before-admission is durable and fences an admission already awaiting resolution',async()=>{
 let release,entered;
 const reached=new Promise(resolve=>{entered=resolve});
 const f=fixture({resolveDestination:async(_device,d)=>{entered();await new Promise(resolve=>{release=resolve});return d;}});
 const id='cancel-admission-race-0001',input=request(id);
 try {
  const admission=f.server.create(device,input,principal);await reached;
  assert.equal((await f.server.cancel(device,id,input,principal)).state,'cancelled');
  release();assert.equal((await admission).state,'cancelled');
  assert.equal((await f.server.upload(device,id,audio)).state,'cancelled');
  await f.server.start();await settle(f.server);await f.reopen();
  assert.equal((await f.server.cancel(device,id,input,principal)).state,'cancelled');
  assert.equal((await f.server.create(device,input,principal)).state,'cancelled');
  await assert.rejects(f.server.cancel(device,id,{...input,bytes:input.bytes+2},principal),/another recording/);
  assert.equal(f.calls.transcribe,0);assert.equal(f.calls.submit,0);
 }finally{await f.close();}
});

test('ten-minute recording survives durable admission and upload; excess is rejected before dispatch',async()=>{
 const {MAX_RECORDING_WAV_BYTES}=await import('../server/audio-limits.mjs');
 const long=wav(MAX_RECORDING_WAV_BYTES),sha=createHash('sha256').update(long).digest('hex');
 const f=fixture({transcribe:async input=>{assert.deepEqual(await readFile(input.file),long);return {text:'Ten-minute request',proof:proof(input.sha256)}}});
 const input={...request('long-recording-0001'),audioSha256:sha,bytes:long.length};
 try {
  await f.server.create(device,input,principal);
  await f.server.upload(device,input.id,long);
  await f.reopen();await f.server.start();await settle(f.server);
  assert.equal(f.calls.submit,1);assert.equal((await f.server.inspect(device,input.id)).state,'completed');
  await assert.rejects(f.server.create(device,{...input,id:'oversized-recording-0001',bytes:long.length+2},principal),/size/);
  assert.equal(f.calls.submit,1);
 }finally{await f.close()}
});
