/** A complete versioned snapshot includes removals; never merge queue items by text. */
export function newerOutbox(current,incoming){
 if(!incoming||incoming.version!==1||!Number.isSafeInteger(incoming.revision)||incoming.revision<0||!Array.isArray(incoming.items))return current;
 if(current&&current.revision>=incoming.revision)return current;
 const ids=new Set();
 for(const row of incoming.items){
  if(!row||typeof row.id!=='string'||!row.id||ids.has(row.id)||typeof row.text!=='string'||!['queued','held','sending','failed','unknown'].includes(row.state))return current;
  ids.add(row.id);
 }
 return incoming;
}
