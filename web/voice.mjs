import {recordingStore} from './storage.mjs';
// Reuses the native-voice.html transport: silent WebAudio sender, buffered manual
// recording, native start/input/commit and explicit playback. No speech API keys.
export class BrowserVoice {
 constructor(api,status){Object.assign(this,{api,status});this.recording=null;this.pending=null;this.connection=null;}
 async restore(){this.pending=await recordingStore();if(this.pending)this.status('Saved recording ready. Open its original conversation to send.',this.pending);}
 supported(){return window.isSecureContext&&!!navigator.mediaDevices?.getUserMedia&&!!window.RTCPeerConnection&&!!window.MediaRecorder;}
 async capture(scope){if(!this.supported())throw Error('Microphone recording is unavailable in this browser. Use the keyboard.');if(this.recording){this.recording.recorder.stop();return;}if(this.pending)throw Error('Send or discard the saved recording first.');
  const stream=await navigator.mediaDevices.getUserMedia({audio:true}),recorder=new MediaRecorder(stream),chunks=[];
  recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data);};recorder.onstop=async()=>{clearTimeout(this.limit);stream.getTracks().forEach(t=>t.stop());this.recording=null;this.pending={scope,id:crypto.randomUUID(),blob:new Blob(chunks,{type:recorder.mimeType}),createdAt:Date.now(),state:'recorded'};await recordingStore(this.pending);this.status('Recording saved. Send when ready.',this.pending);};recorder.onerror=()=>{stream.getTracks().forEach(t=>t.stop());this.recording=null;this.status('Recording failed. Please try again.');};this.recording={recorder,stream};recorder.start();this.limit=setTimeout(()=>{if(recorder.state==='recording')recorder.stop();},120000);this.status('Recording… Tap Mic to stop. Maximum two minutes.');
 }
 async connect(){if(this.connection&&this.pc?.connectionState==='connected')return;
  await this.disconnect();this.ctx=new AudioContext();await this.ctx.resume();this.dest=this.ctx.createMediaStreamDestination();const quiet=this.ctx.createConstantSource();quiet.offset.value=0;quiet.connect(this.dest);quiet.start();this.pc=new RTCPeerConnection();this.pc.addTrack(this.dest.stream.getAudioTracks()[0]);this.dc=this.pc.createDataChannel('oai-events');
  const opened=new Promise((resolve,reject)=>{this.dc.onopen=resolve;this.dc.onerror=()=>reject(Error('Voice channel failed.'));setTimeout(()=>reject(Error('Voice channel timed out.')),15000);});opened.catch(()=>{});
  this.pc.ontrack=e=>{this.audio=new Audio();this.audio.srcObject=e.streams[0]||new MediaStream([e.track]);};
  this.pc.onconnectionstatechange=()=>{if(this.pc?.connectionState==='failed')this.status('Voice disconnected. Saved work remains available.');};
  await this.pc.setLocalDescription(await this.pc.createOffer());await new Promise(resolve=>{if(this.pc.iceGatheringState==='complete')return resolve();this.pc.addEventListener('icegatheringstatechange',()=>{if(this.pc.iceGatheringState==='complete')resolve();});setTimeout(resolve,3000);});
  const result=await this.api('/api/voice/native/start',{sdp:this.pc.localDescription.sdp,purpose:'voice'});this.connection=result.id;await this.pc.setRemoteDescription({type:'answer',sdp:result.sdp});await opened;
  this.heartbeat=setInterval(()=>this.api('/api/voice/native/heartbeat',{connectionId:this.connection}).catch(e=>this.status(e.message)),30000);
 }
 async send(scope){const p=this.pending;if(!p||p.scope!==scope)throw Error('Open the original conversation before sending its recording.');
  const existing=await this.api(`/api/voice/turns/${p.id}`).catch(e=>e.status===404?null:Promise.reject(e));if(existing){await this.discard();return existing;}if(p.state==='unknown')throw Error('Recording delivery is uncertain. Review its original conversation before discarding or making a new recording.');
  this.status('Connecting native voice…',p);await this.api('/api/voice/start',{threadId:scope==='coordinator'?null:scope});await this.connect();
  const buffer=await this.ctx.decodeAudioData(await p.blob.arrayBuffer());await this.api('/api/voice/native/input',{connectionId:this.connection,turnId:p.id});
  this.pending={...p,state:'unknown'};await recordingStore(this.pending);
  this.status('Delivering saved recording…',this.pending);const source=this.ctx.createBufferSource();source.buffer=buffer;source.connect(this.dest);await new Promise(resolve=>{source.onended=resolve;source.start();});
  const result=await this.api('/api/voice/native/commit',{connectionId:this.connection,turnId:p.id});await this.discard();return result;
 }
 async speak(text){await this.connect();if(!this.audio)throw Error('Voice playback is unavailable.');this.audio.muted=false;await this.audio.play();await this.api('/api/voice/native/speak',{connectionId:this.connection,text:text.slice(0,16000)});this.status('Playing native speech. Stop speech to end playback.');}
 pause(){if(this.audio)this.audio.pause();this.status('Speech paused.');}
 async discard(){this.pending=null;await recordingStore(null);this.status('');}
 async disconnect(){clearInterval(this.heartbeat);this.audio?.pause();this.pc?.close();await this.ctx?.close();const id=this.connection;this.connection=null;if(id)await this.api('/api/voice/native/stop',{connectionId:id}).catch(()=>{});if(!this.pending&&!this.recording)this.status('');}
 async close(){clearTimeout(this.limit);if(this.recording){this.recording.recorder.onstop=null;this.recording.recorder.stop();this.recording.stream.getTracks().forEach(t=>t.stop());this.recording=null;}await this.disconnect();}
}
