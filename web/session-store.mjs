import {canonicalPage} from './presentation.mjs';
const previewFields=new Set(['preview','previewKind','previewRole','activityAt','recencyAt']);
/** One entity per session: catalog, live evidence and paginated transcript share ownership. */
export class SessionStore {
 constructor(){this.entities=new Map();this.inventory=[];this.sequence=0;this.catalogChanges=[];this.metadataListeners=new Set();this.pendingMetadata=new Set();}
 entity(id){if(!this.entities.has(id))this.entities.set(id,{thread:{id},fields:new Map(),page:null,summarySeen:false});return this.entities.get(id);}
 subscribeMetadata(listener){this.metadataListeners.add(listener);return ()=>this.metadataListeners.delete(listener);}
 notifyMetadata(id){
  this.pendingMetadata.add(id);if(this.notificationScheduled)return;
  this.notificationScheduled=true;queueMicrotask(()=>{this.notificationScheduled=false;const ids=[...this.pendingMetadata];this.pendingMetadata.clear();for(const id of ids)for(const listener of this.metadataListeners)listener(id);});
 }
 ticket(){return ++this.sequence;}
 metadata(id,patch,ticket=this.ticket(),{summary=false}={}){
  const entity=this.entity(id);let changed=false;
  for(const [key,value] of Object.entries(patch||{})){
   if(key==='turns'||key.startsWith('_pocket')||['__proto__','constructor','prototype'].includes(key)||(key==='id'&&value!==id))continue;
   if(!summary&&entity.summarySeen&&previewFields.has(key))continue;
   if((entity.fields.get(key)||0)>ticket)continue;
   if(!Object.is(entity.thread[key],value))changed=true;entity.thread[key]=value;entity.fields.set(key,ticket);
  }
  if(summary)entity.summarySeen=true;if(changed)this.notifyMetadata(id);
  return entity.thread;
 }
 catalog(rows,ticket){
  const incoming=new Set(rows.map(row=>row.id)),kept=this.inventory.filter(id=>!incoming.has(id)&&(this.entity(id).membership||0)>ticket);
  this.inventory=[...incoming,...kept];this.catalogChanges=[];
  for(const row of rows){
   const entity=this.entity(row.id),prior=entity.summarySeen?JSON.stringify([entity.thread.preview,entity.thread.previewRole,entity.thread.previewKind,entity.thread.interactionId,entity.thread.activityAt,entity.thread.updatedAt]):null;
   const thread=this.metadata(row.id,row,ticket,{summary:true});
   if(prior!==null&&prior!==JSON.stringify([thread.preview,thread.previewRole,thread.previewKind,thread.interactionId,thread.activityAt,thread.updatedAt])&&['assistant','user'].includes(thread.previewRole))this.catalogChanges.push(row.id);
  }
  return this.list();
 }
 list(){return this.inventory.map(id=>this.entity(id).thread);}
 summary(id,patch){
  const prior=this.entity(id),changed=['preview','previewRole','previewKind','interactionId'].some(key=>patch[key]!==undefined&&patch[key]!==prior.thread[key]);
  const ticket=this.ticket();this.metadata(id,patch,ticket,{summary:true});prior.membership=ticket;
  if(!this.inventory.includes(id))this.inventory.push(id);
  return changed;
 }
 has(id){return !!this.entities.get(id)?.page;}
 get(id){return this.entities.get(id)?.page;}
 set(id,page){const entity=this.entity(id);if(page.snapshot?.thread)page.snapshot.thread=entity.thread;entity.page=page;return this;}
 head(id,data,{older=false,ticket}={}){
  if(!Array.isArray(data.timeline?.rows))throw Error('Conversation response did not include its message page.');
  const old=this.get(id)||{},thread=this.metadata(id,data.thread,ticket);
  const snapshot=older?old.snapshot||{...data,thread}:{...data,thread};
  const page={...data.timeline,rows:canonicalPage(old.rows||[],data.timeline.rows,{older}),snapshot,catchup:data.catchup,
   ...(!older&&old.hasEarlier?{hasEarlier:true,before:old.before}:{})};
  this.set(id,page);return page;
 }
 timeline(id,row){
  const page=this.get(id);if(!page||!row?.id)return false;
  const old=page.rows?.find(r=>r.id===row.id);if(!old)return false;
  if((row.version||0)>=(old.version||0))page.rows=page.rows.map(r=>r===old?row:r);
  return true;
 }
 clear(){this.entities.clear();this.inventory=[];this.pendingMetadata.clear();this.sequence++;}
}
