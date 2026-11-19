import {randomUUID} from 'node:crypto';
const terminal=new Set(['redeemed','unavailable','expired','cancelled']);

// One explicitly authorized benefit, one durable idempotency key. This component
// neither starts model work nor purchases anything or changes account settings.
export async function runAccountReset({state,call,save,now=Date.now()}){
 if(state?.mode==='standing'||state?.schemaVersion===2)return runStandingReset({state,call,save,now});
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


export function accountResetDue(state,now=Date.now()){
 if(state?.enabled!==true||now<(state.nextAt||0))return false;
 return state.mode==='standing'||state.schemaVersion===2
  ?!['paused','cancelled'].includes(state.status):!terminal.has(state.status);
}

// Explicit owner migration only; the runner never upgrades a one-off silently.
// The complete original receipt survives, including uncertain original attempts.
export function standingAccountReset(legacy,{accountId,authorizationReference,now=Date.now()}){
 if(typeof accountId!=='string'||!accountId.trim()||typeof authorizationReference!=='string'||!authorizationReference.trim()||legacy?.accountId&&legacy.accountId!==accountId)throw Error('Standing reset requires matching account authorization.');
 if(legacy&&(!['scheduled','redeeming','acknowledged','redeemed','unavailable','expired','cancelled','paused'].includes(legacy.status)||
    typeof legacy.enabled!=='boolean'||typeof legacy.accountId!=='string'||!legacy.accountId.trim()||typeof legacy.creditId!=='string'||!legacy.creditId.trim()||
    typeof legacy.idempotencyKey!=='string'||!legacy.idempotencyKey.trim()))throw Error('Original reset state requires explicit reconciliation.');
 const state={schemaVersion:2,mode:'standing',enabled:true,accountId,authorizationReference,thresholdPercent:99,status:'armed',nextAt:now,receipts:[],legacyReceipt:legacy?structuredClone(legacy):null};
 if(['redeeming','acknowledged'].includes(legacy?.status)){
  if(!legacy.creditId||!legacy.idempotencyKey)throw Error('Original uncertain reset identity is incomplete.');
  state.attempt={creditId:legacy.creditId,idempotencyKey:legacy.idempotencyKey,status:legacy.status==='acknowledged'?'acknowledged':'pending',attemptedAt:legacy.attemptedAt??now,attempts:legacy.attempts??0,...(legacy.outcome?{outcome:legacy.outcome}:{})};
  state.status=legacy.status==='acknowledged'?'awaitingUsageDrop':'redeeming';
 }
 // A deliberate stop cannot be overridden merely by transforming its schema.
 if(legacy?.enabled===false||['paused','cancelled'].includes(legacy?.status)){state.enabled=false;state.status='paused';}
 return state;
}

async function runStandingReset({state,call,save,now,manualId=null}){
 if(!manualId&&!accountResetDue(state,now))return state;
 if(state.schemaVersion!==2||state.mode!=='standing'||!state.accountId||!state.authorizationReference||state.thresholdPercent!==99||
    !['armed','redeeming','awaitingUsageDrop'].includes(state.status)||(!Array.isArray(state.receipts)||state.receipts.some(r=>r?.outcome==='noCredit'&&!Array.isArray(r.eligibleCreditIds))))throw Error('Invalid standing reset authorization.');
 const attempt=state.attempt;
 if(state.status==='armed'&&attempt||state.status!=='armed'&&(!attempt?.creditId||!attempt.idempotencyKey||
    attempt.status!==(state.status==='redeeming'?'pending':'acknowledged')))throw Error('Incomplete durable reset attempt.');
 const persist=async patch=>{
  const next={...state,...patch};
  try{await save(next);}catch{throw Object.assign(Error('Durable reset state unavailable; dispatch stopped.'),{code:'RESET_STATE_WRITE_FAILED'});}
  Object.assign(state,next);
 };
 const defer=async(reason,delay=60000)=>persist({lastCheck:now,reason,nextAt:now+delay});
 try{
  const account=await call('account/read',{refreshToken:false});
  const usage=await call('account/rateLimits/read',{excludeResetCreditDetails:false});
  if(account.account?.type!=='chatgpt'||usage.accountId!==state.accountId){await defer('Signed-in account does not match authorization.',3600000);return state;}
  const buckets=usage.rateLimitsByLimitId;
  const bucket=buckets&&Object.keys(buckets).length?buckets.codex:usage.rateLimits;
  const windows=[bucket?.primary,bucket?.secondary].filter(w=>w!=null);
  if(!bucket||bucket.limitId!=null&&bucket.limitId!=='codex'||!windows.length||windows.some(w=>!Number.isFinite(w.usedPercent)||w.usedPercent<0||w.usedPercent>100||
     !Number.isFinite(w.windowDurationMins)||w.windowDurationMins<=0||!(w.resetsAt===null||Number.isFinite(w.resetsAt)&&w.resetsAt>0))){
   await defer('Unambiguous Codex usage windows unavailable.');return state;
  }
  const fingerprint=JSON.stringify(windows.map(w=>[w.windowDurationMins,w.resetsAt,w.usedPercent]).sort((a,b)=>a[0]-b[0]));
  const used=Math.max(...windows.map(w=>w.usedPercent));
  const weekly=windows.find(w=>w.windowDurationMins===10080);
  await persist({lastCheck:now,weeklyUsedPercent:weekly?.usedPercent??null,highestUsedPercent:used,availableCount:usage.rateLimitResetCredits?.availableCount??null});
  if(state.status==='awaitingUsageDrop'){
   if(Number.isFinite(used)&&used>=0&&used<(state.attempt.manualId?state.attempt.beforeHighestUsedPercent:state.thresholdPercent)){
    await persist({status:'armed',attempt:null,receipts:[...state.receipts,{...state.attempt,verifiedAt:now,afterHighestUsedPercent:used}],reason:'Usage fell below threshold; standing policy rearmed.',nextAt:now+60000});
   }else await defer('Acknowledged attempt retained; waiting for usage below threshold.');
   return state;
  }
  if(bucket?.spendControlReached!==false||!(bucket.individualLimit===null||Number.isFinite(bucket.individualLimit?.remainingPercent)&&bucket.individualLimit.remainingPercent>0)||
     bucket.rateLimitReachedType&&bucket.rateLimitReachedType!=='rate_limit_reached'){
   await defer('Spending-control health does not authorize a reset.',3600000);return state;
  }
  if(state.status==='armed'){
   if(!Number.isFinite(used)||used<(manualId?0.000001:state.thresholdPercent)||used>100){await defer('Waiting for 99 percent usage in a Codex allowance window.');return state;}
   if(state.receipts.at(-1)?.outcome==='nothingToReset'&&state.receipts.at(-1).windowFingerprint===fingerprint){
    await defer('Provider found nothing to reset; waiting for changed usage windows.');return state;
   }
   const credits=usage.rateLimitResetCredits?.credits;
   if(!Array.isArray(credits)){await defer('Banked reset details unavailable; policy remains armed.');return state;}
   const spent=new Set(state.receipts.filter(r=>r.outcome!=='nothingToReset').map(r=>r.creditId));
   if(['redeemed','acknowledged','unavailable'].includes(state.legacyReceipt?.status))spent.add(state.legacyReceipt.creditId);
   const available=credits.filter(c=>typeof c.id==='string'&&c.id&&c.status==='available'&&c.resetType==='codexRateLimits'&&
    (c.expiresAt===null||Number.isFinite(c.expiresAt)&&c.expiresAt*1000>now));
   const rejectedInventory=new Set(state.receipts.filter(r=>r.outcome==='noCredit').flatMap(r=>r.eligibleCreditIds));
   const eligible=available.filter(c=>!spent.has(c.id)&&!rejectedInventory.has(c.id));
   eligible.sort((a,b)=>(a.expiresAt??Infinity)-(b.expiresAt??Infinity)||a.id.localeCompare(b.id));
   const credit=eligible[0];
   if(!credit){await defer('No eligible banked reset; policy remains armed.');return state;}
   await persist({status:'redeeming',attempt:{...(manualId?{manualId}:{}),creditId:credit.id,idempotencyKey:randomUUID(),status:'pending',attemptedAt:now,attempts:0,beforeHighestUsedPercent:used,windowFingerprint:fingerprint,eligibleCreditIds:[...new Set(available.map(c=>c.id))]},reason:'Exact banked reset intent saved.',nextAt:now});
  }
  // Once dispatch is uncertain, expiry/usage/list changes cannot select a new
  // benefit: reconcile the exact provider-idempotent operation after guards.
  await persist({attempt:{...state.attempt,attempts:state.attempt.attempts+1},nextAt:now});
  const response=await call('account/rateLimitResetCredit/consume',{creditId:state.attempt.creditId,idempotencyKey:state.attempt.idempotencyKey});
  if(['reset','alreadyRedeemed'].includes(response.outcome)){
   await persist({status:'awaitingUsageDrop',attempt:{...state.attempt,status:'acknowledged',outcome:response.outcome,acknowledgedAt:now},reason:'Provider acknowledged exact attempt; waiting for usage below threshold.',nextAt:now+60000});
  }else if(['noCredit','nothingToReset'].includes(response.outcome)){
   await persist({status:'armed',attempt:null,receipts:[...state.receipts,{...state.attempt,status:'acknowledged',outcome:response.outcome,windowFingerprint:fingerprint,eligibleCreditIds:state.attempt.eligibleCreditIds??(usage.rateLimitResetCredits?.credits||[]).map(c=>c.id),acknowledgedAt:now}],reason:response.outcome==='noCredit'?'Exact benefit unavailable; waiting for another eligible banked reset.':'Provider found nothing to reset; waiting for changed usage windows.',nextAt:now+60000});
  }else throw Error('Unknown reset outcome.');
 }catch(error){
  if(error?.code==='RESET_STATE_WRITE_FAILED')throw error;
  await defer('Account/reset transport unavailable; retaining exact durable attempt.',Math.min(3600000,120000*2**Math.min(state.attempt?.attempts||0,5)));
 }
 return state;
}

// Invoked only under the same process lock as the automatic runner. A manual
// action never changes enabled, threshold or authorization of the standing job.
export async function runManualAccountReset({state,call,save,request,now=Date.now()}){
 if(!request||!/^[a-zA-Z0-9_-]{16,100}$/.test(request.id)||!request.deviceId||request.accountId!==state.accountId||state.schemaVersion!==2||state.mode!=='standing')
  throw Error('Reset account or request does not match the configured source.');
 const persist=async patch=>{const next={...state,...patch};await save(next);Object.assign(state,next);};
 const previous=state.manualRequests?.[request.id];
 if(previous&&previous.deviceId!==request.deviceId)throw Error('Reset request belongs to another device.');
 if(previous?.result)return previous.result;
 const resultFor=attempt=>({state:attempt.outcome==='reset'||attempt.outcome==='alreadyRedeemed'?'acknowledged':'not-reset',
  message:({reset:'Reset applied. Refreshing account usage.',alreadyRedeemed:'This reset was already applied.',noCredit:'That banked reset is no longer available.',nothingToReset:'The provider found no allowance to reset.'})[attempt.outcome]});
 const finish=async result=>{await persist({...(state.enabled===false&&state.status==='armed'?{status:'paused'}:{}),manualRequests:{...state.manualRequests,[request.id]:{...state.manualRequests?.[request.id],deviceId:request.deviceId,result,updatedAt:now}}});return result;};
 const old=[state.attempt,...state.receipts].find(a=>a?.manualId===request.id&&a.outcome);
 if(old)return finish(resultFor(old));
 if(!previous){
  if(state.status==='awaitingUsageDrop')await runStandingReset({state,call,save,now,manualId:request.id});
  // A second tap/device must not inherit or replace an unresolved reset.
  if(state.attempt||Object.values(state.manualRequests||{}).some(r=>!r.result))return finish({state:'not-reset',message:'An earlier reset is still being reconciled. No additional reset was used.'});
  if(!['armed','paused'].includes(state.status))return {state:'unavailable',message:'Reset state needs reconciliation before another reset can be used.'};
  await persist({...(state.enabled===false?{automaticStop:state.automaticStop||{status:state.status,reason:state.reason}}:{}),manualRequests:{...state.manualRequests,[request.id]:{deviceId:request.deviceId,createdAt:now}},status:'armed'});
 }
 await runStandingReset({state,call,save,now,manualId:request.id});
 const attempt=[state.attempt,...state.receipts].find(a=>a?.manualId===request.id);
 if(attempt?.outcome)return finish(resultFor(attempt));
 if(attempt)return {state:'pending',message:'The reset acknowledgement is pending. Check this same request; do not start another.'};
 // No provider mutation was admitted. A later deliberate action can recheck.
 return finish({state:'not-reset',message:state.reason||'No eligible banked reset is currently available.'});
}
