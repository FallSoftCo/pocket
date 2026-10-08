const terminal=new Set(['redeemed','unavailable','expired','cancelled']);

// One explicitly authorized benefit, one durable idempotency key. This component
// neither starts model work nor purchases anything or changes account settings.
export async function runAccountReset({state,call,save,now=Date.now()}){
 if(!state?.enabled||terminal.has(state.status)||now<(state.nextAt||0))return state;
 if(!state.accountId||!state.creditId||!state.idempotencyKey||!Number.isFinite(state.thresholdPercent)||state.thresholdPercent<90||state.thresholdPercent>100)throw Error('Invalid exact-benefit reset authorization.');
 const persist=patch=>{Object.assign(state,patch);save(state);};
 const defer=(reason,delay=60000)=>persist({lastCheck:now,reason,nextAt:now+delay});
 try{
  const account=await call('account/read',{refreshToken:false});
  const usage=await call('account/rateLimits/read',{excludeResetCreditDetails:false});
  if(account.account?.type!=='chatgpt'||usage.accountId!==state.accountId){defer('Signed-in account does not match authorization.',3600000);return state;}
  const bucket=usage.rateLimitsByLimitId?.codex||usage.rateLimits;
  const weekly=[bucket?.primary,bucket?.secondary].find(w=>w?.windowDurationMins===10080);
  persist({lastCheck:now,weeklyUsedPercent:weekly?.usedPercent??null,availableCount:usage.rateLimitResetCredits?.availableCount??null});
  if(state.status==='acknowledged'){
   persist({status:'redeemed',verifiedAt:now,afterWeeklyUsedPercent:weekly?.usedPercent??null,nextAt:null});return state;
  }
  if(bucket?.spendControlReached!==false||bucket.individualLimit?.remainingPercent<=0){defer('Spending-control health does not authorize a reset.',3600000);return state;}
  // After uncertain delivery only reconcile the exact persisted attempt, never
  // choose another benefit/key or infer non-delivery from a timeout.
  const pending=state.status==='redeeming';
  const credit=usage.rateLimitResetCredits?.credits?.find(c=>c.id===state.creditId);
  if(!pending){
   if(state.expiresAt!==null&&now>=state.expiresAt*1000){persist({status:'expired',nextAt:null});return state;}
   if(!Array.isArray(usage.rateLimitResetCredits?.credits)){defer('Benefit details unavailable.');return state;}
   if(!credit||credit.status!=='available'){persist({status:'unavailable',reason:'The exact authorized benefit is no longer available.',nextAt:null});return state;}
   if(credit.resetType!=='codexRateLimits'){defer('Unsupported benefit type.',3600000);return state;}
   if(!weekly||!Number.isFinite(weekly.usedPercent)||weekly.usedPercent<state.thresholdPercent){defer('Waiting for useful near-exhaustion timing.');return state;}
   // Avoid burning an extra reset just before a normal weekly reset.
   if(weekly.resetsAt>0&&weekly.resetsAt*1000-now<=10*60000&&(credit.expiresAt===null||credit.expiresAt>weekly.resetsAt)){
    defer('Normal weekly reset is imminent.',10*60000);return state;
   }
  }
  persist({status:'redeeming',attemptedAt:state.attemptedAt||now,attempts:(state.attempts||0)+1,beforeWeeklyUsedPercent:state.beforeWeeklyUsedPercent??weekly?.usedPercent});
  const response=await call('account/rateLimitResetCredit/consume',{creditId:state.creditId,idempotencyKey:state.idempotencyKey});
  if(['reset','alreadyRedeemed'].includes(response.outcome)){
   persist({status:'acknowledged',outcome:response.outcome,acknowledgedAt:now,nextAt:now});
   const after=await call('account/rateLimits/read',{excludeResetCreditDetails:false});
   if(after.accountId!==state.accountId)throw Error('Account changed during verification.');
   const b=after.rateLimitsByLimitId?.codex||after.rateLimits;
   persist({status:'redeemed',verifiedAt:now,afterWeeklyUsedPercent:[b?.primary,b?.secondary].find(w=>w?.windowDurationMins===10080)?.usedPercent??null,remainingResets:after.rateLimitResetCredits?.availableCount??null,nextAt:null});
  }else if(response.outcome==='noCredit')persist({status:'unavailable',outcome:response.outcome,nextAt:null});
  else if(response.outcome==='nothingToReset'){persist({status:'scheduled',outcome:response.outcome});defer('Backend found no eligible window to reset.',30*60000);}
  else throw Error('Unknown reset outcome; exact attempt requires reconciliation.');
 }catch{
  // No sensitive provider error payloads; a lost acknowledgement retains the
  // same key across process restarts and uses the documented idempotent API.
  defer('Account/reset transport unavailable; retaining exact durable attempt.',Math.min(3600000,120000*2**Math.min(state.attempts||0,5)));
 }
 return state;
}
