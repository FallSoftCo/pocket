import {statfsSync} from 'node:fs';

export function recoveryStorageReady(path,{statfs=statfsSync,minimumBytes=64*1024*1024}={}){
 try{const fs=statfs(path);return Number(fs.bavail)*Number(fs.bsize)>=minimumBytes;}catch{return false;}
}

export async function recoveryUsagePolicy(codex,threadId){
 // Native goals have their own read route; thread metadata does not contain them.
 const {goal}=await codex.call('thread/goal/get',{threadId});
 if(['paused','budgetLimited','usageLimited','tokenLimited'].includes(goal?.status))return {ok:false,blocked:true,reason:'The task’s usage-stop policy is paused. Recovery will not override it.'};
 return {ok:true};
}
