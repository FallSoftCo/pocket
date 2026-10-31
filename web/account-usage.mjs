import {usageSummary} from './presentation.mjs';
/** Account observations belong to an authenticated physical source, never a
 * room, agent or rotating access token. No quota transport or polling lives here. */
export class AccountUsageCache{
 constructor({clock=()=>Date.now()}={}){this.clock=clock;this.owner=null;this.observations=new Map();}
 profile(owner){if(this.owner!==owner){this.observations.clear();this.owner=owner;}}
 status(environment,value,name){
  if(!environment||typeof value?.deviceId!=='string'||!value.deviceId||value.deviceId==='owner')throw Error('Account observation source is unconfirmed.');
  this.observations.set(environment,{deviceId:value.deviceId,usage:value.usage?structuredClone(value.usage):null,name:name||value.host||environment,available:true,checkedAt:this.clock()});
 }
 limits(environment,usage){const current=this.observations.get(environment);if(!current)return false;current.usage=structuredClone(usage);current.available=true;return true;}
 unavailable(environment){const current=this.observations.get(environment);if(current)current.available=false;}
 needsStatus(environment){const value=this.observations.get(environment);return !value||!value.available||this.clock()-value.checkedAt>30000;}
 view(environment,name){
  const record=this.observations.get(environment),raw=record?.usage,now=this.clock();
  const summary=usageSummary(raw?{...raw,stale:raw.stale===true||!record.available||raw.updatedAt>now+30000}:null,now);
  const balance=raw?.credits?.balance,knownCredit=typeof balance==='number'&&Number.isFinite(balance)&&balance>=0;
  const creditStale=raw?.credits?.stale===true||!raw?.credits?.updatedAt||now-raw.credits.updatedAt>120000||record?.available===false;
  const creditLabel=knownCredit?new Intl.NumberFormat(undefined,{notation:'compact',maximumFractionDigits:1}).format(balance)+' cr':null;
  const label=summary.compact+(summary.remaining===0&&creditLabel?' · '+creditLabel:'');
  const known=summary.remaining!==null,stale=summary.stale||summary.remaining===0&&knownCredit&&creditStale;
  const source=name||record?.name||environment||'Original source unavailable';
  return {...summary,label,source,environment:environment||null,state:!known?'unavailable':stale?'stale':'fresh',creditStale,
   provenance:'Account via '+source+'; not a room-wide quota',freshness:!known?'Usage unavailable':stale?'Last known observation':'Current account observation'};
 }
}
