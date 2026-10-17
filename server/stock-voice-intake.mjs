import express from 'express';
import {resolve} from 'node:path';
import {VoiceRecordings} from './voice-recordings.mjs';
import {RemoteAudioTranscriber} from './remote-audio-transcriber.mjs';

/** Stock execution adapter: shared audio custody, existing coordinator authority and original profile. */
export function mountStockVoiceIntake(app,{db,dir,controller,humanIngress,resolveDestination,remoteCommand,route}){
 const transcriber=remoteCommand?new RemoteAudioTranscriber(remoteCommand):null;
 const authorize=async(device,principal)=>{
  if(principal.deviceId!==device||!db.prepare('SELECT 1 FROM devices WHERE id=?').get(device))
   throw Object.assign(Error('The original device is no longer authorized. Recording retained.'),{status:403});
 };
 controller.authorizeQueued=device=>authorize(device,{deviceId:device});
 const recordings=new VoiceRecordings({db,root:resolve(dir,'voice-recordings'),resolveDestination,
  transcribe:input=>{if(!transcriber)throw Object.assign(Error('Native audio worker is not installed. Recording retained.'),{status:503});return transcriber.transcribe(input);},
  authorize,getReceipt:(device,id)=>controller.get(device,id),cancelTask:(device,id)=>controller.cancelQueued(device,id),
  submit:(device,id,text,destination,principal)=>{
   humanIngress.record('voice-recording',recordings.key(device,id),principal);
   return controller.submitText(device,id,text,null,destination);
  }
 });
 app.get('/api/voice/native/capabilities',(_req,res)=>res.json({turnJournalVersion:1,immutableDestination:true,recordingDigest:true,durableAudioAdmission:!!transcriber,renderedSpeechVersion:0}));
 app.post('/api/voice/recordings',route(async(req,res)=>{
  if(!transcriber)throw Object.assign(Error('This environment needs its native audio worker. Audio remains saved on your device.'),{status:503});
  res.status(202).json(await recordings.create(req.device.id,req.body,req.humanPrincipal));
 }));
 app.put('/api/voice/recordings/:id/audio',express.raw({type:'audio/wav',limit:'4mb'}),route(async(req,res)=>{
  if(!Buffer.isBuffer(req.body))throw Object.assign(Error('Expected recorded WAV audio.'),{status:415});
  res.status(202).json(await recordings.upload(req.device.id,req.params.id,req.body));
 }));
 app.get('/api/voice/recordings/:id',route(async(req,res)=>{
  const receipt=await recordings.inspect(req.device.id,req.params.id);
  if(!receipt)return res.status(404).json({error:'Recording admission not found.'});res.json(receipt);
 }));
 app.post('/api/voice/recordings/:id/cancel',route(async(req,res)=>res.json(recordings.cancel(req.device.id,req.params.id))));
 app.post('/api/voice/recordings/:id/cancel-request',route(async(req,res)=>res.json(await recordings.cancelRequest(req.device.id,req.params.id))));
 app.post('/api/voice/recordings/:id/retry-audio',route(async(req,res)=>{
  const receipt=await recordings.retryAudio(req.device.id,req.params.id);
  humanIngress.action('voice-recording',recordings.key(req.device.id,req.params.id),'retry-audio',req.humanPrincipal);res.status(202).json(receipt);
 }));
 return {recordings,enabled:!!transcriber,start:()=>transcriber?recordings.start():Promise.resolve()};
}
