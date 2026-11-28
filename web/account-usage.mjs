import {usageSummary} from './presentation.mjs';
const BALANCE=/^(?:-?(?:0|[1-9][0-9]{0,23})(?:\.[0-9]{1,12})?)$/;
const CURRENCY=/^[A-Z]{3,8}$/;
const PROVIDER_ID=/^[a-zA-Z0-9_-]{1,80}$/;
function providerView(record,now,source){
 if(!record?.value)return null;
 const raw=record.value,providerId=typeof raw.providerId==='string'&&PROVIDER_ID.test(raw.providerId)?raw.providerId:null;
 if(!providerId)return null;
 const name=providerId==='deepseek'?'DeepSeek':providerId;
 const currency=typeof raw.currency==='string'&&CURRENCY.test(raw.currency)?raw.currency:null;
 const totalBalance=typeof raw.totalBalance==='string'&&BALANCE.test(raw.totalBalance)?raw.totalBalance:null;
 const amount=totalBalance===null?null:Number(totalBalance);
 let formatted=null;
 if(currency&&amount!==null&&Number.isFinite(amount)){try{formatted=new Intl.NumberFormat(undefined,{style:'currency',currency,minimumFractionDigits:2,maximumFractionDigits:2}).format(amount);}catch{}}
 const observedAt=Number.isSafeInteger(raw.updatedAt)&&raw.updatedAt>0?raw.updatedAt:0;
 const stale=record.available===false||raw.available!==true||raw.stale===true||!observedAt||now-observedAt>180000;
 const label=formatted||`${name} balance unavailable`;
 return {providerId,name,label,totalBalance,currency,updatedAt:observedAt,available:raw.available===true,stale,state:formatted?(stale?'stale':'available'):'unavailable',
  provenance:`${name} API balance via ${source}; not a session charge or ChatGPT quota`,
  freshness:!observedAt?'Balance observation unavailable':stale?'Last known balance observation':'Current balance observation',
  detail:`Remaining ${name} API balance. Session token counts are shown under Context & files.`};
}
/** Account observations belong to an authenticated physical source, never a
 * room, agent or rotating access token. No quota transport or polling lives here. */
export class AccountUsageCache{
 constructor({clock=()=>Date.now()}={}){this.clock=clock;this.owner=null;this.observations=new Map();this.providerObservations=new Map();}
 profile(owner){if(this.owner!==owner){this.observations.clear();this.providerObservations.clear();this.owner=owner;}}
 status(environment,value,name){
  if(!environment||typeof value?.deviceId!=='string'||!value.deviceId||value.deviceId==='owner')throw Error('Account observation source is unconfirmed.');
  this.observations.set(environment,{deviceId:value.deviceId,usage:value.usage?structuredClone(value.usage):null,name:name||value.host||environment,available:true,checkedAt:this.clock()});
  this.provider(environment,value.providerUsage);
 }
 provider(environment,value,{available=true}={}){
  if(!environment)return false;
  if(value==null){const current=this.providerObservations.get(environment);if(current)current.available=available;return !!current;}
  if(typeof value!=='object'||Array.isArray(value))return false;
  this.providerObservations.set(environment,{value:structuredClone(value),available,checkedAt:this.clock()});return true;
 }
 limits(environment,usage){const current=this.observations.get(environment);if(!current)return false;current.usage=structuredClone(usage);current.available=true;return true;}
 unavailable(environment){const current=this.observations.get(environment);if(current)current.available=false;const provider=this.providerObservations.get(environment);if(provider)provider.available=false;}
 needsStatus(environment){const value=this.observations.get(environment);return !value||!value.available||this.clock()-value.checkedAt>30000;}
 view(environment,name){
  const record=this.observations.get(environment),raw=record?.usage,now=this.clock();
  const summary=usageSummary(raw?{...raw,stale:raw.stale===true||!record.available||raw.updatedAt>now+30000}:null,now);
  const balance=raw?.credits?.balance,knownCredit=typeof balance==='number'&&Number.isFinite(balance)&&balance>=0;
  const creditStale=raw?.credits?.stale===true||!raw?.credits?.updatedAt||now-raw.credits.updatedAt>120000||record?.available===false;
  const creditLabel=knownCredit?new Intl.NumberFormat(undefined,{notation:'compact',maximumFractionDigits:1}).format(balance)+' cr':null;
  const label=summary.compact+(summary.remaining===0&&creditLabel?' · '+creditLabel:'');
  const known=summary.remaining!==null,stale=summary.stale||summary.remaining===0&&knownCredit&&creditStale;
  const reset=raw?.resetCredits,count=reset?.availableCount;
  const resetKnown=Number.isSafeInteger(count)&&count>=0;
  const resetStale=reset?.stale===true||!reset?.updatedAt||reset.updatedAt>now+30000||now-reset.updatedAt>120000||record?.available===false;
  const resetCredits=resetKnown?`${count} banked ${count===1?'reset':'resets'} available${resetStale?' · Last known observation':''}`:'Banked resets unavailable';
  const source=name||record?.name||environment||'Original source unavailable';
  const provider=providerView(this.providerObservations.get(environment),now,source);
  return {...summary,label,source,provider,resetCredits,environment:environment||null,state:!known?'unavailable':stale?'stale':'fresh',creditStale,
   provenance:'Account via '+source+'; not a room-wide quota',freshness:!known?'Usage unavailable':stale?'Last known observation':'Current account observation'};
 }
}
