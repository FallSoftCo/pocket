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

// Existing personal-account credits are consumed by Codex itself, never by an
// API-key fallback. Authorization is private and bound to the signed-in account.
export function recoveryAccountUsage(cache,authorization,{now=Date.now()}={}){
 const quota=cache.buckets.codex||cache.defaultBucket;
 if(cache.failed||!cache.codex.ready||cache.updatedAt===null||now-cache.updatedAt>cache.ttl*2)return {ok:false};
 if(quota?.spendControlReached===true||quota?.individualLimit?.remainingPercent<=0)return {ok:false};
 if(quota?.rateLimitReachedType&&!['rate_limit_reached','rateLimitReached'].includes(quota.rateLimitReachedType))return {ok:false};
 const stops=[quota?.primary,quota?.secondary].filter(w=>w&&w.usedPercent>=100);
 if(!stops.length)return {ok:true};
 const credits=quota?.credits;
 const creditAllowed=authorization?.enabled===true&&!!authorization.accountId&&authorization.accountId===cache.accountId
   &&['plus','pro','promax'].includes(quota?.planType)&&quota.spendControlReached===false
   &&credits?.hasCredits===true&&Number.isFinite(Number(credits.balance))&&Number(credits.balance)>0;
 if(creditAllowed)return {ok:true,existingCredits:true};
 // Recheck sparsely rather than sleeping until a weekly reset: a balance or
 // account eligibility can recover before then. The durable scheduler backs off.
 return {ok:false};
}
