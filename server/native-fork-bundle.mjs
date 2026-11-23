import {createHash} from 'node:crypto';
import {mkdir,open,readFile,realpath,stat,lstat,link,unlink} from 'node:fs/promises';
import {resolve,relative,dirname,isAbsolute} from 'node:path';

const fail=message=>Object.assign(Error(message),{status:409});
export const digest=value=>createHash('sha256').update(value).digest('hex');
export const nativeId=value=>typeof value==='string'&&/^[a-zA-Z0-9_-]{5,100}$/.test(value);
const within=(root,path)=>{const p=relative(root,path);return p&&!p.startsWith('..')&&!isAbsolute(p);};
export function bundlePath(root,path){
  if(typeof path!=='string'||!/^((sessions\/\d{4}\/\d{2}\/\d{2})|archived_sessions)\/rollout-[a-zA-Z0-9_.:-]+\.jsonl$/.test(path))throw fail('Invalid native rollout location.');
  const destination=resolve(root,path);if(!within(resolve(root),destination))throw fail('Native rollout escaped its profile.');return destination;
}

/** Preserve native bytes, including opaque context, rather than exporting display rows. */
async function snapshotRollout(path,output,expectedId,bound,maxBytes){
  const input=await open(path,'r'),target=await open(output,'wx',0o600);let pending=Buffer.alloc(0),bytes=0,lines=0,header=null,lastOrdinal=-1;const completedTurnIds=[];
  const hash=createHash('sha256');let stopped=false;
  const consume=async line=>{
    let item;try{item=JSON.parse(line.toString('utf8'));}catch{throw fail('Native history contains an incomplete or invalid record.');}
    const ordinal=item.ordinal??lines;if(!Number.isSafeInteger(ordinal)||ordinal<0||ordinal<=lastOrdinal)throw fail('Native history ordinals are not coherent.');
    if(bound!=null&&ordinal>=bound){stopped=true;return;}
    if(lines===0){header=item.payload;if(item.type!=='session_meta'||header?.id!==expectedId)throw fail('Native history identity does not match the requested thread.');}
    if(bytes+line.length>maxBytes)throw fail('Native fork exceeds the configured snapshot size; source work remains intact.');
    if(item.type==='event_msg'&&item.payload?.type==='task_complete'){const id=item.payload.root_turn_id||item.payload.turn_id;if(nativeId(id))completedTurnIds.push(id);}
    await target.writeFile(line);hash.update(line);bytes+=line.length;lines++;lastOrdinal=ordinal;
  };
  try{
    const chunk=Buffer.alloc(65536);
    while(!stopped){const {bytesRead}=await input.read(chunk,0,chunk.length,null);if(!bytesRead)break;pending=Buffer.concat([pending,chunk.subarray(0,bytesRead)]);
      if(pending.length>maxBytes)throw fail('A native history record exceeds the snapshot limit.');
      let newline;while(!stopped&&(newline=pending.indexOf(10))>=0){await consume(pending.subarray(0,newline+1));pending=pending.subarray(newline+1);}
    }
    if(!stopped&&pending.length)throw fail('Native history is still being written; retry at a settled boundary.');
    if(!header)throw fail('Native history has no identity record.');
    if(bound!=null&&!stopped&&lastOrdinal+1<bound)throw fail('Native ancestor history is shorter than the fork boundary.');
    await target.sync();return {id:expectedId,bytes,sha256:hash.digest('hex'),records:lines,ordinalExclusive:bound,completedTurnIds,header};
  }finally{await input.close();await target.close();}
}

export async function exportNativeFork({codex,fork,codeHome,directory,maxBytes=128*1024*1024,maxAncestors=64}){
  if(!nativeId(fork?.id))throw fail('Missing native fork identity.');
  const home=await realpath(codeHome);await mkdir(directory,{recursive:true,mode:0o700});const files=[],visiting=new Set();let remaining=maxBytes;
  const visit=async(thread,bound=null)=>{
    if(visiting.has(thread.id)||visiting.size+files.length>=maxAncestors)throw fail('Native history lineage is cyclic or too deep.');visiting.add(thread.id);
    const path=await realpath(thread.path);if(!within(home,path))throw fail('Native rollout is outside the configured Codex profile.');
    const location=relative(home,path);bundlePath(home,location);const output=resolve(directory,thread.id+'.jsonl');
    const entry=await snapshotRollout(path,output,thread.id,bound,remaining);remaining-=entry.bytes;
    if(entry.header.dynamic_tools?.length)throw fail('This source uses app-owned dynamic tools. Their destination controller must be provisioned and qualified before terminal transfer.');
    const parent=entry.header.forked_from_id,cutoff=entry.header.forked_from_ordinal_exclusive;
    if(parent){if(!nativeId(parent)||!Number.isSafeInteger(cutoff)||cutoff<1)throw fail('Native fork has unsupported or incomplete ancestor lineage.');
      const ancestor=(await codex.call('thread/read',{threadId:parent,includeTurns:false})).thread;
      if(ancestor.id!==parent)throw fail('Native ancestor lookup returned another thread.');await visit(ancestor,cutoff);
    }
    files.push({id:entry.id,location,bytes:entry.bytes,sha256:entry.sha256,records:entry.records,ordinalExclusive:bound,completedTurnIds:entry.completedTurnIds,parent:parent||null,localFile:output});visiting.delete(thread.id);
  };
  await visit(fork);return {format:'nextcomp-native-terminal-fork-v1',forkId:fork.id,files,bytes:maxBytes-remaining};
}

