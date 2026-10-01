const WEEK_MINUTES=7*24*60;
const own=(value,key)=>Object.prototype.hasOwnProperty.call(value||{},key);

function weeklyWindow(bucket){
  const window=[bucket?.primary,bucket?.secondary].find(w=>w?.windowDurationMins===WEEK_MINUTES);
  if(!window||typeof window.usedPercent!=='number'||!Number.isFinite(window.usedPercent))return null;
  const usedPercent=Math.min(100,Math.max(0,window.usedPercent));
  return {usedPercent,remainingPercent:100-usedPercent,
    resetsAt:Number.isFinite(window.resetsAt)&&window.resetsAt>0?window.resetsAt:null};
}

// Only quota information leaves this cache; account identifiers, credits and
// authentication details from the upstream response are never forwarded.
export class AccountRateLimits {
  constructor(codex,onChange=()=>{},{clock=Date.now,ttl=60000}={}){
    this.codex=codex;this.onChange=onChange;this.clock=clock;this.ttl=ttl;
    this.buckets={};this.defaultBucket=null;this.updatedAt=null;this.lastAttempt=null;
    this.failed=false;this.loading=true;this.generation=0;this.inflight=null;this.pendingUpdates=null;
  }
  snapshot(){
    const weekly=weeklyWindow(this.buckets.codex||this.defaultBucket);
    const stale=!!weekly&&(this.failed||!this.codex.ready||this.clock()-this.updatedAt>this.ttl*2
      ||(weekly.resetsAt!==null&&this.clock()>=weekly.resetsAt*1000));
    return {weekly,updatedAt:this.updatedAt,stale,state:weekly?'available':this.loading?'loading':'unavailable'};
  }
  update(payload,{replace=false}={}){
    if(replace){this.buckets={};this.defaultBucket=null;}
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
    this.updatedAt=this.clock();this.failed=false;this.loading=false;
    this.onChange(this.snapshot());
  }
  clear(){
    this.generation++;this.buckets={};this.defaultBucket=null;this.updatedAt=null;
    this.lastAttempt=null;this.inflight=null;this.pendingUpdates=null;this.failed=false;this.loading=true;
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
