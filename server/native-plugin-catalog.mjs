import {createHmac,randomUUID} from 'node:crypto';
const fail=(message,status=409)=>Object.assign(Error(message),{status,pluginCatalog:true});
export const PLUGIN_PAGE_SIZE=30;
const MAX_SNAPSHOTS=4,MAX_ROWS=20000,MAX_BYTES=16*1024*1024,TTL=120000;
// Local presentation cursors, never native cursors or mutation-policy evidence.
// Retain compact projected rows only, with demand-driven expiry and eviction.
export class PluginCatalogPages {
 constructor({secret,capture,check,load}){Object.assign(this,{secret,capture,check,load});this.snapshots=new Map();this.inflight=new Map();}
 invalidate(){this.snapshots.clear();}
 seal(value){const body=Buffer.from(JSON.stringify(value)).toString('base64url');return body+'.'+createHmac('sha256',this.secret).update(body).digest('hex');}
 decode(cursor){try{const [body,mac,...extra]=cursor.split('.');if(extra.length||this.seal(JSON.parse(Buffer.from(body,'base64url').toString()))!==cursor||!/^[a-f0-9]{64}$/.test(mac))throw Error();return JSON.parse(Buffer.from(body,'base64url').toString());}catch{throw fail('Invalid plugin page. Reload the catalog.');}}
 valid(snapshot){try{this.check(snapshot.source);return snapshot.expires>Date.now();}catch{return false;}}
 prune(){for(const [id,s]of this.snapshots)if(!this.valid(s))this.snapshots.delete(id);}
 async page({threadId,cwd,view='installed',query='',cursor,refresh}){
  if(!['installed','browse'].includes(view)||typeof query!=='string'||query.length>120||cursor!==undefined&&(typeof cursor!=='string'||cursor.length>2048)||refresh!==undefined&&refresh!=='1'||cursor&&refresh)throw fail('Invalid plugin catalog request.',400);
  query=query.trim().toLocaleLowerCase();this.prune();
  const context={threadId:threadId||null,cwd:cwd||null,view};let snapshot,offset=0;
  if(cursor){
   const token=this.decode(cursor);snapshot=this.snapshots.get(token.snapshot);
   if(!snapshot||JSON.stringify(snapshot.context)!==JSON.stringify(context)||token.query!==query||token.view!==view||!Number.isSafeInteger(token.offset)||token.offset<0||token.offset%PLUGIN_PAGE_SIZE)throw fail('Plugin page expired or belongs to another source or search. Reload the catalog.');
   this.check(snapshot.source);offset=token.offset;
  }else{
   if(refresh)for(const [id,s]of this.snapshots)if(JSON.stringify(s.context)===JSON.stringify(context))this.snapshots.delete(id);
   snapshot=[...this.snapshots.values()].find(s=>JSON.stringify(s.context)===JSON.stringify(context));
   if(!snapshot){
    const source=this.capture(),key=JSON.stringify({...context,epoch:source.epoch,generation:source.generation,binding:source.binding});
    let pending=this.inflight.get(key);
    if(!pending){
     if(this.inflight.size>=MAX_SNAPSHOTS)throw fail('Plugin catalog inspection is busy. Try this view again.',503);
     pending=(async()=>{
      const result=await this.load(view,cwd,()=>this.check(source));this.check(source);
      const rows=result.entries.map(e=>e.item);if(rows.length>MAX_ROWS)throw fail('Native plugin catalog exceeds this client’s bounded snapshot capacity.',503);
      const bytes=Buffer.byteLength(JSON.stringify(rows));if(bytes>MAX_BYTES)throw fail('Native plugin catalog exceeds this client’s bounded snapshot capacity.',503);
      this.prune();while(this.snapshots.size>=MAX_SNAPSHOTS||[...this.snapshots.values()].reduce((n,s)=>n+s.bytes,0)+bytes>MAX_BYTES)this.snapshots.delete(this.snapshots.keys().next().value);
      const value={id:randomUUID(),context,source,rows,bytes,errorCount:result.errorCount,expires:Date.now()+TTL};this.snapshots.set(value.id,value);return value;
     })();this.inflight.set(key,pending);
    }
    try{snapshot=await pending;}finally{if(this.inflight.get(key)===pending)this.inflight.delete(key);}
   }
  }
  this.check(snapshot.source);
  const matches=query?snapshot.rows.filter(p=>[p.name,p.id,p.marketplace].some(v=>typeof v==='string'&&v.toLocaleLowerCase().includes(query))):snapshot.rows;
  if(offset>0&&offset>=matches.length)throw fail('Plugin page is out of range. Reload the catalog.');
  const token=start=>this.seal({snapshot:snapshot.id,offset:start,view,query});
  return {items:matches.slice(offset,offset+PLUGIN_PAGE_SIZE),pluginView:view,query,total:matches.length,offset,pageSize:PLUGIN_PAGE_SIZE,nextCursor:offset+PLUGIN_PAGE_SIZE<matches.length?token(offset+PLUGIN_PAGE_SIZE):null,previousCursor:offset?token(Math.max(0,offset-PLUGIN_PAGE_SIZE)):null,errorCount:snapshot.errorCount};
 }
}
