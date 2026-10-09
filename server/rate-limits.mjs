import {creditBalance} from './credit-observations.mjs';
const WEEK_MINUTES=7*24*60;
const own=(value,key)=>Object.prototype.hasOwnProperty.call(value||{},key);

function weeklyWindow(bucket){
  const window=[bucket?.primary,bucket?.secondary].find(w=>w?.windowDurationMins===WEEK_MINUTES);
  if(!window||typeof window.usedPercent!=='number'||!Number.isFinite(window.usedPercent))return null;
  const usedPercent=Math.min(100,Math.max(0,window.usedPercent));
  return {usedPercent,remainingPercent:100-usedPercent,
    resetsAt:Number.isFinite(window.resetsAt)&&window.resetsAt>0?window.resetsAt:null};
}

// Only explicit quota/credit display fields leave this cache. Account identifiers,
// authentication and private observation keys are never forwarded.
export class AccountRateLimits {
  constructor(codex,onChange=()=>{},{clock=Date.now,ttl=60000,observations=null,creditAuthorization=null}={}){
    this.codex=codex;this.onChange=onChange;this.clock=clock;this.ttl=ttl;
    this.buckets={};this.defaultBucket=null;this.updatedAt=null;this.lastAttempt=null;
    this.failed=false;this.loading=true;this.generation=0;this.inflight=null;this.pendingUpdates=null;
    this.accountId=null;this.observations=observations;this.creditAuthorization=creditAuthorization;this.creditUpdatedAt=null;this.creditComparison=null;
  }
  snapshot(){
    const weekly=weeklyWindow(this.buckets.codex||this.defaultBucket);
    const stale=!!weekly&&(this.failed||!this.codex.ready||this.clock()-this.updatedAt>this.ttl*2
      ||(weekly.resetsAt!==null&&this.clock()>=weekly.resetsAt*1000));
    const bucket=this.buckets.codex||this.defaultBucket,raw=bucket?.credits;
    const balance=creditBalance(raw?.balance);
    const credits={balance,hasCredits:typeof raw?.hasCredits==='boolean'?raw.hasCredits:null,unlimited:typeof raw?.unlimited==='boolean'?raw.unlimited:null,updatedAt:this.creditUpdatedAt,
      stale:this.failed||!this.codex.ready||!this.creditUpdatedAt||this.clock()-this.creditUpdatedAt>this.ttl*2,
      observation:this.creditComparison?.observation||null,comparisonState:this.creditComparison?.comparisonState||'unavailable'};
    const blocked=bucket?.spendControlReached===true||bucket?.individualLimit?.remainingPercent<=0||(bucket?.rateLimitReachedType&&!['rate_limit_reached','rateLimitReached'].includes(bucket.rateLimitReachedType));
    const authorized=this.creditAuthorization?.enabled===true&&!!this.accountId&&this.creditAuthorization.accountId===this.accountId;
    const available=raw?.unlimited===true||(raw?.hasCredits===true&&balance!==null&&Number(balance)>0);
    const confirmed=available&&['plus','pro','promax'].includes(bucket?.planType)&&bucket?.spendControlReached===false&&!blocked;
    const state=credits.stale?'unverified':blocked?'spending-blocked':weekly?.remainingPercent===0?(confirmed&&authorized?'existing-credits-available':available?'credits-present-unverified':'included-exhausted'):weekly?'included-available':'unverified';
    const reason=state==='credits-present-unverified'?(!authorized?'authorization-unverified':!['plus','pro','promax'].includes(bucket?.planType)?'provider-plan-unverified':'provider-spending-control-unverified'):null;
    const labels={'spending-blocked':'Provider reports a spending or account limit','unverified':'Continuation status unavailable','existing-credits-available':'Included allowance exhausted · existing-credit continuation available','credits-present-unverified':'Included allowance exhausted · credits present; continuation unverified','included-exhausted':'Included allowance exhausted · no available credits reported','included-available':'Included allowance remains'};
    return {weekly,credits,continuation:{state,label:labels[state],reason},updatedAt:this.updatedAt,stale,state:weekly?'available':this.loading?'loading':'unavailable'};
  }
  update(payload,{replace=false}={}){
    if(!replace&&own(payload,'accountId')&&payload.accountId!==this.accountId){this.clear();replace=true;}
    if(replace){this.buckets={};this.defaultBucket=null;this.accountId=payload?.accountId||null;}
    if(own(payload,'rateLimitsByLimitId')){
      for(const [id,bucket] of Object.entries(payload.rateLimitsByLimitId||{})){
        this.buckets[id]=bucket===null?null:replace?bucket:{...this.buckets[id],...bucket};
      }
    }
    if(own(payload,'rateLimits')){
      const bucket=payload.rateLimits;
      if(bucket===null)this.buckets={};
      this.defaultBucket=bucket===null?null:replace?bucket:{...this.defaultBucket,...bucket};
      if(bucket?.limitId)this.buckets[bucket.limitId]=replace?bucket:{...this.buckets[bucket.limitId],...bucket};
    }
    const selected=this.buckets.codex||this.defaultBucket;
    const creditChanged=replace||own(payload?.rateLimitsByLimitId?.codex,'credits')||(own(payload?.rateLimits,'credits')&&(!payload.rateLimits?.limitId||payload.rateLimits.limitId==='codex'));
    if(creditChanged){
      this.creditUpdatedAt=this.clock();
      try{this.creditComparison=this.observations?.observe(this.accountId,selected?.credits,this.creditUpdatedAt)||null;}
      catch{this.creditComparison={observation:null,comparisonState:'storage-unavailable'};}
    }
    this.updatedAt=this.clock();this.failed=false;this.loading=false;
    this.onChange(this.snapshot());
  }
  clear(){
    try{this.observations?.boundary(this.accountId,this.clock());}catch{}
    this.creditUpdatedAt=null;this.creditComparison=null;
    this.generation++;this.buckets={};this.defaultBucket=null;this.updatedAt=null;
    this.lastAttempt=null;this.inflight=null;this.pendingUpdates=null;this.failed=false;this.loading=true;
    this.accountId=null;
    this.onChange(this.snapshot());
  }
  disconnected(){this.failed=true;this.loading=false;this.onChange(this.snapshot());}
  refresh({force=false}={}){
    if(this.inflight)return this.inflight;
    if(!this.codex.ready)return Promise.resolve(this.snapshot());
    if(!force&&this.lastAttempt!==null&&this.clock()-this.lastAttempt<this.ttl)return Promise.resolve(this.snapshot());
    this.lastAttempt=this.clock();const generation=this.generation;const updates=[];this.pendingUpdates=updates;
    const request=this.codex.call('account/rateLimits/read').then(payload=>{
      // Preserve partial live updates received while the read was in flight.
      if(generation===this.generation){this.update(payload,{replace:true});for(const event of updates)this.update(event);}
    }).catch(()=>{
      if(generation===this.generation){this.failed=true;this.loading=false;this.onChange(this.snapshot());}
    }).finally(()=>{if(this.inflight===request){this.inflight=null;this.pendingUpdates=null;}});
    this.inflight=request;
    return request.then(()=>this.snapshot());
  }
  liveUpdate(payload){this.pendingUpdates?.push(payload);this.update(payload);}
}
