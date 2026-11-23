import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {fileURLToPath} from 'node:url';

const failure=message=>Object.assign(Error(message),{status:503});

/** Media-only adapter. Never starts a task turn or submits a transcript as work. */
export class NativeSpeechMedia {
 constructor({voice,python,worker=fileURLToPath(new URL('./native-speech-peer.py',import.meta.url))}){
  Object.assign(this,{voice,python,worker});
 }
 async render({device,id,text,file,signal}){
  if(signal?.aborted)throw failure('Speech request cancelled');
  if(!this.python)throw failure('Native speech renderer is not installed. Text retained.');
  const child=spawn(this.python,[this.worker,'--output',file],{stdio:['pipe','pipe','pipe']});
  let offered=false,connection=null,peerClosed=false,peerExit=null,finalization=null,closing=false,hasFirstAudio=false,complete,failed,watchdog,heartbeat,firstAudioTimer,settled=false;
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
     const answer=await this.voice.start(device,event.sdp,'speech',text);
     connection=answer.id;
     heartbeat=setInterval(()=>{if(!closing)try{this.voice.session(device,connection)}catch(error){failed(error)}},30000);
     send({type:'answer',sdp:answer.sdp});
    }else if(event.type==='providerStarted'){
     if(!hasFirstAudio)firstAudioTimer=setTimeout(()=>failed(failure('Native speech produced no audio. Text retained.')),15000);
    }else if(event.type==='firstAudio'){
     hasFirstAudio=true;clearTimeout(firstAudioTimer);
    }else if(event.type==='finalizing'){
     closing=true;
    }else if(event.type==='rendered'){
     if(finalization)throw failure('Speech peer repeated finalization');
     if(event.confirmed!==true||event.proof?.textSha256!==createHash('sha256').update(text).digest('hex')||event.proof?.transcriptMatches!==true||event.proof?.boundary!=='turn.done+session.closed')throw failure('Speech peer did not confirm the original passage');
     finalization=event.proof;
     const audio=await readFile(file);
     if(audio.length!==event.bytes||audio.length<44||audio.length>16*1024**2||createHash('sha256').update(audio).digest('hex')!==event.sha256||audio.toString('ascii',0,4)!=='RIFF'||audio.toString('ascii',8,12)!=='WAVE')throw failure('Native rendered audio failed integrity verification');
     settled=true;complete({file,sha256:event.sha256,bytes:event.bytes,durationMs:event.durationMs,proof:event.proof});
    }else if(event.type==='peerClosed'){
     peerClosed=event.state==='closed';
    }else if(event.type==='error'){
     throw failure(event.message||'Native audio peer failed');
    }
   })().catch(failed);
  });
  send({type:'passage',text});
  const abort=()=>failed(failure('Speech request cancelled'));signal?.addEventListener('abort',abort,{once:true});
  let value,error;
  try{
   watchdog=setTimeout(()=>failed(failure('Native audio processing exceeded its bounded deadline')),180000);
   value=await result;
  }catch(e){error=e}
  finally{
   signal?.removeEventListener('abort',abort);clearTimeout(watchdog);clearTimeout(firstAudioTimer);clearInterval(heartbeat);
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
