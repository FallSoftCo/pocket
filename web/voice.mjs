import {RecordingController} from './recording-controller.mjs';
export class BrowserVoice extends RecordingController {
 async connect(){if(this.connection&&this.pc?.connectionState==='connected')return;
  await this.disconnect();this.ctx=new AudioContext();await this.ctx.resume();this.dest=this.ctx.createMediaStreamDestination();const quiet=this.ctx.createConstantSource();quiet.offset.value=0;quiet.connect(this.dest);quiet.start();this.pc=new RTCPeerConnection();this.pc.addTrack(this.dest.stream.getAudioTracks()[0]);this.dc=this.pc.createDataChannel('oai-events');
  const opened=new Promise((resolve,reject)=>{this.dc.onopen=resolve;this.dc.onerror=()=>reject(Error('Voice channel failed.'));setTimeout(()=>reject(Error('Voice channel timed out.')),15000);});opened.catch(()=>{});
  this.pc.ontrack=e=>{this.audio=new Audio();this.audio.volume=this.volume??1;this.audio.srcObject=e.streams[0]||new MediaStream([e.track]);};
  this.pc.onconnectionstatechange=()=>{if(this.pc?.connectionState==='failed')this.status('Voice disconnected. Saved work remains available.');};
  await this.pc.setLocalDescription(await this.pc.createOffer());await new Promise(resolve=>{if(this.pc.iceGatheringState==='complete')return resolve();this.pc.addEventListener('icegatheringstatechange',()=>{if(this.pc.iceGatheringState==='complete')resolve();});setTimeout(resolve,3000);});
  const result=await this.api('/api/voice/native/start',{sdp:this.pc.localDescription.sdp,purpose:'voice'});this.connection=result.id;await this.pc.setRemoteDescription({type:'answer',sdp:result.sdp});await opened;
  this.heartbeat=setInterval(()=>this.api('/api/voice/native/heartbeat',{connectionId:this.connection}).catch(e=>this.status(e.message)),30000);
 }
 async speak(text){await this.connect();if(!this.audio)throw Error('Voice playback is unavailable.');this.audio.muted=false;await this.audio.play();await this.api('/api/voice/native/speak',{connectionId:this.connection,text:text.slice(0,16000)});this.status('Playing native speech. Stop speech to end playback.');}
 pause(){if(this.audio)this.audio.pause();this.status('Speech paused.');}
 async disconnect(){clearInterval(this.heartbeat);this.audio?.pause();this.pc?.close();await this.ctx?.close();const id=this.connection;this.connection=null;if(id)await this.api('/api/voice/native/stop',{connectionId:id}).catch(()=>{});if(!this.pending&&!this.recording)this.status('');}
}
