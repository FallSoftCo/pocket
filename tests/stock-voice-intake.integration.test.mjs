import test from 'node:test';
import assert from 'node:assert/strict';
import express from 'express';
import {DatabaseSync} from 'node:sqlite';
import {mkdtempSync,rmSync,readFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {mountStockVoiceIntake} from '../server/stock-voice-intake.mjs';
import {MAX_RECORDING_WAV_BYTES} from '../server/audio-limits.mjs';

test('real stock HTTP intake accepts ten minutes, persists exact bytes and rejects oversized uploads',async()=>{
 const dir=mkdtempSync(join(tmpdir(),'nextcomp-stock-long-audio-')),db=new DatabaseSync(':memory:');
 db.exec('CREATE TABLE devices(id TEXT PRIMARY KEY)');db.prepare('INSERT INTO devices VALUES(?)').run('synthetic-phone');
 const app=express();app.use(express.json());
 app.use((req,_res,next)=>{req.device={id:'synthetic-phone'};req.humanPrincipal={version:1,kind:'paired-device',deviceId:'synthetic-phone'};next()});
 const intake=mountStockVoiceIntake(app,{db,dir,controller:{get:()=>null,cancelQueued:()=>{},submitText:()=>{throw Error('No task dispatch authorized')}},
  humanIngress:{record:()=>{},action:()=>{}},resolveDestination:async(_device,d)=>d,remoteCommand:['/usr/bin/false'],
  route:handler=>(req,res,next)=>Promise.resolve(handler(req,res,next)).catch(next)});
 app.use((e,_req,res,_next)=>res.status(e.status||500).json({error:e.message}));
 const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve));
 const base='http://127.0.0.1:'+server.address().port;
 try {
  const capabilities=await (await fetch(base+'/api/voice/native/capabilities')).json();assert.equal(capabilities.maxRecordingSeconds,600);
  const audio=Buffer.alloc(MAX_RECORDING_WAV_BYTES);audio.write('RIFF');audio.writeUInt32LE(audio.length-8,4);audio.write('WAVEfmt ',8);
  audio.writeUInt32LE(16,16);audio.writeUInt16LE(1,20);audio.writeUInt16LE(1,22);audio.writeUInt32LE(16000,24);
  audio.writeUInt32LE(32000,28);audio.writeUInt16LE(2,32);audio.writeUInt16LE(16,34);audio.write('data',36);audio.writeUInt32LE(audio.length-44,40);
  const id='ten-minute-http-0001',body={id,destination:{threadId:'synthetic-thread'},bytes:audio.length,audioSha256:createHash('sha256').update(audio).digest('hex')};
  assert.equal((await fetch(base+'/api/voice/recordings',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})).status,202);
  for(let n=0;n<2;n++)assert.equal((await fetch(base+'/api/voice/recordings/'+id+'/audio',{method:'PUT',headers:{'Content-Type':'audio/wav'},body:audio})).status,202);
  const row=intake.recordings.row('synthetic-phone',id);assert.equal(row.phase,'ready');assert.deepEqual(readFileSync(intake.recordings.path(row)),audio);
  assert.equal(db.prepare('SELECT count(*) AS n FROM voice_recordings').get().n,1);
  assert.equal((await fetch(base+'/api/voice/recordings/'+id+'/audio',{method:'PUT',headers:{'Content-Type':'audio/wav'},body:Buffer.alloc(audio.length+2)})).status,413);
 }finally{await intake.recordings.stop();await new Promise(resolve=>server.close(resolve));db.close();rmSync(dir,{recursive:true,force:true})}
});
