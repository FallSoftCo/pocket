import {readFile} from 'node:fs/promises';
import {ownerRequest} from './local-client.mjs';
const [operation,...raw]=process.argv.slice(2),options={};for(let i=0;i<raw.length;i+=2){if(!raw[i]?.startsWith('--')||raw[i+1]==null)throw Error('Use named options with values.');options[raw[i].slice(2)]=raw[i+1];}
try{
  let result;
  if(operation==='request'){
    const threadId=process.env.CODEX_THREAD_ID;if(!threadId)throw Error('Run this from the source Codex session; CODEX_THREAD_ID is required.');
    const checkpoint=JSON.parse(await readFile(options.checkpoint,'utf8'));
    result=await ownerRequest('/api/terminal-forks',{method:'POST',body:{...checkpoint,id:options.id,threadId,target:options.to,cwd:options.cwd}});
  }else if(['release','cancel','status'].includes(operation)&&options.id){
    if(operation!=='status'){
      const current=await ownerRequest('/api/terminal-forks/'+encodeURIComponent(options.id));if(!process.env.CODEX_THREAD_ID||current.sourceThreadId!==process.env.CODEX_THREAD_ID)throw Error('Only the original source session can release or cancel its transfer.');
    }
    result=await ownerRequest('/api/terminal-forks/'+encodeURIComponent(options.id)+(operation==='status'?'':'/'+operation),operation==='status'?{}:{method:'POST',body:{}});
  }else if(operation==='list')result=await ownerRequest('/api/terminal-forks');
  else throw Error('Usage: terminal-fork.mjs request --id STABLE_ID --to ENV --cwd DESTINATION --checkpoint FILE | release/cancel/status --id STABLE_ID | list');
  console.log(JSON.stringify(result,null,2));
}catch(e){console.error(e.message);process.exitCode=1;}
