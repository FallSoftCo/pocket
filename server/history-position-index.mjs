// Ordering evidence only: never retains message text or initiates provider reads.
export class HistoryPositionIndex {
  constructor({now=Date.now,ttl=900000,maxTurns=32,maxItems=20000,maxBytes=4194304}={}) {Object.assign(this,{now,ttl,maxTurns,maxItems,maxBytes});this.turns=new Map();}
  clear(){this.turns.clear();}
  has(thread,turn){this.prune();return this.turns.has(JSON.stringify([thread,turn]));}
  prune(){for(const [key,value] of this.turns)if(this.now()-value.at>=this.ttl)this.turns.delete(key);}
  observe(thread,turn,items,{complete=false}={}){
    this.prune();const key=JSON.stringify([thread,turn]);this.turns.delete(key);
    if(items.length>this.maxItems)return;
    const rows=items.map(item=>{const row=item._pocketRow||item;return {id:item.id,client:row.kind==='user'&&typeof row.clientId==='string'?row.clientId:null};});
    const bytes=Buffer.byteLength(JSON.stringify(rows));
    if(rows.length>this.maxItems||bytes>this.maxBytes)return;
    this.turns.set(key,{at:this.now(),rows,bytes,complete});
    const totals=()=>[...this.turns.values()].reduce((a,v)=>[a[0]+v.rows.length,a[1]+v.bytes],[0,0]);
    while(this.turns.size>this.maxTurns||totals()[0]>this.maxItems||totals()[1]>this.maxBytes)this.turns.delete(this.turns.keys().next().value);
  }
  resolve(thread,turn,{itemId,clientId,knownItemIds}){
    this.prune();const entry=this.turns.get(JSON.stringify([thread,turn]));if(!entry)return null;
    const hits=entry.rows.map((row,index)=>({row,index})).filter(({row})=>clientId?row.client===clientId:row.id===itemId);
    if(hits.length!==1)return null;
    const known=new Set(knownItemIds),target=hits[0];
    const successor=entry.rows.slice(target.index+1).find(row=>known.has(row.id));
    // A partial page cannot prove that no later visible successor exists.
    // Let the caller consult the provider rather than treating absence as order.
    if(!successor&&!entry.complete)return null;
    return {available:true,insertBefore:successor?`${turn}/${successor.id}`:null,cursor:null};
  }
}
