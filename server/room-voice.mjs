import {createHash} from 'node:crypto';
const fail=(message,status=400)=>Object.assign(Error(message),{status});
const uuid=v=>typeof v==='string'&&/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v);
const slug=v=>typeof v==='string'&&/^[a-z0-9_-]{1,64}$/.test(v);

// Recorded human turns use native room commands, not a second routing model.
export class RoomVoice {
 constructor({db,client}){this.db=db;this.client=client;this.refreshing=new Map();db.exec(`
 CREATE TABLE IF NOT EXISTS room_voice_turns(device TEXT,id TEXT,hash TEXT,destination TEXT,text TEXT,
 state TEXT,error TEXT,event INTEGER,cursor INTEGER DEFAULT 0,outputs TEXT DEFAULT '[]',response TEXT,
 created_at INTEGER,PRIMARY KEY(device,id));`);
 db.prepare("UPDATE room_voice_turns SET state='unknown',error='Gateway restarted during room execution. Check the original receipt before repeating.' WHERE state='thinking'").run();}
 async destination(value){
  if(!value||Object.keys(value).some(k=>!['team','roomId','authorityId','actorId','direct'].includes(k))||
   !slug(value.team)||!uuid(value.roomId)||!uuid(value.authorityId)||!uuid(value.actorId)||typeof value.direct!=='boolean')throw fail('Choose an exact room and agent.');
  const room=await this.client.call(`teams/${value.team}/rooms/${value.roomId}`);
  const detail=room.room||room;
  const catalog=await this.client.call(`teams/${value.team}/rooms`,{query:{task:detail.task,limit:1}});
  if(catalog.authorityId!==value.authorityId||catalog.rooms?.[0]?.id!==value.roomId)throw fail('Room authority changed. Choose the original room again.',409);
  const actor=detail.members?.find(m=>m.id===value.actorId);
  if(!actor)throw fail('This agent is not a member of the selected room.',409);
  if(actor.paused)throw fail('This agent is deliberately paused. Resume is a separate action.',409);
  return {...value,task:detail.task,agent:actor.agent,title:detail.title,environment:this.client.config.environmentId};
 }
 row(device,id){return this.db.prepare('SELECT * FROM room_voice_turns WHERE device=? AND id=?').get(device,id);}
 get(device,id){const r=this.row(device,id);return r?{id:r.id,state:r.state,transcript:r.text,response:r.response,error:r.error,actions:[],destination:JSON.parse(r.destination)}:null;}
 submitText(device,id,text,destination){
  if(typeof id!=='string'||!/^[a-zA-Z0-9_-]{8,100}$/.test(id)||typeof text!=='string'||!text.trim()||text.length>3500)throw fail('Invalid recorded room request.');
  const hash=createHash('sha256').update(JSON.stringify([text,destination])).digest('hex');
  const old=this.row(device,id);if(old){if(old.hash!==hash)throw fail('This recording identity already belongs to a different request.',409);return this.get(device,id);}
  if(destination.environment!==this.client.config?.environmentId)throw fail('Room connection changed; the recording remains with its original environment.',409);
  this.db.prepare("INSERT INTO room_voice_turns(device,id,hash,destination,text,state,created_at) VALUES(?,?,?,?,?,'thinking',?)").run(device,id,hash,JSON.stringify(destination),text,Date.now());
  void this.dispatch(device,id);return this.get(device,id);
 }
 command(device,id){return 'voice-room-'+createHash('sha256').update(device+'\0'+id).digest('hex');}
 async dispatch(device,id){
  const r=this.row(device,id),d=JSON.parse(r.destination);
  try{
   const value=await this.client.call(`teams/${d.team}/messages`,{body:{commandId:this.command(device,id),task:d.task,body:r.text,targets:[d.agent],visibility:d.direct?'direct':'room'}});
   const event=value.event?.id??value.id;if(!Number.isSafeInteger(event))throw fail('Room did not return a durable receipt.',503);
   this.db.prepare('UPDATE room_voice_turns SET event=?,cursor=? WHERE device=? AND id=?').run(event,event,device,id);
  }catch(e){this.db.prepare("UPDATE room_voice_turns SET state=?,error=? WHERE device=? AND id=?").run([400,404,422].includes(e.status)?'failed':'unknown',e.message,device,id);}
 }
 async refresh(device,id){
  const key=JSON.stringify([device,id]);if(this.refreshing.has(key))return this.refreshing.get(key);
  const work=this.inspect(device,id);this.refreshing.set(key,work);try{return await work;}finally{this.refreshing.delete(key);}
 }
 async inspect(device,id){
  const r=this.row(device,id);if(!r)return null;
  if(['completed','failed'].includes(r.state))return this.get(device,id);
  const d=JSON.parse(r.destination);if(d.environment!==this.client.config?.environmentId)throw fail('The original room connection is unavailable.',409);
  // Reconcile unknown writes by their receipt; never send the request again.
  let receipt;try{receipt=await this.client.call(`teams/${d.team}/commands/${this.command(device,id)}`);}catch(e){if(e.status===404)return this.get(device,id);throw e;}
  const event=receipt.event;
  if(event?.task!==d.task||event.author!=='you'||event.body!==r.text||event.visibility!==(d.direct?'direct:'+d.agent:'room'))throw fail('Recording receipt identity mismatch.',409);
  const deliveries=receipt.deliveries?.filter(x=>x.agent===d.agent)||[];
  if(deliveries.some(x=>x.state==='uncertain')){this.db.prepare("UPDATE room_voice_turns SET state='unknown',error='Native delivery is uncertain. Inspect the room before repeating.' WHERE device=? AND id=?").run(device,id);return this.get(device,id);}
  const turns=new Set(deliveries.map(x=>x.turn).filter(Boolean));
  if(!turns.size)return this.get(device,id);
  const page=await this.client.call(`teams/${d.team}/messages`,{query:{task:d.task,after:r.cursor||event.id,limit:100}});
  const outputs=JSON.parse(r.outputs);let completion=null,cursor=r.cursor||event.id;
  for(const item of page.data||[]){
   cursor=Math.max(cursor,item.id);
   if(item.task!==d.task||item.author!==d.agent||item.visibility!==event.visibility)continue;
   const correlated=item.data?.sourceEvent===event.id||item.data?.replyTo===event.id||turns.has(item.data?.turn);
   if(!correlated)continue;
   if((item.kind==='message'||item.kind==='agentNote'&&item.data?.phase!=='commentary')&&!outputs.some(x=>x.id===item.id))outputs.push({id:item.id,text:item.body});
   if(item.kind==='turn'&&turns.has(item.data?.turn))completion=item;
  }
  const state=completion?(completion.body==='completed'?'completed':'failed'):'thinking';
  const response=outputs.map(x=>x.text).join('\n\n');
  this.db.prepare('UPDATE room_voice_turns SET event=?,cursor=?,outputs=?,response=?,state=?,error=? WHERE device=? AND id=?').run(event.id,cursor,JSON.stringify(outputs),response||(state==='completed'?'The agent finished without publishing a room reply.':null),state,completion&&state==='failed'?'The native room turn '+completion.body+'.':null,device,id);
  return this.get(device,id);
 }
 mark(){} // Audio transport timing remains separate from native execution evidence.
}
