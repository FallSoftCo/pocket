import {createHash} from 'node:crypto';
const fail=(message,status=400)=>Object.assign(Error(message),{status});
const uuid=value=>typeof value==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);

/** Native audio transcribes only; its captured human sends through Hollywood. */
export class HollywoodVoice {
 constructor({db,gateway}){
  Object.assign(this,{db,gateway});this.refreshing=new Map();this.dispatching=new Map();db.exec(`
  CREATE TABLE IF NOT EXISTS hollywood_voice_turns(device TEXT,id TEXT,digest TEXT,destination TEXT,text TEXT,
   operation TEXT,state TEXT,error TEXT,event TEXT,cursor INTEGER DEFAULT 0,outputs TEXT DEFAULT '[]',response TEXT,
   created_at INTEGER,PRIMARY KEY(device,id));`);
  db.prepare("UPDATE hollywood_voice_turns SET state='unknown',error='Gateway restarted; inspect the original receipt without repeating.' WHERE state='thinking'").run();
 }
 async destination(device,value){
  if(!value||Object.keys(value).some(k=>!['backend','bindingId','authorityId','roomId','recipientId','audience','sourceEventId'].includes(k))||value.backend!=='lax'||
    ![value.authorityId,value.roomId,value.recipientId].every(uuid)||!['room','direct'].includes(value.audience)||value.sourceEventId!=null&&!uuid(value.sourceEventId))throw fail('Choose an exact Hollywood room and recipient.');
  const client=this.gateway.client(device);await client.verify();
  if(value.bindingId!==client.config.bindingId||value.authorityId!==client.config.authorityId)throw fail('Recording belongs to another environment or human identity.',409);
  const detail=await client.call('rooms/'+value.roomId);
  if(!detail.permissions.can_request||!detail.actors.some(a=>a.id===value.recipientId&&a.kind==='agent'))throw fail('This human cannot request work from that room and agent.',403);
  return {...value,binding:client.envelope(),title:detail.room.name};
 }
 row(device,id){return this.db.prepare('SELECT * FROM hollywood_voice_turns WHERE device=? AND id=?').get(device,id);}
 get(device,id){const row=this.row(device,id);if(row)this.client(device,JSON.parse(row.destination));return row?{id,state:row.state,transcript:row.text,response:row.response,error:row.error,actions:[],destination:JSON.parse(row.destination)}:null;}
 client(device,destination){
  const client=this.gateway.client(device);
  if(!destination?.binding||['bindingId','installationId','authorityId','principalId'].some(k=>destination.binding[k]!==client.envelope()[k]))throw fail('The recording retains its original environment and human identity.',409);
  return client;
 }
 async submitText(device,id,text,destination){
  if(typeof id!=='string'||!/^[a-zA-Z0-9_-]{8,100}$/.test(id)||typeof text!=='string'||!text.trim()||Buffer.byteLength(text)>6000)throw fail('Invalid recorded room request.');
  const client=this.client(device,destination);
  const detail=await client.call('rooms/'+destination.roomId);
  if(!detail.permissions.can_request||!detail.actors.some(a=>a.id===destination.recipientId&&a.kind==='agent'))throw fail('This human can no longer request work in the captured room.',403);
  const digest=createHash('sha256').update(JSON.stringify([text,destination])).digest('hex');
  const old=this.row(device,id);
  if(old){if(old.digest!==digest)throw fail('This recording ID belongs to another captured request.',409);return this.get(device,id);}
  const operation='voice-hollywood:'+createHash('sha256').update(JSON.stringify([destination.binding.bindingId,device,id])).digest('hex');
  this.db.prepare("INSERT INTO hollywood_voice_turns(device,id,digest,destination,text,operation,state,created_at) VALUES(?,?,?,?,?,?,'thinking',?)")
    .run(device,id,digest,JSON.stringify(destination),text,operation,Date.now());
  const key=JSON.stringify([device,id]),work=this.dispatch(device,id);this.dispatching.set(key,work);
  void work.finally(()=>this.dispatching.delete(key));return this.get(device,id);
 }
 body(row,d){return {operationId:row.operation,kind:'request',body:row.text,audience:d.audience,recipientId:d.recipientId,...(d.sourceEventId?{sourceEventId:d.sourceEventId}:{})};}
 accepted(row,d,value){
  const e=value.event,deliveries=value.deliveries;
  if(!value.accepted||!uuid(e?.id)||e.authority!==d.binding.authorityId||e.author!==d.binding.principalId||e.room!==d.roomId||e.body!==row.text||
    e.kind!=='request'||e.audience!==d.audience||e.recipient!==d.recipientId||(e.source??null)!==(d.sourceEventId??null)||
    !Array.isArray(deliveries)||deliveries.length!==1||deliveries[0].agent!==d.recipientId)throw fail('Original recording receipt is unconfirmed.',502);
  return e;
 }
 async dispatch(device,id){
  const row=this.row(device,id),d=JSON.parse(row.destination);
  try{
   const value=await this.client(device,d).call('rooms/'+d.roomId+'/messages',{method:'POST',body:this.body(row,d)});
   const event=this.accepted(row,d,value);
   this.db.prepare('UPDATE hollywood_voice_turns SET event=?,cursor=? WHERE device=? AND id=?').run(event.id,event.seq,device,id);
  }catch(error){this.db.prepare('UPDATE hollywood_voice_turns SET state=?,error=? WHERE device=? AND id=?')
    .run(error.code==='HOLLYWOOD_REQUEST_REJECTED'?'failed':'unknown',error.message,device,id);}
 }
 async refresh(device,id){
  const key=JSON.stringify([device,id]);if(this.refreshing.has(key))return this.refreshing.get(key);
  const work=this.inspect(device,id);this.refreshing.set(key,work);try{return await work;}finally{this.refreshing.delete(key);}
 }
 async inspect(device,id){
  const sending=this.dispatching.get(JSON.stringify([device,id]));if(sending)await sending;
  const row=this.row(device,id);if(!row)return null;const d=JSON.parse(row.destination),client=this.client(device,d);
  // Current permission checks also apply to retained terminal results.
  await client.call('rooms/'+d.roomId);
  let receipt;
  try{receipt=await client.call('commands/'+row.operation);}
  catch(error){
   if(error.status!==404)throw error;
   this.db.prepare("UPDATE hollywood_voice_turns SET state='unknown',error='The saved operation has no confirmed receipt. It has not been resubmitted.' WHERE device=? AND id=?").run(device,id);
   return this.get(device,id);
  }
  const event=this.accepted(row,d,receipt);
  let page=await client.call('rooms/'+d.roomId+'/history',{query:{after:row.cursor||event.seq,limit:100}});
  const outputs=JSON.parse(row.outputs);let cursor=page.nextCursor;
  for(let count=0;count<5;count++){
   for(const item of page.events){
    if(item.source!==event.id||item.author!==d.recipientId||item.room!==d.roomId)continue;
    if(['message','result'].includes(item.kind)&&!outputs.some(o=>o.id===item.id))outputs.push({id:item.id,text:item.body,kind:item.kind});
   }
   cursor=page.nextCursor;if(page.events.length<100||count===4)break;
   page=await client.call('rooms/'+d.roomId+'/history',{query:{after:cursor,limit:100}});
  }
  const delivery=receipt.deliveries[0];
  // Do not settle before the result event was actually read through this cursor.
  const resultSeen=!delivery.result_event||outputs.some(o=>o.id===delivery.result_event);
  const state=delivery.state==='completed'&&resultSeen?'completed':['failed','cancelled'].includes(delivery.state)&&resultSeen?'failed':delivery.state==='unknown'?'unknown':'thinking';
  const messages=outputs.filter(o=>o.kind==='message');
  const response=(messages.length?messages:outputs).map(o=>o.text).join('\n\n');
  const bounded=response.length>15000?response.slice(0,14900)+'\nFurther details remain in the room.':response;
  this.db.prepare('UPDATE hollywood_voice_turns SET event=?,cursor=?,outputs=?,response=?,state=?,error=? WHERE device=? AND id=?')
    .run(event.id,cursor,JSON.stringify(outputs),bounded||null,state,state==='unknown'?'Original native delivery is unknown; do not repeat.':state==='failed'?'Native work '+delivery.state+'.':null,device,id);
  return this.get(device,id);
 }
 async history(device,before=Number.MAX_SAFE_INTEGER){
  if(!Number.isSafeInteger(before)||before<=0)throw fail('Invalid voice history cursor.');
  const rows=this.db.prepare('SELECT * FROM hollywood_voice_turns WHERE device=? AND created_at<? ORDER BY created_at DESC LIMIT 31').all(device,before);
  const visible=[];
  for(const row of rows.slice(0,30)){
   const d=JSON.parse(row.destination);
   try{await this.client(device,d).call('rooms/'+d.roomId);visible.push({...this.get(device,row.id),createdAt:row.created_at});}
   catch(error){if(error.status!==403&&error.status!==409)throw error;}
  }
  return {turns:visible.reverse(),hasMore:rows.length>30,hasEarlier:rows.length>30,before:rows.length?rows[Math.min(29,rows.length-1)].created_at:null,nextCursor:rows.length?rows[Math.min(29,rows.length-1)].created_at:null};
 }
 mark(){} // Playback presentation is not completion or a room read acknowledgement.
}