async function existingPrefix(path,incoming,exact=false){
  let file;try{if((await lstat(path)).isSymbolicLink())throw fail('Destination rollout is a symbolic link.');file=await open(path,'r');}catch(e){if(e.code==='ENOENT')return false;throw e;}
  try{const size=(await file.stat()).size;if(size<incoming.length||exact&&size!==incoming.length)throw fail('Destination already has different history for this thread. Nothing was overwritten.');const chunk=Buffer.alloc(65536);let offset=0;while(offset<incoming.length){const {bytesRead}=await file.read(chunk,0,Math.min(chunk.length,incoming.length-offset),offset);if(!bytesRead||!chunk.subarray(0,bytesRead).equals(incoming.subarray(offset,offset+bytesRead)))throw fail('Destination already has different history for this thread. Nothing was overwritten.');offset+=bytesRead;}return true;}finally{await file.close();}
}

/** Add only immutable ancestor prefixes; never copy a live SQLite DB or credentials. */
export async function importNativeFork({bundle,codeHome,directory,onPlan=()=>{}}){
  if(bundle?.format!=='nextcomp-native-terminal-fork-v1'||!nativeId(bundle.forkId)||!Array.isArray(bundle.files)||!bundle.files.length||bundle.files.length>64)throw fail('Invalid native fork bundle.');
  const home=await realpath(codeHome),checked=[],seen=new Set();let total=0;
  for(const entry of bundle.files){
    if(!nativeId(entry.id)||seen.has(entry.id)||!Number.isSafeInteger(entry.bytes)||entry.bytes<1||typeof entry.sha256!=='string'||!/^[a-f0-9]{64}$/.test(entry.sha256)||entry.parent&&!seen.has(entry.parent))throw fail('Invalid native lineage ordering.');
    seen.add(entry.id);total+=entry.bytes;if(total>128*1024*1024)throw fail('Native fork bundle is too large.');
    const incomingPath=resolve(directory,entry.id+'.jsonl');if((await stat(incomingPath)).size!==entry.bytes)throw fail('Native fork checksum does not match.');const incoming=await readFile(incomingPath);if(incoming.length!==entry.bytes||digest(incoming)!==entry.sha256)throw fail('Native fork checksum does not match.');
    const first=JSON.parse(incoming.subarray(0,incoming.indexOf(10)).toString());if(first.type!=='session_meta'||first.payload?.id!==entry.id||(first.payload.forked_from_id||null)!==entry.parent)throw fail('Native lineage metadata does not match.');
    const completed=[];let previous=-1;const records=incoming.toString('utf8').split('\n');if(records.pop()!=='')throw fail('Native snapshot ends in a partial record.');for(const [index,line] of records.entries()){const item=JSON.parse(line),ordinal=item.ordinal??index;if(!Number.isSafeInteger(ordinal)||ordinal<=previous)throw fail('Invalid native snapshot ordinals.');previous=ordinal;if(item.type==='event_msg'&&item.payload?.type==='task_complete')completed.push(item.payload.root_turn_id||item.payload.turn_id);}if(JSON.stringify(completed)!==JSON.stringify(entry.completedTurnIds||[]))throw fail('Completed native boundary metadata does not match its history.');
    const path=bundlePath(home,entry.location);await mkdir(dirname(path),{recursive:true,mode:0o700});if(!within(home,await realpath(dirname(path))))throw fail('Destination profile contains an unsafe path.');
    const existing=await existingPrefix(path,incoming,entry.id===bundle.forkId);
    checked.push({path,incoming,existing:!!existing});
  }
  if(!seen.has(bundle.forkId)||bundle.files.at(-1).id!==bundle.forkId)throw fail('Native bundle does not terminate at the fork.');
  const plan={forkId:bundle.forkId,files:checked.length,bytes:total,newIds:bundle.files.filter((entry,i)=>!checked[i].existing).map(entry=>entry.id)};await onPlan(plan);
  for(const {path,incoming,existing} of checked){if(existing)continue;const temporary=path+'.nextcomp-'+bundle.forkId;let handle;
    try{try{handle=await open(temporary,'wx',0o600);}catch(e){if(e.code!=='EEXIST')throw e;const bytes=await readFile(temporary);if(!bytes.equals(incoming))throw fail('An unfinished snapshot has different native bytes.');await unlink(temporary);handle=await open(temporary,'wx',0o600);}await handle.writeFile(incoming);await handle.sync();await handle.close();handle=null;
      try{await link(temporary,path);}catch(e){if(e.code!=='EEXIST')throw e;await existingPrefix(path,incoming,path===bundlePath(home,bundle.files.at(-1).location));}
    }finally{if(handle)await handle.close();await unlink(temporary).catch(()=>{});}
  }
  return plan;
}

export async function workspaceEvidence(cwd,paths=[]){
  const root=await realpath(cwd);if(!(await stat(root)).isDirectory())throw fail('Destination workspace is not a directory.');
  if(!Array.isArray(paths)||paths.length>256)throw fail('Too many workspace evidence files.');
  const evidence=[];for(const name of paths){if(typeof name!=='string'||name.length>4096||isAbsolute(name))throw fail('Use workspace-relative evidence paths.');const path=await realpath(resolve(root,name));if(!within(root,path))throw fail('Workspace evidence escaped its folder.');
    const s=await stat(path);if(!s.isFile()||s.size>64*1024*1024)throw fail('Workspace evidence must be a file under 64 MiB.');evidence.push({path:name,bytes:s.size,sha256:digest(await readFile(path))});}
  return evidence;
}
