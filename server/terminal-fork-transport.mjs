import {spawn} from 'node:child_process';
import {open} from 'node:fs/promises';
import {once} from 'node:events';
import {nativeId} from './native-fork-bundle.mjs';
import {createHash} from 'node:crypto';

const quote=s=>"'"+String(s).replaceAll("'","'\\''")+"'";
export async function sshTerminalFork(target,request){
  if(!/^[a-zA-Z0-9_.-]{1,100}$/.test(target?.sshHost||'')||!target.helper?.startsWith('/')||!target.data?.startsWith('/')||!target.profile?.startsWith('/'))throw Error('The environment owner must configure a private SSH target/helper/profile.');
  const url=new URL(target.url);if(!['127.0.0.1','localhost','[::1]'].includes(url.hostname)||url.protocol!=='http:')throw Error('Destination control must use its own loopback backend.');
  const command=`node ${quote(target.helper)} --data ${quote(target.data)} --profile ${quote(target.profile)} --url ${quote(target.url)}`;
  const child=spawn('ssh',['-o','BatchMode=yes','-o','ConnectTimeout=10',target.sshHost,command],{stdio:['pipe','pipe','pipe']});let output='',errors='',inputError=null;
  const done=new Promise((resolve,reject)=>{child.once('error',reject);child.once('close',code=>resolve(code));});done.catch(()=>{});
  child.stdout.on('data',b=>{output+=b;if(output.length>65536)child.kill();});child.stderr.on('data',b=>{errors+=b;if(errors.length>65536)child.kill();});
  child.stdin.on('error',error=>{inputError=error;});
  const timeout=setTimeout(()=>child.kill(),120000);
  const send=async value=>{if(inputError)throw inputError;if(child.exitCode!=null||child.stdin.destroyed)throw Error('Destination transport closed before transfer completion.');if(!child.stdin.write(JSON.stringify(value)+'\n'))await Promise.race([once(child.stdin,'drain'),done.then(()=>{throw Error('Destination transport closed before transfer completion.');})]);};
  try{
    for await(const frame of terminalForkFrames(request))await send(frame);
    child.stdin.end();const exitCode=await done;let reply;try{reply=JSON.parse(output);}catch{throw Error('Destination transport returned no valid receipt.');}if(reply.error)throw Error(reply.error);if(exitCode!==0)throw Error('Destination transport did not confirm completion.');return reply;
  }finally{clearTimeout(timeout);if(child.exitCode==null)child.kill();}
}

export async function* terminalForkFrames(request){
  const packet=request.packet?{...request.packet,bundle:{...request.packet.bundle,files:request.packet.bundle.files.map(({localFile,...entry})=>entry)}}:undefined;
  if(packet){const manifest=Buffer.from(JSON.stringify(packet));if(manifest.length>34*1024*1024)throw Error('Transfer checkpoint is too large.');yield ({operation:request.operation,id:request.id,manifestBytes:manifest.length,manifestSha256:createHash('sha256').update(manifest).digest('hex')});for(let i=0;i<manifest.length;i+=65536)yield ({manifest:manifest.subarray(i,i+65536).toString('base64')});yield ({manifestEnd:true});}else yield ({operation:request.operation,id:request.id});
  if(packet)for(const entry of request.packet.bundle.files){const file=await open(entry.localFile,'r');try{const bytes=Buffer.alloc(65536);while(true){const {bytesRead}=await file.read(bytes,0,bytes.length,null);if(!bytesRead)break;yield ({file:entry.id,data:bytes.subarray(0,bytesRead).toString('base64')});}yield ({file:entry.id,end:true});}finally{await file.close();}}
}

/** Private, bounded stdin protocol; no credentials or shell arguments come from the agent. */
export async function receiveTerminalFork(lines,{directory,ownerRequest,writeFile,openFile,mkdir}){
  let request=null,active=null,total=0,ended=new Set(),handle=null,hash=null,fileBytes=0,manifestChunks=[],manifestSize=0;
  try{for await(const line of lines){if(Buffer.byteLength(line)>100000)throw Error('Transfer frame is too large.');const frame=JSON.parse(line);
    if(!request){request=frame;if(!nativeId(request.id)||!['prepare','activate','observe'].includes(request.operation))throw Error('Invalid transfer operation.');
      if(request.operation!=='prepare')continue;
      if(request.manifestBytes){if(!Number.isSafeInteger(request.manifestBytes)||request.manifestBytes>34*1024*1024||request.manifestBytes<2||!/^[a-f0-9]{64}$/.test(request.manifestSha256||''))throw Error('Invalid checkpoint envelope.');continue;}
      if(request.packet?.plan?.id!==request.id||request.packet?.bundle?.files?.length>64)throw Error('Invalid transfer checkpoint.');
      await mkdir(directory(request.id));
      await writeFile(directory(request.id)+'/manifest.json',JSON.stringify(request.packet));continue;
    }
    if(request.operation!=='prepare')throw Error('Unexpected transfer data.');
    if(!request.packet){if(frame.manifestEnd){const bytes=Buffer.concat(manifestChunks);if(manifestSize!==request.manifestBytes||createHash('sha256').update(bytes).digest('hex')!==request.manifestSha256)throw Error('Transfer checkpoint checksum mismatch.');request.packet=JSON.parse(bytes.toString('utf8'));manifestChunks=[];if(request.packet?.plan?.id!==request.id||!Array.isArray(request.packet?.bundle?.files)||request.packet.bundle.files.length>64)throw Error('Invalid transfer checkpoint.');await mkdir(directory(request.id));await writeFile(directory(request.id)+'/manifest.json',JSON.stringify(request.packet));continue;}if(typeof frame.manifest!=='string'||frame.manifest.length>90000)throw Error('Invalid checkpoint chunk.');const bytes=Buffer.from(frame.manifest,'base64');manifestSize+=bytes.length;if(manifestSize>request.manifestBytes)throw Error('Transfer checkpoint is too large.');manifestChunks.push(bytes);continue;}
    const entry=request.packet.bundle.files.find(e=>e.id===frame.file);if(!entry||!nativeId(entry.id)||ended.has(entry.id))throw Error('Unexpected native history file.');
    if(!active){active=entry.id;handle=await openFile(directory(request.id)+'/'+entry.id+'.jsonl');hash=createHash('sha256');fileBytes=0;}
    if(active!==entry.id)throw Error('Interleaved history files are not allowed.');
    if(frame.end){if(fileBytes!==entry.bytes||hash.digest('hex')!==entry.sha256)throw Error('Native history transfer checksum mismatch.');await handle.close();handle=null;ended.add(active);active=null;hash=null;continue;}
    if(typeof frame.data!=='string'||frame.data.length>90000)throw Error('Invalid history chunk.');const bytes=Buffer.from(frame.data,'base64');total+=bytes.length;fileBytes+=bytes.length;if(total>128*1024*1024||fileBytes>entry.bytes)throw Error('Native history transfer is too large.');await handle.writeFile(bytes);hash.update(bytes);
  }
  if(!request||active||request.operation==='prepare'&&(!request.packet||ended.size!==request.packet.bundle.files.length))throw Error('Native history transfer is incomplete.');
  return ownerRequest(request.operation==='observe'?`/api/terminal-forks/imports/${request.id}`:`/api/terminal-forks/imports/${request.id}/${request.operation}`,request.operation==='observe'?undefined:{method:'POST',body:{}});
  }finally{if(handle)await handle.close();}
}
