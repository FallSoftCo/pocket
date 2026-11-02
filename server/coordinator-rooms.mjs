import {createHash} from 'node:crypto';
const uuid=value=>typeof value==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);
const fail=(message,status=400)=>Object.assign(Error(message),{status});
const base='/api/hollywood/v3/rooms';
/** Project-room control uses the original human device and existing Hollywood
 * administration. No agent credentials, host processes or task turns are created. */
export async function coordinatorRooms({operation,args:a,active,callId,environmentId,get,post,action,prior}){
 if(operation==='rooms')return get(base);
 if(operation==='room'){
  if(!uuid(a.roomId))throw fail('Choose the exact discovered room.');
  return get(base+'/'+a.roomId);
 }
 if(!callId||!uuid(a.ownerId)||!uuid(a.sourceRoomId)||!['name','purpose'].every(key=>typeof a[key]==='string'&&a[key].trim()&&a[key].length<=(key==='name'?200:4000)))
  throw fail('A new project room needs a name, purpose and an existing agent from a known room.');
 const digest=createHash('sha256').update(JSON.stringify([active.device,active.id,callId])).digest('hex');
 const hex=digest.slice(0,12)+'5'+digest.slice(13,16)+'8'+digest.slice(17,32);
 const roomId=[hex.slice(0,8),hex.slice(8,12),hex.slice(12,16),hex.slice(16,20),hex.slice(20)].join('-');
 const latest=new Map(prior.filter(p=>p.type==='room-create'&&(p.environmentId||'current')===(environmentId||'current')).map(p=>[p.roomId,p]));
 const pending=[...latest.values()].filter(p=>p.state==='submitting');
 for(const p of pending){
  const existing=await get(base+'/'+p.roomId);
  if(existing.room?.id!==p.roomId||existing.room.name!==p.name||existing.room.purpose!==p.purpose||existing.room.owner!==p.ownerId)
   throw fail('Inspect the original room creation; no replacement was submitted.',409);
  action({...p,state:'accepted',reconciledOriginal:true});
  return {room:existing.room,reconciledOriginal:true,notSubmittedNewRoom:true};
 }
 const source=await get(base+'/'+a.sourceRoomId);
 if(!(source.actors||[]).some(actor=>actor.id===a.ownerId&&actor.kind==='agent'&&actor.active!==false))
  throw fail('The chosen owner is not an accessible existing agent in the source room.',403);
 const body={operationId:'coordinator-room-'+digest.slice(0,48),id:roomId,name:a.name.trim(),purpose:a.purpose.trim(),ownerId:a.ownerId};
 action({type:'room-create',state:'submitting',roomId,operationId:body.operationId,name:body.name,purpose:body.purpose,ownerId:a.ownerId,...(environmentId?{environmentId}:{})});
 let result;
 try{result=await post(base,body);}catch(error){
  if([400,401,403,404,409,422].includes(error.status))action({type:'room-create',state:'rejected',roomId,operationId:body.operationId,...(environmentId?{environmentId}:{})});
  throw error;
 }
 const room=result.room||result;
 if(room.id!==roomId||room.name!==body.name||room.purpose!==body.purpose||room.owner!==a.ownerId)
  throw fail('Room creation is unconfirmed. Read the original room ID; do not create another.',409);
 action({type:'room-create',state:'accepted',roomId,operationId:body.operationId,name:room.name,...(environmentId?{environmentId}:{})});
 return {room,created:true,agentsStarted:0,taskSubmitted:false,note:'Project room created using existing host representatives. No project work has been submitted yet.'};
}
