const compare=(a,b)=>a[0]-b[0]||a[1]-b[1]||(a[2]<b[2]?-1:a[2]>b[2]?1:0);
const key=e=>JSON.stringify(e.key);
const invalid=()=>Error('Canonical history response is unavailable.');
export function validateNativePage(data,id){
 const page=data?.timeline;
 if(data?.thread?.id!==id||page?.native!==1||!Array.isArray(page.entries)||page.entries.length>100||!(page.before===null||typeof page.before==='string'&&page.before.length>0))throw invalid();
 let previous=null;const ids=new Set();
 for(const e of page.entries){
  if(!e||typeof e.id!=='string'||ids.has(e.id)||!Array.isArray(e.key)||e.key.length!==3||!Number.isSafeInteger(e.key[0])||e.key[0]<0||![0,1,2,3].includes(e.key[1])||typeof e.key[2]!=='string'||!e.key[2]||e.row!==null&&(!e.row||typeof e.row.id!=='string'||!e.row.id))throw invalid();
  if(previous&&compare(previous,e.key)>=0)throw invalid();previous=e.key;ids.add(e.id);
 }
 return page;
}
/** Source-proven page components. Disjoint cached islands never become adjacent
 * messages; a source cursor or overlapping canonical entry must bridge them. */
export class NativeHistory {
 constructor(){this.components=[];this.selected=null;this.latest=null;this.epoch=null;}
 accept(data,id,{cursor=null,anchor=null}={}){
  const page=validateNativePage(data,id),epoch=data.historyHead?.epoch;
  if(typeof epoch!=='string'||!epoch)throw invalid();
  // Prior-runtime coverage cannot prove positions after source replacement.
  // These components are already unselectable; release their retained bodies.
  if(cursor===null&&this.epoch!==null&&this.epoch!==epoch)this.components=[];
  if(cursor!==null&&page.before===cursor)throw Error('Canonical history cursor did not advance.');
  const incoming=new Map(page.entries.map(e=>[key(e),structuredClone(e)]));
  const joined=this.components.filter(c=>c.epoch===epoch&&([...incoming.keys()].some(k=>c.entries.has(k))||cursor!==null&&c.before===cursor));
  let entries=new Map(),before=page.before,oldest=page.entries[0]?.key;
  for(const c of joined){
   for(const [k,e]of c.entries)entries.set(k,e);
   if(c.first&&(!oldest||compare(c.first,oldest)<0)){oldest=c.first;before=c.before;}
  }
  for(const [k,e]of incoming){const old=entries.get(k);if(old?.row&&e.row&&(old.row.version||0)>(e.row.version||0))e.row=old.row;entries.set(k,e);}
  const ordered=[...entries.values()].sort((a,b)=>compare(a.key,b.key));
  const component={epoch,entries,ordered,before,first:ordered[0]?.key};
  this.components=this.components.filter(c=>!joined.includes(c));this.components.push(component);this.epoch=epoch;
  if(cursor===null||joined.includes(this.latest))this.latest=component;
  // Retain disconnected local components for later source-proven bridging.
  // Never concatenate them merely because their request completed last.
  this.selected=anchor?this.components.find(c=>c.epoch===epoch&&c.ordered.some(e=>e.row?.id===anchor))||component:component;
  return this.snapshot();
 }
 followLatest(){if(this.latest?.epoch===this.epoch)this.selected=this.latest;return this.snapshot();}
 snapshot(){const c=this.selected;return {native:1,rows:c?.ordered.flatMap(e=>e.row?[e.row]:[])||[],hasEarlier:c?.before!=null,before:c?.before??null};}
 live(row){
  const c=this.selected,entry=c?.ordered.find(e=>e.row?.id===row?.id);if(!entry)return false;
  if((row.version||0)>=(entry.row.version||0))entry.row={...row,_nativePosition:entry.key[0],_nativeRank:entry.key[1],_nativeId:entry.key[2],...(row.kind==='user'?{_inputOrder:'canonical'}:{})};
  return true;
 }
}
const unsupported=e=>e?.status===409&&e?.code==='NATIVE_TIMELINE_UNSUPPORTED'||e?.status===400&&e?.message==='thread/timeline/list is not supported yet'||e?.status===404;
/** Protocol belongs to a session key, including its encoded environment identity.
 * Neither a failed network call nor another session can switch its protocol. */
export class HistoryReader {
 constructor(api){this.api=api;this.protocols=new Map();}
 async read(id,{before=null,background=false}={}){
  const options={priority:background?'low':'high',timeout:background?5000:20000};
  const base='/api/threads/'+encodeURIComponent(id),query=before?'?before='+encodeURIComponent(before):'';
  if(this.protocols.get(id)!=='turns'){
   try{const data=await this.api(base+'/native-timeline'+query,undefined,options);validateNativePage(data,id);this.protocols.set(id,'native');return data;}
   catch(error){if(!unsupported(error)||before!==null)throw error;}
  }
  const data=await this.api(base+'?view=timeline'+(before?'&before='+encodeURIComponent(before):''),undefined,options);
  if(data?.thread?.id!==id||!Array.isArray(data.timeline?.rows))throw Error('Conversation response does not match the selected session.');
  this.protocols.set(id,'turns');return data;
 }
 clear(){this.protocols.clear();}
}
