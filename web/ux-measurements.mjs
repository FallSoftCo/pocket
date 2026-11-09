const kinds=new Set(['open','send','history_request']);
const metricKinds={open_first_content_layout:'open',open_cached_first_content_layout:'open',open_fresh_content_layout:'open',send_feedback_committed:'send',send_admitted_callback:'send',history_response:'history_request'};
const ends=new Set(['superseded','backgrounded','failed','unknown','cancelled','complete']);
/** Enum-only recent local diagnostics. No IDs/content/URLs/input capture and no timers. */
export class UxMeasurements {
 constructor({now=()=>performance.now()}={}){this.now=now;this.visible=false;this.serial=0;this.flights=new Map();this.samples=new Map();this.counts={};}
 count(key){this.counts[key]=Math.min(1_000_000,(this.counts[key]||0)+1);}
 foreground(value){this.visible=!!value;if(!this.visible){for(const f of this.flights.values())this.count(f.kind+'_backgrounded');this.flights.clear();}}
 begin(kind){if(!kinds.has(kind))throw Error('Unknown measurement kind');if(!this.visible)return 0;if(this.flights.size>=32){this.count('capacity_dropped');this.flights.delete(this.flights.keys().next().value);}const token=++this.serial;this.flights.set(token,{kind,at:this.now(),seen:new Set()});this.count(kind+'_started');return token;}
 active(token){return this.flights.has(token);}
 mark(token,metric){
  if(!Object.hasOwn(metricKinds,metric))throw Error('Unknown measurement metric');
  const f=this.flights.get(token);if(!this.visible||!f||f.kind!==metricKinds[metric]||f.seen.has(metric))return;
  f.seen.add(metric);const elapsed=this.now()-f.at;if(!Number.isSafeInteger(Math.round(elapsed))||elapsed<0){this.count('invalid_clock');return;}
  const samples=this.samples.get(metric)||[];if(samples.length===128)samples.shift();samples.push(Math.round(elapsed));this.samples.set(metric,samples);
  if(f.kind==='send'&&f.seen.has('send_feedback_committed')&&f.seen.has('send_admitted_callback'))this.end(token,'complete');
 }
 layout(token,fresh){const f=this.flights.get(token);if(!f)return;if(!f.seen.has('open_first_content_layout')){this.mark(token,'open_first_content_layout');if(!fresh)this.mark(token,'open_cached_first_content_layout');}if(fresh){this.mark(token,'open_fresh_content_layout');this.end(token,'complete');}}
 end(token,reason){if(!ends.has(reason))throw Error('Unknown measurement end');const f=this.flights.get(token);if(f){this.flights.delete(token);this.count(f.kind+'_'+reason);}}
 snapshot(){return {schema:1,retention:'Last 128 samples per enum metric in this page process; at most 32 active journeys; foreground only',metrics:Object.fromEntries([...this.samples].map(([key,values])=>{const sorted=[...values].sort((a,b)=>a-b),n=sorted.length;return [key,{count:n,minMs:sorted[0],maxMs:sorted[n-1],p50Ms:sorted[Math.ceil(n*.5)-1],p95Ms:sorted[Math.ceil(n*.95)-1]}];})),counts:{...this.counts},pending:Object.fromEntries([...kinds].map(kind=>[kind,[...this.flights.values()].filter(f=>f.kind===kind).length])),evidence:'History response is transport completion, not presentation. Content layout is viewport intersection observed on an animation frame, not proof pixels were seen. Send feedback is committed DOM state; admission is a receipt, not task completion. No automatic upload.'};}
}
