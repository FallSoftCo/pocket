import {MAX_RECORDING_WAV_BYTES} from './audio-limits.mjs';
import {mkdtempSync,writeFileSync,rmSync,mkdirSync,readFileSync,existsSync,renameSync,openSync,fsyncSync,closeSync,readdirSync,statSync,unlinkSync,statfsSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {DatabaseSync} from 'node:sqlite';
import {createHash} from 'node:crypto';
import {Codex} from './codex.mjs';
import {NativeVoice} from './native-voice.mjs';
import {NativeAudioTranscriber} from './native-audio-transcriber.mjs';

let codex,voice,db,directory;
const cancellation=new AbortController();
const cancelled=()=>{cancellation.abort();process.stdin.destroy();};
process.once('SIGTERM',cancelled);process.once('SIGINT',cancelled);process.once('SIGHUP',cancelled);
async function run(){
 if(!process.env.POCKET_LAX_AUDIO_COMMAND||!process.env.CODEX_HOME||!process.env.POCKET_NATIVE_AUDIO_PYTHON)throw Error('Installed native audio runtime configuration is missing');
 const chunks=[];let length=0;
 for await(const chunk of process.stdin){length+=chunk.length;if(length>(MAX_RECORDING_WAV_BYTES+4097))throw Error('Audio RPC exceeds size limit');chunks.push(chunk)}
 const input=Buffer.concat(chunks),boundary=input.indexOf(10);
 if(boundary<1||boundary>4096)throw Error('Invalid audio RPC header');
 const header=JSON.parse(input.subarray(0,boundary).toString('utf8')),audio=input.subarray(boundary+1);
 if(header.version!==1||audio.length!==header.bytes||!Number.isSafeInteger(header.bytes)||audio.length>MAX_RECORDING_WAV_BYTES||
  typeof header.audioSha256!=='string'||createHash('sha256').update(audio).digest('hex')!==header.audioSha256||
  typeof header.id!=='string'||!/^[a-zA-Z0-9_-]{8,100}$/.test(header.id))throw Error('Audio RPC identity or payload mismatch');
 const cache=process.env.POCKET_NATIVE_AUDIO_CACHE;
 let cachePath;
 if(cache){
  mkdirSync(cache,{recursive:true,mode:0o700});
  cachePath=join(cache,'asr-final-v1-'+header.audioSha256+'.json');
  if(existsSync(cachePath)){
   const saved=JSON.parse(readFileSync(cachePath,'utf8'));
   if(typeof saved.text!=='string'||!saved.text.trim()||saved.proof?.audioSha256!==header.audioSha256||saved.proof?.confirmed!==true||saved.proof?.boundary!=='session.closed')throw Error('Saved audio-only result failed validation');
   return {...saved,mediaCacheHit:true};
  }
  const free=statfsSync(cache);
  if(free.bavail*free.bsize<80*1024**3+64*1024**2)throw Error('Audio worker storage reserve is unavailable; original recording retained');
 }
 const command=JSON.parse(process.env.POCKET_LAX_AUDIO_COMMAND);
 if(!Array.isArray(command)||command.some(x=>typeof x!=='string')||!command.length)throw Error('Invalid installed native command');
 directory=mkdtempSync(join(tmpdir(),'nextcomp-audio-'));
 const file=join(directory,'recording.wav');writeFileSync(file,audio,{mode:0o600});
 db=new DatabaseSync(':memory:');codex=new Codex(undefined,{command,processCwd:directory});
 voice=new NativeVoice({codex,cwd:directory,requireFinalTranscript:true,controller:{db,get:()=>null,submitText:()=>{throw Error('Audio worker has no task authority')}}});
 const transcriber=new NativeAudioTranscriber({voice,python:process.env.POCKET_NATIVE_AUDIO_PYTHON});
 const result=await transcriber.transcribe({device:'upstream-audio-worker',id:header.id,file,sha256:header.audioSha256,signal:cancellation.signal});
 if(cachePath){
  const temp=cachePath+'.'+process.pid+'.preparing';
  const fd=openSync(temp,'wx',0o600);
  try{writeFileSync(fd,JSON.stringify(result));fsyncSync(fd);}finally{closeSync(fd);}
  renameSync(temp,cachePath);const dir=openSync(cache,'r');try{fsyncSync(dir);}finally{closeSync(dir);}
  const retained=readdirSync(cache).filter(name=>/^asr-final-v1-[a-f0-9]{64}\.json$/.test(name)).map(name=>({name,at:statSync(join(cache,name)).mtimeMs})).sort((a,b)=>b.at-a.at);
  for(const row of retained.slice(512))if(join(cache,row.name)!==cachePath)unlinkSync(join(cache,row.name));
 }
 return {...result,mediaCacheHit:false};
}
const output=value=>new Promise((resolve,reject)=>process.stdout.write(JSON.stringify(value)+'\n',error=>error?reject(error):resolve()));
try{await output(await run());}
catch(error){await output({error:String(error.message).slice(0,300)});process.exitCode=1}
finally{
 process.removeListener('SIGTERM',cancelled);process.removeListener('SIGINT',cancelled);process.removeListener('SIGHUP',cancelled);
 if(voice)clearInterval(voice.timer);
 if(codex)codex.closeTransport();
 if(db)db.close();
 if(directory)rmSync(directory,{recursive:true,force:true});
}
