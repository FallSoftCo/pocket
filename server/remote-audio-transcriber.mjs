import {spawn} from 'node:child_process';
import {createReadStream} from 'node:fs';
import {stat} from 'node:fs/promises';

/** Structured media RPC over an installation-owned command (for example SSH). No task destination or bearer token is sent. */
export class RemoteAudioTranscriber {
 constructor(command){if(!Array.isArray(command)||!command.length||command.some(x=>typeof x!=='string'||!x))throw Error('Invalid installed remote audio command');this.command=command;}
 async transcribe({id,file,sha256,signal}){
  if(signal?.aborted)throw Error('Recording cancelled');
  const info=await stat(file);
  if(info.size>3840044||info.size<3244)throw Error('Invalid admitted recording size');
  const [program,...args]=this.command,child=spawn(program,args,{stdio:['pipe','pipe','pipe']});
  let output='',timer,closed=false;
  const exited=new Promise(resolve=>child.once('close',()=>{closed=true;resolve()}));
  const aborted=()=>child.kill('SIGTERM');signal?.addEventListener('abort',aborted,{once:true});
  const result=new Promise((resolve,reject)=>{
   child.on('error',reject);child.stdout.on('data',bytes=>{output+=bytes;if(output.length>262144){child.kill('SIGTERM');reject(Error('Oversized remote audio result'))}});
   child.stderr.resume();
   child.once('close',(code)=>{
    if(code!==0)return reject(Error('Remote audio worker did not complete; recording retained'));
    try{const value=JSON.parse(output);if(value.error)throw Error(value.error);resolve(value)}catch(e){reject(e)}
   });
   timer=setTimeout(()=>{child.kill('SIGTERM');reject(Error('Remote audio processing exceeded its deadline'))},200000);
  });
  child.stdin.on('error',()=>{});
  child.stdin.write(JSON.stringify({version:1,id,audioSha256:sha256,bytes:info.size})+'\n');
  const input=createReadStream(file);input.on('error',()=>child.kill('SIGTERM'));input.pipe(child.stdin);
  try{return await result}finally{
   clearTimeout(timer);signal?.removeEventListener('abort',aborted);input.destroy();child.stdin.destroy();
   if(!closed){
    child.kill('SIGTERM');
    await Promise.race([exited,new Promise(resolve=>{timer=setTimeout(resolve,15000)})]);clearTimeout(timer);
    if(!closed){child.kill('SIGKILL');await exited;}
   }
  }
 }
}
