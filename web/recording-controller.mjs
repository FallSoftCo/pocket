import {recordingStore} from './storage.mjs';
import {voiceJournal,wavFromRecording} from './voice-journal.mjs';
/** Each capture owns a durable ID and destination independently of earlier turns. */
export class RecordingController {
 constructor(api,status){Object.assign(this,{api,status,recording:null,records:[],jobs:new Map(),profile:null});this.preparing=Promise.resolve();this.cancelled=new Set();}
 get pending(){return this.records.filter(r=>r.deviceId===this.profile&&!['submitted','completed','cancelled'].includes(r.state)).at(-1)||null;}
 supported(){return window.isSecureContext&&!!navigator.mediaDevices?.getUserMedia&&!!window.MediaRecorder;}
 publish(message=''){const c=this.recording;this.status(message||(c?`Recording · ${Math.floor((Date.now()-c.startedAt)/1000)}s · tap to stop and send`:''),this.pending);}
 async loadCapabilities(){try{this.capabilities=await this.api('/api/voice/native/capabilities');}catch{this.capabilities=null;}}
 async restore(){
  this.records=(await voiceJournal()).filter(r=>r.deviceId===this.profile);this.cancelled=new Set(this.records.filter(r=>r.state==='cancelled').map(r=>r.id));const legacy=await recordingStore();
  if(legacy&&!this.records.some(r=>r.id===legacy.id)){const entry={...legacy,deviceId:this.legacyProfile||this.profile,state:legacy.state==='unknown'?'unknown':'saved',destination:{threadId:legacy.scope==='coordinator'?null:legacy.scope},error:'Saved recording · choose Send for its original turn.'};await voiceJournal('put',entry);await recordingStore(null);if(entry.deviceId===this.profile)this.records.push(entry);}this.publish();
 }
 async save(record){if(this.cancelled.has(record.id)&&record.state!=='cancelled')throw new DOMException('Recording cancelled','AbortError');await voiceJournal('put',record);const at=this.records.findIndex(r=>r.id===record.id);if(at<0)this.records.push(record);else this.records[at]=record;this.publish();}
 async capture(scope){
  if(this.recording){if(!this.recording.stopping){this.recording.stopping=true;this.recording.recorder.stop();this.publish('Finishing recording…');}return;}
  if(this.starting)return;this.starting=true;this.publish('Requesting microphone…');let stream;
  try{
   if(!this.supported())throw Error('Microphone capture is unavailable in this browser.');
   stream=await navigator.mediaDevices.getUserMedia({audio:true});
   const capabilities=this.capabilities;
   const id=crypto.randomUUID(),recorder=new MediaRecorder(stream),chunks=[],capture={id,scope,deviceId:this.profile,recorder,stream,startedAt:Date.now(),cancelled:false,stopping:false};
   recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data);};
   recorder.onstop=()=>{clearTimeout(capture.limit);clearInterval(capture.timer);stream.getTracks().forEach(t=>t.stop());if(this.recording===capture)this.recording=null;
    if(capture.cancelled){this.publish('Recording cancelled');return;}
    const entry={id,scope,deviceId:capture.deviceId,blob:new Blob(chunks,{type:recorder.mimeType}),createdAt:capture.startedAt,state:'saved',destination:{threadId:scope==='coordinator'?null:scope}};
    void this.save(entry).then(()=>this.sendRecord(entry.id)).catch(e=>this.publish(e.message));
   };
   recorder.onerror=e=>{capture.cancelled=true;clearTimeout(capture.limit);clearInterval(capture.timer);stream.getTracks().forEach(t=>t.stop());if(this.recording===capture)this.recording=null;this.publish(e.error?.message||'Recording failed. No turn was sent.');};
   this.recording=capture;recorder.start(1000);const seconds=Math.min(600,Number(capabilities?.maxRecordingSeconds)||600);capture.limit=setTimeout(()=>{if(this.recording===capture&&!capture.stopping){capture.stopping=true;recorder.stop();}},seconds*1000);capture.timer=setInterval(()=>this.publish(),1000);this.publish();
  }catch(e){stream?.getTracks().forEach(t=>t.stop());this.publish(e.message);throw e;}finally{this.starting=false;}
 }
 async send(scope){const r=this.pending;if(!r||r.scope!==scope)throw Error('Choose the recording in its original conversation.');return this.sendRecord(r.id);}
 async sendRecord(id){
  if(this.jobs.has(id))return this.jobs.get(id);
  const run=this.deliver(id).catch(async e=>{const r=this.records.find(r=>r.id===id);if(r&&!this.cancelled.has(id)&&r.state!=='cancelled'&&r.state!=='unknown')await this.save({...r,state:'saved',error:e.message});throw e;}).finally(()=>this.jobs.delete(id));this.jobs.set(id,run);return run;
 }
 async deliver(id){
  let r=this.records.find(r=>r.id===id);if(!r||r.deviceId!==this.profile)throw Error('Recording belongs to its original browser identity.');
  const capabilities=await this.api('/api/voice/native/capabilities');if(!capabilities?.durableAudioAdmission)throw Error('This environment has no durable native audio adapter. Recording remains saved.');
  if(r.state==='unknown')throw Error('Original delivery is uncertain. Check its receipt before any new submission.');
  if(!r.wav){await this.save({...r,state:'preparing',error:null});const prepare=this.preparing.then(()=>wavFromRecording(r.blob));this.preparing=prepare.catch(()=>{});const wav=await prepare;const digest=[...new Uint8Array(await crypto.subtle.digest('SHA-256',await wav.arrayBuffer()))].map(v=>v.toString(16).padStart(2,'0')).join('');r={...r,wav,audioSha256:digest,bytes:wav.size};await this.save(r);}
  if(this.cancelled.has(id))return;if(this.profile!==r.deviceId)throw Error('Recording retained for its original browser identity.');
  let receipt=await this.api(`/api/voice/recordings/${id}`).catch(e=>e.status===404?null:Promise.reject(e));
  if(!receipt)receipt=await this.api('/api/voice/recordings',{id,destination:r.destination,audioSha256:r.audioSha256,bytes:r.bytes});
  if(receipt.audioSha256!==r.audioSha256)throw Error('Original audio receipt did not match. Recording retained.');
  if(this.cancelled.has(id))return;
  if(['receiving','sealing'].includes(receipt.phase))receipt=await this.api(`/api/voice/recordings/${id}/audio`,r.wav,{method:'PUT'});
  if(receipt.audioSha256!==r.audioSha256)throw Error('Upload receipt did not match the original recording.');
  await this.save({...r,state:receipt.phase==='unknown'?'unknown':receipt.phase==='accepted'?'submitted':receipt.state||'processing',receipt,error:receipt.error||null});this.watch(id);return receipt;
 }
 watch(id){
  if(!this.watchers)this.watchers=new Map();clearTimeout(this.watchers.get(id));let checks=0;
  const check=async()=>{if(++checks>20||document.visibilityState!=='visible')return;const r=this.records.find(r=>r.id===id);if(!r||r.deviceId!==this.profile||r.state==='cancelled')return;
   try{const receipt=await this.api(`/api/voice/recordings/${id}`);const terminal=receipt.phase==='accepted'||['unknown','failed','cancelled'].includes(receipt.phase);await this.save({...r,state:receipt.phase==='accepted'?'submitted':receipt.state,receipt,error:receipt.error});if(!terminal)this.watchers.set(id,setTimeout(check,Math.min(15000,1000*2**Math.min(checks,4))));}catch(e){this.publish(e.message);}
  };this.watchers.set(id,setTimeout(check,1000));
 }
 async discard(id=this.pending?.id){
  const capture=this.recording;if(capture&&(!id||id===capture.id)){capture.cancelled=true;capture.stopping=true;capture.recorder.stop();return;}
  const r=this.records.find(r=>r.id===id);if(!r)return;if(r.state==='unknown'&&!r.audioSha256)throw Error('Original delivery is uncertain. Review it before forgetting the local copy.');this.cancelled.add(id);
  try{
   if(r.audioSha256){const receipt=await this.api(`/api/voice/recordings/${id}/cancel`,{destination:r.destination,audioSha256:r.audioSha256,bytes:r.bytes});if(receipt.state!=='cancelled')throw Error('Original recording cancellation was not confirmed.');}
   await this.save({id:r.id,deviceId:r.deviceId,scope:r.scope,state:'cancelled',createdAt:r.createdAt});
  }catch(e){await voiceJournal('put',{...r,state:'unknown',error:e.message});this.records=this.records.map(x=>x.id===id?{...r,state:'unknown',error:e.message}:x);this.publish(e.message);throw e;}
  this.publish('Recording cancelled');
 }
 async forget(id){const r=this.records.find(r=>r.id===id);if(!r)return;this.cancelled.add(id);await voiceJournal('put',{id:r.id,deviceId:r.deviceId,scope:r.scope,state:'cancelled',originalDelivery:r.state,createdAt:r.createdAt});this.records=this.records.filter(v=>v.id!==id);this.publish();}
 async close(){for(const timer of this.watchers?.values()||[])clearTimeout(timer);if(this.recording){const c=this.recording;c.cancelled=true;c.recorder.stop();c.stream.getTracks().forEach(t=>t.stop());}await this.disconnect?.();}
}
