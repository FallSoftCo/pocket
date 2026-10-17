import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {fileURLToPath} from 'node:url';

const failure=message=>Object.assign(Error(message),{status:503});

/** Media-only adapter. Never starts a task turn or submits a transcript as work. */
export class NativeAudioTranscriber {
 constructor({voice,python,worker=fileURLToPath(new URL('./native-audio-peer.py',import.meta.url))}){
  Object.assign(this,{voice,python,worker});
 }
 async transcribe({device,id,file,sha256,signal}){
  if(signal?.aborted)throw failure('Recording cancelled before transcription');
  if(!this.python)throw failure('Native server audio peer is not installed. Recording retained.');
  const child=spawn(this.python,[this.worker,'--audio',file,'--sha256',sha256],{stdio:['pipe','pipe','pipe']});
  let offered=false,connection=null,peerClosed=false,peerExit=null,finalization=null,complete,failed,watchdog,heartbeat,settled=false;
  const result=new Promise((resolve,reject)=>{complete=resolve;failed=reject});result.catch(()=>{});
  const exited=new Promise(resolve=>{
   child.once('exit',(code,signal)=>{peerExit={code,signal};if(!settled)failed(failure('Audio peer exited before finalization'))});
   child.once('close',resolve);
  });
  const send=value=>{if(!child.stdin.writable)throw failure('Audio peer control channel closed');child.stdin.write(JSON.stringify(value)+'\n')};
  let diagnostics='';
  child.stderr.on('data',data=>{diagnostics=(diagnostics+String(data)).slice(-1000)});
  child.on('error',failed);
  const lines=createInterface({input:child.stdout});
  lines.on('line',line=>{
   void (async()=>{
    if(line.length>262144)throw failure('Oversized media peer response');
    const event=JSON.parse(line);
    if(event.type==='offer'){
     if(offered)throw failure('Audio peer repeated its offer');offered=true;
     const answer=await this.voice.start(device,event.sdp,'transcription');
     connection=answer.id;
     heartbeat=setInterval(()=>{if(!finalization)try{this.voice.session(device,connection)}catch(error){failed(error)}},30000);
     send({type:'answer',sdp:answer.sdp});
    }else if(event.type==='connected'){
     this.voice.listen(device,connection,id);send({type:'play'});
    }else if(event.type==='drained'){
     send({type:'finalize'});
    }else if(event.type==='finalized'){
     if(finalization)throw failure('Audio peer repeated finalization');
     if(event.confirmed!==true)throw failure(event.error||'Audio session did not finalize cleanly');
     finalization={confirmed:true,connectionId:connection,reason:event.final?.reason,usage:event.final?.usage||null};
     const transcript=await this.voice.finalTranscript(device,connection,finalization);
     settled=true;
     complete({text:transcript.text,proof:{version:1,boundary:'session.closed',confirmed:true,
      audioSha256:sha256,reason:finalization.reason,usage:finalization.usage,segments:transcript.segments}});
    }else if(event.type==='peerClosed'){
     peerClosed=event.state==='closed';
    }else if(event.type==='error'){
     throw failure(event.message||'Native audio peer failed');
    }
   })().catch(failed);
  });
  const abort=()=>failed(failure('Recording cancelled during transcription'));signal?.addEventListener('abort',abort,{once:true});
  let value,error;
  try{
   watchdog=setTimeout(()=>failed(failure('Native audio processing exceeded its bounded deadline')),180000);
   value=await result;
  }catch(e){error=e}
  finally{
   signal?.removeEventListener('abort',abort);clearTimeout(watchdog);clearInterval(heartbeat);
   try{if(child.stdin.writable){send({type:'shutdown'});child.stdin.end()}}catch{}
   let timer;
   await Promise.race([exited,new Promise(resolve=>{timer=setTimeout(()=>{child.kill('SIGTERM');resolve()},5000)})]);clearTimeout(timer);
   if(peerExit===null){
    await Promise.race([exited,new Promise(resolve=>{timer=setTimeout(()=>{child.kill('SIGKILL');resolve()},2000)})]);clearTimeout(timer);
   }
   lines.close();
   if(connection){
    const stop=this.voice.stop(device,connection);stop.catch(()=>{});
    await Promise.race([stop,new Promise(resolve=>{timer=setTimeout(resolve,5000)})]).catch(()=>{});clearTimeout(timer);
   }
  }
  if(error)throw error;
  if(!peerClosed||peerExit?.code!==0)throw failure('Audio peer did not release its resources cleanly. Recording retained.');
  if(diagnostics.includes('Traceback'))throw failure('Native audio peer reported a processing failure');
  return value;
 }
}
