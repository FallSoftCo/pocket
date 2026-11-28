const collectionKey='pendingSends';
const legacyKey='pending';

const isPending=value=>!!value&&typeof value==='object'&&typeof value.id==='string'&&value.id&&typeof value.scope==='string'&&value.scope&&typeof value.deviceId==='string'&&value.deviceId&&typeof value.text==='string';
const newestFirst=(a,b)=>(Number(b.createdAt)||0)-(Number(a.createdAt)||0);

/** Keeps every unresolved send while retaining the old single-receipt key for
 * recovery by older page loads. The collection, not the legacy key, is the
 * source of truth after construction. */
export class PendingSends{
 constructor({read,write}){this.read=read;this.write=write;this.items=[];this.reload();}
 reload(){
  const stored=this.read(collectionKey,[]),rows=Array.isArray(stored)?stored:Array.isArray(stored?.items)?stored.items:[],legacy=this.read(legacyKey,null),merged=new Map();
  for(const row of [...rows,legacy])if(isPending(row))merged.set(row.id,row);
  this.items=[...merged.values()].sort(newestFirst);this.persist();return this.all();
 }
 persist(){this.write(collectionKey,this.items);this.write(legacyKey,this.items[0]??null);}
 all(){return [...this.items];}
 forDevice(deviceId){return this.items.filter(row=>row.deviceId===deviceId);}
 forScope(scope,deviceId=null){return this.items.filter(row=>row.scope===scope&&(!deviceId||row.deviceId===deviceId));}
 get(id,scope=null){return this.items.find(row=>row.id===id&&(!scope||row.scope===scope))??null;}
 save(intent){
  if(!isPending(intent))throw Error('A saved send requires an id, scope, device and message text.');
  const index=this.items.findIndex(row=>row.id===intent.id);this.items=index>=0?this.items.map((row,at)=>at===index?{...row,...intent}:row):[...this.items,intent];
  this.items.sort(newestFirst);this.persist();return this.get(intent.id,intent.scope);
 }
 remove(intent,scope=null){
  const id=typeof intent==='string'?intent:intent?.id,targetScope=typeof intent==='string'?scope:intent?.scope??scope;
  if(!id)return false;
  const next=this.items.filter(row=>row.id!==id||targetScope&&row.scope!==targetScope);if(next.length===this.items.length)return false;
  this.items=next;this.persist();return true;
 }
}
