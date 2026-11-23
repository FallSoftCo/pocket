// Absolute device-local times only. Never synthesize a time from receipt/fetch.
export function messageTime(row,{locale=undefined,timeZone=undefined}={}){
 const valid=value=>Number.isSafeInteger(value)&&value>0&&value<=8640000000000000;
 const value=[row?.startedAtMs,row?.completedAtMs].find(valid);if(value===undefined)return null;
 const date=new Date(value),options={timeZone};
 const day=new Intl.DateTimeFormat(locale,{...options,month:'short',day:'numeric'}).format(date);
 const clock=new Intl.DateTimeFormat(locale,{...options,hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(date);
 const full=new Intl.DateTimeFormat(locale,{...options,year:'numeric',month:'long',day:'numeric',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23',timeZoneName:'short'}).format(date);
 return {label:`${day} · ${clock}`,full,iso:date.toISOString()};
}
