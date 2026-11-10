/** Local presentation only: never inserts transcript rows or authorizes delivery. */
export class LocalSendPresentation {
 constructor({read,write}){this.read=read;this.write=write;}
 key(device){return 'localSendPresentation.'+device;}
 list(device,scope){return (this.read(this.key(device),[])||[]).filter(r=>r.deviceId===device&&r.scope===scope);}
 remember(intent,state=intent.state){
  if(!intent?.deviceId||!intent.id||!intent.scope||intent.room||intent.scope==='coordinator'||typeof intent.text!=='string')return;
  const key=this.key(intent.deviceId),rows=this.read(key,[])||[],old=rows.find(r=>r.id===intent.id&&r.scope===intent.scope);
  const row={deviceId:intent.deviceId,scope:intent.scope,id:intent.id,text:old?.state==='accepted'?old.text:intent.text,state:old?.state==='accepted'?'accepted':state};
  this.write(key,old?rows.map(r=>r.id===row.id&&r.scope===row.scope?row:r):[...rows,row]);
 }
 outcome(device,scope,id,state){
  const row=this.list(device,scope).find(r=>r.id===id);if(!row)return;
  if(state==='cancelled')return this.remove(device,scope,new Set([id]));
  if(['accepted','queued','held','sending','failed','unknown'].includes(state))this.remember(row,state);
 }
 remove(device,scope,ids){
  const key=this.key(device),rows=this.read(key,[])||[],next=rows.filter(r=>r.scope!==scope||!ids.has(r.id));
  if(next.length!==rows.length)this.write(key,next);
 }
 reconcile(device,scope,rows){
  const ids=new Set((rows||[]).filter(r=>r.kind==='user'&&typeof r.clientId==='string'&&r.clientId).map(r=>r.clientId));
  this.remove(device,scope,ids);return ids;
 }
}
