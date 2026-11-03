import {createHash} from 'node:crypto';
import {ensureRoomReconciliations,noAdmission} from './coordinator-room-reconciliation.mjs';
import {definitiveRoomRefusal} from './coordinator-room-errors.mjs';
const uuid=value=>typeof value==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value);
const fail=(message,status=400)=>Object.assign(Error(message),{status});
const base='/api/hollywood/v3/rooms';
/** A room command outlives its coordinator model turn and service process. */
export class CoordinatorRoomCommands {
 constructor(db){this.db=db;ensureRoomReconciliations(db);db.exec(`CREATE TABLE IF NOT EXISTS coordinator_room_commands(device TEXT NOT NULL,source TEXT NOT NULL,binding TEXT NOT NULL,operation TEXT NOT NULL,request_hash TEXT NOT NULL,state TEXT NOT NULL,body TEXT NOT NULL,result TEXT,PRIMARY KEY(device,source,binding,operation)); CREATE INDEX IF NOT EXISTS coordinator_room_pending ON coordinator_room_commands(device,source,binding,request_hash,state);`);}
 unresolved(device){return this.db.prepare("SELECT * FROM coordinator_room_commands WHERE device=? AND state='submitting'").all(device).filter(r=>!noAdmission(this.db,r));}
 scope(device,source,binding){
  const db=this.db,key=[device,source||'current',binding];
  return {
   pendingAny:()=>this.unresolved(device).find(r=>r.source===key[1]&&r.binding===binding),
   pending:hash=>this.unresolved(device).find(r=>r.source===key[1]&&r.binding===binding&&r.request_hash===hash),
   receipt:async(operation,get)=>{
    const r=db.prepare('SELECT * FROM coordinator_room_commands WHERE device=? AND source=? AND binding=? AND operation=?').get(...key,operation);
    if(!r)throw fail('Original metadata room operation is not available in this exact scope.',404);
    const overlay=noAdmission(db,r),body=JSON.parse(r.body),result=r.result?JSON.parse(r.result):null;
    if(overlay||r.state==='rejected')return {...(overlay||{admission:'no_admission',evidence:result}),operationId:operation,proposedRoomId:body.id,noNewAdmission:true};
    let value;try{value=await get(base+'/'+body.id);}catch(error){return {operationId:operation,proposedRoomId:body.id,admission:'inaccessible_unknown',observedReadStatus:error.status||null,noNewAdmission:true,note:'Inaccessible or missing read is not absence. Do not resend or ask duplicate-risk consent before admission is established.'};}
    if(value.room?.id!==body.id||value.room.name!==body.name||value.room.purpose!==body.purpose||value.room.owner!==body.ownerId)throw fail('Original room metadata differs; no command was repeated.',409);
    return {operationId:operation,room:value.room,admission:'accepted_room_observed',noNewAdmission:true,workCompletion:'not_inferred'};
   },
   start:(body,hash)=>db.prepare("INSERT INTO coordinator_room_commands VALUES(?,?,?,?,?,'submitting',?,NULL)").run(...key,body.operationId,hash,JSON.stringify(body)),
   finish:(operation,state,result)=>db.prepare('UPDATE coordinator_room_commands SET state=?,result=? WHERE device=? AND source=? AND binding=? AND operation=?').run(state,JSON.stringify(result),...key,operation)
  };
 }
}
/** Project-room control uses the original human device and existing Hollywood
 * administration. No agent credentials, host processes or task turns are created. */
export async function coordinatorRooms({operation,args:a,active,callId,environmentId,get,post,action,commands}){
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
 const requestHash=createHash('sha256').update(JSON.stringify([a.name.trim(),a.purpose.trim(),a.ownerId])).digest('hex');
 const pending=commands.pending(requestHash);
 if(pending){
  const body=JSON.parse(pending.body),existing=await get(base+'/'+body.id);
  if(existing.room?.id!==body.id||existing.room.name!==body.name||existing.room.purpose!==body.purpose||existing.room.owner!==body.ownerId)
   throw fail('Inspect the original room creation; no replacement was submitted.',409);
  commands.finish(body.operationId,'accepted',existing.room);
  action({type:'room-create',state:'accepted',roomId:body.id,operationId:body.operationId,reconciledOriginal:true,...(environmentId?{environmentId}:{})});
  return {room:existing.room,reconciledOriginal:true,notSubmittedNewRoom:true};
 }
 const source=await get(base+'/'+a.sourceRoomId);
 if(!(source.actors||[]).some(actor=>actor.id===a.ownerId&&actor.kind==='agent'&&actor.active!==false))
  throw fail('The chosen owner is not an accessible existing agent in the source room.',403);
 const body={operationId:'coordinator-room-'+digest.slice(0,48),id:roomId,name:a.name.trim(),purpose:a.purpose.trim(),ownerId:a.ownerId,sourceRoomId:a.sourceRoomId};
 commands.start(body,requestHash);
 action({type:'room-create',state:'submitting',roomId,operationId:body.operationId,name:body.name,purpose:body.purpose,ownerId:a.ownerId,...(environmentId?{environmentId}:{})});
 let result;
 try{result=await post(base,body);}catch(error){
  if(definitiveRoomRefusal(error)){commands.finish(body.operationId,'rejected',{status:error.status,code:error.code||null,error:error.message,admission:'no_admission'});action({type:'room-create',state:'rejected',roomId,operationId:body.operationId,...(environmentId?{environmentId}:{})});}
  throw error;
 }
 const room=result.room||result;
 if(room.id!==roomId||room.name!==body.name||room.purpose!==body.purpose||room.owner!==a.ownerId)
  throw fail('Room creation is unconfirmed. Read the original room ID; do not create another.',409);
 commands.finish(body.operationId,'accepted',room);
 action({type:'room-create',state:'accepted',roomId,operationId:body.operationId,name:room.name,...(environmentId?{environmentId}:{})});
 return {room,created:true,agentsStarted:0,taskSubmitted:false,note:'Project room created using existing host representatives. No project work has been submitted yet.'};
}
