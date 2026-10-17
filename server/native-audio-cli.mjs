import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
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
try{
 if(!process.env.POCKET_LAX_AUDIO_COMMAND||!process.env.CODEX_HOME||!process.env.POCKET_NATIVE_AUDIO_PYTHON)throw Error('Installed native audio runtime configuration is missing');
 const chunks=[];let length=0;
 for await(const chunk of process.stdin){length+=chunk.length;if(length>3845000)throw Error('Audio RPC exceeds size limit');chunks.push(chunk)}
 const input=Buffer.concat(chunks),boundary=input.indexOf(10);
 if(boundary<1||boundary>4096)throw Error('Invalid audio RPC header');
 const header=JSON.parse(input.subarray(0,boundary).toString('utf8')),audio=input.subarray(boundary+1);
 if(header.version!==1||audio.length!==header.bytes||!Number.isSafeInteger(header.bytes)||audio.length>3840044||
  typeof header.audioSha256!=='string'||createHash('sha256').update(audio).digest('hex')!==header.audioSha256||
  typeof header.id!=='string'||!/^[a-zA-Z0-9_-]{8,100}$/.test(header.id))throw Error('Audio RPC identity or payload mismatch');
 const command=JSON.parse(process.env.POCKET_LAX_AUDIO_COMMAND);
 if(!Array.isArray(command)||command.some(x=>typeof x!=='string')||!command.length)throw Error('Invalid installed native command');
 directory=mkdtempSync(join(tmpdir(),'nextcomp-audio-'));
 const file=join(directory,'recording.wav');writeFileSync(file,audio,{mode:0o600});
 db=new DatabaseSync(':memory:');codex=new Codex(undefined,{command,processCwd:directory});
 voice=new NativeVoice({codex,cwd:directory,requireFinalTranscript:true,controller:{db,get:()=>null,submitText:()=>{throw Error('Audio worker has no task authority')}}});
 const transcriber=new NativeAudioTranscriber({voice,python:process.env.POCKET_NATIVE_AUDIO_PYTHON});
 const result=await transcriber.transcribe({device:'upstream-audio-worker',id:header.id,file,sha256:header.audioSha256,signal:cancellation.signal});
 console.log(JSON.stringify(result));
}catch(error){console.log(JSON.stringify({error:String(error.message).slice(0,300)}));process.exitCode=1}
finally{
 process.removeListener('SIGTERM',cancelled);process.removeListener('SIGINT',cancelled);process.removeListener('SIGHUP',cancelled);
 if(voice)clearInterval(voice.timer);
 if(codex)codex.closeTransport();
 if(db)db.close();
 if(directory)rmSync(directory,{recursive:true,force:true});
}
